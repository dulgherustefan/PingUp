package ro.safetyplease.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvacuationGateTest {
    @Test
    fun friendsWaitWhileAnEvacuationHoldsAndTheirLinkOpensAfter() {
        val gate = EvacuationGate()
        assertTrue(gate.friendsMayAsk)
        assertTrue(gate.admits(OpenTarget.CHECKIN_ALERTS))

        assertNull(gate.evacuation(true))
        assertFalse(gate.friendsMayAsk)
        assertFalse(gate.admits(OpenTarget.CHECKIN_ALERTS), "a missed check-in waits")
        // what isn't a friend's goes through: the incidents, a report, the discreet one
        assertTrue(gate.admits(OpenTarget.INCIDENTS))
        assertTrue(gate.admits(OpenTarget.REPORT_PREFIX + "abc"))

        assertEquals(OpenTarget.CHECKIN_ALERTS, gate.evacuation(false))
        assertTrue(gate.friendsMayAsk)
        assertNull(gate.evacuation(false), "the held link opens once")
    }

    @Test
    fun anEvacuationWithNothingHeldReleasesNothing() {
        val gate = EvacuationGate()
        gate.evacuation(true)
        assertNull(gate.evacuation(false))
    }
}
