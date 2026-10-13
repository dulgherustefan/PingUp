package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import okio.Path
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.incidents.UpdateDraft
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.position.Regasire
import ro.safetyplease.core.position.Search
import ro.safetyplease.core.position.SearchDraft
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.LostAction
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "M-am pierdut de grup", the last gasp and the last position in a lost report, over the mesh (PROTOCOL.md, 6.6.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class RegasireTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val box = PositionBox(BOX_LAT, BOX_LON)

    /** [north] and [east] metres inside the test phones' grid box. */
    private fun spot(north: Int, east: Int): GeoPoint = box.toLatLon(east, north)

    private val venue = Venue(
        name = "Test",
        bounds = Bounds(BOX_LAT, BOX_LON, BOX_LAT + 0.01, BOX_LON + 0.01),
        zones = emptyList(),
        meetingPoint = spot(10, 10),
        pois = listOf(
            spot(150, 100).let { Poi("info", it.lat, it.lon, number = 1) },
            spot(280, 100).let { Poi("info", it.lat, it.lon, number = 2) },
        ),
    )

    /** A phone with its finder; [at] is where the phone is (its fused position), [battery] its level. */
    private class Finder(val phone: TestPhone, scope: TestScope, clock: TestClock, dir: Path, venue: Venue) {
        var at: GeoFix? = null
        var battery = 50
        val searches = JsonStore(dir / "${phone.name}-searches.json", ListSerializer(Search.serializer()), emptyList(), scope.backgroundScope)
        val reg = Regasire(
            scope.backgroundScope, phone.chat, phone.positions, clock, venue, phone.nodeId, searches,
            walk = { StraightLine }, mapState = { MapState.OPEN }, myPosition = { at }, battery = { battery }, frame = { phone.positionFrame },
        )
    }

    private class World(val scope: TestScope, val dir: Path, val venue: Venue) {
        val net = SimNet(scope.backgroundScope)
        val clock = TestClock(scope.testScheduler)
        val event = TestEvent()
        private var seed = 1700L

        fun finder(name: String) = Finder(TestPhone(name, scope, net, clock, dir, event, seed++), scope, clock, dir, venue)
    }

    /** Where the phone is, for its finder and for the positions it shares. */
    private fun Finder.moveTo(w: World, point: GeoPoint, accuracyM: Float = 5f) {
        at = GeoFix(point.lat, point.lon, accuracyM, w.clock.wallMs())
        phone.chat.onFix(point.lat, point.lon, accuracyM.toDouble(), null, PositionMethod.GNSS, w.clock.wallMs())
    }

    /** A, B and C in one group; only A has the others as friends. */
    private suspend fun TestScope.group(w: World): Triple<Finder, Finder, Finder> {
        val a = w.finder("A")
        val b = w.finder("B")
        val c = w.finder("C")
        a.phone.befriend(b.phone)
        a.phone.befriend(c.phone)
        w.net.start()
        advanceTimeBy(30_000)
        a.phone.chat.createGroup("Gașca", listOf(b.phone.nodeId, c.phone.nodeId))
        advanceTimeBy(5_000)
        return Triple(a, b, c)
    }

    private fun Finder.groupConversation() = Conversations.group(phone.groups.value.single().id)

    // --- "M-am pierdut de grup" ---

    @Test
    fun theGroupHearsTheFairestLandmarkAndVinAcoloSaysYesToIt() = runTest {
        val w = World(this, tmp.root, venue)
        val (a, b, c) = group(w)
        val conversation = a.groupConversation()
        a.moveTo(w, spot(0, 100))
        b.moveTo(w, spot(100, 100))
        c.moveTo(w, spot(300, 100))
        b.phone.chat.startShare(conversation, 30, SharePrecision.EXACT)
        c.phone.chat.startShare(conversation, 30, SharePrecision.EXACT)
        advanceTimeBy(10_000)
        assertEquals(2, a.phone.positions.value.records.size, "A knows where both are")

        assertTrue(a.reg.imLost(conversation))
        advanceTimeBy(10_000)

        for (other in listOf(b, c)) {
            val alert = other.reg.alerts.value.single()
            assertEquals(a.phone.nodeId, alert.sender)
            assertEquals(1, alert.landmark, "150 m for the farthest, against 280 m at landmark 2")
            assertEquals(50, alert.battery)
        }
        assertEquals(1, a.phone.chat.meetings.value.single().place.landmark)
        assertEquals(SharePrecision.EXACT, a.phone.chat.activeShares.value.single { it.conversation == conversation }.precision)
        assertEquals(1, a.reg.target.value?.landmark)

        b.reg.comeThere(b.reg.alerts.value.single())
        advanceTimeBy(10_000)
        assertTrue(b.reg.alerts.value.isEmpty())
        assertEquals(1, b.reg.target.value?.landmark)
        assertEquals(listOf(b.phone.nodeId), a.phone.chat.meetings.value.single().accepted, "B's yes reached the lost one")
        assertTrue(b.phone.chat.activeShares.value.any { it.conversation == conversation && it.precision == SharePrecision.EXACT })
    }

    @Test
    fun withNoFreshPositionTheVenueMeetingPointStandsIn() = runTest {
        val w = World(this, tmp.root, venue)
        val (a, b, _) = group(w)
        val conversation = a.groupConversation()
        a.moveTo(w, spot(0, 100))
        advanceTimeBy(11 * 60_000L)

        assertTrue(a.reg.imLost(conversation))
        advanceTimeBy(10_000)
        assertEquals(0, b.reg.alerts.value.single().landmark)
        assertTrue(a.phone.chat.meetings.value.single().place.venuePoint)
        assertEquals(venue.meetingPoint, a.reg.target.value?.point)
    }

    @Test
    fun oneActivationPerPersonEveryTenMinutesSentAndHeard() = runTest {
        val w = World(this, tmp.root, venue)
        val (a, b, _) = group(w)
        val conversation = a.groupConversation()
        a.moveTo(w, spot(0, 100))
        assertTrue(a.reg.imLost(conversation))
        assertFalse(a.reg.imLost(conversation), "the user can't send another yet")
        assertTrue(a.reg.activationWaitMs() > 9 * 60_000L)
        advanceTimeBy(10_000)
        val first = b.reg.alerts.value.single()

        // a phone that sends more often is not heard more often
        a.phone.chat.sendLost(conversation) { id, group -> Inner.LostSession(id, group, LostAction.START, 9, 15, 50, 2) }
        advanceTimeBy(10_000)
        assertEquals(first, b.reg.alerts.value.single())

        advanceTimeBy(Regasire.ACTIVATION_INTERVAL_MS)
        a.moveTo(w, spot(0, 100))
        assertTrue(a.reg.imLost(conversation))
        advanceTimeBy(10_000)
        assertNotEquals(first.msgId, b.reg.alerts.value.single().msgId, "the new one replaces it")
    }

    @Test
    fun underCongestionTheLostSessionGoesAndItsPositionsWait() = runTest {
        val w = World(this, tmp.root, venue)
        val (a, b, _) = group(w)
        val conversation = a.groupConversation()
        a.moveTo(w, spot(0, 100))
        // the air is full: 130 new packets this minute, more than the 75% that isn't for positions
        repeat(130) { a.phone.engine.unicast(UNKNOWN_TYPE, 0L, ByteArray(16), encrypted = false) }

        assertTrue(a.reg.imLost(conversation))
        advanceTimeBy(15_000)
        assertEquals(a.phone.nodeId, b.reg.alerts.value.single().sender, "the lost session is chat, not P2")
        assertNotNull(b.phone.positions.value.sessionOf(a.phone.nodeId, w.clock.wallMs()), "the share started")
        assertTrue(b.phone.positions.value.records.none { it.sender == a.phone.nodeId }, "but its position was dropped first")

        advanceTimeBy(60_000)
        assertTrue(b.phone.positions.value.records.any { it.sender == a.phone.nodeId }, "once the window clears it goes")
    }

    // --- the last gasp ---

    @Test
    fun theLastGaspGoesOnceOnlyToWhoWeShareWithAtTheirPrecision() = runTest {
        val w = World(this, tmp.root, venue)
        val a = w.finder("A")
        val exact = w.finder("B")
        val approximate = w.finder("C")
        val zone = w.finder("D")
        val none = w.finder("E")
        for (f in listOf(exact, approximate, zone, none)) a.phone.befriend(f.phone)
        w.net.start()
        advanceTimeBy(30_000)
        a.moveTo(w, spot(200, 200))
        a.phone.chat.startShare(Conversations.friend(exact.phone.nodeId), 60, SharePrecision.EXACT)
        a.phone.chat.startShare(Conversations.friend(approximate.phone.nodeId), 60, SharePrecision.APPROXIMATE)
        a.phone.chat.startShare(Conversations.friend(zone.phone.nodeId), 60, SharePrecision.ZONE)
        advanceTimeBy(70_000)
        a.moveTo(w, spot(200, 230))

        a.reg.onBattery(6, charging = false)
        advanceTimeBy(5_000)
        assertTrue(exact.reg.gasps.value.isEmpty())

        a.reg.onBattery(5, charging = false)
        advanceTimeBy(5_000)
        val gasp = exact.reg.gasps.value.single()
        assertEquals(5, gasp.battery)
        assertTrue(distanceMeters(spot(200, 230), GeoPoint(gasp.lat, gasp.lon)) <= 1, "exact for the exact share")
        assertEquals(gasp.lat, exact.phone.positions.value.records.single().lat, "and shown as their position")
        val masked = approximate.reg.gasps.value.single()
        val off = distanceMeters(spot(200, 230), GeoPoint(masked.lat, masked.lon))
        assertTrue(off in 1..91, "an approximate share gets the cell, $off m off")
        assertTrue(zone.reg.gasps.value.isEmpty(), "a zone-only share never gets a position")
        assertTrue(none.reg.gasps.value.isEmpty(), "nor does a friend we don't share with")

        a.reg.onBattery(4, charging = false)
        advanceTimeBy(5_000)
        assertEquals(gasp, exact.reg.gasps.value.single(), "once per fall to 5%")

        // on the charger and off again: a new fall sends a new one
        a.reg.onBattery(4, charging = true)
        a.moveTo(w, spot(200, 260))
        a.reg.onBattery(4, charging = false)
        advanceTimeBy(5_000)
        assertNotEquals(gasp, exact.reg.gasps.value.single())
    }

    @Test
    fun aLastGaspFromSomeoneWhoDoesNotShareWithUsIsDropped() = runTest {
        val w = World(this, tmp.root, venue)
        val a = w.finder("A")
        val b = w.finder("B")
        a.phone.befriend(b.phone)
        w.net.start()
        advanceTimeBy(30_000)
        val fix = ro.safetyplease.core.protocol.LastFix(BOX_LAT, BOX_LON, w.clock.wallMs() / 1000, 2)
        a.phone.chat.sendLastGasp(b.phone.nodeId, Inner.LastGasp(fix, 5))
        advanceTimeBy(5_000)
        assertTrue(b.reg.gasps.value.isEmpty())
        assertTrue(b.phone.positions.value.records.isEmpty())
    }

    @Test
    fun noFreshFixNoLastGasp() = runTest {
        val w = World(this, tmp.root, venue)
        val a = w.finder("A")
        val b = w.finder("B")
        a.phone.befriend(b.phone)
        w.net.start()
        advanceTimeBy(30_000)
        a.moveTo(w, spot(200, 200))
        a.phone.chat.startShare(Conversations.friend(b.phone.nodeId), 60, SharePrecision.EXACT)
        advanceTimeBy(3 * 60_000L)
        a.reg.onBattery(5, charging = false)
        advanceTimeBy(5_000)
        assertTrue(b.reg.gasps.value.isEmpty())
    }

    // --- the last position in a lost report ---

    @Test
    fun theLastPositionComesOnlyFromTheSearchedPersonAndOnlyWithTheReportersYes() = runTest {
        val w = World(this, tmp.root, venue)
        val a = w.finder("A")
        val b = w.finder("B")
        val c = w.finder("C")
        val d = w.finder("D")
        for (f in listOf(b, c, d)) a.phone.befriend(f.phone)
        w.net.start()
        advanceTimeBy(30_000)
        b.moveTo(w, spot(100, 100))
        c.moveTo(w, spot(300, 300))
        b.phone.chat.startShare(Conversations.friend(a.phone.nodeId), 60, SharePrecision.EXACT)
        c.phone.chat.startShare(Conversations.friend(a.phone.nodeId), 60, SharePrecision.EXACT)
        advanceTimeBy(5 * 60_000L)

        val seen = a.reg.lastSeenOf(b.phone.nodeId)!!
        assertTrue(distanceMeters(spot(100, 100), GeoPoint(seen.lat, seen.lon)) <= 1)
        assertEquals(5, seen.ageMin)
        assertNull(a.reg.lastSeenOf(d.phone.nodeId), "D shares nothing with A, so there is nothing of theirs to give")

        a.reg.setDraft(SearchDraft(b.phone.nodeId, attach = false))
        assertEquals(ReportExtras.NONE, a.reg.extrasFor(IncidentCategory.LOST_FRIEND), "without the yes, nothing")
        a.reg.setDraft(SearchDraft(b.phone.nodeId, attach = true))
        assertEquals(seen, a.reg.extrasFor(IncidentCategory.LOST_FRIEND).lastSeen, "B's own, never C's")
        assertEquals(seen, a.reg.extrasFor(IncidentCategory.LOST_CHILD).lastSeen)
        assertEquals(ReportExtras.NONE, a.reg.extrasFor(IncidentCategory.MEDICAL), "only a lost report carries it")
        a.reg.setDraft(SearchDraft(d.phone.nodeId, attach = true))
        assertEquals(ReportExtras.NONE, a.reg.extrasFor(IncidentCategory.LOST_FRIEND))
    }

    @Test
    fun staffSeeWhereTheFriendWasAndTheirSuntBineLetsTheReporterCloseTheReport() = runTest {
        val w = World(this, tmp.root, venue)
        val a = w.finder("A")
        val b = w.finder("B")
        val staff = w.finder("S")
        a.phone.befriend(b.phone)
        staff.phone.staffSecret = w.event.secret
        staff.phone.engine.setRole(staff = true, anchor = false)
        w.net.start()
        advanceTimeBy(30_000)
        b.moveTo(w, spot(120, 80))
        b.phone.chat.startShare(Conversations.friend(a.phone.nodeId), 60, SharePrecision.EXACT)
        advanceTimeBy(3 * 60_000L)

        a.reg.setDraft(SearchDraft(b.phone.nodeId, attach = true))
        val category = IncidentCategory.LOST_FRIEND
        val draft = ReportDraft(category, Severity.LOW, "", null, null, "", "A", extras = a.reg.extrasFor(category))
        assertTrue(a.phone.incidents.report(draft))
        val id = a.phone.myReports.single().incidentId
        a.reg.reportSent(id, category)
        advanceTimeBy(10_000)

        val incident = staff.phone.staffIncidents.single()
        val seen = assertNotNull(incident.lastSeen)
        assertTrue(distanceMeters(spot(120, 80), GeoPoint(seen.lat, seen.lon)) <= 1)
        assertEquals(incident.reportedAt - 3 * 60_000L, seen.atMs)
        assertNull(a.reg.draft.value, "the draft goes with the report")

        val search = b.reg.searchedBy.value.single()
        assertEquals(a.phone.nodeId, search.sender)
        b.reg.imOk(search)
        advanceTimeBy(10_000)
        assertTrue(b.reg.searchedBy.value.isEmpty())
        val reply = a.reg.found.value.single()
        assertEquals(id, reply.incidentId)

        assertTrue(a.phone.incidents.update(id, UpdateDraft(UpdateKind.RESOLVED_ITSELF)))
        a.reg.doneWith(reply)
        advanceTimeBy(10_000)
        assertEquals(UpdateKind.RESOLVED_ITSELF, staff.phone.staffIncidents.single().updates.single().kind)
        assertTrue(a.reg.found.value.isEmpty())
        assertTrue(a.searches.value.isEmpty())
    }

    @Test
    fun aSuntBineFromSomeoneNoReportLooksForChangesNothing() = runTest {
        val w = World(this, tmp.root, venue)
        val a = w.finder("A")
        val b = w.finder("B")
        a.phone.befriend(b.phone)
        w.net.start()
        advanceTimeBy(30_000)
        b.phone.chat.sendLost(Conversations.friend(a.phone.nodeId)) { id, _ -> Inner.LostSession(id, null, LostAction.FOUND, 0, 1, 50, 0) }
        advanceTimeBy(10_000)
        assertTrue(a.reg.found.value.isEmpty())
    }

    private companion object {
        /** A type this version only relays: it fills the air without going before chat. */
        const val UNKNOWN_TYPE = 0x40
    }
}
