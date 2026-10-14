package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.KeyPair
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.IncidentBody
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ResponseCode
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.toHex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A box key of small order, which libsodium refuses to write to, sent on purpose or kept by an older version: no phone
 * crashes on it, again and again at every start, and the rest goes on. Any exception in a phone's coroutines fails these.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmallOrderKeyTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    @Test
    fun anIntroWithASmallOrderBoxKeyIsIgnoredAndThePhoneGoesOn() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val victim = TestPhone("V", this, net, clock, tmp.root, event, 1)
        val friend = TestPhone("F", this, net, clock, tmp.root, event, 3)
        // all it takes is the victim's friend code, photographed; the attacker's own box key is a point of small order
        val attacker = TestPhone("X", this, net, clock, tmp.root, event, 2, identity = Identity(KeyPair(ByteArray(32), ByteArray(32) { 7 }), testCrypto.signKeyPair()))
        net.start()
        advanceTimeBy(30_000)
        attacker.chat.addFriend(victim.chat.myCard())
        advanceTimeBy(30_000)
        assertTrue(victim.friends.value.none { it.nodeId == attacker.nodeId }, "nothing could ever be sent to that key")

        victim.befriend(friend)
        advanceTimeBy(30_000)
        friend.chat.sendText(Conversations.friend(victim.nodeId), "salut")
        advanceTimeBy(30_000)
        assertEquals(listOf("salut"), victim.incoming.filter { it.kind == MsgKind.TEXT }.map { it.text }, "the victim's chat still runs")
    }

    @Test
    fun aMessageToAFriendKeptWithASmallOrderKeyFailsAtOnceInsteadOfComingBackAtEveryStart() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 10)
        TestPhone("B", this, net, clock, tmp.root, event, 11)
        net.start()
        advanceTimeBy(30_000)
        // kept by an older version, which took the key in the code as it came
        val ghost = Friend(0x1234L, "Fantoma", ByteArray(32).toHex(), testCrypto.signKeyPair().publicKey.toHex(), 0L)
        a.friends.update { it + ghost }
        a.chat.sendText(Conversations.friend(ghost.nodeId), "salut")
        advanceTimeBy(10_000)
        assertTrue(a.chatStore.value.outbox.isEmpty(), "nothing waits in the outbox to be tried again")
        assertEquals(MsgStatus.FAILED, a.messages.single().status)
    }

    @Test
    fun aGroupInviteLeavesOutAMemberWithASmallOrderKey() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 20)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 21)
        a.befriend(b)
        net.start()
        advanceTimeBy(30_000)
        val ghost = Friend(0x1234L, "Fantoma", ByteArray(32).toHex(), testCrypto.signKeyPair().publicKey.toHex(), 0L)
        a.friends.update { it + ghost }
        a.chat.createGroup("Gașca", listOf(b.nodeId, ghost.nodeId))
        advanceTimeBy(30_000)
        val group = b.groups.value.single()
        assertEquals(setOf(a.nodeId, b.nodeId), group.members.map { it.nodeId }.toSet())
    }

    @Test
    fun theConsoleCannotBeBroughtDownByAReportsReplyKey() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val console = TestPhone("D", this, net, clock, tmp.root, event, 31)
        console.staffSecret = event.secret
        console.engine.setRole(staff = true, anchor = false)
        val attacker = TestPhone("X", this, net, clock, tmp.root, event, 32)
        net.start()
        advanceTimeBy(30_000)
        // an anonymous report, alarming on purpose, whose reply key is a point of small order
        val body = IncidentBody(
            IncidentCategory.MEDICAL, Severity.URGENT, clock.wallMs() / 1000, "scena", 44.0, 26.0, "Nu respira, veniti!", null,
            replyKey = ByteArray(32),
        )
        attacker.engine.publishReport(testCrypto.random(16), attacker.staffCrypto.sealReport(body.encode())!!)
        advanceTimeBy(10_000)
        val incident = console.staffIncidents.single()
        assertEquals("", incident.replyKey, "read as no reply key")
        assertFalse(console.incidents.respond(incident.incidentId, ResponseCode.COMING), "the console says it can't answer")

        // one an older version kept with the key as it came
        console.incidentStore.update { data -> data.copy(staff = data.staff.map { it.copy(replyKey = ByteArray(32).toHex()) }) }
        assertFalse(console.incidents.respond(incident.incidentId, ResponseCode.COMING))
        advanceTimeBy(10_000)
        assertTrue(console.staffIncidents.single().responses.isEmpty())
    }
}
