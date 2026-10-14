package ro.safetyplease.app.platform.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssPrewarmTest {
    @Test
    fun androidEightAndNineAskForXtraTheLaterOnesForPsdsAndAllForTheTime() {
        val xtra = listOf("force_xtra_injection", "force_time_injection")
        val psds = listOf("force_psds_injection", "force_time_injection")
        assertEquals(xtra, prewarmCommands(26))
        assertEquals(xtra, prewarmCommands(28))
        assertEquals(psds, prewarmCommands(29))
        assertEquals(psds, prewarmCommands(36))
    }

    @Test
    fun theOrbitsAreFetchedAtOnceAndThenAtMostEveryTwelveHours() {
        assertTrue(injectionDue(null, 5_000))
        assertFalse(injectionDue(5_000, 5_000 + GnssPrewarm.EVERY_MS - 1))
        assertTrue(injectionDue(5_000, 5_000 + GnssPrewarm.EVERY_MS))
        assertEquals(12 * 3_600_000L, GnssPrewarm.EVERY_MS)
    }
}
