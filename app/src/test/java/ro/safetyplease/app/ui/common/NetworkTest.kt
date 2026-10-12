package ro.safetyplease.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkTest {
    private fun gate(access: Boolean = true, bluetooth: Boolean = true, locationOff: Boolean = false) =
        RadioGate(access, bluetooth, locationOff, accessBlocked = false, requestAccess = {}, enableBluetooth = {})

    @Test
    fun sendingIsBlockedByTheFirstMissingPiece() {
        assertNull(gate().problem())
        assertEquals(RadioProblem.ACCESS, gate(access = false, bluetooth = false).problem())
        assertEquals(RadioProblem.BLUETOOTH, gate(bluetooth = false, locationOff = true).problem())
        assertEquals(RadioProblem.LOCATION, gate(locationOff = true).problem())
    }
}
