package ro.safetyplease.core.venue.route

import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters

/** Where a walk leads: a point, which may move (a friend's last position), or the nearest open place of a type. */
sealed interface WalkGoal {
    data class To(val point: GeoPoint) : WalkGoal

    /** [closedPoints]: the numbered points staff marked closed or out of service. */
    data class NearestOpen(val type: String, val closedPoints: Set<Int> = emptySet()) : WalkGoal
}

sealed interface WalkState {
    data object NoPosition : WalkState

    /** The phone is off the routes' map: no route is drawn until it comes back. */
    data object Outside : WalkState

    /** The goal has no place now, like a friend who shares no position. */
    data object NoGoal : WalkState

    /** No walk reaches the goal: water, fences or closed zones in between, or nothing of that type open. */
    data object Unreachable : WalkState

    /** [destination] is the place [WalkGoal.NearestOpen] found; null for a point. */
    data class Guiding(val route: Route, val progress: Progress, val destination: Destination?) : WalkState
}

/**
 * A walk being guided, fix after fix. The route is planned again only when the console's map state changes, the goal
 * moves more than [RouteGuide.OFF_ROUTE_M] or the person stays off the route ([RouteGuide]); after a failed plan, also
 * once the person moved that far. Not thread-safe: one caller at a time.
 */
class WalkNavigation(private val router: WalkRouter, private val staff: Boolean) {
    private var guide: RouteGuide? = null
    private var destination: Destination? = null
    private var plannedState: MapState? = null
    private var plannedGoal: WalkGoal? = null
    private var failedFrom: GeoPoint? = null

    fun update(at: GeoPoint?, accuracyM: Float, nowMs: Long, state: MapState, goal: WalkGoal?): WalkState {
        if (at == null) return WalkState.NoPosition
        if (!router.covers(at)) {
            forget()
            return WalkState.Outside
        }
        if (goal == null) return WalkState.NoGoal
        val changed = state != plannedState || goalChanged(goal)
        val failed = failedFrom
        if (failed != null && !changed && distanceMeters(failed, at) <= RouteGuide.OFF_ROUTE_M) return WalkState.Unreachable
        val current = guide
        val before = current?.update(at, accuracyM, nowMs)
        if (current != null && before != null && !changed && !before.reroute) return WalkState.Guiding(current.route, before, destination)
        plannedState = state
        plannedGoal = goal
        val planned = plan(at, state, goal)
        if (planned == null) {
            guide = null
            destination = null
            failedFrom = at
            return WalkState.Unreachable
        }
        failedFrom = null
        val fresh = RouteGuide(planned.first)
        guide = fresh
        destination = planned.second
        val progress = fresh.update(at, accuracyM, nowMs)
        return WalkState.Guiding(fresh.route, progress.copy(event = before?.event ?: progress.event), destination)
    }

    private fun plan(at: GeoPoint, state: MapState, goal: WalkGoal): Pair<Route, Destination?>? = when (goal) {
        is WalkGoal.To -> router.route(at, goal.point, state, staff)?.let { it to null }
        is WalkGoal.NearestOpen -> router.nearestOpen(at, goal.type, state, staff, goal.closedPoints)?.let { it.route to it }
    }

    private fun goalChanged(goal: WalkGoal): Boolean {
        val planned = plannedGoal
        return when {
            planned is WalkGoal.To && goal is WalkGoal.To -> distanceMeters(planned.point, goal.point) > RouteGuide.OFF_ROUTE_M
            else -> planned != goal
        }
    }

    private fun forget() {
        guide = null
        destination = null
        plannedState = null
        plannedGoal = null
        failedFrom = null
    }
}
