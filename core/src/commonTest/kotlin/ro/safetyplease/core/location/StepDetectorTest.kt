package ro.safetyplease.core.location

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Steps out of the accelerometer's magnitude: one per stride, none standing still or on a jolt too soon after a step. */
class StepDetectorTest {
    /** [seconds] of samples at 50 Hz: gravity plus a sine of [amplitude] m/s² at [stepsPerSecond]. */
    private fun StepDetector.walk(seconds: Int, stepsPerSecond: Double, amplitude: Double, startMs: Long = 0): Int =
        (0 until seconds * 50).count { i ->
            val t = i * 20L
            val magnitude = StepDetector.GRAVITY + amplitude * sin(2 * PI * stepsPerSecond * t / 1000.0)
            onSample(magnitude.toFloat(), startMs + t)
        }

    @Test
    fun aSteadyWalkGivesOneStepPerStride() {
        assertEquals(20, StepDetector().walk(10, 2.0, 3.0), "two strides a second for ten seconds")
    }

    @Test
    fun standingStillOrSwayingGivesNone() {
        assertEquals(0, StepDetector().walk(10, 0.0, 0.0))
        assertEquals(0, StepDetector().walk(10, 1.0, 0.8), "under the rise above gravity")
    }

    @Test
    fun twoPeaksCloserThanTheGapCountOnce() {
        val d = StepDetector()
        // a sharp peak, a dip under the fall, and another peak 200 ms later
        assertEquals(true, (0 until 10).any { d.onSample(StepDetector.GRAVITY + 6f, it * 10L) })
        repeat(10) { d.onSample(StepDetector.GRAVITY - 4f, 100 + it * 10L) }
        assertEquals(false, (0 until 5).any { d.onSample(StepDetector.GRAVITY + 6f, 200 + it * 10L) })
        repeat(10) { d.onSample(StepDetector.GRAVITY - 4f, 250 + it * 10L) }
        assertEquals(true, (0 until 10).any { d.onSample(StepDetector.GRAVITY + 6f, StepDetector.MIN_GAP_MS + 100 + it * 10L) })
    }

    @Test
    fun afterAResetItStartsFromGravity() {
        val d = StepDetector()
        d.walk(5, 2.0, 3.0)
        d.reset()
        assertEquals(10, d.walk(5, 2.0, 3.0, startMs = 60_000))
    }
}
