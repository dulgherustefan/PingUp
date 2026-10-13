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
import ro.safetyplease.core.chat.MeetingPlace
import ro.safetyplease.core.chat.TorchPulse
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.V10Node
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.InnerCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.PingAction
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.PositionBox
import java.io.File

/** "Fă-te văzut", meeting points and "Vin la tine" over the mesh (PROTOCOL.md, section 6.6.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetupsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class World(scope: TestScope, dir: File) {
        val net = SimNet(scope.backgroundScope)
        val clock = TestClock(scope.testScheduler)
        private val event = TestEvent()
        private var seed = 1300L
        val phone: (String) -> TestPhone = { name -> TestPhone(name, scope, net, clock, dir, event, seed++) }
    }

    private val box = PositionBox(BOX_LAT, BOX_LON)

    private fun TestPhone.privateSent(): Long = engine.state.value.counters.sentByType[PacketType.PRIVATE] ?: 0

    private suspend fun TestScope.pair(w: World): Pair<TestPhone, TestPhone> {
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        return a to b
    }

    /** A, B and C in one group; only A has the others as friends. */
    private suspend fun TestScope.group(w: World): Triple<TestPhone, TestPhone, TestPhone> {
        val a = w.phone("A")
        val b = w.phone("B")
        val c = w.phone("C")
        a.befriend(b)
        a.befriend(c)
        w.net.start()
        advanceTimeBy(30_000)
        a.chat.createGroup("Gașca", listOf(b.nodeId, c.nodeId))
        advanceTimeBy(5_000)
        return Triple(a, b, c)
    }

    // --- "Fă-te văzut" ---

    @Test
    fun aFriendWhoAcceptsLightsTheTorchAndTheAskerKnows() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.chat.pingFriend(b.nodeId)
        advanceTimeBy(5_000)
        val ask = b.chat.pingAsks.value.single()
        assertEquals(a.nodeId, ask.sender)
        assertEquals(TorchPulse.SECONDS, ask.seconds)
        assertTrue("nothing in the chat", b.messages.isEmpty())

        b.chat.answerPing(a.nodeId, accept = true)
        advanceTimeBy(2_000)
        assertTrue(b.chat.pingAsks.value.isEmpty())
        assertEquals(b.nodeId, a.chat.lighting.value.single().friend)
        advanceTimeBy(TorchPulse.DURATION_MS + ChatManager.RETRY_TICK_MS)
        assertTrue("dark again", a.chat.lighting.value.isEmpty())
    }

    @Test
    fun oneAskEveryTwoMinutesPerFriendAndADeclineIsSilent() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.chat.pingFriend(b.nodeId)
        advanceTimeBy(5_000)
        val sent = a.privateSent()
        a.chat.pingFriend(b.nodeId)
        advanceTimeBy(5_000)
        assertEquals("the second ask didn't go", sent, a.privateSent())

        val answered = b.privateSent()
        b.chat.answerPing(a.nodeId, accept = false)
        advanceTimeBy(5_000)
        assertEquals("nothing answers a no", answered, b.privateSent())
        assertTrue(a.chat.lighting.value.isEmpty())

        advanceTimeBy(ChatManager.PING_INTERVAL_MS)
        a.chat.pingFriend(b.nodeId)
        advanceTimeBy(5_000)
        assertEquals(1, b.chat.pingAsks.value.size)
        advanceTimeBy(ChatManager.PING_ASK_MS + ChatManager.RETRY_TICK_MS)
        assertTrue("unanswered, it goes stale", b.chat.pingAsks.value.isEmpty())
    }

    @Test
    fun aTorchNobodyAskedForIsIgnored() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val lit = InnerCodec.encode(Inner.PingRequest(PingAction.LIGHTING, 10))
        b.engine.unicast(PacketType.PRIVATE, a.nodeId, testCrypto.box(lit, a.identity.box.publicKey, b.identity.box.secretKey))
        advanceTimeBy(5_000)
        assertTrue(a.chat.lighting.value.isEmpty())
    }

    // --- meeting points ---

    @Test
    fun aMeetingPointIsPinnedForTheGroupAndAcceptedWithOneTap() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)
        val conversation = Conversations.group(a.groups.value.single().id)
        a.chat.proposeMeeting(conversation, MeetingPlace.landmark(14), 22 * 60 + 30, 60)
        advanceTimeBy(5_000)
        for (phone in listOf(b, c)) {
            val pinned = phone.chat.meetings.value.single()
            assertEquals(a.nodeId, pinned.proposer)
            assertEquals(14, pinned.place.landmark)
            assertEquals(22 * 60 + 30, pinned.minuteOfDay)
        }
        assertTrue("delivered like a message", a.chatStore.value.outbox.isEmpty())

        b.chat.acceptMeeting(conversation)
        advanceTimeBy(5_000)
        for (phone in listOf(a, b, c)) assertEquals(listOf(b.nodeId), phone.chat.meetings.value.single().accepted)
        b.chat.acceptMeeting(conversation)
        advanceTimeBy(5_000)
        assertEquals("once", listOf(b.nodeId), a.chat.meetings.value.single().accepted)
    }

    @Test
    fun oneProposalAMinutePerConversationAndTheLatestReplacesIt() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)
        val conversation = Conversations.group(a.groups.value.single().id)
        a.chat.proposeMeeting(conversation, MeetingPlace.landmark(3), null, 60)
        advanceTimeBy(5_000)
        a.chat.proposeMeeting(conversation, MeetingPlace.landmark(4), null, 60)
        advanceTimeBy(5_000)
        assertEquals(3, b.chat.meetings.value.single().place.landmark)

        advanceTimeBy(ChatManager.MEETING_INTERVAL_MS)
        val spot = box.toLatLon(100, 200)
        a.chat.proposeMeeting(conversation, MeetingPlace.venue(spot), null, 60)
        advanceTimeBy(5_000)
        val pinned = c.chat.meetings.value.single()
        assertTrue(pinned.place.venuePoint)
        assertEquals(spot.lat, pinned.place.point!!.lat, 1e-6)

        a.chat.withdrawMeeting(conversation)
        advanceTimeBy(5_000)
        assertTrue(listOf(a, b, c).all { it.chat.meetings.value.isEmpty() })
    }

    @Test
    fun aMeetingPointEndsByItselfAndOnlyItsProposerWithdrawsIt() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.chat.proposeMeeting(Conversations.friend(b.nodeId), MeetingPlace.landmark(7), 23 * 60, 3)
        advanceTimeBy(5_000)
        b.chat.withdrawMeeting(Conversations.friend(a.nodeId))
        advanceTimeBy(5_000)
        assertEquals("not B's to withdraw", 1, a.chat.meetings.value.size)
        assertEquals(1, b.chat.meetings.value.size)

        advanceTimeBy(3 * 60_000L)
        assertTrue(a.chat.meetings.value.isEmpty())
        assertTrue(b.chat.meetings.value.isEmpty())
    }

    @Test
    fun theMiddleOfTheGroupComesFromTheKnownPositions() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val conversation = Conversations.friend(b.nodeId)
        assertNull("only one point is no middle", a.chat.meetingCentroid(conversation))
        b.chat.startShare(Conversations.friend(a.nodeId), 30, SharePrecision.EXACT)
        val there = box.toLatLon(1000, 1000)
        b.chat.onFix(there.lat, there.lon, 4.0, null, PositionMethod.GNSS, w.clock.wallMs())
        val here = box.toLatLon(1000, 1200)
        a.chat.onFix(here.lat, here.lon, 4.0, null, PositionMethod.GNSS, w.clock.wallMs())
        advanceTimeBy(10_000)
        val middle = a.chat.meetingCentroid(conversation)!!
        assertEquals(box.toLatLon(1000, 1100).lat, middle.lat, 1e-5)
        assertEquals(box.toLatLon(1000, 1100).lon, middle.lon, 1e-5)
    }

    // --- "Vin la tine" ---

    @Test
    fun comingStartsAShareBothWaysOnceTheFriendAccepts() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.chat.requestComing(b.nodeId)
        advanceTimeBy(5_000)
        assertTrue("nothing yet", a.chat.activeShares.value.isEmpty() && b.chat.activeShares.value.isEmpty())
        assertEquals(a.nodeId, b.chat.comeAsks.value.single().sender)

        b.chat.answerComing(a.nodeId, accept = true, precision = SharePrecision.APPROXIMATE)
        advanceTimeBy(5_000)
        val theirs = b.chat.activeShares.value.single()
        val ours = a.chat.activeShares.value.single()
        assertEquals(Conversations.friend(a.nodeId), theirs.conversation)
        assertEquals(SharePrecision.APPROXIMATE, theirs.precision)
        assertEquals(Conversations.friend(b.nodeId), ours.conversation)
        assertEquals(SharePrecision.EXACT, ours.precision)
        val end = w.clock.wallMs() + ChatManager.COME_MIN * 60_000L
        assertTrue(ours.expiresAtMs in end - 15_000..end)

        val spot = box.toLatLon(500, 500)
        a.chat.onFix(spot.lat, spot.lon, 4.0, null, PositionMethod.GNSS, w.clock.wallMs())
        b.chat.onFix(spot.lat, spot.lon, 4.0, null, PositionMethod.GNSS, w.clock.wallMs())
        advanceTimeBy(10_000)
        assertEquals(a.nodeId, b.chat.friendPositions.value.single().sender)
        assertEquals(b.nodeId, a.chat.friendPositions.value.single().sender)
    }

    @Test
    fun aDeclineOrALateYesStartsNothingAndALongerShareStays() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        a.chat.requestComing(b.nodeId)
        advanceTimeBy(5_000)
        b.chat.answerComing(a.nodeId, accept = false)
        advanceTimeBy(5_000)
        assertTrue(a.chat.activeShares.value.isEmpty() && b.chat.activeShares.value.isEmpty())

        advanceTimeBy(ChatManager.PING_INTERVAL_MS)
        a.chat.requestComing(b.nodeId)
        advanceTimeBy(ChatManager.COME_ASK_MS + ChatManager.RETRY_TICK_MS)
        b.chat.answerComing(a.nodeId, accept = true)
        advanceTimeBy(5_000)
        assertTrue("too late", a.chat.activeShares.value.isEmpty() && b.chat.activeShares.value.isEmpty())

        a.chat.startShare(Conversations.friend(b.nodeId), 60, SharePrecision.EXACT)
        a.chat.requestComing(b.nodeId)
        advanceTimeBy(5_000)
        b.chat.answerComing(a.nodeId, accept = true)
        advanceTimeBy(5_000)
        assertEquals(1, b.chat.activeShares.value.size)
        val ours = a.chat.activeShares.value.single()
        assertTrue("the hour A chose is kept", ours.expiresAtMs > w.clock.wallMs() + 50 * 60_000L)
    }

    // --- older phones ---

    @Test
    fun anOlderPhoneDropsAllThreeWithoutAnswerOrBan() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val oldIdentity = Identity.generate(testCrypto)
        val old = V10Node(backgroundScope, w.net, "V", oldIdentity.nodeId, w.clock, { true }, connects = true)
        a.chat.addFriend(FriendCard("Vechi", oldIdentity.box.publicKey, oldIdentity.sign.publicKey))
        w.net.start()
        advanceTimeBy(30_000)

        a.chat.pingFriend(old.nodeId)
        a.chat.requestComing(old.nodeId)
        a.chat.proposeMeeting(Conversations.friend(old.nodeId), MeetingPlace.landmark(1), null, 30)
        advanceTimeBy(10_000)

        val kinds = old.received.filter { it.type == PacketType.PRIVATE && it.sender == a.nodeId }.map {
            testCrypto.boxOpen(it.payload, a.identity.box.publicKey, oldIdentity.box.secretKey)!![0].toInt()
        }
        assertTrue("$kinds", kinds.containsAll(listOf(12, 13, 14)))
        assertEquals(0, old.malformed)
        assertTrue("no answer, so nothing starts", a.chat.activeShares.value.isEmpty() && a.chat.lighting.value.isEmpty())
    }
}
