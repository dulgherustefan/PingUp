package ro.safetyplease.core.location

import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.pointInPolygon
import kotlin.math.abs
import kotlin.math.max

/**
 * Drops fixes that cannot be where the person is: not newer than the last one, far less accurate than those of the
 * last [WINDOW_MS], or implying a speed nobody reaches on foot, unless the receiver's own Doppler speed agrees.
 * Nothing locks a phone out for good: [ESCAPE_FIXES] inaccurate fixes in a row, or inaccurate fixes for [ESCAPE_MS], go
 * through with their real radius (indoors, under a stage roof), and after [MAX_REJECTED_JUMPS] jumps the new place is
 * taken as real. A fix in the venue's water is kept and flagged ([Verdict.WATER]): someone at the waterline or
 * swimming must not vanish. [reset] at each start of the receiver.
 */
class FixGate(private val water: List<List<GeoPoint>> = emptyList()) {
    enum class Verdict(val accepted: Boolean) {
        ACCEPTED(true),

        /** Kept, but inside the venue's water: a person at the waterline or swimming, or a GNSS error. */
        WATER(true),
        OLD(false),
        INACCURATE(false),
        TOO_FAST(false),
    }

    private class Sample(val atMs: Long, val accuracyM: Float)

    private val recent = ArrayDeque<Sample>()
    private var last: LocationFix? = null
    private var jumps = 0
    private var inaccurate = 0
    private var inaccurateSinceMs = 0L

    fun check(fix: LocationFix): Verdict {
        val previous = last
        if (previous != null && fix.elapsedMs <= previous.elapsedMs) return Verdict.OLD
        while (recent.isNotEmpty() && fix.elapsedMs - recent.first().atMs > WINDOW_MS) recent.removeFirst()
        if (farWorse(fix)) {
            if (inaccurate == 0) inaccurateSinceMs = fix.elapsedMs
            inaccurate++
            if (inaccurate < ESCAPE_FIXES && fix.elapsedMs - inaccurateSinceMs < ESCAPE_MS) return Verdict.INACCURATE
        } else {
            inaccurate = 0
        }
        if (previous != null && tooFast(previous, fix) && ++jumps <= MAX_REJECTED_JUMPS) return Verdict.TOO_FAST
        jumps = 0
        last = fix
        if (Accuracy.known(fix.accuracyM)) {
            recent.addLast(Sample(fix.elapsedMs, fix.accuracyM))
            if (recent.size > MAX_SAMPLES) recent.removeFirst()
        }
        return if (water.any { pointInPolygon(fix.lat, fix.lon, it) }) Verdict.WATER else Verdict.ACCEPTED
    }

    /** Forgets everything: the receiver starts again, maybe elsewhere and under another sky. */
    fun reset() {
        recent.clear()
        last = null
        jumps = 0
        inaccurate = 0
    }

    /** The median accuracy of the fixes accepted in the last [WINDOW_MS], once there are enough of them to mean something. */
    fun median(): Float? = if (recent.size < MIN_SAMPLES) null else median(recent.map { it.accuracyM })

    /** An unknown accuracy can't be judged, so it never counts as worse. */
    private fun farWorse(fix: LocationFix): Boolean {
        val median = median() ?: return false
        return fix.accuracyM > median * ACCURACY_FACTOR && fix.accuracyM > median + ACCURACY_MARGIN_M
    }

    private fun tooFast(a: LocationFix, b: LocationFix): Boolean {
        val seconds = (b.elapsedMs - a.elapsedMs) / 1000.0
        val distance = distanceMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)).toDouble()
        // both fixes may be off by their accuracy, so only the distance beyond that counts as movement
        val slack = slackM(a) + slackM(b)
        val moved = distance - slack
        return moved > 0 && moved / seconds > MAX_SPEED_MPS && !dopplerAgrees(a, b, distance / seconds, slack / seconds)
    }

    /**
     * A car or a train: the receiver measures its speed from the Doppler shift, which the multipath that throws a
     * position off leaves alone, and the distance covered agrees with it.
     */
    private fun dopplerAgrees(a: LocationFix, b: LocationFix, impliedMps: Double, slackMps: Double): Boolean {
        val speed = b.speedMps?.takeIf { it >= 0f } ?: return false
        val error = b.speedAccuracyMps
        if (error != null && error > MAX_SPEED_ERROR_MPS) return false
        // between two fixes the speed changes at most steadily: their mean is the speed over the interval
        val mean = a.speedMps?.takeIf { it >= 0f }?.let { (it + speed) / 2.0 } ?: speed.toDouble()
        val tolerance = slackMps + max(DOPPLER_MIN_MPS, DOPPLER_PART * mean) + 2.0 * (error ?: DOPPLER_ERROR_MPS)
        return abs(impliedMps - mean) <= tolerance
    }

    private fun slackM(fix: LocationFix): Double =
        if (Accuracy.known(fix.accuracyM)) fix.accuracyM.toDouble() else DisplayFilter.UNKNOWN_ACCURACY_M

    companion object {
        /** The sky of the last two minutes, not of the whole day, sets what counts as inaccurate. */
        const val WINDOW_MS = 2 * 60_000L
        const val MIN_SAMPLES = 5
        const val ACCURACY_FACTOR = 3f
        const val ACCURACY_MARGIN_M = 15f

        /** Inaccurate fixes in a row, or for this long, are believed: a dot frozen indoors says less than a wide one. */
        const val ESCAPE_FIXES = 3
        const val ESCAPE_MS = 10_000L

        /** Running pace with margin; anything faster in a festival crowd is a multipath jump, unless the Doppler speed agrees. */
        const val MAX_SPEED_MPS = 12.0
        const val MAX_REJECTED_JUMPS = 3

        /** A Doppler speed less sure than this doesn't vouch for a jump. */
        const val MAX_SPEED_ERROR_MPS = 3f
        private const val DOPPLER_MIN_MPS = 2.0
        private const val DOPPLER_PART = 0.25
        private const val DOPPLER_ERROR_MPS = 1f

        /** Two minutes of fixes at 1 Hz, with room to spare. */
        private const val MAX_SAMPLES = 256
    }
}

/**
 * What a phone hands over before the gate, where nothing else paces it (CoreLocation on an iPhone): nothing older than
 * [GeoFix.FRESH_MS]; after a start, nothing older than [FIRST_MAX_AGE_MS], since the system may first hand over a fix
 * it kept from long ago; and about one fix a second at most.
 */
class FixIntake {
    private var fresh = false
    private var lastMs: Long? = null

    /** The receiver starts again. */
    fun restart() {
        fresh = false
        lastMs = null
    }

    /** A fix [ageMs] old by its own time stamp, from [elapsedMs] on the monotonic clock. */
    fun take(ageMs: Long, elapsedMs: Long): Boolean {
        if (ageMs > GeoFix.FRESH_MS || (!fresh && ageMs > FIRST_MAX_AGE_MS)) return false
        val last = lastMs
        if (last != null && elapsedMs - last < MIN_GAP_MS) return false
        fresh = true
        lastMs = elapsedMs
        return true
    }

    companion object {
        const val FIRST_MAX_AGE_MS = 10_000L

        /** One a second, with room for fixes stamped a few milliseconds early. */
        const val MIN_GAP_MS = 900L
    }
}

internal fun median(values: Collection<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
