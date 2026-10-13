package ro.safetyplease.core.incidents

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** One accelerometer reading on the phone's axes, gravity included, in m/s²; [atMs] on the monotonic clock. */
data class MotionSample(val atMs: Long, val x: Float, val y: Float, val z: Float) {
    /** The magnitude in g. */
    val g: Double get() = sqrt((x * x + y * y + z * z).toDouble()) / ManDownDetector.GRAVITY
}

/**
 * Staff man-down (roadmap row 16): a hard impact, then the phone lying still in another posture than before it, for
 * [STILL_MS]. Field detectors raise many false alarms (73% of falls caught at 37.5% precision, more when dancing or
 * carrying), so every threshold leans to missing a soft fall rather than ringing: the impact must be hard, a phone
 * that fell on its own (a long true free fall) doesn't count, any movement while lying ends it, and the person is always
 * asked first ([ManDownWatch]). Not thread-safe: one caller at a time.
 */
class ManDownDetector {
    private class Candidate(val impactAt: Long, val before: DoubleArray) {
        val sum = DoubleArray(3)
        var count = 0
    }

    private val recent = ArrayDeque<MotionSample>()
    private var fallStart: Long? = null
    private var lastFall: LongRange? = null
    private var candidate: Candidate? = null
    private var lastAt: Long? = null

    /** True when this sample completes a suspected fall; the detector then starts over. */
    fun add(s: MotionSample): Boolean {
        val gap = lastAt?.let { s.atMs - it }
        lastAt = s.atMs
        // the sensor stopped for a while (the phone slept, the watch was off): nothing before it holds
        if (gap != null && (gap < 0 || gap > MAX_GAP_MS)) reset()
        val g = s.g
        trackFreeFall(s.atMs, g)
        recent.addLast(s)
        while (recent.first().atMs < s.atMs - BUFFER_MS) recent.removeFirst()

        val c = candidate
        if (c == null) {
            if (g >= IMPACT_G && !droppedOnItsOwn(s.atMs)) candidate = before(s.atMs)?.let { Candidate(s.atMs, it) }
            return false
        }
        val since = s.atMs - c.impactAt
        if (since < SETTLE_MS) return false
        if (abs(g - 1.0) > STILL_BAND_G) {
            candidate = null
            return false
        }
        c.sum[0] += s.x
        c.sum[1] += s.y
        c.sum[2] += s.z
        c.count++
        if (since < SETTLE_MS + STILL_MS) return false
        candidate = null
        return c.count >= MIN_SAMPLES && angleDeg(c.before, c.sum) >= TILT_DEG
    }

    fun reset() {
        recent.clear()
        fallStart = null
        lastFall = null
        candidate = null
    }

    private fun trackFreeFall(at: Long, g: Double) {
        val start = fallStart
        if (g < FREE_FALL_G) {
            if (start == null) fallStart = at
        } else if (start != null) {
            lastFall = start..at
            fallStart = null
        }
    }

    /** A true free fall this long just before the impact: the phone itself fell, from a hand or a table. */
    private fun droppedOnItsOwn(impactAt: Long): Boolean {
        val fall = lastFall ?: return false
        return impactAt - fall.last <= FALL_TO_IMPACT_MS && fall.last - fall.first >= DROPPED_FALL_MS
    }

    /** The mean of the readings in the window before the impact, the fall itself left out: the posture before. */
    private fun before(impactAt: Long): DoubleArray? {
        val window = recent.filter { it.atMs in (impactAt - PRE_MS - FALL_TO_IMPACT_MS) until (impactAt - FALL_TO_IMPACT_MS) }
        if (window.size < MIN_SAMPLES) return null
        return doubleArrayOf(window.sumOf { it.x.toDouble() }, window.sumOf { it.y.toDouble() }, window.sumOf { it.z.toDouble() })
    }

    companion object {
        const val GRAVITY = 9.80665
        const val IMPACT_G = 3.0
        const val FREE_FALL_G = 0.35
        const val DROPPED_FALL_MS = 300L
        const val FALL_TO_IMPACT_MS = 1_000L
        const val PRE_MS = 2_000L

        /** Bounces and the first seconds on the ground don't count against stillness. */
        const val SETTLE_MS = 2_000L
        const val STILL_MS = 20_000L
        const val STILL_BAND_G = 0.15
        const val TILT_DEG = 45.0
        const val MIN_SAMPLES = 10
        const val MAX_GAP_MS = 5_000L
        private const val BUFFER_MS = PRE_MS + FALL_TO_IMPACT_MS + 500

        fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
            val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            val norms = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (norms == 0.0) return 0.0
            return acos((dot / norms).coerceIn(-1.0, 1.0)) * 57.29577951308232
        }
    }
}

/**
 * The question after a suspected fall: "Ești OK?" for [ASK_MS], with sound and vibration. An answer ends it; silence
 * sends the staff-in-danger report. After either, the detector rests a while so one fall raises one question.
 * Times are monotonic.
 */
class ManDownWatch {
    /** Until when the question stands; null when none does. */
    var askingUntil: Long? = null
        private set
    private var restUntil = 0L

    /** True if the question starts now. */
    fun suspected(nowMs: Long): Boolean {
        if (askingUntil != null || nowMs < restUntil) return false
        askingUntil = nowMs + ASK_MS
        return true
    }

    /** "Sunt OK": false if nothing was asked. */
    fun answered(nowMs: Long): Boolean {
        if (askingUntil == null) return false
        askingUntil = null
        restUntil = nowMs + REST_AFTER_ANSWER_MS
        return true
    }

    /** True once, when the question ran out unanswered: the report goes now. */
    fun silent(nowMs: Long): Boolean {
        val until = askingUntil ?: return false
        if (nowMs < until) return false
        askingUntil = null
        restUntil = nowMs + REST_AFTER_ALERT_MS
        return true
    }

    companion object {
        const val ASK_MS = 30_000L
        const val REST_AFTER_ANSWER_MS = 2 * 60_000L

        /** The console has the report by then; another fall would only say the same. */
        const val REST_AFTER_ALERT_MS = 10 * 60_000L
    }
}
