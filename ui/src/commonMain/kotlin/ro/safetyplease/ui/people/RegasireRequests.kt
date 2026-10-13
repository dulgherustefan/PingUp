package ro.safetyplease.ui.people

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.position.FoundReply
import ro.safetyplease.core.position.FriendGasp
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.position.LostAlert
import ro.safetyplease.core.position.LostTarget
import ro.safetyplease.core.position.Regasire
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.ModalAsk
import ro.safetyplease.ui.ModalKind
import ro.safetyplease.ui.ModalShow
import ro.safetyplease.ui.ask
import ro.safetyplease.ui.common.meetingPlaceName
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.NavTarget
import ro.safetyplease.ui.map.OverlayLayer
import ro.safetyplease.ui.map.OverlayPin
import ro.safetyplease.ui.map.PinKind
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.found_close
import ro.safetyplease.ui.resources.found_keep
import ro.safetyplease.ui.resources.found_text
import ro.safetyplease.ui.resources.found_title
import ro.safetyplease.ui.resources.gasp_label
import ro.safetyplease.ui.resources.lost_alert_battery
import ro.safetyplease.ui.resources.lost_alert_far
import ro.safetyplease.ui.resources.lost_alert_place
import ro.safetyplease.ui.resources.lost_alert_title
import ro.safetyplease.ui.resources.lost_come
import ro.safetyplease.ui.resources.not_now
import ro.safetyplease.ui.resources.report_update_wait
import ro.safetyplease.ui.resources.searched_ok
import ro.safetyplease.ui.resources.searched_text
import ro.safetyplease.ui.resources.searched_title
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow

/**
 * Finding each other, over every screen: "X s-a pierdut" with "Vin acolo", which opens the walk to the meeting landmark,
 * "Te caută prietenii" with "Sunt bine", and the reporter's offer to close a report once the friend it looks for is
 * fine; one dialog at a time, when [ro.safetyplease.ui.ModalArbiter] lets friends ask. It also keeps the map layer of
 * this feature: the landmark this phone is headed to and the friends' last signals. [AppRoot] hosts it.
 */
@Composable
fun RegasireRequests(vm: AppViewModel) {
    val reg = vm.c.regasire
    val alerts by reg.alerts.collectAsStateWithLifecycle()
    val searched by reg.searchedBy.collectAsStateWithLifecycle()
    val found by reg.found.collectAsStateWithLifecycle()
    val target by reg.target.collectAsStateWithLifecycle()
    val gasps by reg.gasps.collectAsStateWithLifecycle()
    val now = rememberNow()
    RegasireLayer(vm, target?.takeIf { it.untilMs > now }, gasps.filter { now - it.fixAtMs < Regasire.GASP_SHOWN_MS }, now)

    val reply = found.firstOrNull()
    val search = searched.firstOrNull { it.untilMs > now }
    val alert = alerts.firstOrNull { it.untilMs > now }
    val ask = when {
        reply != null -> ModalAsk(ModalKind.FRIEND, "found:${reply.incidentId}:${reply.friend}")
        search != null -> ModalAsk(ModalKind.FRIEND, "searched:${search.sender}:${search.msgId}", search.untilMs)
        alert != null -> ModalAsk(ModalKind.FRIEND, "lost:${alert.sender}:${alert.msgId}", alert.untilMs)
        else -> null
    }
    if (vm.modals.ask(ModalKind.FRIEND, ask) != ModalShow.DIALOG || ask == null) return
    fun acted(walks: Boolean = false) = vm.modals.acted(ask.key, walks)
    when {
        reply != null -> FoundDialog(vm, reply) { acted() }
        search != null -> AppDialog(
            {
                acted()
                reg.dismiss(search)
            },
            stringResource(Res.string.searched_title), stringResource(Res.string.searched_ok),
            onConfirm = {
                acted()
                reg.imOk(search)
            },
            dismissLabel = stringResource(Res.string.not_now),
        ) {
            Note(stringResource(Res.string.searched_text, vm.c.chat.nameOf(search.sender)))
        }
        alert != null -> LostDialog(vm, alert, ::acted)
    }
}

