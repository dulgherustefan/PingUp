package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.venue.route.AnchorKind
import ro.safetyplease.core.venue.route.GuideEvent
import ro.safetyplease.core.venue.route.RouteStep
import ro.safetyplease.core.venue.route.Turn
import ro.safetyplease.core.venue.route.WalkGoal
import ro.safetyplease.core.venue.route.WalkNavigation
import ro.safetyplease.core.venue.route.WalkState
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.route_ahead
import ro.safetyplease.ui.resources.route_gate
import ro.safetyplease.ui.resources.route_landmark
import ro.safetyplease.ui.resources.route_last
import ro.safetyplease.ui.resources.route_toward
import ro.safetyplease.ui.resources.route_turn_back
import ro.safetyplease.ui.resources.route_turn_left
import ro.safetyplease.ui.resources.route_turn_right
import ro.safetyplease.ui.resources.route_turn_straight

/** How often a walk is followed: a fix comes about every second while it lasts. */
private const val WALK_TICK_MS = 1_000L

/** A walk being followed: where it stands, and how far off the position it was read from may be (0: unknown). */
@Stable
class WalkTrack {
    var state by mutableStateOf<WalkState?>(null)
        private set
    var accuracyM by mutableFloatStateOf(0f)
        private set

    internal fun follow(state: WalkState, accuracyM: Float) {
        this.state = state
        this.accuracyM = accuracyM
    }
}

/**
 * Follows the walk to what [goal] names at each tick, the route screen's and the evacuation's alike, until it arrives
 * or leaves the screen; [key] starts it over. While it leads, the GPS gives fast fixes and, with the setting, the steps
 * bridge its gaps ([AppViewModel.walking]). [onEvent] gets the turns, leaving the route and the arrival.
 */
@Composable
fun rememberWalk(vm: AppViewModel, key: Any?, goal: () -> WalkGoal?, onEvent: (GuideEvent) -> Unit = {}): WalkTrack {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val staff = settings.role.hasStaffKeys
    val track = remember(key, staff) { WalkTrack() }
    val currentGoal by rememberUpdatedState(goal)
    val currentEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(track) {
        val nav = WalkNavigation(vm.c.walkRouter, staff)
        vm.walking(true)
        try {
            while (true) {
                val at = vm.pointNow()
                // a simulated position has no accuracy of its own
                val fix = vm.c.locationFix.value?.takeIf { at != null && it.lat == at.lat && it.lon == at.lon }
                val target = currentGoal()
                val state = vm.c.mapState.value
                val mono = vm.c.clock.monoMs()
                val next = withContext(Dispatchers.Default) { nav.update(at, fix?.accuracyM ?: 0f, mono, state, target) }
                track.follow(next, fix?.accuracyM ?: 0f)
                (next as? WalkState.Guiding)?.progress?.event?.let(currentEvent)
                // there: the fast fixes and the guidance stop, the screen keeps the arrival
                if ((next as? WalkState.Guiding)?.progress?.arrived == true) break
                delay(WALK_TICK_MS)
            }
        } finally {
            vm.walking(false)
        }
    }
    return track
}

/** "Spre Reperul 14, apoi la dreapta"; with nothing to see nearby, "Înainte, apoi la dreapta"; the last step names [label]. */
@Composable
fun walkStepText(step: RouteStep, label: String): String {
    if (step.turn == Turn.ARRIVE) return stringResource(Res.string.route_last, label)
    val turn = stringResource(
        when (step.turn) {
            Turn.LEFT -> Res.string.route_turn_left
            Turn.RIGHT -> Res.string.route_turn_right
            Turn.BACK -> Res.string.route_turn_back
            else -> Res.string.route_turn_straight
        },
    )
    val anchor = step.anchor
    val place = when (anchor.kind) {
        AnchorKind.LANDMARK -> stringResource(Res.string.route_landmark, anchor.number)
        AnchorKind.GATE -> anchor.name.ifEmpty { null }
            ?: if (anchor.number > 0) stringResource(Res.string.route_landmark, anchor.number) else stringResource(Res.string.route_gate)
        AnchorKind.ZONE -> anchor.name
        AnchorKind.NONE -> null
    }
    return if (place != null) stringResource(Res.string.route_toward, place, turn) else stringResource(Res.string.route_ahead, turn)
}
