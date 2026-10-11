package ro.safetyplease.app.platform.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.mesh.ScanDuty

class ScanCycleTest {
    private val duty = ScanDuty(windowMs = 10_000, intervalMs = 25_000)

    @Test
    fun continuousNeverSchedulesAnything() {
        val cycle = ScanCycle()
        assertNull(cycle.nextAt(scanning = true, startedAt = 0))
        assertFalse(cycle.set(ScanDuty.CONTINUOUS))
    }

    @Test
    fun scansForTheWindowThenPausesForTheRest() {
        val cycle = ScanCycle()
        assertTrue(cycle.set(duty))
        assertEquals(15_000L, cycle.nextAt(scanning = true, startedAt = 5_000))
        cycle.pause(15_000)
        assertTrue(cycle.paused)
        assertEquals("pause lasts interval minus window", 30_000L, cycle.nextAt(scanning = false, startedAt = 5_000))
        cycle.resume()
        assertFalse(cycle.paused)
        assertNull("down for another reason: the periodic check restarts it", cycle.nextAt(scanning = false, startedAt = 5_000))
    }

    @Test
    fun theWindowCountsFromTheRealStartNotFromTheRequest() {
        val cycle = ScanCycle()
        cycle.set(duty)
        cycle.pause(10_000)
        // the restart was due at 25 s but waited for Android's spacing between starts
        cycle.resume()
        assertEquals(31_500L + 10_000, cycle.nextAt(scanning = true, startedAt = 31_500))
    }

    @Test
    fun aNewDutyEndsThePause() {
        val cycle = ScanCycle()
        cycle.set(duty)
        cycle.pause(10_000)
        assertFalse("the same duty again changes nothing", cycle.set(ScanDuty(10_000, 25_000)))
        assertTrue(cycle.paused)
        assertTrue(cycle.set(ScanDuty.CONTINUOUS))
        assertFalse(cycle.paused)
        assertNull(cycle.nextAt(scanning = true, startedAt = 10_000))
    }

    @Test
    fun overAnHourTheLeafScansTheDutyFraction() {
        val cycle = ScanCycle()
        cycle.set(PowerPolicy.LEAF_DUTY)
        var now = 0L
        var startedAt = 0L
        var scanning = true
        var scannedMs = 0L
        var starts = 1
        val hour = 3_600_000L
        while (now < hour) {
            val next = minOf(cycle.nextAt(scanning, startedAt)!!, hour)
            if (scanning) scannedMs += next - now
            now = next
            if (now == hour) break
            if (cycle.paused) {
                cycle.resume()
                scanning = true
                startedAt = now
                starts++
            } else {
                scanning = false
                cycle.pause(now)
            }
        }
        assertEquals(PowerPolicy.LEAF_DUTY.fraction, scannedMs.toDouble() / hour, 0.01)
        assertEquals(hour / PowerPolicy.LEAF_DUTY.intervalMs, starts.toLong())
    }
}
