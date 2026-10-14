package ro.safetyplease.app.platform.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExemptionTest {
    @Test
    fun vendorsThatKillBackgroundAppsMatchWhateverTheCase() {
        for (vendor in listOf("samsung", "Samsung", "Xiaomi", "Redmi", "POCO", "HUAWEI", "OnePlus", "OPPO", "vivo")) {
            assertTrue(vendor, killsBackgroundApps(vendor))
        }
    }

    @Test
    fun otherVendorsAreLeftAlone() {
        for (vendor in listOf("Google", "motorola", "Fairphone", "")) assertFalse(vendor, killsBackgroundApps(vendor))
    }

    @Test
    fun fromAndroid12EveryPhoneNeedsTheExemptionToRestartTheMesh() {
        for (vendor in listOf("motorola", "Google", "Nokia", "Sony", "TCL", "Fairphone", "samsung")) {
            assertTrue(vendor, needsExemption(31, vendor))
            assertTrue(vendor, needsExemption(36, vendor))
        }
    }

    @Test
    fun beforeAndroid12OnlyTheVendorsThatKillBackgroundAppsNeedIt() {
        assertTrue(needsExemption(30, "Xiaomi"))
        assertTrue(needsExemption(26, "HUAWEI"))
        assertFalse(needsExemption(30, "motorola"))
        assertFalse(needsExemption(29, "Google"))
    }

    @Test
    fun subBrandsGetTheirParentVendorsSteps() {
        assertEquals(BatteryVendor.XIAOMI, batteryVendor("POCO"))
        assertEquals(BatteryVendor.XIAOMI, batteryVendor("Redmi"))
        assertEquals(BatteryVendor.HUAWEI, batteryVendor("HONOR"))
        assertEquals(BatteryVendor.OPPO, batteryVendor("realme"))
        assertEquals(BatteryVendor.VIVO, batteryVendor("iQOO"))
        assertEquals(BatteryVendor.ONEPLUS, batteryVendor("OnePlus"))
        assertEquals(BatteryVendor.SAMSUNG, batteryVendor("samsung"))
        assertNull(batteryVendor("Google"))
    }
}
