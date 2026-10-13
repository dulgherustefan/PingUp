package ro.safetyplease.core.chat

import ro.safetyplease.core.venue.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorchPulseTest {
    @Test
    fun tenSecondsStartingLitAndEndingDark() {
        val steps = TorchPulse.steps()
        assertEquals(TorchPulse.DURATION_MS, steps.sumOf { it.ms })
        assertTrue(steps.first().lit)
        assertFalse(steps.last().lit, "the torch is off when it is over")
        assertTrue(steps.zipWithNext().all { (a, b) -> a.lit != b.lit })
        assertTrue(TorchPulse.litAt(0))
        assertFalse(TorchPulse.litAt(-1))
        assertFalse(TorchPulse.litAt(TorchPulse.DURATION_MS))
    }

    @Test
    fun neverMoreThanThreeFlashesInAnySecond() {
        val onsets = (0 until TorchPulse.DURATION_MS).filter { TorchPulse.litAt(it) && !TorchPulse.litAt(it - 1) }
        assertTrue(onsets.size >= 15)
        for (start in 0 until TorchPulse.DURATION_MS step 10) assertTrue(onsets.count { it in start until start + 1000 } <= 3)
    }

    @Test
    fun theVibrationFollowsTheTorch() {
        val wave = TorchPulse.vibration()
        assertEquals(0L, wave[0])
        assertEquals(TorchPulse.steps().map { it.ms }, wave.drop(1))
    }

    @Test
    fun aShortPulseIsCutToItsLength() {
        val steps = TorchPulse.steps(500)
        assertEquals(500L, steps.sumOf { it.ms })
        assertFalse(steps.last().lit)
    }

    @Test
    fun theMiddleNeedsTwoPoints() {
        assertNull(centroid(listOf(GeoPoint(44.0, 26.0))))
        assertEquals(GeoPoint(44.5, 26.5), centroid(listOf(GeoPoint(44.0, 26.0), GeoPoint(45.0, 27.0))))
    }
}
