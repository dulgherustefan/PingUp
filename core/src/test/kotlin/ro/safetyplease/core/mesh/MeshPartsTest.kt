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
import ro.safetyplease.core.protocol.AckFlags
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.RequestEntry
import ro.safetyplease.core.protocol.SummaryEntry
import ro.safetyplease.core.util.toHex
import kotlin.random.Random

class MeshPartsTest {
    private fun packet(type: Int, id: Long = 1) = Packet(type, 7, id, 5L, 0L, null, false, ByteArray(1))

    // --- dedup ---

    @Test
    fun dedupRemembersWithinWindow() {
        val d = DedupCache(capacity = 4096, windowMs = 15 * 60_000L)
        assertFalse(d.contains(1L, 0))
        d.add(1L, 0)
        assertTrue(d.contains(1L, 14 * 60_000L))
        assertFalse("new again after the window", d.contains(1L, 15 * 60_000L + 1))
    }

    @Test
    fun dedupEvictsOldestBeyondCapacity() {
        val d = DedupCache(capacity = 3, windowMs = 60_000)
        for (i in 1L..4L) d.add(i, i)
        assertEquals(3, d.size)
        assertFalse("1 was evicted", d.contains(1L, 10))
        assertTrue(d.contains(4L, 10))
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
        q.offer(Outbound(packet(PacketType.REQUEST, 8), true))
        q.offer(Outbound(packet(PacketType.SUMMARY, 9), true))
        assertEquals(listOf(6L, 4L, 5L, 3L, 7L, 8L, 9L, 2L, 1L), List(9) { q.poll()!!.packet.id })
        assertNull(q.poll())
    }

