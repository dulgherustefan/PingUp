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
