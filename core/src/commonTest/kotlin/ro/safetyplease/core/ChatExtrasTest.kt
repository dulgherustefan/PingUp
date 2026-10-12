package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okio.Path
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.PacketType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Blocking, leaving a group for real, and where a friend last was. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatExtrasTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private class World(scope: TestScope, dir: Path) {
        val net = SimNet(scope.backgroundScope)
        private val clock = TestClock(scope.testScheduler)
        private val event = TestEvent()
        private var seed = 700L
        val phone: (String) -> TestPhone = { name -> TestPhone(name, scope, net, clock, dir, event, seed++) }
    }

    private fun TestPhone.chatSent(): Long = engine.state.value.counters.sentByType[PacketType.PRIVATE] ?: 0

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

    // --- leaving a group ---

    @Test
    fun leavingAGroupTellsTheOthersAndTheyStopSendingToIt() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)
        val groupId = a.groups.value.single().id

        c.chat.leaveGroup(groupId)
        advanceTimeBy(10_000)

        assertTrue(c.groups.value.isEmpty(), "gone from the leaver")
        assertEquals(setOf(a.nodeId, b.nodeId), a.groups.value.single().members.map { it.nodeId }.toSet())
        assertEquals(setOf(a.nodeId, b.nodeId), b.groups.value.single().members.map { it.nodeId }.toSet())
        val notice = b.messages.single { it.kind == MsgKind.LEFT }
        assertEquals(c.nodeId, notice.senderId)
        assertEquals("C", notice.text, "the notice keeps the name, which the group no longer has")
        assertTrue(notice.read, "a notice isn't unread mail")
        assertTrue(c.chatStore.value.outbox.isEmpty(), "every member confirmed, even B who has no friend key for C")

        val before = c.messages.size
        a.chat.sendText(Conversations.group(groupId), "fără tine")
        advanceTimeBy(10_000)
        assertEquals(before, c.messages.size, "the leaver gets nothing more")
        assertEquals(MsgStatus.DELIVERED, a.messages.single { it.text == "fără tine" }.status)
    }

    @Test
    fun aMessageStillOnItsWayToTheLeaverIsNoLongerWaitedFor() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)
        val groupId = a.groups.value.single().id
        c.radio.powerOff()
        advanceTimeBy(2_000)

        a.chat.sendText(Conversations.group(groupId), "unde ești?")
        advanceTimeBy(2_000)
        val sent = a.messages.single { it.text == "unde ești?" }
        assertEquals(1, b.messages.count { it.text == "unde ești?" })
        assertEquals(1, a.chatStore.value.outbox.count { it.recipient == c.nodeId })
        assertEquals(2, sent.recipients)

        c.radio.powerOn()
        c.chat.leaveGroup(groupId)
        advanceTimeBy(8_000)

        val after = a.messages.single { it.text == "unde ești?" }
        assertTrue(a.chatStore.value.outbox.none { it.recipient == c.nodeId })
        assertEquals(1, after.recipients)
        assertEquals(MsgStatus.DELIVERED, after.status)
    }

    @Test
    fun aGroupMemberWhoLeftCannotPostBackIn() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)
        val group = a.groups.value.single()
        val kept = c.groups.value.single()
        c.chat.leaveGroup(group.id)
        advanceTimeBy(10_000)

        c.groups.update { listOf(kept) }
        c.chat.sendText(Conversations.group(group.id), "încă aici?")
        advanceTimeBy(10_000)
        assertTrue(a.messages.none { it.text == "încă aici?" })
        assertTrue(b.messages.none { it.text == "încă aici?" })
    }

    // --- an unknown kind, as an older phone sees it ---

    @Test
    fun anInnerKindNobodyKnowsIsDroppedWithoutAnswerOrBan() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        val answered = b.chatSent()
        val links = b.engine.state.value.readyLinks

        // type 99 is what an older build makes of a kind added later: its decoder has no case for it
        val unknown = byteArrayOf(99, 0, 0, 0, 0, 0, 0, 0, 42)
        a.engine.unicast(PacketType.PRIVATE, b.nodeId, testCrypto.box(unknown, b.identity.box.publicKey, a.identity.box.secretKey))
        advanceTimeBy(10_000)

        assertTrue(b.messages.isEmpty())
        assertEquals(answered, b.chatSent(), "no DELIVERED, no Pong")
        assertEquals(links, b.engine.state.value.readyLinks, "not malformed, so nobody is banned")
        a.chat.sendText(Conversations.friend(b.nodeId), "încă merge")
        advanceTimeBy(5_000)
        assertEquals("încă merge", b.messages.single().text)
    }

    // --- blocking ---

    @Test
    fun aBlockedFriendLeavesTheListsAndNothingFromThemIsOpenedOrAnswered() = runTest {
        val w = World(this, tmp.root)
        val (a, b, c) = group(w)

        a.chat.block(b.nodeId)
        advanceTimeBy(1_000)
        assertTrue(a.chat.isBlocked(b.nodeId))
        assertEquals(listOf(c.nodeId), a.friends.value.map { it.nodeId })
        assertEquals(setOf(a.nodeId, c.nodeId), a.groups.value.single().members.map { it.nodeId }.toSet())
        assertEquals(b.chat.myCard().nickname, a.blocked.value.single().nickname)

        b.chat.sendText(Conversations.friend(a.nodeId), "hei")
        b.chat.sendText(Conversations.group(a.groups.value.single().id), "hei grup")
        advanceTimeBy(30_000)
        assertTrue(a.messages.none { it.senderId == b.nodeId }, "nothing shown")
        assertTrue(b.messages.none { it.fromMe && it.delivered > 0 && it.conversation == Conversations.friend(a.nodeId) }, "and never confirmed to B")

        a.chat.sendText(Conversations.group(a.groups.value.single().id), "fără B")
        advanceTimeBy(10_000)
        assertTrue(b.messages.none { it.text == "fără B" })
        assertEquals("fără B", c.messages.last().text, "c still gets the group")
    }

    @Test
    fun scanningTheCodeOfSomeoneBlockedTalksToThemAgain() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        a.chat.block(b.nodeId)
        assertTrue(a.friends.value.isEmpty())

        a.chat.addFriend(b.chat.myCard())
        assertFalse(a.chat.isBlocked(b.nodeId))
        assertEquals(1, a.friends.value.size)
        a.chat.unblock(b.nodeId)
        assertTrue(a.blocked.value.isEmpty())
    }

    @Test
    fun blockingYourselfDoesNothing() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        a.chat.block(a.nodeId)
        assertTrue(a.blocked.value.isEmpty())
    }

    // --- the friends' last zone ---

    @Test
    fun aFriendHearsTheNewZoneOnceAndShowsItInTheList() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)

        a.chat.zoneChanged("main-stage")
        advanceTimeBy(5_000)
        val seen = b.friends.value.single()
        assertEquals("main-stage", seen.lastZone)
        assertTrue(seen.lastZoneAt > 0)
        assertTrue(b.messages.isEmpty(), "not a chat message")

        val sent = a.chatSent()
        a.chat.zoneChanged("main-stage")
        advanceTimeBy(10 * 60_000L)
        assertEquals(sent, a.chatSent(), "the same zone is not said again, and there is no timer")

        a.chat.zoneChanged("bar")
        advanceTimeBy(5_000)
        assertEquals("bar", b.friends.value.single().lastZone, "the latest wins")
    }

    @Test
    fun aFriendCanAskWhereYouAreAndGetTheLastZone() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        b.chat.zoneChanged("food")
        advanceTimeBy(5_000)
        assertEquals("food", a.friends.value.single().lastZone)

        // A lost it, say, after a restart of its list
        a.friends.update { list -> list.map { it.copy(lastZone = "", lastZoneAt = 0) } }
        a.chat.requestZone(b.nodeId)
        advanceTimeBy(5_000)
        assertEquals("food", a.friends.value.single().lastZone)
    }

    @Test
    fun withSharingOffNeitherChangesNorRequestsTellAnything() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        a.sharesZone = false

        a.chat.zoneChanged("camping")
        b.chat.requestZone(a.nodeId)
        advanceTimeBy(10_000)
        assertEquals("", b.friends.value.single().lastZone)
    }

    @Test
    fun aRequestFromSomeoneWhoIsNotAFriendIsNotAnswered() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        val x = w.phone("X")
        a.befriend(b)
        x.chat.addFriend(a.chat.myCard())
        w.net.start()
        advanceTimeBy(30_000)
        // X's scan made them A's friend too; A takes them off
        a.chat.removeFriend(x.nodeId)
        a.chat.zoneChanged("bar")
        advanceTimeBy(5_000)

        x.chat.requestZone(a.nodeId)
        advanceTimeBy(10_000)
        assertEquals("", x.friends.value.single().lastZone)
    }

    @Test
    fun aZoneSharedInAChatAlsoCountsAsTheLastOne() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)

        a.chat.sendZone(Conversations.friend(b.nodeId), "camping", null, null)
        advanceTimeBy(5_000)
        assertEquals("camping", b.friends.value.single().lastZone)
        assertEquals(MsgKind.ZONE, b.messages.single().kind)
    }

    @Test
    fun aFriendAddedLaterHearsTheZoneWhenALinkComesUp() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        val c = w.phone("C")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(30_000)
        a.chat.zoneChanged("bar")
        advanceTimeBy(5_000)

        a.befriend(c)
        c.radio.powerOff()
        advanceTimeBy(1_000)
        c.radio.powerOn()
        advanceTimeBy(20_000)
        assertEquals("bar", c.friends.value.single().lastZone)
    }
}
