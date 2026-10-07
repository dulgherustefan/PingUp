package ro.safetyplease.core.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.SummaryEntry

class MeshPartsTest {
    private fun packet(type: Int, id: Long = 1) = Packet(type, 7, id, 5L, 0L, null, false, ByteArray(1))

    // --- dedup ---

    @Test
    fun dedupRemembersWithinWindow() {
        val d = DedupCache(capacity = 4096, windowMs = 15 * 60_000L)
        assertFalse(d.checkAndAdd(1L, 0))
        assertTrue(d.checkAndAdd(1L, 14 * 60_000L))
        assertFalse("new again after the window", d.checkAndAdd(1L, 14 * 60_000L + 15 * 60_000L + 1))
    }

    @Test
    fun dedupEvictsOldestBeyondCapacity() {
        val d = DedupCache(capacity = 3, windowMs = 60_000)
        for (i in 1L..4L) d.checkAndAdd(i, i)
        assertEquals(3, d.size)
        assertFalse("1 was evicted", d.checkAndAdd(1L, 10))
        assertTrue(d.checkAndAdd(4L, 10))
    }

    // --- token bucket ---

    @Test
    fun tokenBucketAllowsBurstThenRefills() {
        val b = TokenBucket(30.0, 3.0, 0)
        repeat(30) { assertTrue(b.tryTake(0)) }
        assertFalse(b.tryTake(0))
        assertEquals(334, b.waitMs(0))
        assertTrue(b.tryTake(334))
        assertFalse(b.tryTake(334))
        repeat(30) { assertTrue(b.tryTake(20_000)) }
        assertFalse("never exceeds capacity", b.tryTake(20_000))
    }

    // --- priority queue ---

    @Test
    fun queueOrdersByPriority() {
        val q = SendQueue()
        q.offer(Outbound(packet(PacketType.TEST, 1), true))
        q.offer(Outbound(packet(PacketType.PRIVATE, 2), true))
        q.offer(Outbound(packet(PacketType.INCIDENT_ACK, 3), true))
        q.offer(Outbound(packet(PacketType.INCIDENT_REPORT, 4), true))
        q.offer(Outbound(packet(PacketType.INCIDENT_CANCEL, 7), true))
        q.offer(Outbound(packet(PacketType.INCIDENT_REPORT, 5), true))
        q.offer(Outbound(packet(PacketType.HELLO, 6), true))
        assertEquals(listOf(6L, 4L, 5L, 3L, 7L, 2L, 1L), List(7) { q.poll()!!.packet.id })
        assertNull(q.poll())
    }

