package ro.safetyplease.app.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshServiceTypesTest {
    private val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun onAndroid10And11TheMeshHoldsLocationSoScansWorkWithTheScreenOff() {
        for (sdk in 29..30) {
            assertEquals("$sdk", device or location, meshServiceTypes(sdk, sharing = false, locationAllowed = true))
            assertEquals("$sdk, location refused", device, meshServiceTypes(sdk, sharing = false, locationAllowed = false))
        }
    }

    @Test
    fun fromAndroid12OnlyAShareAddsLocation() {
        for (sdk in 31..36) {
            assertEquals("$sdk", device, meshServiceTypes(sdk, sharing = false, locationAllowed = true))
            assertEquals("$sdk", device or location, meshServiceTypes(sdk, sharing = true, locationAllowed = true))
        }
    }

    @Test
    fun beforeAndroid10ThereAreNoTypes() {
        assertEquals(0, meshServiceTypes(28, sharing = true, locationAllowed = true))
    }
}
