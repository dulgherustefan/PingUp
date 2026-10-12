package ro.safetyplease.core.location

import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.pointInPolygon

/**
 * Drops fixes that cannot be where the person is: not newer than the last one, inside water, far less accurate than
 * the recent ones, or implying a speed nobody reaches on foot. After [MAX_REJECTED_JUMPS] jumps in a row the new
 * place is taken as real (a car, a shuttle), so the gate cannot lock a phone onto an old spot.
 */
class FixGate(private val water: List<List<GeoPoint>> = emptyList()) {
    enum class Verdict { ACCEPTED, OLD, WATER, INACCURATE, TOO_FAST }

    private val accuracies = ArrayDeque<Float>()
    private var last: LocationFix? = null
    private var jumps = 0

    fun check(fix: LocationFix): Verdict {
        val previous = last
        if (previous != null && fix.elapsedMs <= previous.elapsedMs) return Verdict.OLD
        if (water.any { pointInPolygon(fix.lat, fix.lon, it) }) return Verdict.WATER
        val median = median()
        if (median != null && fix.accuracyM > median * ACCURACY_FACTOR && fix.accuracyM > median + ACCURACY_MARGIN_M) {
            return Verdict.INACCURATE
        }
        if (previous != null && tooFast(previous, fix) && ++jumps <= MAX_REJECTED_JUMPS) return Verdict.TOO_FAST
        jumps = 0
        last = fix
        if (fix.accuracyM > 0f) {
            accuracies.addLast(fix.accuracyM)
            if (accuracies.size > WINDOW) accuracies.removeFirst()
        }
        return Verdict.ACCEPTED
    }

    /** The median accuracy of the last accepted fixes, once there are enough of them to mean something. */
    fun median(): Float? = if (accuracies.size < MIN_SAMPLES) null else median(accuracies)

    private fun tooFast(a: LocationFix, b: LocationFix): Boolean {
        val seconds = (b.elapsedMs - a.elapsedMs) / 1000.0
        // both fixes may be off by their accuracy, so only the distance beyond that counts as movement
        val moved = distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) - a.accuracyM - b.accuracyM
        return moved > 0 && moved / seconds > MAX_SPEED_MPS
    }

    companion object {
        const val WINDOW = 15
        const val MIN_SAMPLES = 5
        const val ACCURACY_FACTOR = 3f
        const val ACCURACY_MARGIN_M = 15f

        /** Running pace with margin; anything faster in a festival crowd is a multipath jump. */
        const val MAX_SPEED_MPS = 12.0
        const val MAX_REJECTED_JUMPS = 3
    }
}

internal fun median(values: Collection<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
