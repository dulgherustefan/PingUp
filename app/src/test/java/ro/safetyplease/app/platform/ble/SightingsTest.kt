package ro.safetyplease.app.platform.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.mesh.PowerMode

class SightingsTest {
    // --- RSSI average ---

    @Test
    fun firstSampleIsTakenAsIs() {
        assertEquals(-70.0, smoothRssi(null, -70), 1e-9)
    }

    @Test
    fun averageMovesThirtyPercentTowardsTheNewSample() {
        assertEquals(-69.0, smoothRssi(-60.0, -90), 1e-9)
        assertEquals(-60.0, smoothRssi(-60.0, -60), 1e-9)
    }

    @Test
    fun throttledResultsStillFeedTheAverage() {
        val s = Sightings()
        assertEquals(-60, s.heard("A", 7, coded = false, rssi = -60, now = 0))
        assertNull(s.heard("A", 7, coded = false, rssi = -90, now = 100))
        // -60 -> -69 (dropped result) -> -75.3
        assertEquals(-75, s.heard("A", 7, coded = false, rssi = -90, now = 1_000))
    }

    @Test
    fun oneDeepFadeDoesNotReorderPeers() {
        val s = Sightings()
        var t = 0L
        repeat(5) {
            s.heard("A", 1, coded = false, rssi = -65, now = t)
            t += 1_000
        }
        val faded = s.heard("A", 1, coded = false, rssi = -90, now = t)!!
        assertTrue(faded > -75)
    }

    @Test
    fun forgottenAddressStartsAFreshAverage() {
        val s = Sightings()
        s.heard("A", 1, coded = false, rssi = -60, now = 0)
        assertEquals(listOf("A"), s.forget(BleConstants.SIGHTING_FORGET_MS + 1))
        assertEquals(-90, s.heard("A", 1, coded = false, rssi = -90, now = BleConstants.SIGHTING_FORGET_MS + 2))
    }

    @Test
    fun clearDropsAverages() {
        val s = Sightings()
        s.heard("A", 1, coded = false, rssi = -60, now = 0)
        s.clear()
        assertEquals(-90, s.heard("A", 1, coded = false, rssi = -90, now = 5_000))
    }

    // --- reporting window ---

    @Test
    fun reportWindowIsOneSecond() {
        assertTrue(shouldReport(null, false, full = true, now = 0))
        assertEquals(false, shouldReport(0, true, full = true, now = 999))
        assertTrue(shouldReport(0, true, full = true, now = 1_000))
    }

    @Test
    fun fullResultIsNotHiddenByOneWithoutScanResponse() {
        assertTrue(shouldReport(0, reportedFull = false, full = true, now = 300))
        val s = Sightings()
        assertEquals(-60, s.heard("A", null, coded = false, rssi = -60, now = 0))
        assertEquals(-60, s.heard("A", 9, coded = false, rssi = -60, now = 300))
    }

    @Test
    fun resultWithoutScanResponseDoesNotOverwriteARecentFullOne() {
        val s = Sightings()
        s.heard("A", 9, coded = false, rssi = -60, now = 0)
        assertNull(s.heard("A", null, coded = false, rssi = -60, now = 1_500))
        assertNull(s.heard("A", null, coded = false, rssi = -60, now = BleConstants.FULL_RESULT_HOLD_MS - 1))
        // no full result for 10 s, so report what we have
        assertEquals(-60, s.heard("A", null, coded = false, rssi = -60, now = BleConstants.FULL_RESULT_HOLD_MS))
    }

    @Test
    fun countsLegacyResultsWithoutScanResponse() {
        val s = Sightings()
        s.heard("A", 9, coded = false, rssi = -60, now = 0)
        s.heard("B", null, coded = false, rssi = -60, now = 0)
        s.heard("C", 3, coded = true, rssi = -60, now = 0)
        assertEquals(2 to 1, s.drainStats())
        assertEquals(0 to 0, s.drainStats())
    }

    // --- one address per prefix ---

    @Test
    fun legacyAddressWinsWhileRecentlyHeard() {
        assertEquals("L", preferredAddress("L", 0, "C", 2_900, now = 3_000))
        assertEquals("L", preferredAddress("L", 0, "C", 3_000, now = BleConstants.LEGACY_PREFERENCE_MS))
    }

    @Test
    fun codedAddressTakesOverWhenLegacyGoesQuiet() {
        assertEquals("C", preferredAddress("L", 0, "C", 3_500, now = 3_500))
    }

    @Test
    fun staleOnBothSidesPicksTheLastHeard() {
        assertEquals("L", preferredAddress("L", 5_000, "C", 1_000, now = 20_000))
        assertEquals("C", preferredAddress("L", 1_000, "C", 5_000, now = 20_000))
    }

    @Test
    fun singleKnownAddressIsUsed() {
        assertEquals("C", preferredAddress(null, 0, "C", 0, now = 0))
        assertEquals("L", preferredAddress("L", 0, null, 0, now = 60_000))
        assertNull(preferredAddress(null, 0, null, 0, now = 0))
    }

