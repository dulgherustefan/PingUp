package ro.safetyplease.core.venue.route

import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The moments worth a vibration on a walk, none of which depends on which way the phone faces. */
enum class GuideEvent { TURN_SOON, OFF_ROUTE, ARRIVED }

/**
 * Where a person is on a route: the index of the next [step] in [Route.steps], what is left, whether the fix is off the
 * route, and [toward], the next point of the route ahead, for the arrow. [event] is set on the update that first sees
 * it; [reroute] asks for a new route. [arrived] within [RouteGuide.ARRIVE_M] of the end is [close]; farther, the fix was
 * only good enough to say the person is near.
 */
data class Progress(
    val step: Int,
    val remainingM: Int,
    val remainingSec: Int,
    val offRoute: Boolean,
    val toward: GeoPoint,
    val event: GuideEvent?,
    val reroute: Boolean,
    val arrived: Boolean,
    val close: Boolean,
)

/**
 * Follows a walk along [route] as the fixes come. A fix farther from the route than max([OFF_ROUTE_M], twice its
 * accuracy) is off it; off it for [OFF_ROUTE_MS] asks for a new route, so a GNSS jump doesn't. Arrival is within the
 * fix's accuracy of the end, at least [ARRIVE_M] and at most [ARRIVE_MAX_M]. The fix is matched to the route ahead of
 * where the walk already got, so a leg running beside another doesn't make the progress jump between them. The times
 * are from a monotonic clock.
 */
class RouteGuide(val route: Route) {
    private val lat0 = route.points.first().lat
    private val lon0 = route.points.first().lon
    private val metersPerLon = METERS_PER_LAT * cos(radians(lat0))
    private val xs = DoubleArray(route.points.size) { (route.points[it].lon - lon0) * metersPerLon }
    private val ys = DoubleArray(route.points.size) { (route.points[it].lat - lat0) * METERS_PER_LAT }
    private val along = DoubleArray(route.points.size).also { a ->
        for (i in 1 until a.size) a[i] = a[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    private var warnedStep = -1
    private var offSinceMs = -1L
    private var offWarned = false
    private var arrived = false
    private var close = false

    /** The farthest the walk got along the route, in metres. */
    private var reached = 0.0

    fun update(at: GeoPoint, accuracyM: Float, nowMs: Long): Progress {
        val px = (at.lon - lon0) * metersPerLon
        val py = (at.lat - lat0) * METERS_PER_LAT
        val limit = max(OFF_ROUTE_M, 2.0 * accuracyM)
        // the route just behind and ahead first; anywhere on it only when the fix is off that stretch
        var match = nearest(px, py, reached - BEHIND_M, reached + AHEAD_M)
        if (match.off > limit) match = nearest(px, py, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val segment = match.segment
        val off = match.off
        val position = match.position
        val offRoute = off > limit
        if (!offRoute) reached = max(reached, position)
        var event: GuideEvent? = null
        val toEnd = distanceMeters(at, route.end).toDouble()
        if (!arrived && toEnd <= max(ARRIVE_M, min(accuracyM.toDouble(), ARRIVE_MAX_M))) {
            arrived = true
            close = toEnd <= ARRIVE_M
            event = GuideEvent.ARRIVED
        }
        var reroute = false
        if (offRoute && !arrived) {
            if (offSinceMs < 0) offSinceMs = nowMs
            if (nowMs - offSinceMs >= OFF_ROUTE_MS) {
                reroute = true
                if (!offWarned) event = GuideEvent.OFF_ROUTE
                offWarned = true
            }
        } else {
            offSinceMs = -1L
            offWarned = false
        }
        val stepIndex = route.steps.indexOfFirst { it.alongM > position + PASSED_M }.let { if (it < 0) route.steps.lastIndex else it }
        val step = route.steps[stepIndex]
        val toStep = max(0.0, step.alongM - position)
        if (event == null && !offRoute && step.turn != Turn.ARRIVE && toStep <= TURN_SOON_M && warnedStep != stepIndex) {
            warnedStep = stepIndex
            event = GuideEvent.TURN_SOON
        }
        val remaining = max(0.0, along.last() - position)
        val seconds = if (route.meters > 0) route.seconds * remaining / route.meters else 0.0
        return Progress(
            step = stepIndex,
            remainingM = remaining.roundToInt(),
            remainingSec = seconds.roundToInt(),
            offRoute = offRoute,
            toward = toward(segment, px, py),
            event = event,
            reroute = reroute,
            arrived = arrived,
            close = close,
        )
    }

    private class Match(val segment: Int, val off: Double, val position: Double)

    /** The point of the route nearest the fix among the segments that reach into [from]..[to] metres along it. */
    private fun nearest(px: Double, py: Double, from: Double, to: Double): Match {
        var segment = 0
        var off = Double.MAX_VALUE
        var position = 0.0
        for (i in 0 until xs.size - 1) {
            if (along[i + 1] < from || along[i] > to) continue
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val length2 = dx * dx + dy * dy
            val t = if (length2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / length2).coerceIn(0.0, 1.0)
            val d = hypot(px - xs[i] - t * dx, py - ys[i] - t * dy)
            // at a shared corner the later segment wins: the turn there is behind
            if (d <= off) {
                off = d
                segment = i
                position = along[i] + t * (along[i + 1] - along[i])
            }
        }
        return Match(segment, off, position)
    }

    /** The end of the segment the fix is on, or the one after when that end is only a few metres away. */
    private fun toward(segment: Int, px: Double, py: Double): GeoPoint {
        val next = (segment + 1).coerceAtMost(xs.size - 1)
        val close = hypot(xs[next] - px, ys[next] - py) < NEAR_POINT_M
        return route.points[if (close && next < xs.size - 1) next + 1 else next]
    }

    companion object {
        const val OFF_ROUTE_M = 25.0
        const val OFF_ROUTE_MS = 20_000L
        const val ARRIVE_M = 8.0

        /** However vague the fix, arrival is never said farther than this from the end. */
        const val ARRIVE_MAX_M = 20.0

        /** The stretch of route a fix is first matched to: a little behind the farthest point reached, more ahead. */
        private const val BEHIND_M = 10.0
        private const val AHEAD_M = 60.0

        /** A turn this close ahead is about to come: one vibration, once. */
        const val TURN_SOON_M = 20.0

        /** A step this little behind the fix counts as passed. */
        private const val PASSED_M = 2.0
        private const val NEAR_POINT_M = 5.0
        private const val METERS_PER_LAT = 111_320.0
    }
}