    @Test
    fun newerTypesGoLastExceptAnnouncementsWhichGoWithReports() {
        val q = SendQueue()
        q.offer(Outbound(packet(0x55, 1), true))
        q.offer(Outbound(packet(PacketType.PRIVATE, 2), true))
        q.offer(Outbound(packet(PacketType.SUMMARY2, 3), true))
        q.offer(Outbound(packet(PacketType.INCIDENT_ACK, 4), true))
        q.offer(Outbound(packet(PacketType.ANNOUNCEMENT, 5), true))
        q.offer(Outbound(packet(PacketType.REQUEST2, 6), true))
        assertEquals(listOf(5L, 4L, 3L, 6L, 2L, 1L), List(6) { q.poll()!!.packet.id })
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

    @Test
    fun chatCannotPushOutStoreAndForward() {
        val q = SendQueue(capacity = 3)
        repeat(3) { q.offer(Outbound(packet(PacketType.PRIVATE, it.toLong()), true)) }
        assertTrue(q.offer(Outbound(packet(PacketType.SUMMARY, 10), true)))
        assertTrue(q.offer(Outbound(packet(PacketType.REQUEST, 11), true)))
        assertFalse(q.offer(Outbound(packet(PacketType.PRIVATE, 12), true)))
        assertEquals(listOf(10L, 11L, 0L), List(3) { q.poll()!!.packet.id })
    }

    @Test
    fun fullQueueCountsEveryLostItem() {
        var lost = 0
        val q = SendQueue(capacity = 2) { lost++ }
        repeat(2) { q.offer(Outbound(packet(PacketType.PRIVATE, it.toLong()), true)) }
        assertEquals(0, lost)
        q.offer(Outbound(packet(PacketType.PRIVATE, 9), true))
        assertEquals("refused", 1, lost)
        q.offer(Outbound(packet(PacketType.INCIDENT_REPORT, 10), true))
        assertEquals("evicted", 2, lost)
        assertEquals(2, q.size)
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

    // --- window limit ---

    @Test
    fun windowLimitAllowsSoManyPerWindow() {
        val w = WindowLimit(limit = 6, windowMs = 60_000)
        repeat(6) { assertTrue(w.tryAdd(it * 1_000L)) }
        assertTrue(w.isFull(30_000))
        assertFalse(w.tryAdd(59_999))
        assertTrue("the first one left the window", w.tryAdd(60_000))
        assertFalse(w.tryAdd(60_500))
        assertFalse("a refusal doesn't count", w.isFull(61_000))
        assertEquals(1, w.remaining(61_000))
        assertEquals(6, w.remaining(200_000))
    }

    // --- store-and-forward ---

    private fun id(n: Int) = ByteArray(INCIDENT_ID_SIZE).also { it[7] = n.toByte() }

    private fun ack(n: Int, status: Int, ts: Long = 100, team: String = "T1", epoch: Int = 0) =
        IncidentAck(id(n), status, ts, team, ByteArray(IncidentAck.SIGNATURE_SIZE), IncidentAck.extensions(0, epoch))

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
    fun aTeamsTakeBeatsTheConsolesAssignmentWhateverTheClocks() {
        val c = IncidentCache()
        fun assignment(ts: Long, team: String) = IncidentAck(
            id(1), AckStatus.ACKNOWLEDGED, ts, team, ByteArray(IncidentAck.SIGNATURE_SIZE), IncidentAck.extensions(AckFlags.ASSIGNED),
        )
        fun offer(ack: IncidentAck, id: Long) = c.offerAck(ack, packet(PacketType.INCIDENT_ACK, id), 0)
        assertTrue(offer(assignment(50, "Medical"), 1))
        assertFalse("a later assignment doesn't move it", offer(assignment(60, "Securitate"), 2))
        assertTrue("an older one does", offer(assignment(40, "Securitate"), 3))
        assertTrue("a take, even signed later", offer(ack(1, AckStatus.ACKNOWLEDGED, ts = 100, team = "Medical 2"), 4))
        assertFalse("no assignment undoes a take", offer(assignment(10, "Medical"), 5))
        assertTrue("between takes the older wins", offer(ack(1, AckStatus.ACKNOWLEDGED, ts = 90, team = "Pompieri"), 6))
        assertEquals(6L, c.ack(1L)!!.id)
        assertTrue("a higher status still wins", offer(ack(1, AckStatus.RESOLVED, ts = 200), 7))
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
    fun aStatusFromANewerVersionIsNotRequested() {
        val c = IncidentCache()
        val wanted = c.missing(listOf(SummaryEntry(1L, true, 0x7e), SummaryEntry(2L, false, 0x7e)), 0)
        assertEquals(listOf(RequestEntry(1L, wantReport = true, wantAck = false)), wanted)
    }

    @Test
    fun fieldStatesAndControlsDecisionsFollowTheRankTable() {
        val c = IncidentCache()
        var n = 0L
        fun offer(status: Int, ts: Long = 100) = c.offerAck(ack(1, status, ts), packet(PacketType.INCIDENT_ACK, ++n), 0)
        val climb = listOf(
            AckStatus.RECEIVED, AckStatus.ACKNOWLEDGED, AckStatus.ON_SCENE, AckStatus.HANDED_OVER, AckStatus.RESOLVED,
            AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED, AckStatus.CANCELLED,
        )
        for (status in climb) assertTrue("$status moves it on", offer(status))
        for (status in climb.dropLast(1) + AckStatus.REOPENED) assertFalse("$status comes too late", offer(status, ts = 1))
        assertEquals(AckStatus.CANCELLED, c.ackStatus(id(1)))
    }

    @Test
    fun aReopenStartsAnEpochThatBeatsEveryStatusOfTheOneBefore() {
        val c = IncidentCache()
        var n = 0L
        fun offer(status: Int, epoch: Int) = c.offerAck(ack(1, status, epoch = epoch), packet(PacketType.INCIDENT_ACK, ++n), 0)
        assertTrue(offer(AckStatus.CANCELLED, 0))
        assertTrue("a reopen ranks low, but in the next epoch", offer(AckStatus.REOPENED, 1))
        assertFalse("the old epoch's closing is stale now", offer(AckStatus.RESOLVED, 0))
        assertTrue(offer(AckStatus.ACKNOWLEDGED, 1))
        assertFalse("a reopen is the start of its epoch", offer(AckStatus.REOPENED, 1))
        assertEquals(listOf(SummaryEntry(1L, false, AckStatus.ACKNOWLEDGED, epoch = 1)), c.summary(0))
    }

    @Test
    fun summariesCompareEpochsBeforeStatuses() {
        val c = IncidentCache()
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT, 1), 0)
        c.offerAck(ack(1, AckStatus.CLOSED_BY_CONTROL), packet(PacketType.INCIDENT_ACK, 2), 0)
        c.offerAck(ack(2, AckStatus.CANCELLED), packet(PacketType.INCIDENT_ACK, 3), 0)
        c.offerAck(ack(3, AckStatus.UNFOUNDED), packet(PacketType.INCIDENT_ACK, 4), 0)
        val wanted = c.missing(
            listOf(
                SummaryEntry(1L, true, AckStatus.ACKNOWLEDGED, epoch = 1),
                SummaryEntry(2L, true, AckStatus.REOPENED, epoch = 1),
                SummaryEntry(3L, true, AckStatus.RESOLVED),
            ),
            0,
        )
        assertEquals(
            listOf(
                RequestEntry(1L, wantReport = false, wantAck = true),
                // reopened, so no longer cancelled: the report is worth having again
                RequestEntry(2L, wantReport = true, wantAck = true),
                RequestEntry(3L, wantReport = true, wantAck = false),
            ),
            wanted,
        )
    }

    @Test
    fun summaryListsOnlyWhatTheNeighborCanTake() {
        val c = IncidentCache()
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT, 1), 0)
        c.offerAck(ack(1, AckStatus.RESOLVED), packet(PacketType.INCIDENT_ACK, 2), 0)
        c.offerAck(ack(2, AckStatus.RECEIVED), packet(PacketType.INCIDENT_ACK, 3), 0)
        val noAcks = c.summary(0) { it.type != PacketType.INCIDENT_ACK }
        assertEquals(listOf(SummaryEntry(1L, true, AckStatus.NONE)), noAcks)
        assertEquals(2, c.summary(0).size)
    }

    @Test
    fun knowsWhichReportsItHasSeen() {
        val c = IncidentCache()
        assertFalse(c.hasSeenReport(id(1), 0))
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT), 0)
        c.forgetReport(id(1), 0)
        assertTrue("a forgotten report is still seen", c.hasSeenReport(id(1), 10))
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

    @Test
    fun ownReportOutlastsFiftyForeignOnesUntilStaffHasIt() {
        val c = IncidentCache(maxReports = 50)
        c.offerReport(id(1), packet(PacketType.INCIDENT_REPORT, 1), 0, own = true)
        for (i in 2..80) c.offerReport(id(i), packet(PacketType.INCIDENT_REPORT, i.toLong()), i.toLong())
        assertEquals(50, c.reportCount)
        assertNotNull("ours is the oldest, yet it stays", c.report(1L))
        assertNull("the oldest foreign ones went instead", c.report(2L))

        c.offerAck(ack(1, AckStatus.RECEIVED), packet(PacketType.INCIDENT_ACK), 100)
        c.offerReport(id(81), packet(PacketType.INCIDENT_REPORT, 81), 101)
        assertNull("once staff has it, it goes like any other", c.report(1L))
        assertEquals(50, c.reportCount)
    }

    @Test
    fun reportsStaffAlreadyHasAreEvictedFirst() {
        val c = IncidentCache(maxReports = 3)
        for (i in 1..3) c.offerReport(id(i), packet(PacketType.INCIDENT_REPORT, i.toLong()), i.toLong())
        c.offerAck(ack(3, AckStatus.ACKNOWLEDGED), packet(PacketType.INCIDENT_ACK), 4)
        c.offerReport(id(4), packet(PacketType.INCIDENT_REPORT, 4), 5)
        assertNull("the newest, but already with staff", c.report(3L))
        assertNotNull(c.report(1L))
        assertNotNull(c.report(4L))
    }

    // --- connection policy ---

    private val current = NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.PREFIX_IN_HELLO

    private fun cand(addr: String, prefix: Int?, flags: Int = current, rssi: Int = -60, since: Long = 0, seen: Long = 0) =
        Candidate(addr, prefix, flags, rssi, since, seen)

    private fun link(id: Int, prefix: Int, outgoing: Boolean, up: Long = 0, activity: Long = 0, flags: Int = 0, inbound: Long? = null) =
        LinkView(id, "L$id", prefix, flags, outgoing, up, activity, inbound)

    private fun me(prefix: Int, nodePrefix: Int = 0, canAdvertise: Boolean = true) = Me(prefix, nodePrefix, canAdvertise)

    private val none: (Candidate) -> Boolean = { false }

    @Test
    fun lowerPrefixInitiates() {
        val c = listOf(cand("x", 100))
        assertNotNull(ConnectionPolicy.pick(me(50), LinkLimits.DEFAULT, c, emptyList(), 0, none))
        assertNull(ConnectionPolicy.pick(me(200), LinkLimits.DEFAULT, c, emptyList(), 0, none))
    }

    @Test
    fun theRuleComparesAdvertisedPrefixesNotNodeIds() {
        val c = listOf(cand("x", 100))
        assertNotNull(ConnectionPolicy.pick(me(50, nodePrefix = 0x7fff_ffff), LinkLimits.DEFAULT, c, emptyList(), 0, none))
        assertNull(ConnectionPolicy.pick(me(200, nodePrefix = 1), LinkLimits.DEFAULT, c, emptyList(), 0, none))
    }

    @Test
    fun prefixComparisonIsUnsigned() {
        val high = listOf(cand("x", 0x80000000.toInt()))
        assertNotNull(ConnectionPolicy.pick(me(1), LinkLimits.DEFAULT, high, emptyList(), 0, none))
        assertNull(ConnectionPolicy.pick(me(0x80000001.toInt()), LinkLimits.DEFAULT, high, emptyList(), 0, none))
    }

    @Test
    fun higherPrefixConnectsAnywayAfterFallbackDelay() {
        val c = listOf(cand("x", 100, since = 0, seen = 20_000))
        assertNull(ConnectionPolicy.pick(me(200), LinkLimits.DEFAULT, listOf(cand("x", 100, seen = 19_000)), emptyList(), 19_000, none))
        assertNotNull(ConnectionPolicy.pick(me(200), LinkLimits.DEFAULT, c, emptyList(), 20_000, none))
    }

    @Test
    fun anOlderPhoneIsConnectedToOnlyFromAHigherNodeIdEvenAfterTheFallback() {
        val old = listOf(cand("old", 100, NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.PROTOCOL_1_1, since = 0, seen = 60_000))
        assertNotNull(ConnectionPolicy.pick(me(1, nodePrefix = 200), LinkLimits.DEFAULT, old, emptyList(), 60_000, none))
        assertNull(ConnectionPolicy.pick(me(1, nodePrefix = 50), LinkLimits.DEFAULT, old, emptyList(), 60_000, none))
        assertNull("equal isn't higher", ConnectionPolicy.pick(me(1, nodePrefix = 100), LinkLimits.DEFAULT, old, emptyList(), 60_000, none))
    }

    @Test
    fun aPeerWhoseServiceDataNeverArrivedIsLeftToConnectToUs() {
        val unknown = listOf(cand("x", null, NodeFlags.ACCEPTS_CONNECTIONS, since = 0, seen = 60_000))
        assertNull(ConnectionPolicy.pick(me(1, nodePrefix = -1), LinkLimits.DEFAULT, unknown, emptyList(), 60_000, none))
        assertNotNull(ConnectionPolicy.pick(me(1, canAdvertise = false), LinkLimits.DEFAULT, unknown, emptyList(), 60_000, none))
    }

    @Test
    fun leafAlwaysInitiates() {
        assertNotNull(ConnectionPolicy.pick(me(200, canAdvertise = false), LinkLimits.DEFAULT, listOf(cand("x", 100)), emptyList(), 0, none))
        val old = listOf(cand("old", 100, NodeFlags.ACCEPTS_CONNECTIONS))
        assertNotNull(ConnectionPolicy.pick(me(200, nodePrefix = 1, canAdvertise = false), LinkLimits.DEFAULT, old, emptyList(), 0, none))
    }

    @Test
    fun seeksOneLinkWithARoleThenGoesByRssi() {
        val c = listOf(
            cand("near", 100, rssi = -40),
            // an older version still advertises its pending report; it gets no precedence for it
            cand("pending", 101, current or NodeFlags.PENDING_INCIDENT, rssi = -80),
            cand("anchor", 102, current or NodeFlags.ANCHOR, rssi = -90),
        )
        assertEquals(listOf("anchor", "near", "pending"), ConnectionPolicy.eligible(me(1), c, emptyList(), 0, none).map { it.address })
        val withStaff = listOf(link(1, 7, false, flags = NodeFlags.STAFF))
        val after = ConnectionPolicy.eligible(me(1), c, withStaff, 0, none).map { it.address }
        assertEquals("a second role gets no precedence", listOf("near", "pending", "anchor"), after)
    }

    @Test
    fun skipsFullStaleBlockedAndAlreadyLinkedPeers() {
        val c = listOf(
            cand("full", 100, flags = NodeFlags.PREFIX_IN_HELLO),
            cand("stale", 101, seen = 0),
            cand("blocked", 102, seen = 20_000),
            cand("linked", 103, seen = 20_000),
            cand("ok", 104, seen = 20_000),
        )
        val links = listOf(link(1, 103, true))
        val picked = ConnectionPolicy.eligible(me(1), c, links, 20_000) { it.address == "blocked" }
        assertEquals(listOf("ok"), picked.map { it.address })
    }

    @Test
    fun respectsOutgoingAndTotalLimits() {
        val c = listOf(cand("x", 100))
        val fourOut = List(4) { link(it, it + 1, true) }
        assertNull(ConnectionPolicy.pick(me(1), LinkLimits.DEFAULT, c, fourOut, 0, none))
        val threeOutFourIn = List(3) { link(it, it + 1, true) } + List(4) { link(10 + it, 20 + it, false) }
        assertNull(ConnectionPolicy.pick(me(1), LinkLimits.ANCHOR, c, threeOutFourIn, 0, none))
        assertNotNull(ConnectionPolicy.pick(me(1), LinkLimits.DEFAULT, c, List(3) { link(it, it + 1, true) }, 0, none))
    }

    @Test
    fun rotatedMacCountsAsOnePeer() {
        val c = listOf(cand("old", 100, seen = 1_000), cand("new", 100, seen = 5_000))
        assertEquals(listOf("new"), ConnectionPolicy.eligible(me(1), c, emptyList(), 5_000, none).map { it.address })
    }

    @Test
    fun rotationClosesOldestInactiveOutgoingLinkButKeepsOneWithARole() {
        val now = 100_000L
        val c = listOf(cand("x", 100, seen = now))
        val links = listOf(
            // idle, and the oldest, but staff sent something a minute ago
            link(1, 1, true, up = 1, activity = now - 60_000, flags = NodeFlags.STAFF, inbound = now - 60_000),
            link(2, 2, true, up = 20, activity = 20),
            link(3, 3, true, up = 30, activity = now - 1_000),
            link(4, 4, true, up = 5, activity = 40),
            link(5, 5, false, up = 1, activity = 1),
        )
        assertEquals(4, ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, links, now, none)!!.id)
        assertNull("no rotation without candidates", ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, emptyList(), links, now, none))
        assertNull("no rotation with free slots", ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, links.take(2), now, none))
    }

    @Test
    fun ofSeveralLinksWithARoleOnlyTheOneMostRecentlyHeardFromIsKept() {
        val now = 100_000L
        val c = listOf(cand("x", 100, seen = now))
        val roles = listOf(
            link(1, 1, true, up = 5, activity = now - 40_000, flags = NodeFlags.ANCHOR, inbound = now - 50_000),
            link(2, 2, true, up = 10, activity = now - 40_000, flags = NodeFlags.ANCHOR, inbound = now - 40_000),
            link(3, 3, true, up = 15, activity = now - 40_000, flags = NodeFlags.STAFF, inbound = now - 60_000),
            link(4, 4, true, up = 20, activity = now - 35_000, flags = NodeFlags.ANCHOR),
        )
        assertEquals(1, ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, roles, now, none)!!.id)
        val next = roles.drop(1) + link(5, 5, true, up = 1_000, activity = 0)
        assertEquals("staff rotates too", 3, ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, next, now, none)!!.id)
        val inbound = roles.map {
            if (it.id == 1) it.copy(outgoing = false, lastActivityMs = now - 1_000, lastInboundMs = now - 1_000) else it
        } + link(6, 6, true, up = 1_000)
        assertEquals("the kept one came in, so every outgoing one rotates", 2,
            ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, inbound, now, none)!!.id)
    }

    @Test
    fun aLinkWithARoleThatOnlyWeWriteToRotatesLikeAnyOther() {
        val now = 100_000L
        val c = listOf(cand("x", 100, seen = now))
        val others = listOf(link(3, 3, true, up = 30), link(4, 4, true, up = 40))
        // the oldest, and we wrote to it a moment before it went idle, but it never sent anything back
        val fake = link(1, 1, true, up = 1, activity = now - ConnectionPolicy.INACTIVE_MS, flags = NodeFlags.ANCHOR)
        val quiet = link(2, 2, true, up = 2, activity = 0, flags = NodeFlags.STAFF, inbound = now - ConnectionPolicy.ROLE_HEARD_MS)
        assertEquals(1, ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, listOf(fake, quiet) + others, now, none)!!.id)
        assertEquals("heard from too long ago", 2,
            ConnectionPolicy.rotationVictim(me(1), LinkLimits.DEFAULT, c, listOf(quiet) + others + link(5, 5, true, up = 50), now, none)!!.id)
    }

    // --- advertised prefix ---

    @Test
    fun theAdvertisedPrefixIsDueEveryTwelveToEighteenMinutesAndAlwaysNew() {
        val p = AdvertisedPrefix(Random(3), nowMs = 0)
        var now = 0L
        repeat(200) {
            val due = generateSequence(now) { it + 1_000 }.first { p.isDue(it) }
            assertTrue("${due - now}", due - now in 12 * 60_000L..18 * 60_000L + 1_000)
            now = due
            val before = p.value
            p.renew(now)
            assertTrue(p.value != before)
            assertFalse(p.isDue(now))
        }
    }

    @Test
    fun anAdvertisingSetStartingAgainUnderItsOldPrefixAsksForANewOneOnce() {
        val gate = NewSetPrefix(fixed = 7)
        var asked = 0
        assertTrue("the first set", gate.mayStart(100, null) { asked++ })
        assertTrue("a set for a new prefix", gate.mayStart(200, 100) { asked++ })
        assertFalse(gate.mayStart(200, 200) { asked++ })
        assertFalse("waits for it", gate.mayStart(200, 200) { asked++ })
        assertEquals("asked once", 1, asked)
        assertTrue(gate.mayStart(300, 200) { asked++ })
        assertFalse(gate.mayStart(300, 300) { asked++ })
        assertEquals(2, asked)
        assertTrue("an anchor's prefix starts again as it is", gate.mayStart(7, 7) { asked++ })
        assertEquals(2, asked)
    }

    @Test
    fun phonesStartedTogetherDoNotRenewTogether() {
        val dues = (1..20).map { seed ->
            val p = AdvertisedPrefix(Random(seed), nowMs = 0)
            generateSequence(0L) { it + 1_000 }.first { p.isDue(it) }
        }
        assertTrue(dues.distinct().size > 15)
    }

    // --- chat fan-out ---

    @Test
    fun chatGoesOnTheCeilingOfLog2OfTheLinks() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 3, 3), (0..8).map { FanOut.size(it) })
    }

    @Test
    fun fanOutPicksThatManyDifferentLinksAndTheRecipientsOwnFirst() {
        val links = (1..7).toList()
        val random = Random(5)
        repeat(50) {
            val picked = FanOut.pick(links, null, random)
            assertEquals(3, picked.size)
            assertEquals(3, picked.distinct().size)
            assertTrue(links.containsAll(picked))
            assertEquals(6, FanOut.pick(links, 6, random).first())
        }
        val spread = (1..300).flatMap { FanOut.pick(links, null, random) }.groupingBy { it }.eachCount()
        assertEquals("every link gets its share", links.toSet(), spread.keys)
        assertEquals(listOf(4), FanOut.pick(listOf(4), 4, random))
        assertEquals(listOf(2), FanOut.pick(listOf(1, 2), 2, random))
        assertEquals("a recipient that isn't a neighbor changes nothing", 3, FanOut.pick(links, 9, random).size)
    }

    // --- the cache on disk ---

    private fun report(n: Int) = Packet(PacketType.INCIDENT_REPORT, 6, n.toLong(), 0L, 0L, null, true, id(n) + ByteArray(70))

    private fun ackPacket(ack: IncidentAck) = Packet(PacketType.INCIDENT_ACK, 6, 99L, 5L, 0L, null, false, ack.encode())

    @Test
    fun theCacheComesBackWithItsAgesAndExpiresOnTime() {
        val wall = 1_760_000_000_000L
        val c = IncidentCache()
        c.offerReport(id(1), report(1), 1_000, hops = 3, own = true)
        c.offerAck(ack(1, AckStatus.ACKNOWLEDGED, team = "Medical 1"), ackPacket(ack(1, AckStatus.ACKNOWLEDGED)), 2_000)
        c.offerReport(id(2), report(2), 5 * 60_000L)
        val saved = c.snapshot(nowMs = 10 * 60_000L, wallMs = wall)

        // a reboot: the monotonic clock starts over, the wall clock went on 5 minutes
        val back = IncidentCache()
        back.restore(saved, nowMs = 0, wallMs = wall + 5 * 60_000L, maxAgeMs = 60 * 60_000L)
        assertEquals(PacketCodec.encode(report(1)).toList(), PacketCodec.encode(back.report(1L)!!).toList())
        assertEquals(listOf(report(1).id to 3, report(2).id to 0), back.reports().map { it.first.id to it.second })
        assertEquals(AckStatus.ACKNOWLEDGED, back.ackStatus(id(1)))
        assertEquals(listOf(SummaryEntry(2L, true, 0), SummaryEntry(1L, true, AckStatus.ACKNOWLEDGED)), back.summary(0))
        assertTrue(back.missing(listOf(SummaryEntry(1L, true, AckStatus.RECEIVED)), 0).isEmpty())

        // report 1 was nearly 15 minutes old at the reboot, so it has a little over 15 of its 30 left; report 2 has 20
        back.summary(15 * 60_000L - 1_000)
        assertNotNull(back.report(1L))
        back.summary(15 * 60_000L + 2_000)
        assertNull(back.report(1L))
        assertNotNull(back.report(2L))
        assertTrue("the trace stays", back.hasSeenReport(id(1), 15 * 60_000L + 2_000))
    }

    @Test
    fun aRestoredCacheKeepsTheEpochOfAReopenedIncident() {
        val wall = 1_760_000_000_000L
        val c = IncidentCache()
        c.offerReport(id(1), report(1), 0)
        c.offerAck(ack(1, AckStatus.CLOSED_BY_CONTROL, ts = 100, team = "Control"), ackPacket(ack(1, AckStatus.CLOSED_BY_CONTROL)), 0)
        val reopened = ack(1, AckStatus.REOPENED, ts = 200, team = "Control", epoch = 1)
        assertTrue(c.offerAck(reopened, ackPacket(reopened), 0))
        val saved = c.snapshot(nowMs = 0, wallMs = wall)
        assertEquals(1, saved.incidents.single().ackEpoch)

        val back = IncidentCache()
        back.restore(saved, nowMs = 0, wallMs = wall + 60_000L, maxAgeMs = 60 * 60_000L)
        assertEquals(listOf(SummaryEntry(1L, true, AckStatus.REOPENED, epoch = 1)), back.summary(0))
        val staleClose = ack(1, AckStatus.CLOSED_BY_CONTROL, ts = 300, team = "Control")
        assertTrue("an epoch 0 close no longer wins", back.missing(listOf(SummaryEntry(1L, true, AckStatus.CLOSED_BY_CONTROL)), 0).isEmpty())
        assertFalse(back.offerAck(staleClose, ackPacket(staleClose), 0))

        // with the ACK packet past every retention only the trace comes back, and it still knows the epoch
        val late = IncidentCache()
        late.restore(saved, nowMs = 0, wallMs = wall + 61 * 60_000L, maxAgeMs = 60 * 60_000L)
        assertNull(late.ack(1L))
        assertFalse(late.offerAck(staleClose, ackPacket(staleClose), 0))
        val onScene = ack(1, AckStatus.ON_SCENE, ts = 400, team = "Medical 1", epoch = 1)
        assertTrue(late.offerAck(onScene, ackPacket(onScene), 0))
    }

    @Test
    fun whatNoRoleKeepsAnyMoreIsDroppedOnLoadButItsTraceStays() {
        val wall = 1_760_000_000_000L
        val c = IncidentCache()
        c.offerReport(id(1), report(1), 0)
        c.forgetReport(id(2), 0)
        val saved = c.snapshot(nowMs = 0, wallMs = wall)

        val hourLater = IncidentCache(retentionMs = 60 * 60_000L)
        hourLater.restore(saved, nowMs = 0, wallMs = wall + 61 * 60_000L, maxAgeMs = 60 * 60_000L)
        assertNull(hourLater.report(1L))
        assertTrue(hourLater.hasSeenReport(id(1), 0) && hourLater.hasSeenReport(id(2), 0))

        val dayLater = IncidentCache()
        dayLater.restore(saved, nowMs = 0, wallMs = wall + 7 * 60 * 60_000L, maxAgeMs = 60 * 60_000L)
        assertFalse(dayLater.hasSeenReport(id(1), 0))
        assertTrue(dayLater.snapshot(0, wall).incidents.isEmpty())
    }

    @Test
    fun aPacketThatDoesNotDecodeIsDroppedAndTheRestKept() {
        val wall = 1_760_000_000_000L
        val c = IncidentCache()
        c.offerReport(id(1), report(1), 0)
        c.offerReport(id(2), report(2), 0)
        val saved = c.snapshot(0, wall)
        val damaged = saved.copy(incidents = saved.incidents.map {
            if (it.id8 == 1L) it.copy(report = it.report!!.copy(frame = "zz01")) else it
        } + CachedIncident(3L, reportSeen = true, report = CachedPacket(PacketCodec.encode(ackPacket(ack(3, 1))).toHex(), wall)))
        val back = IncidentCache()
        back.restore(damaged, 0, wall, 60 * 60_000L)
        assertNull(back.report(1L))
        assertTrue(back.hasSeenReport(id(1), 0))
        assertNotNull(back.report(2L))
        assertNull("an ACK where a report should be", back.report(3L))
    }
}
