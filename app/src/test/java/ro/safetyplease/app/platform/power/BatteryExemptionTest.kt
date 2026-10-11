package ro.safetyplease.app.platform.power

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExemptionTest {
    @Test
    fun vendorsThatKillBackgroundAppsMatchWhateverTheCase() {
        for (vendor in listOf("samsung", "Samsung", "Xiaomi", "Redmi", "POCO")) assertTrue(vendor, killsBackgroundApps(vendor))
    }

    @Test
    fun otherVendorsAreLeftAlone() {
        for (vendor in listOf("Google", "motorola", "OnePlus", "")) assertFalse(vendor, killsBackgroundApps(vendor))
    }
}
