package ro.safetyplease.app.demo

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.core.ble.Advert
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.MeshCounters
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.SeenPeer

class MeasureTest {
    private fun columns(line: String) = line.split(',').size

    // --- field CSV ---

    @Test
    fun everyRowHasEveryColumn() {
        val header = columns(FieldRow.HEADER)
        assertEquals(header, columns(FieldRow("adv", peer = "0a0b0c0d", address = "AA:BB", rssi = -70, phyRx = "1M").csv(1, 2)))
        assertEquals(header, columns(FieldRow.stat("battery_pct", 87).csv(1, 2)))
        assertEquals(header, columns(FieldRow("test_echo", seq = 3, hops = 2, hopsBack = 1, rttMs = 840).csv(1, 2)))
    }

    @Test
    fun unusedColumnsStayEmpty() {
        assertEquals("1000,5,stat,,,,,,,,,,battery_pct,87", FieldRow.stat("battery_pct", 87).csv(1_000, 5))
        assertEquals("1000,5,test_echo,0a0b0c0d,,,,,3,2,1,840,,", FieldRow("test_echo", "0a0b0c0d", seq = 3, hops = 2, hopsBack = 1, rttMs = 840).csv(1_000, 5))
    }

    @Test
    fun aValueCannotBreakTheColumns() {
        assertEquals(columns(FieldRow.HEADER), columns(FieldRow.info("device", "Maker, Inc. Phone\nPro").csv(0, 0)))
    }

    @Test
    fun phyNamesFollowAndroidValues() {
        assertEquals(listOf("1M", "2M", "coded", "9"), listOf(1, 2, 3, 9).map(::phyName))
    }

    // --- scan side of the field recording ---

    @Test
    fun resultWithoutScanResponseGetsThePrefixItsAddressShowed() {
        val scan = FieldScan()
        assertEquals("", scan.row(Advert("A", null, 1, false, -70)).peer)
        assertEquals("0000000c", scan.row(Advert("A", 12, 1, false, -71)).peer)
        val bare = scan.row(Advert("A", null, 1, false, -72))
        assertEquals("0000000c", bare.peer)
        assertEquals(-72, bare.rssi)
        assertEquals("", scan.row(Advert("B", null, 1, false, -60)).peer)
    }

    @Test
    fun rowCarriesTheAdvertisingPhy() {
        val scan = FieldScan()
        assertEquals("coded", scan.row(Advert("C", 5, 1, true, -90)).phyRx)
        assertEquals("1M", scan.row(Advert("L", 5, 1, false, -60)).phyRx)
    }

    @Test
    fun scanResponseRateCountsLegacyResultsLikeTheRadioLog() {
        val scan = FieldScan()
        scan.row(Advert("A", null, 1, false, -70))
        scan.row(Advert("A", 3, 1, false, -70))
        scan.row(Advert("B", null, 1, false, -70))
        scan.row(Advert("C", 4, 1, true, -70))
        assertEquals(listOf("scan_results" to "3", "scan_without_response" to "2"), scan.drain().map { it.name to it.value })
        assertEquals(listOf("0", "0"), scan.drain().map { it.value })
    }

    @Test
    fun meshCountersComeFromTheEngineState() {
        val state = MeshState(
            links = listOf(LinkInfo(1, "A", 7L, null, 1, true, 0), LinkInfo(2, "B", 0L, null, 1, false, 0)),
            seen = listOf(SeenPeer("A", 1, 1, -60, 0), SeenPeer("B", 1, 1, -70, 0), SeenPeer("C", 2, 1, -80, 0)),
            sent = 10, received = 20, relayed = 5, dropped = 2, queueFull = 4, cachedReports = 3,
            counters = MeshCounters(
                drops = mapOf(DropReason.RATE_LIMIT to 1L, DropReason.DUPLICATE to 1L), storeAndForward = 6, refusedBanned = 7,
                reportHops = mapOf(3 to 2L, 1 to 1L), ackHops = mapOf(2 to 4L), linksOpened = 8, linksClosed = 5,
            ),
        )
        assertEquals(
            listOf("mesh_sent" to "10", "mesh_received" to "20", "mesh_relayed" to "5", "mesh_dropped" to "2",
                "mesh_queue_full" to "4", "mesh_refused_banned" to "7", "mesh_store_and_forward" to "6",
                "mesh_links_opened" to "8", "mesh_links_closed" to "5",
                "cached_reports" to "3", "links" to "1", "visible_peers" to "2", "drop_duplicate" to "1", "drop_rate_limit" to "1",
                "report_hops_1" to "1", "report_hops_3" to "2", "ack_hops_2" to "4"),
            meshStats(state).map { it.name to it.value },
        )
    }

    // --- census ---

    @Test
    fun censusCountsAdvertisersAndAdvertisementsSeparately() {
        val census = CensusCounter()
        repeat(5) { census.heard("A", -60) }
        census.heard("B", -95)
        val cells = census.drain().split(',').map(String::toInt)
        assertEquals(listOf(2, 6), cells.take(2))
        assertEquals(6, cells.drop(2).sum())
    }

    @Test
    fun censusWindowStartsEmpty() {
        val census = CensusCounter()
        census.heard("A", -60)
        census.drain()
        assertEquals("0,0,0,0,0,0,0,0,0,0,0", census.drain())
    }

    @Test
    fun censusRowsFitTheHeader() {
        assertEquals(columns(CensusCounter.HEADER), 2 + columns(CensusCounter().drain()))
    }

    @Test
    fun rssiBucketsAreTenDecibelsWithOpenEnds() {
        assertEquals(0, CensusCounter.bucket(-120))
        assertEquals(0, CensusCounter.bucket(-101))
        assertEquals(1, CensusCounter.bucket(-100))
        assertEquals(1, CensusCounter.bucket(-91))
        assertEquals(2, CensusCounter.bucket(-90))
        assertEquals(7, CensusCounter.bucket(-31))
        assertEquals(8, CensusCounter.bucket(-30))
        assertEquals(8, CensusCounter.bucket(5))
    }

    @Test
    fun bucketsLineUpWithTheirColumns() {
        val names = CensusCounter.HEADER.split(',').drop(4)
        assertEquals("rssi_lt_-100", names[CensusCounter.bucket(-105)])
        assertEquals("rssi_-70", names[CensusCounter.bucket(-65)])
        assertEquals("rssi_ge_-30", names[CensusCounter.bucket(-20)])
    }
}
