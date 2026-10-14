package ro.safetyplease.core.location

/** Where a fix came from: our own GNSS request, another app's fix heard passively, or the one Android kept. */
enum class FixMethod { GNSS, PASSIVE, CACHED }

/**
 * One position as the phone measured it. [accuracyM] is the 68% radius, [Accuracy.UNKNOWN] when the phone gives none;
 * [elapsedMs] is the monotonic time of the fix, [timeMs] the wall clock. [l5] says the receiver used an L5/E5a signal
 * around then. [speedAccuracyMps] is the 68% error of [speedMps], null when unknown; [mock] says another app made the fix up.
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
    /** The radius to draw: 95% of fixes fall inside it, against 68% for the raw accuracy; a wide one when unknown. */
    val ring95M: Float get() = Accuracy.ring95(accuracyM)

    companion object {
        const val RING_95 = 1.6f
    }
}

/**
 * An accuracy as both phones give it: the 68% radius in metres, or [UNKNOWN] when the phone gives none. Unknown never
 * means exact: it is drawn as a wide dashed circle, never as none.
 */
object Accuracy {
    const val UNKNOWN = 0f

    /** The circle drawn for a fix of unknown accuracy, dashed. */
    const val UNKNOWN_RING_M = 50f

    fun known(accuracyM: Float): Boolean = accuracyM > 0f

    /** The 95% radius: [LocationFix.RING_95] times the 68% one, or [UNKNOWN_RING_M]. */
    fun ring95(accuracyM: Float): Float = if (known(accuracyM)) accuracyM * LocationFix.RING_95 else UNKNOWN_RING_M
}

/** How much the compass can be trusted, as Android's sensor status reports it. */
enum class CompassAccuracy {
    UNRELIABLE, LOW, MEDIUM, HIGH;

    companion object {
        /** From a heading error in degrees, as an iPhone gives it; negative means the compass can't be read. */
        fun ofErrorDeg(errorDeg: Double): CompassAccuracy = when {
            errorDeg < 0 -> UNRELIABLE
            errorDeg <= 15 -> HIGH
            errorDeg <= 30 -> MEDIUM
            errorDeg <= 60 -> LOW
            else -> UNRELIABLE
        }
    }
}

object Heading {
    /** Below this the GNSS course is mostly noise: a person standing still "turns" at random. */
    const val COURSE_MIN_SPEED_MPS = 1f

    /** Past this error the course says little of where the person walks. */
    const val MAX_COURSE_ERROR_DEG = 45f

    /** The GNSS course when walking fast enough, otherwise the compass if it is calibrated; null when neither is usable. */
    fun pick(courseDeg: Float?, speedMps: Float?, compassDeg: Float?, compass: CompassAccuracy): Float? = when {
        courseDeg != null && speedMps != null && speedMps > COURSE_MIN_SPEED_MPS -> normalize(courseDeg)
        compassDeg != null && compass >= CompassAccuracy.MEDIUM -> normalize(compassDeg)
        else -> null
    }

    /** The GNSS course to keep with a fix: moving fast enough and, where the phone gives its error, not too wide. */
    fun course(courseDeg: Float?, courseErrorDeg: Float?, speedMps: Float?): Float? =
        if (courseErrorDeg != null && courseErrorDeg > MAX_COURSE_ERROR_DEG) null else pick(courseDeg, speedMps, null, CompassAccuracy.UNRELIABLE)

    private fun normalize(deg: Float) = ((deg % 360f) + 360f) % 360f
}
