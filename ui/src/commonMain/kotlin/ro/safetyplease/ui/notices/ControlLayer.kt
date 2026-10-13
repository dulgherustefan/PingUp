package ro.safetyplease.ui.notices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.EvacuationTarget
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.OverlayLayer
import ro.safetyplease.ui.map.OverlayPin
import ro.safetyplease.ui.map.PinKind
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.evac_exit_number
import ro.safetyplease.ui.resources.evac_pin_exit
import ro.safetyplease.ui.resources.zone_state_closed
import ro.safetyplease.ui.resources.zone_state_exit_only

/** The words on the control layer's pins; [exitNumber] names an exit without a name, with `%1$d` for its number. */
data class ControlLabels(val closed: String, val exitOnly: String, val exit: String, val exitNumber: String)

/**
 * The console's map on every map of the app: the zones styled by their state (closed hatched, crowded tinted, exit
 * only marked, evacuated highlighted), a pin naming the state of each closed or exit-only zone, and, in an evacuation
 * for this phone, a pin on its exit.
 */
fun controlOverlay(venue: Venue, state: MapState, target: EvacuationTarget?, labels: ControlLabels): MapOverlay {
    val zonePins = venue.zones.mapNotNull { zone ->
        val label = when (state.state(zone.id)) {
            ZoneState.CLOSED -> labels.closed
            ZoneState.EXIT_ONLY -> labels.exitOnly
            else -> return@mapNotNull null
        }
        OverlayPin(ZONE_KEY + zone.id, zone.center, PinKind.SERVICE, "${zone.name}: $label")
    }
    val exitPin = target?.let { t ->
        OverlayPin(EXIT_KEY, t.point, PinKind.SEARCH_RESULT, "${labels.exit}: ${exitName(t.exit.name, t.exit.number, labels.exitNumber)}")
    }
    return MapOverlay(zones = MapOverlay.zoneStyles(state, venue), pins = zonePins + listOfNotNull(exitPin))
}

/** An exit's name, or [numbered] filled with its number where the venue gives it none. */
fun exitName(name: String, number: Int, numbered: String): String = name.ifBlank { numbered.replace("%1\$d", number.toString()) }

private const val ZONE_KEY = "control-zone-"
private const val EXIT_KEY = "control-exit"

/** Keeps the control layer in [AppViewModel.mapOverlays] in step with the map state; a tap on a pin says what it is. */
@Composable
fun ControlMapLayer(vm: AppViewModel, zone: String?) {
    val state by vm.c.mapState.collectAsStateWithLifecycle()
    val labels = ControlLabels(
        stringResource(Res.string.zone_state_closed), stringResource(Res.string.zone_state_exit_only),
        stringResource(Res.string.evac_pin_exit), stringResource(Res.string.evac_exit_number),
    )
    val target = Evacuations.target(vm.venue, state, zone)
    LaunchedEffect(state, target, labels) {
        val overlay = controlOverlay(vm.venue, state, target, labels)
        if (overlay == MapOverlay.EMPTY) vm.mapOverlays.clear(OverlayLayer.CONTROL)
        else vm.mapOverlays.set(OverlayLayer.CONTROL, overlay) { pin -> vm.phone.toast(pin.label) }
    }
}
