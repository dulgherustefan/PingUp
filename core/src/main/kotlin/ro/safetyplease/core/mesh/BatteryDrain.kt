package ro.safetyplease.core.mesh

import kotlin.math.roundToInt

/**
 * How fast the battery falls under one power profile, from the moments it was seen to drop a percent. Both ends of
 * the span are such moments, so the drop between them is exact; a profile change, a charger or a level that goes up
 * starts a new measurement, because the old rate no longer describes the phone.
 */
class BatteryDrain {
    private class Drop(val atMs: Long, val percent: Int)

    private val drops = ArrayDeque<Drop>()
    private var profile: PowerProfile? = null
    private var lastPercent: Int? = null

    fun record(atMs: Long, percent: Int, charging: Boolean, profile: PowerProfile) {
        val last = lastPercent
        lastPercent = percent
        if (charging || profile != this.profile || (last != null && percent > last)) {
            drops.clear()
            this.profile = profile
            if (charging) lastPercent = null
            return
        }
        if (last == null || percent == last) return
        drops.addLast(Drop(atMs, percent))
        while (drops.first().atMs < atMs - WINDOW_MS) drops.removeFirst()
    }

    /** Percent points per hour, or null while the measurement is too short to say. */
    fun percentPerHour(): Double? {
        if (drops.size < 2) return null
        val first = drops.first()
        val last = drops.last()
        val spanMs = last.atMs - first.atMs
        val fallen = first.percent - last.percent
        if (spanMs < MIN_SPAN_MS || fallen < MIN_FALL) return null
        return fallen * HOUR_MS.toDouble() / spanMs
    }

    /** Minutes until empty at the measured rate from [percent], to the nearest 5; null while there is no rate. */
    fun minutesLeft(percent: Int): Int? {
        val rate = percentPerHour() ?: return null
        val minutes = percent / rate * 60
        return ((minutes / ROUND_MIN).roundToInt() * ROUND_MIN).coerceAtLeast(ROUND_MIN)
    }

    companion object {
        const val WINDOW_MS = 60 * 60_000L
        const val MIN_SPAN_MS = 15 * 60_000L
        const val MIN_FALL = 2
        private const val HOUR_MS = 60 * 60_000L
        private const val ROUND_MIN = 5
    }
}
