package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okio.Path
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Fă-te văzut", meeting points and "Vin la tine" over the mesh (PROTOCOL.md, section 6.6.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetupsTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private class World(scope: TestScope, dir: Path) {
        val net = SimNet(scope.backgroundScope)
        val clock = TestClock(scope.testScheduler)
        private val event = TestEvent()
        private var seed = 1300L
        val phone: (String) -> TestPhone = { name -> TestPhone(name, scope, net, clock, dir, event, seed++) }

        /** The test clock [minutes] from now, in Unix seconds: the time of a meeting point, whatever zone this machine is in. */
        fun inMinutes(minutes: Int): Long = clock.wallMs() / 1000 + minutes * 60L
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
        assertTrue(a.chat.pingFriend(b.nodeId))
        advanceTimeBy(5_000)
        val ask = b.chat.pingAsks.value.single()
        assertEquals(a.nodeId, ask.sender)
        assertEquals(TorchPulse.SECONDS, ask.seconds)
        assertTrue(b.messages.isEmpty(), "nothing in the chat")

        b.chat.answerPing(a.nodeId, accept = true)
        advanceTimeBy(2_000)
        assertTrue(b.chat.pingAsks.value.isEmpty())
        assertEquals(b.nodeId, a.chat.lighting.value.single().friend)
        advanceTimeBy(TorchPulse.DURATION_MS + ChatManager.RETRY_TICK_MS)
        assertTrue(a.chat.lighting.value.isEmpty(), "dark again")
    }

    @Test
    fun oneAskEveryTwoMinutesPerFriendAndADeclineIsSilent() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        assertTrue(a.chat.pingFriend(b.nodeId))
        advanceTimeBy(5_000)
        val sent = a.privateSent()
        assertFalse(a.chat.pingFriend(b.nodeId))
        advanceTimeBy(5_000)
        assertEquals(sent, a.privateSent(), "the second ask didn't go")

        val answered = b.privateSent()
        b.chat.answerPing(a.nodeId, accept = false)
        advanceTimeBy(5_000)
        assertEquals(answered, b.privateSent(), "nothing answers a no")
        assertTrue(a.chat.lighting.value.isEmpty())

        advanceTimeBy(ChatManager.PING_INTERVAL_MS)
        assertTrue(a.chat.pingFriend(b.nodeId))
        advanceTimeBy(5_000)
        assertEquals(1, b.chat.pingAsks.value.size)
        advanceTimeBy(ChatManager.PING_ASK_MS + ChatManager.RETRY_TICK_MS)
        assertTrue(b.chat.pingAsks.value.isEmpty(), "unanswered, it goes stale")
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

    // --- every request says whether it went out ---

    @Test
    fun aRequestSaysWhetherItWentOut() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val conversation = Conversations.friend(b.nodeId)
        assertTrue(a.chat.pingFriend(b.nodeId))
        assertTrue(a.chat.requestComing(b.nodeId))
        assertTrue(a.chat.proposeMeeting(conversation, MeetingPlace.landmark(3), null, 60))
        assertFalse(a.chat.pingFriend(b.nodeId), "inside the two minutes")
        assertFalse(a.chat.requestComing(b.nodeId), "inside the two minutes")
        assertFalse(a.chat.proposeMeeting(conversation, MeetingPlace.landmark(4), null, 60), "one a minute")

        advanceTimeBy(ChatManager.PING_INTERVAL_MS)
        assertTrue(a.chat.pingFriend(b.nodeId))
        assertTrue(a.chat.requestComing(b.nodeId))
        assertTrue(a.chat.proposeMeeting(conversation, MeetingPlace.landmark(4), null, 60))
    }

    @Test
    fun nothingGoesToAStrangerOrOverNoLinkAndWaitsForNothing() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        val stranger = Identity.generate(testCrypto).nodeId
        assertFalse(a.chat.pingFriend(stranger))
        assertFalse(a.chat.requestComing(stranger))
        assertFalse(a.chat.proposeMeeting(Conversations.friend(stranger), MeetingPlace.landmark(1), null, 30))

        assertFalse(a.chat.pingFriend(b.nodeId), "no link yet")
        assertFalse(a.chat.requestComing(b.nodeId), "no link yet")
        w.net.start()
        advanceTimeBy(30_000)
        assertTrue(a.chat.pingFriend(b.nodeId), "a refused try took no turn")
        assertTrue(a.chat.requestComing(b.nodeId), "a refused try took no turn")
    }

    // --- meeting points ---

    @Test
    fun aMeetingPointIsPinnedForTheGroupAndAcceptedWithOneTap() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)
        val conversation = Conversations.group(a.groups.value.single().id)
        // the evening, hours ahead of the test clock's morning: the time is a moment, so no zone makes it a day away
        val tonight = w.inMinutes(13 * 60 + 30)
        a.chat.proposeMeeting(conversation, MeetingPlace.landmark(14), tonight, 60)
        advanceTimeBy(5_000)
        for (phone in listOf(b, c)) {
            val pinned = phone.chat.meetings.value.single()
            assertEquals(a.nodeId, pinned.proposer)
            assertEquals(14, pinned.place.landmark)
            assertEquals(tonight, pinned.meetAtSec)
        }
        assertTrue(a.chatStore.value.outbox.isEmpty(), "delivered like a message")

        b.chat.acceptMeeting(conversation)
        advanceTimeBy(5_000)
        for (phone in listOf(a, b, c)) assertEquals(listOf(b.nodeId), phone.chat.meetings.value.single().accepted)
        b.chat.acceptMeeting(conversation)
        advanceTimeBy(5_000)
        assertEquals(listOf(b.nodeId), a.chat.meetings.value.single().accepted, "once")
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
        a.chat.proposeMeeting(Conversations.friend(b.nodeId), MeetingPlace.landmark(7), w.inMinutes(30), 3)
        advanceTimeBy(5_000)
        b.chat.withdrawMeeting(Conversations.friend(a.nodeId))
        advanceTimeBy(5_000)
        assertEquals(1, a.chat.meetings.value.size, "not B's to withdraw")
        assertEquals(1, b.chat.meetings.value.size)

        advanceTimeBy(3 * 60_000L)
        assertTrue(a.chat.meetings.value.isEmpty())
        assertTrue(b.chat.meetings.value.isEmpty())
    }

    @Test
    fun aMeetingPointMoreThanAnHourPastIsDroppedWhenItArrives() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val conversation = Conversations.friend(b.nodeId)
        assertTrue(a.chat.proposeMeeting(conversation, MeetingPlace.landmark(3), w.inMinutes(-90), 60))
        advanceTimeBy(5_000)
        assertTrue(b.chat.meetings.value.isEmpty(), "B drops it")
        assertTrue(a.chatStore.value.outbox.isEmpty(), "and still confirms it")

        advanceTimeBy(ChatManager.MEETING_INTERVAL_MS)
        assertTrue(a.chat.proposeMeeting(conversation, MeetingPlace.landmark(4), w.inMinutes(-30), 60))
        advanceTimeBy(5_000)
        assertEquals(4, b.chat.meetings.value.single().place.landmark)

        advanceTimeBy(ChatManager.MEETING_INTERVAL_MS)
        assertTrue(a.chat.proposeMeeting(conversation, MeetingPlace.landmark(5), w.inMinutes(30), 60))
        advanceTimeBy(5_000)
        assertEquals(5, b.chat.meetings.value.single().place.landmark)
    }

    @Test
    fun theMiddleOfTheGroupComesFromTheKnownPositions() = runTest {
        val w = World(this, tmp.root)
        val (a, b) = pair(w)
        val conversation = Conversations.friend(b.nodeId)
        assertNull(a.chat.meetingCentroid(conversation), "only one point is no middle")
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
        assertTrue(a.chat.activeShares.value.isEmpty() && b.chat.activeShares.value.isEmpty(), "nothing yet")
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
        assertTrue(a.chat.activeShares.value.isEmpty() && b.chat.activeShares.value.isEmpty(), "too late")

        a.chat.startShare(Conversations.friend(b.nodeId), 60, SharePrecision.EXACT)
        a.chat.requestComing(b.nodeId)
        advanceTimeBy(5_000)
        b.chat.answerComing(a.nodeId, accept = true)
        advanceTimeBy(5_000)
        assertEquals(1, b.chat.activeShares.value.size)
        val ours = a.chat.activeShares.value.single()
        assertTrue(ours.expiresAtMs > w.clock.wallMs() + 50 * 60_000L, "the hour A chose is kept")
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

        // the intro travels sealed, not boxed, and doesn't open here
        val kinds = old.received.filter { it.type == PacketType.PRIVATE && it.sender == a.nodeId }.mapNotNull {
            testCrypto.boxOpen(it.payload, a.identity.box.publicKey, oldIdentity.box.secretKey)?.get(0)?.toInt()
        }
        assertTrue(kinds.containsAll(listOf(13, 14, 15)), "$kinds")
        assertEquals(0, old.malformed)
        assertTrue(a.chat.activeShares.value.isEmpty() && a.chat.lighting.value.isEmpty(), "no answer, so nothing starts")
    }
}
