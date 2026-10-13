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
}
