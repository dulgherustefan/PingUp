package ro.safetyplease.core.control

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.MobileReading
import ro.safetyplease.core.protocol.NodeFlags

class CoverageTest {
    private fun mobile(level: Int?, transport: Int, probe: Int, validated: Boolean = false) =
        MobileReading(level, MobileReading.TECH_UNKNOWN, transport, probe, validated)

    private fun anchor(prefix: Int, beatAt: Long, mobile: MobileReading?, square: Pair<Int, Int>?, links: Int = 1, crowd: Int = 1) =
        HeardPeer(
            prefix, NodeFlags.ANCHOR, false, beatAt,
            beat = Heartbeat(80, true, 30, 0, links, crowd, "A", mobile, square?.first, square?.second),
            beatAtMs = beatAt,
        )

    @Test
    fun whatTheAnchorsSayOfMobileData() {
        assertEquals(CellData.UNKNOWN, Coverage.data(null))
        assertEquals(CellData.NO_NETWORK, Coverage.data(mobile(null, MobileReading.TRANSPORT_NONE, MobileReading.PROBE_NOT_RUN)))
        assertEquals(CellData.UNKNOWN, Coverage.data(mobile(3, MobileReading.TRANSPORT_NONE, MobileReading.PROBE_NOT_RUN)))
        assertEquals(CellData.WORKS, Coverage.data(mobile(2, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_OK)))
        assertEquals(CellData.FAILS, Coverage.data(mobile(2, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_FAILED, true)))
        assertEquals(CellData.WORKS, Coverage.data(mobile(2, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_NOT_RUN, true)))
        assertEquals(
            "on Wi-Fi it says nothing of mobile data",
            CellData.UNKNOWN,
            Coverage.data(mobile(4, MobileReading.TRANSPORT_WIFI, MobileReading.PROBE_NOT_RUN, true)),
        )
    }

    @Test
    fun aSquareShowsItsWorstReadingAndOnlyFreshPlacedAnchorsCount() {
        val now = 100 * 60_000L
        val peers = listOf(
            anchor(1, now, mobile(3, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_OK), 2 to 3, links = 4, crowd = 2),
            anchor(2, now - 60_000, mobile(1, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_FAILED), 2 to 3, links = 1, crowd = 5),
            anchor(3, now, null, 0 to 0, links = 0, crowd = 0),
            anchor(4, now - Coverage.FRESH_MS, mobile(4, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_OK), 5 to 5),
            anchor(5, now, mobile(4, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_OK), null),
            HeardPeer(6, 0, true, now),
        )
        val squares = Coverage.squares(peers, now).associateBy { it.column to it.row }
        assertEquals("a stale anchor and one without a square leave no data", setOf(2 to 3, 0 to 0), squares.keys)
        assertEquals(SquareCoverage(2, 3, 2, 1, CellData.FAILS, 4, 5), squares.getValue(2 to 3))
        assertEquals(
            "an anchor without the network log still shows the mesh",
            SquareCoverage(0, 0, 1, null, CellData.UNKNOWN, 0, 0),
            squares.getValue(0 to 0),
        )
    }
}
