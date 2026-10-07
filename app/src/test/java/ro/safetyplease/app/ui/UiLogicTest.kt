package ro.safetyplease.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.data.Settings
import ro.safetyplease.app.location.GeoFix
import ro.safetyplease.app.mesh.LinkInfo
import ro.safetyplease.app.mesh.MeshState
import ro.safetyplease.app.mesh.RadioStatus
import ro.safetyplease.app.mesh.SeenPeer
import ro.safetyplease.app.ui.common.Nearby
import ro.safetyplease.app.venue.Bounds
import ro.safetyplease.app.venue.GeoPoint
import ro.safetyplease.app.venue.Venue
import ro.safetyplease.app.venue.Zone

class UiLogicTest {
    private fun square(id: String, lat: Double, lon: Double) =
        Zone(id, id, "#000000", listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 1), GeoPoint(lat + 1, lon + 1), GeoPoint(lat + 1, lon)))

    // doua zone alaturate: A intre lon 0 si 1, B intre lon 1 si 2
    private val venue = Venue("test", Bounds(0.0, 0.0, 1.0, 3.0), listOf(square("a", 0.0, 0.0), square("b", 0.0, 1.0)))
    private val inA = GeoPoint(0.5, 0.5)
    private val inB = GeoPoint(0.5, 1.5)
    private val outside = GeoPoint(0.5, 2.5)

    // --- pozitia curenta ---

    private val t0 = 1_000_000L
    private val fix = GeoFix(inA.lat, inA.lon, 5f, t0)

    @Test
    fun freshFixIsThePosition() {
        assertEquals(inA, currentPoint(Settings(), fix, t0))
        assertEquals(inA, currentPoint(Settings(), fix, t0 + GeoFix.FRESH_MS - 1))
    }

    @Test
    fun oldFixExpiresWithoutAnyNewEvent() {
        assertNull(currentPoint(Settings(), fix, t0 + GeoFix.FRESH_MS))
        assertNull(currentPoint(Settings(), fix, t0 + GeoFix.FRESH_MS + 1))
        assertNull(currentPoint(Settings(), null, t0))
    }

    @Test
    fun zoneFollowsOnlyAFreshFix() {
        fun zoneAt(nowMs: Long) = currentPoint(Settings(), fix, nowMs)?.let { venue.zoneAt(it.lat, it.lon) }?.id
        assertEquals("a", zoneAt(t0 + 60_000))
        assertNull("40 de minute mai tarziu zona veche nu mai e a noastra", zoneAt(t0 + 40 * 60_000))
    }

    @Test
    fun simulatedPositionWinsEvenOverAStaleFix() {
        val simulated = Settings(simLat = inB.lat, simLon = inB.lon)
        assertEquals(inB, currentPoint(simulated, fix, t0))
        assertEquals(inB, currentPoint(simulated, fix, t0 + 10 * GeoFix.FRESH_MS))
        assertEquals(inB, currentPoint(simulated, null, t0))
    }

    // --- coordonatele raportului ---

    @Test
    fun coordinatesMatchingTheChosenZoneAreKept() {
        assertEquals(inA, reportPoint("a", inA, venue))
    }

    @Test
    fun coordinatesContradictingTheChosenZoneAreDropped() {
        assertNull("omul a ales B, dar sta in A", reportPoint("b", inA, venue))
        assertNull("zona aleasa, pozitia in afara tuturor zonelor", reportPoint("a", outside, venue))
    }

    @Test
    fun withoutAZoneThePositionGoesAsItIs() {
        assertEquals(inA, reportPoint("", inA, venue))
        assertEquals(outside, reportPoint("", outside, venue))
        assertNull(reportPoint("a", null, venue))
    }

    // --- cine e aproape ---

    private val friend = 0x1234_5678_0000_0001L
    private val prefix = (friend ushr 32).toInt()

    private fun link(peer: Long, muted: Boolean = false) = LinkInfo(1, "AA:BB", peer, null, 0, true, muted, 0)

    @Test
    fun nearbyIgnoresSignalAndTimingChurn() {
        val a = MeshState(seen = listOf(SeenPeer("AA", prefix, 0, -60, 1_000)), relayed = 3)
        val b = MeshState(seen = listOf(SeenPeer("AA", prefix, 0, -75, 2_000)), relayed = 9, received = 40)
        assertEquals(Nearby.of(a), Nearby.of(b))
    }

    @Test
    fun nearbyChangesWhenSomeoneLinksOrLeaves() {
        val alone = Nearby.of(MeshState())
        val seen = Nearby.of(MeshState(seen = listOf(SeenPeer("AA", prefix, 0, -60, 1_000))))
        val linked = Nearby.of(MeshState(links = listOf(link(friend))))
        assertTrue(alone != seen)
        assertTrue(seen != linked)
        assertTrue(Nearby.of(MeshState(radio = RadioStatus(bluetoothOn = true))) != alone)
    }

    @Test
    fun inRangeByLinkOrByScanPrefix() {
        val seen = Nearby.of(MeshState(seen = listOf(SeenPeer("AA", prefix, 0, -60, 1_000), SeenPeer("BB", null, 0, -90, 1_000))))
        assertTrue(seen.isInRange(friend))
        assertFalse(seen.isLinked(friend))
        assertFalse(seen.isInRange(0x7777_0000_0000_0001L))

        val linked = Nearby.of(MeshState(links = listOf(link(friend))))
        assertTrue(linked.isLinked(friend))
        assertTrue(linked.isInRange(friend))
    }

    @Test
    fun readyLinksCountsOnlyIdentifiedUnmutedLinks() {
        val state = MeshState(links = listOf(link(friend), link(0L), link(0x2222_0000_0000_0002L, muted = true)))
        assertEquals(1, Nearby.of(state).readyLinks)
    }

    // --- stiva de ecrane ---

    @Test
    fun doubleTapDoesNotPushTheSameScreenTwice() {
        val stack = mutableListOf<Dest>()
        stack.push(Dest.Conversation("f:1"))
        stack.push(Dest.Conversation("f:1"))
        assertEquals(listOf<Dest>(Dest.Conversation("f:1")), stack)
        stack.push(Dest.Profile("f:1"))
        assertEquals(2, stack.size)
    }

    @Test
    fun myReportsLinkFromAReportOpenedInTheListGoesBackToTheList() {
        val stack = mutableListOf(Dest.MyReports, Dest.ReportSent("x"))
        stack.removeAt(stack.lastIndex)
        stack.push(Dest.MyReports)
        assertEquals(listOf<Dest>(Dest.MyReports), stack)

        val fromReport = mutableListOf<Dest>(Dest.ReportSent("y"))
        fromReport.removeAt(fromReport.lastIndex)
        fromReport.push(Dest.MyReports)
        assertEquals(listOf<Dest>(Dest.MyReports), fromReport)
    }
}
