package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.V10Node
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.NextUpdate
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.distanceMeters
import java.io.File
import kotlin.random.Random

/** Sharing a position with friends over the mesh (PROTOCOL.md, section 6.6). */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionSharingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class World(scope: TestScope, dir: File) {
        val net = SimNet(scope.backgroundScope)
        val clock = TestClock(scope.testScheduler)
        val event = TestEvent()
        private var seed = 900L
        val phone: (String) -> TestPhone = { name -> TestPhone(name, scope, net, clock, dir, event, seed++) }
    }

    private val box = PositionBox(BOX_LAT, BOX_LON)

    /** [north] and [east] metres inside the test phones' grid box. */
    private fun spot(north: Int, east: Int): GeoPoint = box.toLatLon(east, north)

    private fun TestPhone.fix(w: World, at: GeoPoint, accuracyM: Double = 4.0, headingDeg: Double? = 90.0) =
        chat.onFix(at.lat, at.lon, accuracyM, headingDeg, PositionMethod.GNSS, w.clock.wallMs())

    private fun TestPhone.privateSent(): Long = engine.state.value.counters.sentByType[PacketType.PRIVATE] ?: 0

    private suspend fun TestScope.pair(w: World): Pair<TestPhone, TestPhone> {
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        return a to b
    }

    @Test
    fun aFriendSeesWhereWeAreToTheMetreUntilWeStop() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val here = spot(1000, 2000)
        a.chat.startShare(Conversations.friend(b.nodeId), 30, SharePrecision.EXACT)
        a.fix(w, here)
        advanceTimeBy(10_000)

        val seen = b.chat.friendPositions.value.single()
        assertEquals(a.nodeId, seen.sender)
        assertTrue(distanceMeters(GeoPoint(seen.lat, seen.lon), here) <= 1)
        assertEquals(AccuracyClass.of(4.0), seen.accuracy)
        assertEquals(PositionMethod.GNSS, seen.method)
        assertEquals(90, seen.headingDeg)
        assertEquals(NextUpdate.of(120_000), seen.nextUpdate)
        assertTrue("the minute of the fix", w.clock.wallMs() - seen.fixAtMs in 0..70_000)
        assertEquals("B accepted the session", listOf(b.nodeId), a.chat.activeShares.value.single().accepted)

        advanceTimeBy(60_000)
        val moved = spot(1100, 2000)
        a.fix(w, moved)
        advanceTimeBy(5_000)
        assertTrue(distanceMeters(b.positions.value.records.single().let { GeoPoint(it.lat, it.lon) }, moved) <= 1)

        a.chat.stopShare(Conversations.friend(b.nodeId))
        advanceTimeBy(5_000)
        assertTrue("stopped", b.positions.value.records.isEmpty() && b.positions.value.incoming.isEmpty())
        assertEquals(a.nodeId, b.positions.value.lastSeen.single().sender)
        assertTrue("silently: no message, no notification", b.messages.isEmpty() && b.incoming.isEmpty())
        assertTrue(a.chat.activeShares.value.isEmpty())
    }

    @Test
    fun theReceiverEndsTheSessionWhenTheStopNeverComes() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.chat.startShare(Conversations.friend(b.nodeId), 2, SharePrecision.EXACT)
        a.fix(w, spot(300, 300))
        advanceTimeBy(10_000)
        assertEquals(1, b.positions.value.records.size)

        w.net.cut("A", "B")
        a.chat.stopShare(Conversations.friend(b.nodeId))
        advanceTimeBy(30_000)
        assertEquals("the STOP didn't get through", 1, b.positions.value.records.size)
        advanceTimeBy(2 * 60_000)
        assertTrue("gone at the session's end all the same", b.positions.value.records.isEmpty())
        assertTrue(b.positions.value.incoming.isEmpty())
        assertEquals(a.nodeId, b.positions.value.lastSeen.single().sender)
    }

    @Test
    fun anApproximateShareNeverLeavesThePhoneMorePreciseThanItsCell() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val here = spot(1234, 567)
        a.chat.startShare(Conversations.friend(b.nodeId), 30, SharePrecision.APPROXIMATE)
        a.fix(w, here, headingDeg = 45.0)
        advanceTimeBy(10_000)

        val seen = b.positions.value.records.single()
        assertEquals("the centre of the cell", box.toLatLon(576, 1216), GeoPoint(seen.lat, seen.lon))
        assertEquals("no better than 100 m", 4, seen.accuracy)
        assertNull("no heading either", seen.headingDeg)

        // walking 60 m inside the same cell is no move: nothing new goes
        advanceTimeBy(120_000 - 10_000 - 1_000)
        val sent = a.privateSent()
        a.fix(w, spot(1194, 530))
        advanceTimeBy(500)
        assertEquals(sent, a.privateSent())
    }

    @Test
    fun aShareOfTheZoneOnlySendsTheZone() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.sharesZone = false
        a.chat.zoneChanged("bar")
        a.chat.startShare(Conversations.friend(b.nodeId), 30, SharePrecision.ZONE)
        a.fix(w, spot(100, 100))
        advanceTimeBy(10_000)
        assertTrue(b.positions.value.records.isEmpty())
        assertEquals("the share is consent enough", "bar", b.friends.value.single().lastZone)
    }

    @Test
    fun whereAreYouIsAnsweredOnceAMinuteFromAFreshFixWhenTheFriendAllowsIt() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        b.chat.zoneChanged("bar")
        b.fix(w, spot(800, 800))
        advanceTimeBy(5_000)

        a.chat.requestPosition(b.nodeId)
        advanceTimeBy(5_000)
        assertTrue("auto-answer is off by default: only the zone", a.positions.value.records.isEmpty())
        assertEquals("bar", a.friends.value.single().lastZone)

        b.chat.setAutoAnswer(true)
        val asked = a.privateSent()
        a.chat.requestPosition(b.nodeId)
        advanceTimeBy(30_000)
        assertEquals("one question a minute", asked, a.privateSent())

        advanceTimeBy(25_000)
        a.chat.requestPosition(b.nodeId)
        advanceTimeBy(5_000)
        val answer = a.positions.value.records.single()
        assertEquals(b.nodeId, answer.sender)
        assertEquals(NextUpdate.ON_REQUEST, answer.nextUpdate)
        assertEquals("approximate, without a share", 4, answer.accuracy)

        val answered = b.privateSent()
        a.chat.requestZone(b.nodeId)
        advanceTimeBy(5_000)
        assertEquals("one answer a minute", answered, b.privateSent())

        // past 2 minutes the fix is too old to give: the zone again
        advanceTimeBy(120_000)
        a.friends.update { list -> list.map { it.copy(lastZone = "") } }
        a.chat.requestPosition(b.nodeId)
        advanceTimeBy(5_000)
        assertEquals("bar", a.friends.value.single().lastZone)
        assertEquals(answer, a.positions.value.records.single())
    }

    @Test
    fun aPhoneThatNeverAcceptsGetsTheZoneAndAFewStartsButNeverAPosition() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val oldIdentity = Identity.generate(testCrypto)
        val old = V10Node(backgroundScope, w.net, "V", oldIdentity.nodeId, w.clock, { true }, connects = true)
        a.chat.addFriend(FriendCard("Vechi", oldIdentity.box.publicKey, oldIdentity.sign.publicKey))
        w.net.start()
        advanceTimeBy(30_000)

        a.chat.zoneChanged("bar")
        a.chat.startShare(Conversations.friend(old.nodeId), 30, SharePrecision.EXACT)
        for (minute in 0 until 10) {
            a.fix(w, spot(100 * minute, 100))
            advanceTimeBy(60_000)
        }

        val kinds = old.received.filter { it.type == PacketType.PRIVATE && it.sender == a.nodeId }.map {
            testCrypto.boxOpen(it.payload, a.identity.box.publicKey, oldIdentity.box.secretKey)!![0].toInt()
        }
        assertTrue("the zone: $kinds", 10 in kinds)
        assertEquals("START, then given up: $kinds", ChatManager.MAX_START_TRIES, kinds.count { it == 12 })
        assertTrue("never a position: $kinds", 11 !in kinds)
        assertEquals(0, old.malformed)
    }

    @Test
    fun positionsNeverPushReportsOut() = runTest {
        val w = World(this, tmp.root)
        val phones = List(50) { w.phone("P$it") }
        w.net.range = 15.0
        w.net.grid(phones.map { it.radio }, columns = 10, spacing = 10.0)
        for (staff in listOf(phones[22], phones[27])) {
            staff.staffSecret = w.event.secret
            staff.engine.setRole(staff = true, anchor = false)
        }
        val reporters = listOf(phones[0], phones[9], phones[45])
        val sharers = phones - reporters.toSet() - setOf(phones[22], phones[27])
        // everyone else shares with the phone four columns away, both ways
        val pairs = sharers.filter { it.name.drop(1).toInt() % 10 < 5 }.mapNotNull { a ->
            sharers.firstOrNull { it.name == "P${a.name.drop(1).toInt() + 5}" }?.let { a to it }
        }
        for ((a, b) in pairs) a.befriend(b)
        w.net.start()
        advanceTimeBy(60_000)
        for ((a, b) in pairs) {
            a.chat.startShare(Conversations.friend(b.nodeId), 30, SharePrecision.EXACT)
            b.chat.startShare(Conversations.friend(a.nodeId), 30, SharePrecision.EXACT)
        }
        val walk = Random(3)
        fun everyoneMoves(step: Int) = pairs.flatMap { it.toList() }.forEach { it.fix(w, spot(100 + 60 * step, 100 + walk.nextInt(3000))) }

        everyoneMoves(0)
        advanceTimeBy(2_000)
        val urgent = ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, "", null, null, "", null)
        for (r in reporters) assertTrue(r.incidents.report(urgent))
        val start = testScheduler.currentTime
        while (reporters.any { it.myReports.single().status < AckStatus.RECEIVED } && testScheduler.currentTime - start < 10_000) {
            everyoneMoves(((testScheduler.currentTime - start) / 1_000).toInt() + 1)
            advanceTimeBy(250)
        }
        val stillOpen = reporters.filter { it.myReports.single().status < AckStatus.RECEIVED }.map { it.name }
        assertTrue("not confirmed within 10 s: $stillOpen", stillOpen.isEmpty())

        for (step in 1..3) {
            advanceTimeBy(61_000)
            everyoneMoves(10 + step)
        }
        advanceTimeBy(10_000)
        val seeing = pairs.flatMap { it.toList() }.count { it.positions.value.records.size == 1 }
        println("Positions among 50 phones: ${pairs.size * 2} sharing, $seeing see their friend; reports confirmed")
        assertTrue("positions still get through: $seeing of ${pairs.size * 2}", seeing >= pairs.size)
    }
}
