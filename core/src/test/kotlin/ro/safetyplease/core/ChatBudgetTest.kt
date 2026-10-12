package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.PacketType
import java.io.File

/** What a phone may spend on chat: packets a minute, messages in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatBudgetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class World(scope: TestScope, dir: File) {
        val net = SimNet(scope.backgroundScope)
        private val clock = TestClock(scope.testScheduler)
        private val event = TestEvent()
        private var seed = 300L
        val phone: (String) -> TestPhone = { name -> TestPhone(name, scope, net, clock, dir, event, seed++) }
    }

    private fun TestPhone.chatSent(): Long = engine.state.value.counters.sentByType[PacketType.PRIVATE] ?: 0

    @Test
    fun aPhoneWritesAtMostThirtyChatPacketsAMinuteAndTheRestWaits() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val b = w.phone("B")
        a.befriend(b)
        w.net.start()
        advanceTimeBy(10_000)
        val before = a.chatSent()
        repeat(40) { a.chat.sendText(Conversations.friend(b.nodeId), "mesaj $it") }
        advanceTimeBy(59_000)
        val inAMinute = a.chatSent() - before
        assertTrue("$inAMinute", inAMinute in 20..ChatManager.PACKETS_PER_MINUTE.toLong())
        advanceTimeBy(10 * 60_000L)
        assertEquals(40, b.messages.size)
        assertTrue(a.messages.all { it.status == MsgStatus.DELIVERED })
    }

    @Test
    fun atMostSixteenMessagesAreTriedAtOnceTheOthersWaitTheirTurn() = runTest {
        val w = World(this, tmp.root)
        val a = w.phone("A")
        val relay = w.phone("R")
        val b = w.phone("B")
        a.befriend(b)
        w.net.cut("A", "B")
        b.radio.powerOff()
        w.net.start()
        advanceTimeBy(10_000)
        repeat(20) { a.chat.sendText(Conversations.friend(b.nodeId), "mesaj $it") }
        advanceTimeBy(5 * 60_000L)
        val outbox = a.chatStore.value.outbox
        // besides the messages, A's intro is still waiting for B
        assertEquals(20, outbox.count { !it.sealed })
        assertEquals(ChatManager.MAX_IN_FLIGHT, outbox.count { it.attempts > 0 })
        assertTrue(a.messages.count { it.status == MsgStatus.QUEUED } >= 20 - ChatManager.MAX_IN_FLIGHT)

        b.radio.powerOn()
        advanceTimeBy(30 * 60_000L)
        assertEquals("all arrive once the first ones are through", 20, b.messages.size)
        assertTrue(a.chatStore.value.outbox.isEmpty())
        assertTrue(relay.engine.state.value.relayed > 0)
    }
}
