package ro.safetyplease.core.location

import ro.safetyplease.core.location.DutyCycle.Companion.MOVING_MS
import ro.safetyplease.core.location.DutyCycle.Companion.STATIONARY_MS
import ro.safetyplease.core.location.DutyCycle.Companion.STILL_AFTER_MS
import ro.safetyplease.core.location.DutyCycle.Companion.URGENT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DutyCycleTest {
    private fun fix(atMs: Long, northM: Double = 0.0, speed: Float? = 0f) =
        LocationFix(44.0 + northM / 111_320.0, 28.0, 4f, null, speed, FixMethod.GNSS, atMs, atMs, false)

    @Test
    fun walkingPaceUntilAMinuteWithoutSteps() {
        val cycle = DutyCycle(0, stepSensor = true, canWake = true)
        assertEquals(DutyCycle.Plan(MOVING_MS, 1_000, true), cycle.plan(0, urgent = false, shareMaxMs = null))
        cycle.onMotion(30_000)
        assertEquals(MOVING_MS, cycle.plan(30_000 + STILL_AFTER_MS - 1, false, null).intervalMs)
        val still = cycle.plan(30_000 + STILL_AFTER_MS, false, null)
        assertEquals(STATIONARY_MS, still.intervalMs)
        assertFalse(still.continuous)
        cycle.onMotion(200_000)
        assertTrue(cycle.plan(200_001, false, null).continuous)
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
        assertEquals(MOVING_MS, cycle.plan(10 * STILL_AFTER_MS, false, null).intervalMs)
    }

    @Test
    fun reportsGoAtOneHertzAndShareSessionsCapTheInterval() {
        val cycle = DutyCycle(0, stepSensor = true, canWake = true)
        val later = 5 * STILL_AFTER_MS
        assertEquals(DutyCycle.Plan(URGENT_MS, URGENT_MS, true), cycle.plan(later, urgent = true, shareMaxMs = null))
        val shared = cycle.plan(later, urgent = false, shareMaxMs = 30_000)
        assertEquals(30_000L, shared.intervalMs)
        assertFalse(shared.continuous)
        // a session slower than the cycle changes nothing
        assertEquals(STATIONARY_MS, cycle.plan(later, false, shareMaxMs = 300_000L).intervalMs)
        assertEquals(MOVING_MS, cycle.plan(0, false, shareMaxMs = 300_000L).intervalMs)
        assertEquals(DutyCycle.Plan(URGENT_MS, URGENT_MS, true), cycle.plan(later, false, shareMaxMs = 1))
    }
}
