package ro.safetyplease.core.incidents

import ro.safetyplease.core.incidents.ManDownDetector.Companion.GRAVITY
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic accelerometer traces at 25 Hz, in g on the phone's axes, gravity included. */
class ManDownTest {
    private class Trace {
        val samples = mutableListOf<MotionSample>()
        var t = 0L
        private val noise = Random(7)

        /** [ms] of readings from [at], a function of the time since the segment began. */
        fun add(ms: Long, at: (Long) -> Triple<Double, Double, Double>) {
            val begin = t
            while (t < begin + ms) {
                val (x, y, z) = at(t - begin)
                samples += MotionSample(t, (x * GRAVITY).toFloat(), (y * GRAVITY).toFloat(), (z * GRAVITY).toFloat())
                t += STEP_MS
            }
        }

        fun jitter(g: Double) = (noise.nextDouble() * 2 - 1) * g

        /** Upright, phone in a trouser pocket: gravity along -y, with the sway of walking. */
        fun walk(ms: Long) = add(ms) { s -> Triple(jitter(0.05), -1.0 + 0.25 * sin(2 * PI * s / 550.0), jitter(0.05)) }

        /** Lying still: gravity along z, with only the breathing's tremor. */
        fun lie(ms: Long) = add(ms) { Triple(jitter(0.01), jitter(0.01), 1.0 + jitter(0.02)) }

        fun impact(peakG: Double) = add(80) { Triple(0.3, -0.5, peakG) }

        fun detected(): List<Long> {
            val detector = ManDownDetector()
            return samples.filter { detector.add(it) }.map { it.atMs }
        }

        companion object {
            const val STEP_MS = 40L
        }
    }

    @Test
    fun aFallFollowedByLyingStillIsSuspectedOnceAfterTwentySeconds() {
        val trace = Trace()
        trace.walk(5_000)
        // the body tips over: the phone never falls freely, it swings at about half a g
        trace.add(400) { Triple(0.4, -0.3, 0.2) }
        val impactAt = trace.t
        trace.impact(4.0)
        trace.lie(40_000)
        val found = trace.detected()
        assertEquals(1, found.size)
        val after = found.single() - impactAt
        assertTrue(after in ManDownDetector.SETTLE_MS + ManDownDetector.STILL_MS..ManDownDetector.SETTLE_MS + ManDownDetector.STILL_MS + 500, "at $after ms")
    }

    @Test
    fun aFallFromWhichThePersonGetsUpIsNot() {
        val trace = Trace()
        trace.walk(5_000)
        trace.add(400) { Triple(0.4, -0.3, 0.2) }
        trace.impact(4.0)
        trace.lie(8_000)
        trace.walk(30_000)
        assertTrue(trace.detected().isEmpty())
    }

    @Test
    fun aPhoneDroppedOnATableIsNot() {
        val trace = Trace()
        // held in the hand, screen up and tilted, then dropped from about a metre
        trace.add(5_000) { Triple(0.0, -0.6, 0.8) }
        trace.add(450) { Triple(0.02, 0.01, 0.03) }
        trace.impact(6.0)
        // flat on the table, perfectly still, in another posture than in the hand
        trace.add(40_000) { Triple(0.0, 0.0, -1.0) }
        assertTrue(trace.detected().isEmpty())
    }

    @Test
    fun aPhoneSetDownOnATableIsNot() {
        val trace = Trace()
        trace.walk(5_000)
        trace.add(600) { s -> Triple(0.1, -0.7 + s / 2000.0, 0.5) }
        trace.add(40_000) { Triple(0.0, 0.0, 1.0) }
        assertTrue(trace.detected().isEmpty(), "no impact")
    }

    @Test
    fun dancingIsNot() {
        val trace = Trace()
        repeat(120) {
            // a jump: short time in the air, a hard landing, then the knees take it
            trace.add(200) { Triple(0.05, -0.1, 0.0) }
            trace.add(80) { Triple(0.5, -3.5, 0.3) }
            trace.add(220) { Triple(0.2, -1.4 + 0.3 * trace.jitter(1.0), 0.2) }
        }
        // then standing still to watch the stage: still, but upright as before
        trace.add(40_000) { Triple(0.0, -1.0, 0.02) }
        assertTrue(trace.detected().isEmpty())
    }

    @Test
    fun runningIsNot() {
        val trace = Trace()
        trace.add(120_000) { s -> Triple(0.2 * sin(s / 90.0), -1.0 - 2.2 * maxOf(0.0, sin(2 * PI * s / 360.0)), 0.3 * sin(s / 50.0)) }
        trace.lie(1_000)
        assertTrue(trace.detected().isEmpty())
    }

    @Test
    fun aSoftFallStaysUnderTheImpactThreshold() {
        val trace = Trace()
        trace.walk(5_000)
        trace.add(400) { Triple(0.4, -0.3, 0.2) }
        trace.impact(2.5)
        trace.lie(40_000)
        assertTrue(trace.detected().isEmpty(), "missed by design: the threshold leans to fewer false alarms")
    }

    @Test
    fun aGapInTheReadingsStartsOver() {
        val detector = ManDownDetector()
        val trace = Trace()
        trace.walk(5_000)
        trace.impact(4.0)
        trace.lie(10_000)
        trace.samples.forEach { detector.add(it) }
        val later = MotionSample(trace.t + 60_000, 0f, 0f, GRAVITY.toFloat())
        assertFalse(detector.add(later))
        var t = later.atMs
        var found = false
        repeat(1_000) {
            t += 40
            found = found || detector.add(MotionSample(t, 0f, 0f, GRAVITY.toFloat()))
        }
        assertFalse(found, "the impact before the gap no longer counts")
    }

    @Test
    fun anAnswerCancelsAndSilenceSendsOnce() {
        val watch = ManDownWatch()
        assertTrue(watch.suspected(0))
        assertEquals(ManDownWatch.ASK_MS, watch.askingUntil)
        assertFalse(watch.suspected(1_000), "one question at a time")
        assertFalse(watch.silent(ManDownWatch.ASK_MS - 1))
        assertTrue(watch.answered(10_000), "Sunt OK")
        assertNull(watch.askingUntil)
        assertFalse(watch.silent(ManDownWatch.ASK_MS), "nothing goes out after an answer")
        assertFalse(watch.suspected(10_000 + ManDownWatch.REST_AFTER_ANSWER_MS - 1), "a rest after the answer")

        val again = 10_000 + ManDownWatch.REST_AFTER_ANSWER_MS
        assertTrue(watch.suspected(again))
        assertTrue(watch.silent(again + ManDownWatch.ASK_MS), "silence: the report goes")
        assertFalse(watch.silent(again + ManDownWatch.ASK_MS + 1), "once")
        assertFalse(watch.answered(again + ManDownWatch.ASK_MS + 2), "too late to take back")
        assertFalse(watch.suspected(again + ManDownWatch.ASK_MS + ManDownWatch.REST_AFTER_ALERT_MS - 1))
        assertTrue(watch.suspected(again + ManDownWatch.ASK_MS + ManDownWatch.REST_AFTER_ALERT_MS))
    }
}
