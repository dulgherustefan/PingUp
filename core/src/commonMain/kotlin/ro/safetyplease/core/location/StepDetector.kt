package ro.safetyplease.core.location

/**
 * Steps from the accelerometer's magnitude, for the dead reckoning of [Positioning]: smoothed, it rises past [RISE]
 * above gravity, at least [MIN_GAP_MS] after the last step, and must fall below [FALL] before the next one counts.
 */
class StepDetector {
    private var smooth = GRAVITY
    private var armed = true
    private var lastStepMs = Long.MIN_VALUE / 2

    /** True when the sample of [magnitude] (m/s²) at [atMs] completes a step. */
    fun onSample(magnitude: Float, atMs: Long): Boolean {
        smooth += SMOOTHING * (magnitude - smooth)
        val above = smooth - GRAVITY
        if (!armed) {
            if (above < FALL) armed = true
            return false
        }
        if (above < RISE || atMs - lastStepMs < MIN_GAP_MS) return false
        armed = false
        lastStepMs = atMs
        return true
    }

    fun reset() {
        smooth = GRAVITY
        armed = true
        lastStepMs = Long.MIN_VALUE / 2
    }

    companion object {
        /** An average adult stride; the field campaign measures how far it drifts at 10 and 30 s. */
        const val STEP_M = 0.7

        /** Standard gravity, what the magnitude reads standing still. */
        const val GRAVITY = 9.80665f
        private const val SMOOTHING = 0.25f
        const val RISE = 1.2f
        private const val FALL = 0.2f
        const val MIN_GAP_MS = 300L
    }
}