@Composable
private fun LostDialog(vm: AppViewModel, alert: LostAlert, acted: (walks: Boolean) -> Unit) {
    val reg = vm.c.regasire
    val position by vm.position.collectAsStateWithLifecycle()
    val point = if (alert.landmark != 0) vm.venue.landmark(alert.landmark)?.point else vm.venue.meetingPoint
    val far = point?.let { p -> position?.let { Guide.round(distanceMeters(it, p)) } }
    val details = listOfNotNull(
        far?.let { stringResource(Res.string.lost_alert_far, it.toString()) },
        alert.battery.takeIf { it in 0..100 }?.let { stringResource(Res.string.lost_alert_battery, "$it%") },
    )
    AppDialog(
        {
            acted(false)
            reg.dismiss(alert)
        },
        stringResource(Res.string.lost_alert_title, vm.c.chat.nameOf(alert.sender)), stringResource(Res.string.lost_come),
        onConfirm = {
            // the alert leaves its list a moment later: it must not open again over the route
            acted(true)
            reg.comeThere(alert)
            vm.navigate(NavTarget.meeting(alert.landmark))
        },
        dismissLabel = stringResource(Res.string.not_now),
    ) {
        Note((listOf(stringResource(Res.string.lost_alert_place, meetingPlaceName(alert.landmark))) + details).joinToString(" · "))
    }
}

@Composable
private fun FoundDialog(vm: AppViewModel, reply: FoundReply, acted: () -> Unit) {
    val reg = vm.c.regasire
    val wait = stringResource(Res.string.report_update_wait)
    fun done() {
        acted()
        reg.doneWith(reply)
    }
    AppDialog(
        ::done, stringResource(Res.string.found_title, vm.c.chat.nameOf(reply.friend)), stringResource(Res.string.found_close),
        onConfirm = {
            // a report that takes no more updates has nothing left to close; one that must wait a minute keeps the offer
            if (vm.updateReport(reply.incidentId, UpdateKind.RESOLVED_ITSELF) || vm.updateWaitMs(reply.incidentId) == null) done()
            else vm.phone.toast(wait)
        },
        dismissLabel = stringResource(Res.string.found_keep),
    ) {
        Note(stringResource(Res.string.found_text))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
}

/**
 * The landmark a lost session sends this phone to, a tap on it the walk there, and where friends' phones last were
 * before their battery ran out.
 */
@Composable
private fun RegasireLayer(vm: AppViewModel, target: LostTarget?, gasps: List<FriendGasp>, now: Long) {
    val targetPin = target?.let { OverlayPin(TARGET_KEY, it.point, PinKind.MEETING, meetingPlaceName(it.landmark)) }
    val gaspPins = gasps.map { gasp ->
        val label = stringResource(Res.string.gasp_label, "${gasp.battery}%", agoText(gasp.fixAtMs, now))
        OverlayPin(GASP_KEY + gasp.sender, GeoPoint(gasp.lat, gasp.lon), PinKind.LAST_SEEN, "${vm.c.chat.nameOf(gasp.sender)} · $label")
    }
    val overlay = MapOverlay(pins = listOfNotNull(targetPin) + gaspPins)
    LaunchedEffect(overlay) {
        if (overlay.pins.isEmpty()) {
            vm.mapOverlays.clear(OverlayLayer.REGASIRE)
            return@LaunchedEffect
        }
        vm.mapOverlays.set(OverlayLayer.REGASIRE, overlay) { pin ->
            when {
                pin.key == TARGET_KEY -> target?.let { vm.navigate(NavTarget.meeting(it.landmark)) }
                pin.key.startsWith(GASP_KEY) -> pin.key.removePrefix(GASP_KEY).toLongOrNull()?.let { vm.open(Dest.Profile(Conversations.friend(it))) }
            }
        }
    }
}

private const val TARGET_KEY = "regasire-target"
private const val GASP_KEY = "regasire-gasp-"
