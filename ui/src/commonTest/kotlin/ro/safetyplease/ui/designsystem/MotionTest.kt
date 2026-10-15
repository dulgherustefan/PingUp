package ro.safetyplease.ui.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun aLoopRunsFromZeroToOneAndStartsAgain() {
        assertEquals(0f, loopPhase(0, 1800))
        assertEquals(0.5f, loopPhase(900, 1800))
        assertEquals(0f, loopPhase(1800, 1800))
        assertEquals(0.25f, loopPhase(1800 * 1000L + 450, 1800), "hours in, the phase is still exact")
        for (elapsed in 0L..7200L step 7) assertTrue(loopPhase(elapsed, 1800) in 0f..<1f)
    }

    @Test
    fun theMascotRisesForHalfTheLoopAndComesBackTheSameWay() {
        assertEquals(0f, mascotLift(0f), 0.001f)
        assertEquals(-14f, mascotLift(0.5f), 0.001f)
        // eased at both ends, as the tween it replaces: slow off the ground and slow at the top
        assertTrue(mascotLift(0.05f) > -1f)
        assertEquals(mascotLift(0.2f), mascotLift(0.8f), 0.001f)
        assertTrue(mascotLift(0.999f) > -0.01f)
    }

    @Test
    fun aTapTheListReportedLateStillShowsForAMoment() {
        assertEquals(MIN_PRESS_MS, pressHoldLeft(0), "press and release in the same frame")
        assertEquals(MIN_PRESS_MS - 40, pressHoldLeft(40))
        assertEquals(0L, pressHoldLeft(MIN_PRESS_MS))
        assertEquals(0L, pressHoldLeft(600), "a long press lets go at once")
    }

    @Test
    fun anArrowTurnsTheShortWayRound() {
        assertEquals(20f, shortestTurn(350f, 10f), 0.001f, "across north, not 340° back")
        assertEquals(-20f, shortestTurn(10f, 350f), 0.001f)
        assertEquals(90f, shortestTurn(0f, 90f), 0.001f)
        assertEquals(0f, shortestTurn(725f, 5f), 0.001f, "an angle that went round twice")
        for (from in -720..720 step 37) for (to in 0..359 step 23) {
            val turn = shortestTurn(from.toFloat(), to.toFloat())
            assertTrue(turn >= -180f && turn <= 180f, "$from to $to: $turn")
            val off = ((from + turn - to) % 360f + 360f) % 360f
            assertTrue(off < 0.01f || off > 359.99f, "$from to $to lands on it: $off")
        }
    }
}