    @Test
    fun fullQueueSacrificesChatForIncidents() {
        val q = SendQueue(capacity = 3)
        repeat(3) { q.offer(Outbound(packet(PacketType.PRIVATE, it.toLong()), true)) }
        assertFalse("chat over chat is dropped", q.offer(Outbound(packet(PacketType.PRIVATE, 9), true)))
        assertTrue(q.offer(Outbound(packet(PacketType.INCIDENT_REPORT, 10), true)))
        assertEquals(3, q.size)
        assertEquals(10L, q.poll()!!.packet.id)
        assertEquals(listOf(0L, 1L), List(2) { q.poll()!!.packet.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun awaitItemWakesOnOffer() = runTest {
        val q = SendQueue()
        var woke = false
        val job = launch {
            q.awaitItem()
            woke = true
        }
        runCurrent()
        assertFalse(woke)
        q.offer(Outbound(packet(PacketType.TEST), true))
        runCurrent()
        assertTrue(woke)
        job.cancel()
    }

    // --- store-and-forward ---

    private fun id(n: Int) = ByteArray(INCIDENT_ID_SIZE).also { it[7] = n.toByte() }

    private fun ack(n: Int, status: Int, ts: Long = 100, team: String = "T1") =
        IncidentAck(id(n), status, ts, team, ByteArray(IncidentAck.SIGNATURE_SIZE))

    @Test
    fun cacheStoresReportOnce() {
        val c = IncidentCache()
        assertTrue(c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT, 1), 0))
        assertFalse(c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT, 2), 10))
        assertEquals(1, c.reportCount)
        assertEquals(listOf(SummaryEntry(1L, true, AckStatus.NONE)), c.summary(10))
    }

    @Test
    fun higherAckReplacesLower() {
        val c = IncidentCache()
        assertTrue(c.offerAck(ack(1, AckStatus.RECEIVED), packet(PacketType.INCIDENT_ACK, 1), 0))
        assertFalse(c.offerAck(ack(1, AckStatus.RECEIVED, team = "T9"), packet(PacketType.INCIDENT_ACK, 2), 0))
        assertTrue(c.offerAck(ack(1, AckStatus.RESOLVED), packet(PacketType.INCIDENT_ACK, 3), 0))
        assertFalse(c.offerAck(ack(1, AckStatus.ACKNOWLEDGED), packet(PacketType.INCIDENT_ACK, 4), 0))
        assertEquals(AckStatus.RESOLVED, c.ackStatus(id(1)))
        assertEquals(3L, c.ack(1L)!!.id)
    }

    @Test
    fun sameStatusPrefersEarlierThenTeamName() {
        val c = IncidentCache()
        assertTrue(c.offerAck(ack(1, AckStatus.ACKNOWLEDGED, ts = 100, team = "B"), packet(PacketType.INCIDENT_ACK, 1), 0))
        assertTrue(c.offerAck(ack(1, AckStatus.ACKNOWLEDGED, ts = 90, team = "C"), packet(PacketType.INCIDENT_ACK, 2), 0))
        assertTrue(c.offerAck(ack(1, AckStatus.ACKNOWLEDGED, ts = 90, team = "A"), packet(PacketType.INCIDENT_ACK, 3), 0))
        assertFalse(c.offerAck(ack(1, AckStatus.ACKNOWLEDGED, ts = 95, team = "A"), packet(PacketType.INCIDENT_ACK, 4), 0))
    }

    @Test
    fun missingAsksOnlyForWhatIsNewOrBetter() {
        val c = IncidentCache()
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), 0)
        c.offerAck(ack(1, AckStatus.RECEIVED), packet(PacketType.INCIDENT_ACK), 0)
        val remote = listOf(
            SummaryEntry(1L, true, AckStatus.RECEIVED),
            SummaryEntry(2L, true, AckStatus.NONE),
            SummaryEntry(3L, false, AckStatus.RESOLVED),
        )
        val wanted = c.missing(remote, 0)
        assertEquals(2, wanted.size)
        assertTrue(wanted.first { it.id8 == 2L }.wantReport)
        assertFalse(wanted.first { it.id8 == 2L }.wantAck)
        assertTrue(wanted.first { it.id8 == 3L }.wantAck)
        val better = c.missing(listOf(SummaryEntry(1L, true, AckStatus.ACKNOWLEDGED)), 0).single()
        assertFalse(better.wantReport)
        assertTrue(better.wantAck)
    }

    @Test
    fun expiredReportLeavesTraceSoItIsNotRequestedAgain() {
        val c = IncidentCache(retentionMs = 30 * 60_000L)
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), 0)
        val later = 30 * 60_000L + 1
        assertTrue(c.summary(later).isEmpty())
        assertNull(c.report(1L))
        assertTrue(c.missing(listOf(SummaryEntry(1L, true, AckStatus.NONE)), later).isEmpty())
        assertFalse(c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), later))
    }

    @Test
    fun cancelledAckReplacesEveryOtherAndStopsTheReportSpreading() {
        val c = IncidentCache()
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), 0)
        c.offerAck(ack(1, AckStatus.RESOLVED), packet(PacketType.INCIDENT_ACK, 1), 0)
        assertTrue(c.offerAck(ack(1, AckStatus.CANCELLED), packet(PacketType.INCIDENT_ACK, 2), 0))
        assertFalse(c.offerAck(ack(1, AckStatus.RESOLVED, ts = 1), packet(PacketType.INCIDENT_ACK, 3), 0))
        assertEquals(AckStatus.CANCELLED, c.ackStatus(id(1)))
        assertEquals(listOf(SummaryEntry(1L, true, AckStatus.CANCELLED)), c.summary(0))

        val remote = c.missing(listOf(SummaryEntry(2L, true, AckStatus.CANCELLED)), 0).single()
        assertFalse("a cancelled report is no longer requested", remote.wantReport)
        assertTrue(remote.wantAck)
        c.offerAck(ack(3, AckStatus.CANCELLED), packet(PacketType.INCIDENT_ACK), 0)
        assertTrue(c.missing(listOf(SummaryEntry(3L, true, AckStatus.RECEIVED)), 0).isEmpty())
    }

    @Test
    fun forgottenReportIsNotServedNorRequestedAgain() {
        val c = IncidentCache()
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), 0)
        c.forgetReport(id(1), 10)
        assertNull(c.report(1L))
        assertEquals(0, c.reportCount)
        assertTrue(c.summary(10).isEmpty())
        assertTrue(c.missing(listOf(SummaryEntry(1L, true, AckStatus.NONE)), 10).isEmpty())
        assertFalse(c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), 20))
    }

    @Test
    fun keepsAtMostFiftyReports() {
        val c = IncidentCache(maxReports = 50)
        for (i in 1..60) c.offerReport(id(i), packet(PacketType.INCIDENT_REPORT, i.toLong()), i.toLong())
        assertEquals(50, c.reportCount)
        assertNull("the oldest were evicted", c.report(1L))
        assertNotNull(c.report(60L))
    }

    // --- connection policy ---

    private fun cand(addr: String, prefix: Int?, flags: Int = NodeFlags.ACCEPTS_CONNECTIONS, rssi: Int = -60, since: Long = 0, seen: Long = 0) =
        Candidate(addr, prefix, flags, rssi, since, seen)

    private fun link(id: Int, prefix: Int, outgoing: Boolean, up: Long = 0, activity: Long = 0, flags: Int = 0) =
        LinkView(id, "L$id", prefix, flags, outgoing, up, activity)

    private val none: (Candidate) -> Boolean = { false }

    @Test
    fun lowerPrefixInitiates() {
        val c = listOf(cand("x", 100))
        assertNotNull(ConnectionPolicy.pick(50, true, LinkLimits.DEFAULT, c, emptyList(), 0, none))
        assertNull(ConnectionPolicy.pick(200, true, LinkLimits.DEFAULT, c, emptyList(), 0, none))
    }

    @Test
    fun prefixComparisonIsUnsigned() {
        val high = listOf(cand("x", 0x80000000.toInt()))
        assertNotNull(ConnectionPolicy.pick(1, true, LinkLimits.DEFAULT, high, emptyList(), 0, none))
        assertNull(ConnectionPolicy.pick(0x80000001.toInt(), true, LinkLimits.DEFAULT, high, emptyList(), 0, none))
    }

    @Test
    fun higherPrefixConnectsAnywayAfterFallbackDelay() {
        val c = listOf(cand("x", 100, since = 0, seen = 20_000))
        assertNull(ConnectionPolicy.pick(200, true, LinkLimits.DEFAULT, listOf(cand("x", 100, seen = 19_000)), emptyList(), 19_000, none))
        assertNotNull(ConnectionPolicy.pick(200, true, LinkLimits.DEFAULT, c, emptyList(), 20_000, none))
    }

    @Test
    fun leafAlwaysInitiates() {
        assertNotNull(ConnectionPolicy.pick(200, false, LinkLimits.DEFAULT, listOf(cand("x", 100)), emptyList(), 0, none))
    }

    @Test
    fun prefersStaffThenPendingIncidentThenRssi() {
        val accept = NodeFlags.ACCEPTS_CONNECTIONS
        val c = listOf(
            cand("near", 100, rssi = -40),
            cand("pending", 101, accept or NodeFlags.PENDING_INCIDENT, rssi = -80),
            cand("anchor", 102, accept or NodeFlags.ANCHOR, rssi = -90),
        )
        val order = ConnectionPolicy.eligible(1, true, c, emptyList(), 0, none).map { it.address }
        assertEquals(listOf("anchor", "pending", "near"), order)
    }

    @Test
    fun skipsFullStaleBlockedAndAlreadyLinkedPeers() {
        val c = listOf(
            cand("full", 100, flags = 0),
            cand("stale", 101, seen = 0),
            cand("blocked", 102, seen = 20_000),
            cand("linked", 103, seen = 20_000),
            cand("ok", 104, seen = 20_000),
        )
        val links = listOf(link(1, 103, true))
        val picked = ConnectionPolicy.eligible(1, true, c, links, 20_000) { it.address == "blocked" }
        assertEquals(listOf("ok"), picked.map { it.address })
    }

    @Test
    fun respectsOutgoingAndTotalLimits() {
        val c = listOf(cand("x", 100))
        val fourOut = List(4) { link(it, it + 1, true) }
        assertNull(ConnectionPolicy.pick(1, true, LinkLimits.DEFAULT, c, fourOut, 0, none))
        val threeOutFourIn = List(3) { link(it, it + 1, true) } + List(4) { link(10 + it, 20 + it, false) }
        assertNull(ConnectionPolicy.pick(1, true, LinkLimits.ANCHOR, c, threeOutFourIn, 0, none))
        assertNotNull(ConnectionPolicy.pick(1, true, LinkLimits.DEFAULT, c, List(3) { link(it, it + 1, true) }, 0, none))
    }

    @Test
    fun rotatedMacCountsAsOnePeer() {
        val c = listOf(cand("old", 100, seen = 1_000), cand("new", 100, seen = 5_000))
        assertEquals(listOf("new"), ConnectionPolicy.eligible(1, true, c, emptyList(), 5_000, none).map { it.address })
    }

    @Test
    fun rotationClosesOldestInactiveOutgoingLinkButKeepsStaff() {
        val now = 100_000L
        val c = listOf(cand("x", 100, seen = now))
        val links = listOf(
            link(1, 1, true, up = 10, activity = 10, flags = NodeFlags.STAFF),
            link(2, 2, true, up = 20, activity = 20),
            link(3, 3, true, up = 30, activity = now - 1_000),
            link(4, 4, true, up = 5, activity = 40),
            link(5, 5, false, up = 1, activity = 1),
        )
        assertEquals(4, ConnectionPolicy.rotationVictim(1, true, LinkLimits.DEFAULT, c, links, now, none)!!.id)
        assertNull("no rotation without candidates", ConnectionPolicy.rotationVictim(1, true, LinkLimits.DEFAULT, emptyList(), links, now, none))
        assertNull("no rotation with free slots", ConnectionPolicy.rotationVictim(1, true, LinkLimits.DEFAULT, c, links.take(2), now, none))
    }
}
