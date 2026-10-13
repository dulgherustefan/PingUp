package ro.safetyplease.core.ble

import ro.safetyplease.core.mesh.PowerProfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleSweepTest {
    @Test
    fun phonesSweepButNotOnALowBatteryOrAsAnAnchor() {
        assertTrue(AppleSweep.wanted(PowerProfile.LEAF))
        assertTrue(AppleSweep.wanted(PowerProfile.STAFF))
        assertTrue(AppleSweep.wanted(null), "before the first power plan")
        // the flood of a crowd's Apple devices costs what the battery no longer has
        assertFalse(AppleSweep.wanted(PowerProfile.ECONOMY))
        // an anchor doesn't connect to an iPhone whose prefix it doesn't know
        assertFalse(AppleSweep.wanted(PowerProfile.ANCHOR))
    }

    @Test
    fun aSweepIsAFewSecondsAMinute() {
        assertTrue(AppleSweep.FOR_MS in 3_000L..5_000L)
        assertTrue(AppleSweep.EVERY_MS >= 12 * AppleSweep.FOR_MS, "a small share of the time")
    }
}
