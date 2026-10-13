package ro.safetyplease.ui.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.safety.FriendAlert
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.ReportPreset
import ro.safetyplease.ui.Tab
import ro.safetyplease.ui.common.placeText
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.map.NavTarget
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.checkin_alert_dismiss
import ro.safetyplease.ui.resources.checkin_alert_lost
import ro.safetyplease.ui.resources.checkin_alert_lost_text
import ro.safetyplease.ui.resources.checkin_alert_map
import ro.safetyplease.ui.resources.checkin_alert_message
import ro.safetyplease.ui.resources.checkin_alert_no_fix
import ro.safetyplease.ui.resources.checkin_alert_position
import ro.safetyplease.ui.resources.checkin_alert_received
import ro.safetyplease.ui.resources.checkin_alert_silent
import ro.safetyplease.ui.resources.checkin_alert_time
import ro.safetyplease.ui.resources.checkin_alerts_empty
import ro.safetyplease.ui.resources.checkin_alerts_title
import ro.safetyplease.ui.resources.route_go_to
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow

/** The alerts of friends who didn't confirm they arrived, each with what to do about it; nothing when there are none. */
@Composable
fun FriendAlertCards(vm: AppViewModel, alerts: List<FriendAlert>) {
    if (alerts.isEmpty()) return
    val now = rememberNow()
    SectionTitle(stringResource(Res.string.checkin_alerts_title), top = 8.dp)
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        alerts.forEach { FriendAlertCard(vm, it, now) }
    }
}

/** Where a notification about a friend's missed check-in leads. */
@Composable
fun FriendAlertsScreen(vm: AppViewModel) {
    val data by vm.c.checkin.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(Res.string.checkin_alerts_title),
        background = AppTheme.colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(bottom = 24.dp)) {
            if (data.alerts.isEmpty()) EmptyState(stringResource(Res.string.checkin_alerts_empty), null, icon = Sym.CheckCircle)
            FriendAlertCards(vm, data.alerts)
        }
    }
}

@Composable
private fun FriendAlertCard(vm: AppViewModel, alert: FriendAlert, now: Long) {
    val colors = AppTheme.colors
    val name = vm.c.chat.nameOf(alert.sender)
    val lat = alert.lat
    val lon = alert.lon
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    val where = point?.let { p ->
        val square = vm.c.grid.cellAt(p.lat, p.lon).orEmpty()
        placeText(square, vm.venue.nearestLandmark(p)?.number ?: 0, AccuracyClass.meters(alert.accuracy) ?: 0, vm.venue)
    }
    val position = if (point == null) {
        stringResource(Res.string.checkin_alert_no_fix)
    } else {
        val zone = vm.venue.zoneAt(point.lat, point.lon)?.name.orEmpty()
        stringResource(Res.string.checkin_alert_position, where.orEmpty().ifEmpty { zone }, agoText(alert.fixedAt * 1000, now))
    }
    val lost = stringResource(Res.string.checkin_alert_lost_text, name, alert.label, where.orEmpty())
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.cell).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.checkin_alert_silent, name, alert.label), style = MaterialTheme.typography.headline)
        val deadline = alert.deadlineAt * 1000
        Text(
            stringResource(Res.string.checkin_alert_time, deadlineText(deadline, now), agoText(deadline, now)) + " · " + position,
            style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
        )
        Text(
            stringResource(Res.string.checkin_alert_received, agoText(alert.receivedAt, now)), style = MaterialTheme.typography.footnote,
            color = colors.secondaryLabel,
        )
        AppButton(
            stringResource(Res.string.checkin_alert_message), { vm.open(Dest.Conversation(Conversations.friend(alert.sender))) },
            Modifier.fillMaxWidth(), icon = Sym.Chat,
        )
        AppButton(
            stringResource(Res.string.checkin_alert_lost),
            {
                vm.reportPreset = ReportPreset(IncidentCategory.LOST_FRIEND, lost)
                vm.home(Tab.REPORT)
            },
            Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Sym.PersonSearch,
        )
        if (point != null && vm.c.walkRouter.covers(point)) {
            AppButton(
                stringResource(Res.string.route_go_to, name), { vm.navigate(NavTarget.Place(point, name)) },
                Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Sym.Walk,
            )
        }
        if (point != null) {
            AppButton(
                stringResource(Res.string.checkin_alert_map),
                { vm.open(Dest.Pin(point.lat, point.lon, vm.venue.zoneAt(point.lat, point.lon)?.id.orEmpty(), name)) },
                Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Sym.Place,
            )
        }
        TextLink(
            stringResource(Res.string.checkin_alert_dismiss), { vm.c.checkin.dismissAlert(alert.sender, alert.msgId) },
            Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
