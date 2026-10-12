package ro.safetyplease.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType

/** Positions are P2: a quarter of the air at most, none under congestion, and the first thing a full queue drops. */
class PositionBudgetTest {
    private fun packet(type: Int, id: Long) = Packet(type, 7, id, 5L, 0L, 9L, true, ByteArray(1))

    @Test
    fun aFullQueueDropsPositionsFirstAndNeverForThem() {
        val q = SendQueue(capacity = 3)
        assertTrue(q.offer(Outbound(packet(PacketType.PRIVATE, 1), own = true, position = true)))
        assertTrue(q.offer(Outbound(packet(PacketType.PRIVATE, 2), own = true, position = true)))
        assertTrue(q.offer(Outbound(packet(PacketType.PRIVATE, 3), own = true)))
        assertTrue("chat pushes out a position", q.offer(Outbound(packet(PacketType.PRIVATE, 4), own = true)))
        assertTrue("so does a report", q.offer(Outbound(packet(PacketType.INCIDENT_REPORT, 5), own = false)))
        assertFalse("a position pushes out nothing", q.offer(Outbound(packet(PacketType.PRIVATE, 6), own = true, position = true)))
        assertEquals(listOf(5L, 3L, 4L), List(3) { q.poll()!!.packet.id })
    }

    @Test
    fun aPositionWaitsBehindEverythingElse() {
        val q = SendQueue()
        q.offer(Outbound(packet(PacketType.PRIVATE, 1), own = true, position = true))
        q.offer(Outbound(packet(0x40, 2), own = false))
        q.offer(Outbound(packet(PacketType.PRIVATE, 3), own = true))
        q.offer(Outbound(packet(PacketType.INCIDENT_ACK, 4), own = false))
        assertEquals(listOf(4L, 3L, 2L, 1L), List(4) { q.poll()!!.packet.id })
    }

    @Test
    fun positionsTakeAQuarterOfTheAirAtMost() {
        val budget = PositionBudget(capacityPerSecond = 2.8)
        // 2.8 a second is 168 a minute: 42 for positions
        val allowed = (0 until 60).count { budget.tryPosition(it * 1_000L).also { ok -> if (ok) budget.onUnique(it * 1_000L) } }
        assertEquals(42, allowed)
        assertTrue("a minute later the window has moved on", budget.tryPosition(61_000))
    }

    @Test
    fun noPositionGoesWhileOtherTrafficFillsTheRest() {
        val budget = PositionBudget(capacityPerSecond = 2.8)
        // 126 other packets in a minute: the 75 % that isn't for positions
        repeat(125) { budget.onUnique(it * 400L) }
        assertTrue(budget.tryPosition(50_000))
        budget.onUnique(50_000)
        budget.onUnique(50_100)
        assertFalse("congested", budget.tryPosition(50_200))
        assertTrue("it clears as the window moves", budget.tryPosition(61_000))
    }
}
