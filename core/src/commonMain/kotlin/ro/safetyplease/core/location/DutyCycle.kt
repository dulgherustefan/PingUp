package ro.safetyplease.core.location

import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import kotlin.math.max

/**
 * How often to ask the GNSS chip for a fix. Walking: every [MOVING_MS]. Still for [STILL_AFTER_MS] (no steps, a
 * stationary event, or fixes that stay in place): one fix every [STATIONARY_MS]. A report or SOS being written: 1 Hz
 * until it is sent. A share session caps the interval at what its policy needs.
 *
 * Never still while [searching]: before the first fix of this run, or with the last one over [GeoFix.FRESH_MS] old, the
 * chip has to track without a break, since a cold start offline takes it minutes of that.
 *
 * Still is only trusted when some sensor can say the person moved again ([canWake]); without one the phone keeps the
 * walking pace. Full tracking (setFullTracking) is never used: it costs an undocumented amount of power, changes the
 * position reported to every app on the phone and only helps carrier-phase RTK, which Ping Up does not do.
 */
class DutyCycle(startMs: Long, private val stepSensor: Boolean, private val canWake: Boolean) {
    /** A fix every [intervalMs], none closer than [minIntervalMs]; [searching] while no recent fix says where the phone is. */
    data class Plan(val intervalMs: Long, val minIntervalMs: Long, val searching: Boolean)

    private var lastMotion = startMs
    private var lastStill = Long.MIN_VALUE
    private var anchor: GeoPoint? = null
    private var anchorAt = startMs
    private var fixAt: Long? = null

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

    /**
     * An accepted fix: one that leaves the spot where the last ones were restarts the still timer. Only a fix measured
     * in this run ends the search: the one Android kept from before may be from another sky.
     */
    fun onFix(nowMs: Long, fix: LocationFix) {
        if (fix.method != FixMethod.CACHED) fixAt = max(fixAt ?: fix.elapsedMs, fix.elapsedMs)
        val here = GeoPoint(fix.lat, fix.lon)
        val from = anchor
        val moving = (fix.speedMps ?: 0f) > STILL_SPEED_MPS
        if (from == null || moving || distanceMeters(from, here) > max(STILL_RADIUS_M.toFloat(), fix.ring95M)) {
            anchor = here
            anchorAt = nowMs
        }
    }

    /** No fix in this run yet, or the last one is too old to say where the person is. */
    fun searching(nowMs: Long): Boolean = fixAt.let { it == null || nowMs - it > GeoFix.FRESH_MS }

    fun stationary(nowMs: Long): Boolean {
        if (!canWake || searching(nowMs) || nowMs - lastMotion < STILL_AFTER_MS) return false
        return lastStill > lastMotion || stepSensor || nowMs - anchorAt >= STILL_AFTER_MS
    }

    /** [urgent] while a report or SOS is being written; [shareMaxMs] the slowest pace an active share session allows. */
    fun plan(nowMs: Long, urgent: Boolean, shareMaxMs: Long?): Plan {
        val searching = searching(nowMs)
        if (urgent) return Plan(URGENT_MS, URGENT_MS, searching)
        val base = if (stationary(nowMs)) STATIONARY_MS else MOVING_MS
        val interval = shareMaxMs?.let { minOf(base, max(it, URGENT_MS)) } ?: base
        return Plan(interval, minOf(interval, MOVING_MIN_MS), searching)
    }

    companion object {
        const val URGENT_MS = 1_000L
        const val MOVING_MS = 2_000L
        const val MOVING_MIN_MS = 1_000L

        /**
         * One fix this often while still, from a periodic request: Android then keeps the chip on until each fix comes,
         * where a one-shot request gives up after 30 s.
         */
        const val STATIONARY_MS = 45_000L
        const val STILL_AFTER_MS = 60_000L
        const val STILL_RADIUS_M = 10
        const val STILL_SPEED_MPS = 0.5f
    }
}
