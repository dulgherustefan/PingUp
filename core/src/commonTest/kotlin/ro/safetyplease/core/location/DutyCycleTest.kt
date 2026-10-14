package ro.safetyplease.core.location

import ro.safetyplease.core.location.DutyCycle.Companion.MOVING_MS
import ro.safetyplease.core.location.DutyCycle.Companion.STATIONARY_MS
import ro.safetyplease.core.location.DutyCycle.Companion.STILL_AFTER_MS
import ro.safetyplease.core.location.DutyCycle.Companion.URGENT_MS
import ro.safetyplease.core.venue.GeoFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DutyCycleTest {
    private fun fix(atMs: Long, northM: Double = 0.0, speed: Float? = 0f, method: FixMethod = FixMethod.GNSS) =
        LocationFix(44.0 + northM / 111_320.0, 28.0, 4f, null, speed, method, atMs, atMs, false)

    /** A fix in the same spot every 30 s from [fromMs] through [toMs], so the last one is never stale. */
    private fun DutyCycle.fixesUntil(toMs: Long, fromMs: Long = 0) {
        var t = fromMs
        while (t <= toMs) {
            onFix(t, fix(t))
            t += 30_000
        }
    }

    @Test
    fun walkingPaceUntilAMinuteWithoutSteps() {
        val cycle = DutyCycle(0, stepSensor = true, canWake = true)
        cycle.onFix(0, fix(0))
        assertEquals(DutyCycle.Plan(MOVING_MS, 1_000, searching = false), cycle.plan(0, urgent = false, shareMaxMs = null))
        cycle.onMotion(30_000)
        cycle.fixesUntil(30_000 + STILL_AFTER_MS, fromMs = 30_000)
        assertEquals(MOVING_MS, cycle.plan(30_000 + STILL_AFTER_MS - 1, false, null).intervalMs)
        assertEquals(STATIONARY_MS, cycle.plan(30_000 + STILL_AFTER_MS, false, null).intervalMs)
        cycle.onMotion(100_000)
        assertEquals(MOVING_MS, cycle.plan(100_001, false, null).intervalMs)
    }

    @Test
    fun neverStillBeforeTheFirstFixOfTheRun() {
        // sitting with the map open, offline: the cold start needs the chip on without a break
        val cycle = DutyCycle(0, stepSensor = true, canWake = true)
        cycle.onStill(1_000)
        val plan = cycle.plan(10 * STILL_AFTER_MS, false, null)
        assertEquals(MOVING_MS, plan.intervalMs)
        assertTrue(plan.searching)
        // the fix Android kept from before isn't one of this run
        cycle.onFix(10 * STILL_AFTER_MS, fix(10 * STILL_AFTER_MS - 60_000, method = FixMethod.CACHED))
        assertTrue(cycle.plan(10 * STILL_AFTER_MS + 1, false, null).searching)
        cycle.onFix(10 * STILL_AFTER_MS + 2, fix(10 * STILL_AFTER_MS + 2))
        val found = cycle.plan(10 * STILL_AFTER_MS + 3, false, null)
        assertFalse(found.searching)
        assertEquals(STATIONARY_MS, found.intervalMs, "no step for minutes: still, now that there is a fix")
    }

    @Test
    fun aStaleFixSendsTheChipBackToSearching() {
        val cycle = DutyCycle(0, stepSensor = true, canWake = true)
        cycle.onFix(0, fix(0))
        assertEquals(STATIONARY_MS, cycle.plan(STILL_AFTER_MS, false, null).intervalMs)
        // under a roof no fix comes any more
        val stale = cycle.plan(GeoFix.FRESH_MS + 1, false, null)
        assertTrue(stale.searching)
        assertEquals(MOVING_MS, stale.intervalMs)
        assertFalse(cycle.stationary(GeoFix.FRESH_MS + 1))
        cycle.onFix(GeoFix.FRESH_MS + 5_000, fix(GeoFix.FRESH_MS + 5_000))
        assertFalse(cycle.plan(GeoFix.FRESH_MS + 5_001, false, null).searching)
    }

    @Test
    fun theStationaryDetectorEndsTheWalkingPaceWithoutAStepSensor() {
        val cycle = DutyCycle(0, stepSensor = false, canWake = true)
        cycle.onFix(1_000, fix(1_000, speed = 1.4f))
        cycle.onFix(2_000, fix(2_000, northM = 2.0, speed = 1.4f))
        assertFalse(cycle.stationary(STILL_AFTER_MS + 1))
        cycle.onStill(STILL_AFTER_MS + 1)
        assertTrue(cycle.stationary(STILL_AFTER_MS + 2))
    }

    @Test
    fun fixesThatStayInPlaceCountAsStill() {
        val cycle = DutyCycle(0, stepSensor = false, canWake = true)
        cycle.onFix(1_000, fix(1_000))
        cycle.onFix(30_000, fix(30_000, northM = 3.0))
        assertFalse(cycle.stationary(60_999))
        assertTrue(cycle.stationary(61_000))
        // leaving the spot restarts the minute
        cycle.onFix(70_000, fix(70_000, northM = 40.0))
        assertFalse(cycle.stationary(100_000))
    }

    @Test
    fun withoutASensorToWakeItStaysAtWalkingPace() {
        val cycle = DutyCycle(0, stepSensor = false, canWake = false)
        cycle.onStill(1_000)
        cycle.fixesUntil(10 * STILL_AFTER_MS)
        assertEquals(MOVING_MS, cycle.plan(10 * STILL_AFTER_MS, false, null).intervalMs)
    }

    @Test
    fun reportsGoAtOneHertzAndShareSessionsCapTheInterval() {
        val cycle = DutyCycle(0, stepSensor = true, canWake = true)
        val later = 5 * STILL_AFTER_MS
        cycle.fixesUntil(later)
        assertEquals(DutyCycle.Plan(URGENT_MS, URGENT_MS, searching = false), cycle.plan(later, urgent = true, shareMaxMs = null))
        val shared = cycle.plan(later, urgent = false, shareMaxMs = 30_000)
        assertEquals(30_000L, shared.intervalMs)
        assertEquals(1_000L, shared.minIntervalMs)
        // a session slower than the cycle changes nothing
        assertEquals(STATIONARY_MS, cycle.plan(later, false, shareMaxMs = 300_000L).intervalMs)
        assertEquals(DutyCycle.Plan(URGENT_MS, URGENT_MS, searching = false), cycle.plan(later, false, shareMaxMs = 1))
        val fresh = DutyCycle(later, stepSensor = true, canWake = true)
        assertEquals(MOVING_MS, fresh.plan(later, false, shareMaxMs = 300_000L).intervalMs)
    }
}
