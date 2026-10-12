package ro.safetyplease.core.location

import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import kotlin.math.max

/**
 * How often to ask the GNSS chip for a fix. Walking: every [MOVING_MS]. Still for [STILL_AFTER_MS] (no steps, a
 * stationary event, or fixes that stay in place): one fix every [STATIONARY_MS] or on demand. A report or SOS being
 * written: 1 Hz until it is sent. A share session caps the interval at what its policy needs.
 *
 * Still is only trusted when some sensor can say the person moved again ([canWake]); without one the phone keeps the
 * walking pace. Full tracking (setFullTracking) is never used: it costs an undocumented amount of power, changes the
 * position reported to every app on the phone and only helps carrier-phase RTK, which Ping Up does not do.
 */
class DutyCycle(startMs: Long, private val stepSensor: Boolean, private val canWake: Boolean) {
    /** [continuous] keeps one request open; otherwise the caller asks for one fix every [intervalMs]. */
    data class Plan(val intervalMs: Long, val minIntervalMs: Long, val continuous: Boolean)

    private var lastMotion = startMs
    private var lastStill = Long.MIN_VALUE
    private var anchor: GeoPoint? = null
    private var anchorAt = startMs

    /** A step, a motion event or significant motion. */
    fun onMotion(nowMs: Long) {
        lastMotion = max(lastMotion, nowMs)
        anchor = null
        anchorAt = nowMs
    }

    /** Android's stationary detector fired. */
    fun onStill(nowMs: Long) {
        lastStill = nowMs
    }

    /** An accepted fix: one that leaves the spot where the last ones were restarts the still timer. */
    fun onFix(nowMs: Long, fix: LocationFix) {
        val here = GeoPoint(fix.lat, fix.lon)
        val from = anchor
        val moving = (fix.speedMps ?: 0f) > STILL_SPEED_MPS
        if (from == null || moving || distanceMeters(from, here) > max(STILL_RADIUS_M.toFloat(), fix.ring95M)) {
            anchor = here
            anchorAt = nowMs
        }
    }

    fun stationary(nowMs: Long): Boolean {
        if (!canWake || nowMs - lastMotion < STILL_AFTER_MS) return false
        return lastStill > lastMotion || stepSensor || nowMs - anchorAt >= STILL_AFTER_MS
    }

    /** [urgent] while a report or SOS is being written; [shareMaxMs] the slowest pace an active share session allows. */
    fun plan(nowMs: Long, urgent: Boolean, shareMaxMs: Long?): Plan {
        if (urgent) return Plan(URGENT_MS, URGENT_MS, true)
        val base = if (stationary(nowMs)) STATIONARY_MS else MOVING_MS
        val interval = shareMaxMs?.let { minOf(base, max(it, URGENT_MS)) } ?: base
        return Plan(interval, minOf(interval, MOVING_MIN_MS), interval <= CHIP_ON_MS)
    }

    companion object {
        const val URGENT_MS = 1_000L
        const val MOVING_MS = 2_000L
        const val MOVING_MIN_MS = 1_000L
        const val STATIONARY_MS = 45_000L
        const val STILL_AFTER_MS = 60_000L

        /** AOSP keeps the GNSS engine on between requests closer than this, so a timer gains nothing below it. */
        const val CHIP_ON_MS = 10_000L
        const val STILL_RADIUS_M = 10
        const val STILL_SPEED_MPS = 0.5f
    }
}
