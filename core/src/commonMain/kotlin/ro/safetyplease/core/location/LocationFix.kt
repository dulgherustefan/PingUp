package ro.safetyplease.core.location

/** Where a fix came from: our own GNSS request, another app's fix heard passively, or the one Android kept. */
enum class FixMethod { GNSS, PASSIVE, CACHED }

/**
 * One position as the phone measured it. [accuracyM] is Android's 68% radius, 0 when it gives none; [elapsedMs] is
 * the monotonic time of the fix, [timeMs] the wall clock. [l5] says the receiver used an L5/E5a signal around then.
 * [speedAccuracyMps] is the 68% error of [speedMps], null when unknown; [mock] says another app made the fix up.
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val headingDeg: Float?,
    val speedMps: Float?,
    val method: FixMethod,
    val timeMs: Long,
    val elapsedMs: Long,
    val l5: Boolean,
    val speedAccuracyMps: Float? = null,
    val mock: Boolean = false,
) {
    /** The radius to draw: 95% of fixes fall inside it, against 68% for the raw accuracy. */
    val ring95M: Float get() = accuracyM * RING_95

    companion object {
        const val RING_95 = 1.6f
    }
}

/** How much the compass can be trusted, as Android's sensor status reports it. */
enum class CompassAccuracy { UNRELIABLE, LOW, MEDIUM, HIGH }

object Heading {
    /** Below this the GNSS course is mostly noise: a person standing still "turns" at random. */
    const val COURSE_MIN_SPEED_MPS = 1f

    /** The GNSS course when walking fast enough, otherwise the compass if it is calibrated; null when neither is usable. */
    fun pick(courseDeg: Float?, speedMps: Float?, compassDeg: Float?, compass: CompassAccuracy): Float? = when {
        courseDeg != null && speedMps != null && speedMps > COURSE_MIN_SPEED_MPS -> normalize(courseDeg)
        compassDeg != null && compass >= CompassAccuracy.MEDIUM -> normalize(compassDeg)
        else -> null
    }

    private fun normalize(deg: Float) = ((deg % 360f) + 360f) % 360f
}