    @Test
    fun onePhoneOnBothPhysIsReportedUnderOneAddress() {
        val s = Sightings()
        assertEquals(-60, s.heard("L", 5, coded = false, rssi = -60, now = 0))
        assertNull(s.heard("C", 5, coded = true, rssi = -70, now = 500))
        assertNull(s.heard("C", 5, coded = true, rssi = -70, now = 2_000))
        // 1M not heard for over 3 s: switch to Coded
        assertEquals(-70, s.heard("C", 5, coded = true, rssi = -70, now = 3_500))
        // 1M is back and takes priority again
        assertEquals(-60, s.heard("L", 5, coded = false, rssi = -60, now = 4_000))
        assertNull(s.heard("C", 5, coded = true, rssi = -70, now = 4_600))
    }

    @Test
    fun legacyResultWithoutScanResponseStaysHiddenWhileCodedIsPreferred() {
        val s = Sightings()
        s.heard("L", 5, coded = false, rssi = -60, now = 0)
        s.heard("C", 5, coded = true, rssi = -80, now = 20_000)
        assertNull(s.heard("L", null, coded = false, rssi = -60, now = 20_500))
    }

    @Test
    fun differentPrefixesDoNotInterfere() {
        val s = Sightings()
        assertEquals(-60, s.heard("L", 5, coded = false, rssi = -60, now = 0))
        assertEquals(-80, s.heard("C", 6, coded = true, rssi = -80, now = 100))
    }

    // --- batches, filtered before anything reaches the engine thread ---

    private fun advert(address: String, prefix: Int?, rssi: Int, coded: Boolean = false) =
        Advert(address, prefix, flags = 1, coded = coded, rssi = rssi)

    @Test
    fun batchFromOnePhoneGivesOneReport() {
        val s = Sightings()
        val batch = listOf(advert("A", 7, -60), advert("A", 7, -90), advert("A", 7, -90))
        assertEquals(listOf(advert("A", 7, -60)), s.heard(batch, now = 0))
    }

    @Test
    fun throttledResultsOfABatchStillFeedTheAverage() {
        val s = Sightings()
        s.heard(listOf(advert("A", 7, -60), advert("A", 7, -90), advert("A", 7, -90)), now = 0)
        // -60 -> -69 -> -75.3 (dropped in the first batch) -> -79.71
        assertEquals(listOf(advert("A", 7, -80)), s.heard(listOf(advert("A", 7, -90)), now = 1_000))
    }

    @Test
    fun batchReportsEachPhoneOnce() {
        val s = Sightings()
        val batch = listOf(advert("A", 1, -60), advert("B", 2, -70), advert("A", 1, -60), advert("B", 2, -70))
        assertEquals(listOf("A", "B"), s.heard(batch, now = 0).map { it.address })
    }

    @Test
    fun batchKeepsTheFlagsOfTheReportedResult() {
        val s = Sightings()
        val staff = Advert("A", 1, flags = 9, coded = false, rssi = -60)
        assertEquals(9, s.heard(listOf(staff), now = 0).single().flags)
    }

    @Test
    fun batchWithBothPhysReportsTheLegacyAddress() {
        val s = Sightings()
        val batch = listOf(advert("L", 5, -60), advert("C", 5, -70, coded = true))
        assertEquals(listOf("L"), s.heard(batch, now = 0).map { it.address })
    }

    @Test
    fun fullResultLaterInTheBatchIsNotHidden() {
        val s = Sightings()
        val reports = s.heard(listOf(advert("A", null, -60), advert("A", 9, -60)), now = 0)
        assertEquals(listOf(null, 9), reports.map { it.prefix })
    }

    @Test
    fun everyResultOfABatchCountsForScanResponseStats() {
        val s = Sightings()
        s.heard(listOf(advert("A", null, -60), advert("A", null, -60), advert("A", 9, -60), advert("C", 3, -60, coded = true)), now = 0)
        assertEquals(3 to 2, s.drainStats())
    }

    @Test
    fun emptyBatchReportsNothing() {
        assertTrue(Sightings().heard(emptyList(), now = 0).isEmpty())
    }

    // --- batched scanning ---

    @Test
    fun scanIsBatchedOnlyWhereTheControllerQueuesResults() {
        assertEquals(BleConstants.SCAN_BATCH_DELAY_MS, scanReportDelay(PowerMode.BALANCED, offloadedBatching = true))
        assertEquals(BleConstants.SCAN_BATCH_DELAY_MS, scanReportDelay(PowerMode.LOW_POWER, offloadedBatching = true))
        assertEquals(0L, scanReportDelay(PowerMode.BALANCED, offloadedBatching = false))
    }

    @Test
    fun hurriedScanIsNeverBatched() {
        assertEquals(0L, scanReportDelay(PowerMode.LOW_LATENCY, offloadedBatching = true))
    }
}
