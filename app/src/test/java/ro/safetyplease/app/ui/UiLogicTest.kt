package ro.safetyplease.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.MainActivity
import ro.safetyplease.app.platform.location.GeoFix
import ro.safetyplease.app.ui.common.Nearby
import ro.safetyplease.app.ui.common.helpOrigin
import ro.safetyplease.app.ui.common.onSite
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.mesh.SeenPeer
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.LocationFields
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone

class UiLogicTest {
    private fun square(id: String, lat: Double, lon: Double) =
        Zone(id, id, "#000000", listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 1), GeoPoint(lat + 1, lon + 1), GeoPoint(lat + 1, lon)))

    // two adjacent zones: A between lon 0 and 1, B between lon 1 and 2
    private val venue = Venue("test", Bounds(0.0, 0.0, 1.0, 3.0), listOf(square("a", 0.0, 0.0), square("b", 0.0, 1.0)))
    private val inA = GeoPoint(0.5, 0.5)
    private val inB = GeoPoint(0.5, 1.5)
    private val outside = GeoPoint(0.5, 2.5)

    // --- current position ---

    private val t0 = 1_000_000L
    private val fix = GeoFix(inA.lat, inA.lon, 5f, t0)

    // --- distances ---

    @Test
    fun distancesAreMeasuredOnlyFromInsideTheVenue() {
        assertEquals(inA, onSite(inA, venue))
        assertNull("at home, far from the venue", onSite(GeoPoint(44.43, 26.10), venue))
        assertNull(onSite(null, venue))
    }

    @Test
    fun awayFromTheVenueTheWayToHelpStartsFromTheZonePickedByHand() {
        assertEquals(venue.zone("b")!!.center, helpOrigin(GeoPoint(44.43, 26.10), null, "b", venue))
        assertEquals(inA, helpOrigin(inA, null, "b", venue))
    }

    // --- zone picked by hand ---

    @Test
    fun aZonePickedByHandShowsItsAgeOnceItIsFiveMinutesOld() {
        val picked = Settings(manualZone = "bar", manualZoneAt = t0)
        assertNull(pickedZoneAge(picked, t0 + 5 * 60_000L - 1))
        assertEquals(5 * 60_000L, pickedZoneAge(picked, t0 + 5 * 60_000L))
        assertNull("no zone", pickedZoneAge(Settings(manualZoneAt = t0), t0 + 60 * 60_000L))
        assertNull("picked before the time was kept", pickedZoneAge(Settings(manualZone = "bar"), t0))
    }

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
        assertNull("40 minutes later the old zone is no longer ours", zoneAt(t0 + 40 * 60_000))
    }

    @Test
    fun simulatedPositionWinsEvenOverAStaleFix() {
        val simulated = Settings(simLat = inB.lat, simLon = inB.lon)
        assertEquals(inB, currentPoint(simulated, fix, t0))
        assertEquals(inB, currentPoint(simulated, fix, t0 + 10 * GeoFix.FRESH_MS))
        assertEquals(inB, currentPoint(simulated, null, t0))
    }

    // --- friend's zone coordinates ---

    @Test
    fun chatCoordinatesMatchingTheZoneAreKept() {
        assertEquals(inA, chatPoint("a", inA, venue))
        assertEquals(outside, chatPoint("", outside, venue))
    }

    @Test
    fun chatCoordinatesContradictingTheZoneAreDropped() {
        assertNull("the user picked B but stands in A", chatPoint("b", inA, venue))
        assertNull(chatPoint("a", null, venue))
    }

    // --- where a report is written from ---

    private val landmarks = venue.copy(
        pois = listOf(Poi("wc", 0.5, 0.5005, "Toalete", number = 4), Poi("water", 0.5, 0.5002, "Apă"), Poi("exit", 0.5, 0.6, "Poarta", number = 1)),
    )

    @Test
    fun reportCarriesThePositionWhateverZoneWasPicked() {
        val spot = reportSpot(Settings(manualZone = "b"), fix, t0, landmarks)!!
        assertEquals(inA, spot.point)
        assertEquals(5, spot.accuracyM)
        assertEquals(4, spot.landmark?.number)
        assertEquals(LocationFields(5, 4), spot.fields)
    }

    @Test
    fun reportUsesOnlyAFixFromTheLastTwoMinutes() {
        assertEquals(inA, reportSpot(Settings(), fix, t0 + GeoFix.FRESH_MS - 1, venue)?.point)
        assertNull(reportSpot(Settings(), fix, t0 + GeoFix.FRESH_MS, venue))
        assertNull(reportSpot(Settings(), null, t0, venue))
    }

    @Test
    fun simulatedPositionHasNoAccuracy() {
        val spot = reportSpot(Settings(simLat = inB.lat, simLon = inB.lon), fix, t0, venue)!!
        assertEquals(inB, spot.point)
        assertEquals(0, spot.accuracyM)
        assertNull("nothing to add to the coordinates", spot.fields)
    }

    @Test
    fun accuracyIsRoundedUpAndCapped() {
        assertEquals(13, reportSpot(Settings(), fix.copy(accuracyM = 12.2f), t0, venue)!!.accuracyM)
        assertEquals(LocationFields.MAX_ACCURACY_M, reportSpot(Settings(), fix.copy(accuracyM = 900f), t0, venue)!!.accuracyM)
        assertEquals(0, reportSpot(Settings(), fix.copy(accuracyM = 0f), t0, venue)!!.accuracyM)
    }

    // --- who is nearby ---

    private val friend = 0x1234_5678_0000_0001L
    private val prefix = (friend ushr 32).toInt()

    private fun link(peer: Long) = LinkInfo(1, "AA:BB", peer, null, 0, true, 0)

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
    fun readyLinksCountsOnlyIdentifiedLinks() {
        val state = MeshState(links = listOf(link(friend), link(0L), link(0x2222_0000_0000_0002L)))
        assertEquals(2, Nearby.of(state).readyLinks)
    }

    // --- screen stack ---

    @Test
    fun doubleTapDoesNotPushTheSameScreenTwice() {
        val stack = mutableListOf<Dest>()
        stack.push(Dest.Conversation("f:1"))
        stack.push(Dest.Conversation("f:1"))
        assertEquals(listOf<Dest>(Dest.Conversation("f:1")), stack)
        stack.push(Dest.Profile("f:1"))
        assertEquals(2, stack.size)
    }

    // --- clearing closed ones ---

    private fun report(id: String, status: Int = AckStatus.NONE, cancelled: Boolean = false) = MyReport(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "a", createdAt = 0,
        status = status, cancelled = cancelled,
    )

    @Test
    fun onlyReportsThatNoLongerWaitForHelpAreClosed() {
        val open = listOf(report("nou"), report("primit", AckStatus.RECEIVED), report("preluat", AckStatus.ACKNOWLEDGED))
        val closed = listOf(
            report("rezolvat", AckStatus.RESOLVED),
            report("se-anuleaza", AckStatus.RECEIVED, cancelled = true),
            report("anulat", AckStatus.CANCELLED, cancelled = true),
        )
        assertTrue(open.all { it.isOpen() })
        assertEquals(listOf("rezolvat", "se-anuleaza", "anulat"), closedReports(open + closed))
        assertEquals(emptyList<String>(), closedReports(open))
    }

    private fun staff(id: String, zone: String, atMin: Int, status: Int) = StaffIncident(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = Severity.MEDIUM, zone = zone,
        reportedAt = atMin * 60_000L, receivedAt = atMin * 60_000L, hops = 2, status = status,
    )

    @Test
    fun clearingClosedIncidentsTakesEveryClosedReportOnItsOwn() {
        val incidents = listOf(
            staff("rezolvat", "a", 0, AckStatus.RESOLVED),
            staff("anulat", "a", 1, AckStatus.CANCELLED),
            staff("rezolvat-langa-deschis", "b", 0, AckStatus.RESOLVED),
            staff("deschis", "b", 1, AckStatus.RECEIVED),
            staff("preluat", "", 0, AckStatus.ACKNOWLEDGED),
        )
        assertEquals(
            "an open report from the same zone, a minute later, is another incident and stays",
            setOf("rezolvat", "anulat", "rezolvat-langa-deschis"), closedIncidents(incidents).toSet(),
        )
    }

    // --- links from outside the app ---

    @Test
    fun reportLinkOpensTheCategoriesWithNothingOnTop() {
        assertEquals(Tab.REPORT to null, landing(MainActivity.OPEN_REPORT))
    }

    @Test
    fun linksLandOnTheirTabAndScreen() {
        assertEquals(Tab.REPORT to Dest.ReportSent("ab12"), landing(MainActivity.OPEN_REPORT_PREFIX + "ab12"))
        assertEquals(Tab.MESSAGES to Dest.Conversation("f1"), landing(MainActivity.OPEN_CHAT_PREFIX + "f1"))
        assertEquals(Tab.INCIDENTS to null, landing(MainActivity.OPEN_INCIDENTS))
        assertNull("home keeps the tab the user was on", landing(MainActivity.OPEN_HOME))
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
