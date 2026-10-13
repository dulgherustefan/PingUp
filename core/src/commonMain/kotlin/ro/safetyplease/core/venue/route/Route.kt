package ro.safetyplease.core.venue.route

import ro.safetyplease.core.venue.GeoPoint
import kotlin.math.ceil
import kotlin.math.max

/** Which way to go on at a step, read from the route's own bends: never from a compass. */
enum class Turn { LEFT, RIGHT, STRAIGHT, BACK, ARRIVE }

enum class AnchorKind { LANDMARK, GATE, ZONE, NONE }

/**
 * What a step is told by, something a person can see: a numbered landmark ([number], [name]), a gate, the zone it is in,
 * or nothing nearby.
 */
data class Anchor(val kind: AnchorKind, val number: Int = 0, val name: String = "") {
    companion object {
        val NONE = Anchor(AnchorKind.NONE)
    }
}

/** "Walk towards [anchor], then [turn]", at [point], [alongM] metres from the start of the route. */
data class RouteStep(val point: GeoPoint, val alongM: Int, val turn: Turn, val anchor: Anchor)

/**
 * A walk along [points], [meters] long, about [seconds] at a walking pace, slower through crowded zones. [steps] are the
 * real turns and gates, then the arrival.
 */
data class Route(val points: List<GeoPoint>, val meters: Int, val seconds: Int, val steps: List<RouteStep>) {
    val end: GeoPoint get() = points.last()

    companion object {
        private const val SLOW_FACTOR = 1.5

        /** [seconds] of walking as a range of minutes ("10–15 min"): a single figure would promise more than a crowd allows. */
        fun minutes(seconds: Int): IntRange {
            val low = max(1, ceil(seconds / 60.0).toInt())
            val high = max(low + 1, ceil(seconds * SLOW_FACTOR / 60.0).toInt())
            return low..high
        }
    }
}

/**
 * The place [nearest][WalkRouter.nearestOpen] found: its [name], its landmark [number] (0 for none), where it stands
 * ([point], the middle of a zone) and the way there.
 */
data class Destination(val name: String, val number: Int, val route: Route, val point: GeoPoint = route.end)
