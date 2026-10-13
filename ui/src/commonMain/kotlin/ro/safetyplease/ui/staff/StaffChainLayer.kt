package ro.safetyplease.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.StaffDensity
import ro.safetyplease.core.incidents.ResponderTarget
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.OverlayLayer
import ro.safetyplease.ui.map.OverlayPin
import ro.safetyplease.ui.map.PinKind
import ro.safetyplease.ui.map.ZoneStyle
import ro.safetyplease.ui.report.NOT_BREATHING_SHEET
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.density_age
import ro.safetyplease.ui.resources.density_pin
import ro.safetyplease.ui.resources.mandown_countdown
import ro.safetyplease.ui.resources.mandown_ok
import ro.safetyplease.ui.resources.mandown_text
import ro.safetyplease.ui.resources.mandown_title
import ro.safetyplease.ui.resources.responder_dea
import ro.safetyplease.ui.resources.responder_dea_name
import ro.safetyplease.ui.resources.responder_dea_patient
import ro.safetyplease.ui.resources.responder_distance
import ro.safetyplease.ui.resources.responder_no_dea
import ro.safetyplease.ui.resources.responder_open
import ro.safetyplease.ui.resources.responder_sheet
import ro.safetyplease.ui.resources.responder_title
import ro.safetyplease.ui.resources.responder_zone
import ro.safetyplease.ui.text.rememberNow
import ro.safetyplease.ui.text.fill

/** Above every screen: "Ești OK?" after a suspected fall, the responder's alert, and the zones' crowd levels on the map. */
@Composable
fun StaffChainLayer(vm: AppViewModel) {
    DensityLayer(vm)
    val ask by vm.c.staffChain.manDownAsk.collectAsStateWithLifecycle()
    ask?.let { ManDownQuestion(vm, it) }
    val targets by vm.c.staffChain.responderTargets.collectAsStateWithLifecycle()
    // it stays, with the defibrillator, after a team takes the incident: the responder closes it
    targets.firstOrNull()?.let { target ->
        ResponderAlert(vm, target) { vm.c.staffChain.dismissResponder(target.incidentId) }
    }
}

/**
 * The staff layer of the map: each zone's latest crowd level as a pin with its age, a zone at 4 or 5 shaded as
 * crowded. Only on staff phones, which are the ones that hold the levels.
 */
@Composable
private fun DensityLayer(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val log by vm.c.staffChain.density.state.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    LaunchedEffect(settings.role, log, now) {
        if (!settings.role.hasStaffKeys) {
            vm.mapOverlays.clear(OverlayLayer.STAFF)
            return@LaunchedEffect
        }
        val latest = StaffDensity.latest(log.readings).values.filter { now - StaffDensity.seenAt(it) < LAYER_MS }
        val template = getString(Res.string.density_pin)
        val pins = latest.mapNotNull { r ->
            vm.venue.zone(r.zone)?.center?.let { center ->
                val age = getString(Res.string.density_age, ((now - StaffDensity.seenAt(r)) / 60_000).toInt())
                OverlayPin("density:${r.zone}", center, PinKind.SERVICE, template.fill(r.level, age))
            }
        }
        val zones = latest.filter { it.level >= StaffDensity.INCIDENT_LEVEL }.associate { it.zone to ZoneStyle.CROWDED }
        if (pins.isEmpty()) vm.mapOverlays.clear(OverlayLayer.STAFF) else vm.mapOverlays.set(OverlayLayer.STAFF, MapOverlay(zones = zones, pins = pins))
    }
}

/** A level this old has left the map. */
private const val LAYER_MS = 60 * 60_000L

@Composable
private fun ManDownQuestion(vm: AppViewModel, untilMs: Long) {
    val colors = AppTheme.colors
    val now = rememberNow(1_000)
    val seconds = ((untilMs - now).coerceAtLeast(0) + 999) / 1000
    Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(colors.red).padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(Res.string.mandown_title), style = MaterialTheme.typography.title2, color = colors.onAccent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.mandown_text), style = MaterialTheme.typography.headline, color = colors.onAccent, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.mandown_countdown, seconds.toInt()), style = MaterialTheme.typography.subheadline,
                color = colors.onAccent, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            AppButton(stringResource(Res.string.mandown_ok), vm.c.staffChain::answerManDown, Modifier.widthIn(max = 360.dp).fillMaxWidth().height(72.dp))
        }
    }
}

/** Someone doesn't breathe nearby: how far, the defibrillator on the way, the incident and the sheet. */
@Composable
private fun ResponderAlert(vm: AppViewModel, target: ResponderTarget, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val incident = vm.c.incidentStore.value.staff.firstOrNull { it.incidentId == target.incidentId }
    val where = target.direct?.let { stringResource(Res.string.responder_distance, it) }
        ?: incident?.zone?.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.responder_zone, vm.venue.zoneName(it)) }
    val deaName = target.dea?.name?.ifEmpty { null } ?: stringResource(Res.string.responder_dea_name)
    val dea = when {
        target.dea == null -> stringResource(Res.string.responder_no_dea)
        target.toDea != null && target.deaToPatient != null -> stringResource(Res.string.responder_dea, deaName, target.toDea!!, target.deaToPatient!!)
        target.deaToPatient != null -> stringResource(Res.string.responder_dea_patient, deaName, target.deaToPatient!!)
        else -> stringResource(Res.string.responder_no_dea)
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.dialog).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Res.string.responder_title), style = MaterialTheme.typography.title2, color = colors.redInk, fontWeight = FontWeight.Bold)
            where?.let { Text(it, style = MaterialTheme.typography.headline) }
            Text(dea, style = MaterialTheme.typography.subheadline)
            AppButton(stringResource(Res.string.responder_open), {
                onClose()
                vm.open(Dest.Incident(target.incidentId))
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger)
            AppButton(stringResource(Res.string.responder_sheet), {
                onClose()
                vm.open(Dest.FirstAidSheet(NOT_BREATHING_SHEET))
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            AppButton(stringResource(Res.string.close), onClose, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}
