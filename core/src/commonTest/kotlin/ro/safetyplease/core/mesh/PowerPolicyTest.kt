package ro.safetyplease.core.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PowerPolicyTest {
    private enum class Role(val staff: Boolean, val anchor: Boolean) {
        PARTICIPANT(false, false), STAFF(true, false), ANCHOR(false, true)
    }

    private val bools = listOf(false, true)

    private fun plan(
        role: Role,
        battery: Int = 80,
        charging: Boolean = false,
        screenOn: Boolean = false,
        boost: Boolean = false,
        pending: Boolean = false,
    ) = PowerPolicy.plan(role.staff, role.anchor, battery, charging, screenOn, boost, pending)

    @Test
    fun profileFollowsRoleUntilTheBatteryRunsLow() {
        assertEquals(PowerProfile.LEAF, PowerPolicy.profile(staff = false, anchor = false, batteryPercent = 80, charging = false))
        assertEquals(PowerProfile.STAFF, PowerPolicy.profile(staff = true, anchor = false, batteryPercent = 80, charging = false))
        assertEquals(PowerProfile.ANCHOR, PowerPolicy.profile(staff = false, anchor = true, batteryPercent = 80, charging = false))
        for (role in Role.entries) {
            assertEquals(PowerProfile.ECONOMY, PowerPolicy.profile(role.staff, role.anchor, batteryPercent = 19, charging = false))
            assertNotEquals(PowerProfile.ECONOMY, PowerPolicy.profile(role.staff, role.anchor, 20, false), "20% is not low yet")
            assertNotEquals(PowerProfile.ECONOMY, PowerPolicy.profile(role.staff, role.anchor, 5, true), "charging lifts economy")
        }
    }

    @Test
    fun lowBatteryStartsBelowTwentyAndTextOnlyBelowFiveAndAChargerLiftsBoth() {
        assertFalse(PowerPolicy.lowBattery(20, charging = false))
        assertTrue(PowerPolicy.lowBattery(19, charging = false))
        assertFalse(PowerPolicy.lowBattery(19, charging = true))
        assertFalse(PowerPolicy.textOnly(5, charging = false))
        assertTrue(PowerPolicy.textOnly(4, charging = false))
        assertTrue(PowerPolicy.textOnly(0, charging = false))
        assertFalse(PowerPolicy.textOnly(4, charging = true))
        for (battery in 0..100) {
            if (PowerPolicy.textOnly(battery, charging = false)) assertTrue(PowerPolicy.lowBattery(battery, charging = false), "$battery%")
        }
    }

    @Test
    fun theTableOfProfiles() {
        val leafDuty = PowerPolicy.LEAF_DUTY
        val economyDuty = PowerPolicy.ECONOMY_DUTY
        val continuous = ScanDuty.CONTINUOUS
        assertEquals(PowerPlan(PowerProfile.LEAF, PowerMode.BALANCED, PowerMode.LOW_POWER, leafDuty), plan(Role.PARTICIPANT))
        assertEquals(PowerPlan(PowerProfile.STAFF, PowerMode.BALANCED, PowerMode.BALANCED, continuous), plan(Role.STAFF))
        assertEquals(PowerPlan(PowerProfile.ANCHOR, PowerMode.BALANCED, PowerMode.BALANCED, continuous), plan(Role.ANCHOR))
        for (role in Role.entries) {
            assertEquals(PowerPlan(PowerProfile.ECONOMY, PowerMode.LOW_POWER, PowerMode.LOW_POWER, economyDuty), plan(role, battery = 10))
        }
    }

    @Test
    fun advertisingNeverChangesBecauseOfAReportOrTheScreen() {
        for (role in Role.entries) for (battery in listOf(5, 50)) for (charging in bools) {
            val quiet = plan(role, battery, charging)
            for (screenOn in bools) for (boost in bools) for (pending in bools) {
                val p = plan(role, battery, charging, screenOn, boost, pending)
                assertEquals(quiet.advertise, p.advertise, "$role $battery% $screenOn $boost $pending")
                assertEquals(quiet.profile, p.profile)
                assertNotEquals(PowerMode.LOW_LATENCY, p.advertise, "LOW_LATENCY advertising is never used")
            }
        }
    }

    @Test
    fun lowLatencyScanningOnlyInTheBurstAfterAReport() {
        for (role in Role.entries) for (battery in listOf(5, 50)) for (screenOn in bools) for (pending in bools) {
            val calm = plan(role, battery, screenOn = screenOn, pending = pending)
            assertNotEquals(PowerMode.LOW_LATENCY, calm.scan)
            val burst = plan(role, battery, screenOn = screenOn, boost = true, pending = pending)
            assertEquals(PowerMode.LOW_LATENCY, burst.scan, "even on low battery")
            assertTrue(burst.duty.continuous, "no pause during the burst")
        }
    }

    @Test
    fun aPendingReportKeepsTheScanFromPausing() {
        for (role in Role.entries) for (battery in listOf(5, 50)) {
            val waiting = plan(role, battery, pending = true)
            assertTrue(waiting.duty.continuous, "$role $battery%")
            assertEquals(plan(role, battery).scan, waiting.scan, "the scan mode stays the profile's")
        }
    }

    @Test
    fun aLitScreenKeepsOnlyALeafScanning() {
        assertFalse(plan(Role.PARTICIPANT, screenOn = false).duty.continuous)
        assertTrue(plan(Role.PARTICIPANT, screenOn = true).duty.continuous)
        assertEquals(PowerPolicy.ECONOMY_DUTY, plan(Role.STAFF, battery = 10, screenOn = true).duty, "economy pauses even with the screen on")
        assertTrue(plan(Role.ANCHOR, screenOn = false).duty.continuous)
    }

    @Test
    fun dutyMaths() {
        val d = ScanDuty(windowMs = 10_000, intervalMs = 25_000)
        assertFalse(d.continuous)
        assertEquals(15_000L, d.offMs)
        assertEquals(0.4, d.fraction, 1e-9)
        assertEquals(2L, d.startsIn(30_000))
        assertEquals(1L, d.startsIn(25_000))
        assertEquals("10000/25000 ms", d.toString())
        assertTrue(ScanDuty.CONTINUOUS.continuous)
        assertEquals(0L, ScanDuty.CONTINUOUS.offMs)
        assertEquals(1.0, ScanDuty.CONTINUOUS.fraction, 1e-9)
        assertEquals(0L, ScanDuty.CONTINUOUS.startsIn(30_000))
        assertTrue(ScanDuty(7_000, 7_000).continuous)
        assertFailsWith<IllegalArgumentException> { ScanDuty(0, 1_000) }
        assertFailsWith<IllegalArgumentException> { ScanDuty(2_000, 1_000) }
    }

    @Test
    fun dutyCyclesFitAndroidAndTheConnectionPolicy() {
        for (duty in listOf(PowerPolicy.LEAF_DUTY, PowerPolicy.ECONOMY_DUTY)) {
            assertTrue(duty.startsIn(30_000) <= 2, "leaves room under Android's 5 starts in 30 s for mode changes")
            assertTrue(duty.offMs < ConnectionPolicy.FRESH_MS, "a peer heard at the end of one window is still fresh at the next")
            assertTrue(duty.windowMs >= 10_240, "the window spans at least one screen-off LOW_POWER scan interval of the stack")
            assertTrue(duty.fraction < 1.0)
        }
    }
}
