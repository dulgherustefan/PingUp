package ro.safetyplease.core.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Request2
import ro.safetyplease.core.protocol.Request2Codec
import ro.safetyplease.core.protocol.Summary2
import ro.safetyplease.core.protocol.Summary2Codec
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.toLong
import kotlin.random.Random

private const val BAD_SIGNATURE: Byte = 0x66

private fun verify(payload: ByteArray): Boolean = payload.last() != BAD_SIGNATURE

/**
 * Protocol 1.1: what a node does with frames, types, statuses and summaries from newer versions, the limits on
 * them, and phones still on 1.0 ([V10Node]) next to 1.1 ones, in both directions, without a single malformed packet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Protocol11Test {
    private class World(scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val bg = scope.backgroundScope
        val net = SimNet(bg)

        fun v11(name: String, id: Long) = Node(name, id, this)

        fun v10(name: String, id: Long) = V10Node(bg, net, name, id, clock, ::verify)

        /** The named radios don't hear each other, only the ones left out. */
        fun apart(vararg names: String) {
            for (x in names) for (y in names) if (x != y) net.outOfRange += setOf(x, y)
        }
    }

    private class Node(name: String, val id: Long, world: World) {
        val radio = world.net.radio(name, id.nodePrefix())
        val engine = MeshEngine(world.bg, radio, world.clock, Random(id), id, MeshConfig(), PacketLog(false), verifyAck = ::verify)
        val received = mutableListOf<Packet>()

        /** Type and TTL of every frame this node put on the air. */
        val sent = mutableListOf<Pair<Int, Int>>()

        init {
            world.bg.launch { engine.events.collect { if (it is MeshEvent.Received) received += it.packet } }
            radio.tamper = { frame -> frame.also { sent += (it[1].toInt() and 0xff) to it[2].toInt() } }
            engine.start()
        }

        val drops: Map<DropReason, Long> get() = engine.state.value.counters.drops
        val arrived: Map<Int, Long> get() = engine.state.value.counters.receivedByType

        fun ofType(type: Int) = received.filter { it.type == type }

        fun sentTtls(type: Int) = sent.filter { it.first == type }.map { it.second }

        /** Hands [packet] to the other end of this node's only link, as a peer that ignores the rules would. */
        fun inject(packet: Packet) = radio.inject(radio.links.keys.single(), PacketCodec.encode(packet))
    }

    private companion object {
        const val HUB = 0x0100_0000_0000_0001L
        const val A = 0x1000_0000_0000_000AL
        const val B = 0x2000_0000_0000_000BL
        const val C = 0x3000_0000_0000_000CL
        // below every 1.1 nodeId here, so the 1.1 phones connect to them (ConnectionPolicy.initiates)
        const val OLD = 0x0070_0000_0000_0007L
        const val OLD2 = 0x0071_0000_0000_0007L
        const val UNKNOWN = 0x55
        const val NEWER_STATUS = 0x7e
    }

    private fun incidentId(n: Int) = ByteArray(INCIDENT_ID_SIZE) { (n + it).toByte() }

    private fun ack(n: Int, status: Int, extensions: ByteArray = ByteArray(0)) =
        IncidentAck(incidentId(n), status, 100, "Medical 1", ByteArray(IncidentAck.SIGNATURE_SIZE) { 1 }, extensions)

    private fun packet(type: Int, id: Long, sender: Long = A, size: Int = 10, ttl: Int = 7, recipient: Long? = null) =
        Packet(type, ttl, id, sender, 0L, recipient, false, ByteArray(size))

    private fun report(n: Int) =
        Packet(PacketType.INCIDENT_REPORT, 7, 100L + n, 0L, 0L, null, true, incidentId(n) + ByteArray(80))

    private fun assertNothingMalformed(nodes: List<Node>, old: List<V10Node> = emptyList()) {
        for (n in nodes) assertNull("malformed at ${n.id}", n.drops[DropReason.MALFORMED])
        for (o in old) assertEquals("malformed at ${o.nodeId}", 0, o.malformed)
    }

    private fun TestScope.pair(): Pair<Node, Node> {
        val w = World(this)
        val a = w.v11("A", A)
        val b = w.v11("B", B)
        w.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, b.engine.state.value.readyLinks)
        return a to b
    }

    /** [HUB] hears the leaves, which don't hear each other. */
    private fun TestScope.star(
        world: World,
        v11Leaves: List<Pair<String, Long>>,
        v10Leaves: List<Pair<String, Long>> = emptyList(),
    ): Triple<Node, List<Node>, List<V10Node>> {
        val hub = world.v11("H", HUB)
        val leaves = v11Leaves.map { (name, id) -> world.v11(name, id) }
        val old = v10Leaves.map { (name, id) -> world.v10(name, id) }
        world.apart(*(v11Leaves + v10Leaves).map { it.first }.toTypedArray())
        world.net.start()
        advanceTimeBy(10_000)
        assertEquals(leaves.size + old.size, hub.engine.state.value.readyLinks)
        return Triple(hub, leaves, old)
    }

    // --- what newer versions may send ---

    @Test
    fun framesFromANewerVersionAreDroppedWithoutABan() = runTest {
        val (a, b) = pair()
        val good = PacketCodec.encode(packet(PacketType.PRIVATE, 1))
        val link = a.radio.links.keys.single()
        repeat(5) { a.radio.inject(link, good.clone().also { it[0] = 2 }) }
        repeat(5) { a.radio.inject(link, good.clone().also { it[3] = (it[3].toInt() or 0x10).toByte() }) }
        advanceTimeBy(2_000)
        assertEquals(10L, b.drops[DropReason.UNSUPPORTED])
        assertNothingMalformed(listOf(a, b))
        assertEquals(1, b.engine.state.value.readyLinks)
    }

    @Test
    fun aLinkThatKeepsBringingWhatThisVersionCantUseIsClosedWithoutABan() = runTest {
        val (a, b) = pair()
        val foreign = PacketCodec.encode(packet(PacketType.PRIVATE, 1)).also { it[0] = 2 }
        val link = a.radio.links.keys.single()
        repeat(11) { a.radio.inject(link, foreign) }
        advanceTimeBy(1_000)
        assertTrue("closed past ten a minute", a.radio.links.isEmpty())
        // B waits out a cooldown; A connects at once or, if its prefix is the higher one, after the fallback
        advanceTimeBy(ConnectionPolicy.FALLBACK_MS + 5_000)
        assertEquals("not banned: the peer connects again", 1, b.engine.state.value.readyLinks)
        assertNothingMalformed(listOf(a, b))
    }

    @Test
    fun unknownTypesAreRelayedAsTheyAreButNotFar() = runTest {
        val (hub, leaves) = star(World(this), listOf("A" to A, "C" to C))
        val (a, c) = leaves
        a.inject(packet(UNKNOWN, 1))
        a.inject(packet(PacketType.ANNOUNCEMENT, 2, size = PacketCodec.MAX_PAYLOAD))
        advanceTimeBy(5_000)
        assertEquals("ttl capped at 3", listOf(3), hub.sentTtls(UNKNOWN))
        assertEquals(listOf(3), hub.sentTtls(PacketType.ANNOUNCEMENT))
        assertEquals(1L, c.arrived[UNKNOWN])
        assertEquals(1L, c.arrived[PacketType.ANNOUNCEMENT])
        assertTrue("nothing for the app", c.received.isEmpty() && hub.received.isEmpty())
        assertNothingMalformed(listOf(hub, a, c))
    }

    @Test
    fun unknownTypesGoNowhereWhenLinkLocalSpentAddressedHereOrTooBig() = runTest {
        val (hub, leaves) = star(World(this), listOf("A" to A, "C" to C))
        val (a, c) = leaves
        a.inject(packet(0x06, 1))
        a.inject(packet(UNKNOWN, 2, ttl = 1))
        a.inject(packet(UNKNOWN, 3, recipient = HUB))
        a.inject(packet(UNKNOWN, 4, size = PacketType.OPAQUE_MAX_PAYLOAD + 1))
        advanceTimeBy(5_000)
        assertTrue(hub.sentTtls(UNKNOWN).isEmpty() && hub.sentTtls(0x06).isEmpty())
        assertNull(c.arrived[UNKNOWN])
        assertEquals(3L, hub.drops[DropReason.UNSUPPORTED])
        assertEquals(1L, hub.drops[DropReason.OPAQUE_LIMIT])
        assertNothingMalformed(listOf(hub, a, c))
    }

    @Test
    fun eachLinkHasABudgetForTypesThisVersionOnlyRelays() = runTest {
        val (hub, leaves) = star(World(this), listOf("A" to A, "C" to C))
        val a = leaves.first()
        repeat(15) { a.inject(packet(UNKNOWN, 10L + it)) }
        advanceTimeBy(5_000)
        assertEquals(12, hub.sentTtls(UNKNOWN).size)
        assertEquals(3L, hub.drops[DropReason.OPAQUE_LIMIT])
        advanceTimeBy(60_000)
        a.inject(packet(UNKNOWN, 99))
        advanceTimeBy(1_000)
        assertEquals("the budget refills", 13, hub.sentTtls(UNKNOWN).size)
    }

    @Test
    fun announcementsHaveASmallerBudgetUntilTheyAreSigned() = runTest {
        val (hub, leaves) = star(World(this), listOf("A" to A, "C" to C))
        val a = leaves.first()
        repeat(6) { a.inject(packet(PacketType.ANNOUNCEMENT, 10L + it)) }
        repeat(3) { a.inject(packet(UNKNOWN, 20L + it)) }
        advanceTimeBy(5_000)
        assertEquals(4, hub.sentTtls(PacketType.ANNOUNCEMENT).size)
        assertEquals("other types keep their own budget", 3, hub.sentTtls(UNKNOWN).size)
        assertEquals(2L, hub.drops[DropReason.OPAQUE_LIMIT])
    }

    @Test
    fun atMostSixNewReportsAMinuteFromOneLink() = runTest {
        val (a, b) = pair()
        val reports = List(8) { report(it) }
        reports.forEach { a.inject(it) }
        a.inject(reports[0])
        advanceTimeBy(5_000)
        assertEquals(6, b.ofType(PacketType.INCIDENT_REPORT).size)
        assertEquals(2L, b.drops[DropReason.REPORT_LIMIT])
        assertEquals("a copy isn't a new report", 1L, b.drops[DropReason.DUPLICATE])
        advanceTimeBy(60_000)
        a.inject(reports[7])
        advanceTimeBy(1_000)
        assertEquals("a refused report can still come later", 7, b.ofType(PacketType.INCIDENT_REPORT).size)
        assertNothingMalformed(listOf(a, b))
    }

    /** Served reports count too: a neighbor could list fake ids in its summary and fill the cache through REQUEST. */
    @Test
    fun aLargeCacheComesOverSixReportsAMinute() = runTest {
        val w = World(this)
        val a = w.v11("A", A)
        val b = w.v11("B", B)
        repeat(8) { a.engine.publishReport(incidentId(it), ByteArray(80)) }
        runCurrent()
        w.net.start()
        advanceTimeBy(30_000)
        assertEquals(6, b.ofType(PacketType.INCIDENT_REPORT).size)
        advanceTimeBy(150_000)
        assertEquals("the rest come with later summaries", 8, b.ofType(PacketType.INCIDENT_REPORT).size)
        assertNull("what the budget can't take isn't requested, so nothing crosses only to be dropped", b.drops[DropReason.REPORT_LIMIT])
        assertNothingMalformed(listOf(a, b))
    }

    @Test
    fun ackWithANewerStatusIsRelayedButNeitherKeptNorDelivered() = runTest {
        val (hub, leaves) = star(World(this), listOf("A" to A, "C" to C))
        val (a, c) = leaves
        a.inject(Packet(PacketType.INCIDENT_ACK, 7, 1L, A, 0L, null, false, ack(1, NEWER_STATUS).encode()))
        a.inject(Packet(PacketType.INCIDENT_ACK, 7, 2L, A, 0L, null, false, ack(2, AckStatus.RESOLVED, byteArrayOf(1, 2)).encode()))
        a.inject(packet(PacketType.INCIDENT_CANCEL, 3, sender = 0L, size = IncidentCancel.SIZE + 4))
        advanceTimeBy(5_000)
        assertEquals(2L, c.arrived[PacketType.INCIDENT_ACK])
        assertEquals(AckStatus.NONE, c.engine.ackStatus(incidentId(1)))
        assertEquals(AckStatus.NONE, hub.engine.ackStatus(incidentId(1)))
        assertEquals("fields a newer version adds don't stop a known status", AckStatus.RESOLVED, c.engine.ackStatus(incidentId(2)))
        assertEquals(listOf(2L), c.ofType(PacketType.INCIDENT_ACK).map { it.id })
        assertEquals(1, c.ofType(PacketType.INCIDENT_CANCEL).size)
        assertNothingMalformed(listOf(hub, a, c))
    }

    @Test
    fun summaryKindsThisVersionDoesNotReadAreIgnored() = runTest {
        val (a, b) = pair()
        a.inject(Packet(PacketType.SUMMARY2, 1, 1L, A, 0L, null, false, Summary2Codec.encode(Summary2(0x7e, emptyList())) + 5))
        a.inject(Packet(PacketType.REQUEST2, 1, 2L, A, 0L, null, false, Request2Codec.encode(Request2(0x7e, emptyList()))))
        advanceTimeBy(2_000)
        assertEquals(2L, b.drops[DropReason.UNSUPPORTED])
        assertTrue(b.sentTtls(PacketType.REQUEST2).isEmpty())
        assertNothingMalformed(listOf(a, b))
    }

    @Test
    fun eachTypeStartsWithItsOwnTtl() = runTest {
        val (a, b) = pair()
        a.engine.unicast(PacketType.PRIVATE, B, ByteArray(60))
        a.engine.publishReport(incidentId(1), ByteArray(80))
        a.engine.broadcast(PacketType.ANNOUNCEMENT, ByteArray(10))
        advanceTimeBy(5_000)
        assertEquals(listOf(PacketType.CHAT_TTL), a.sentTtls(PacketType.PRIVATE))
        assertEquals(listOf(7), a.sentTtls(PacketType.INCIDENT_REPORT))
        assertEquals(listOf(3), a.sentTtls(PacketType.ANNOUNCEMENT))
        assertTrue(a.sentTtls(PacketType.HELLO).all { it == 1 })
        assertEquals(1, b.ofType(PacketType.PRIVATE).single().hops)
    }

    @Test
    fun oneOnePeersSummarizeWithSummary2() = runTest {
        val w = World(this)
        val a = w.v11("A", A)
        val b = w.v11("B", B)
        a.engine.publishReport(incidentId(1), ByteArray(80))
        runCurrent()
        w.net.start()
        advanceTimeBy(10_000)
        assertEquals(1, b.ofType(PacketType.INCIDENT_REPORT).size)
        assertTrue(a.sentTtls(PacketType.SUMMARY2).isNotEmpty() && b.sentTtls(PacketType.REQUEST2).isNotEmpty())
        val legacy = (a.sent + b.sent).map { it.first }.filter { it == PacketType.SUMMARY || it == PacketType.REQUEST }
        assertTrue("no 1.0 summaries between 1.1 peers", legacy.isEmpty())
    }

    // --- next to phones still on 1.0 ---

    @Test
    fun reportsAcksAndChatCrossBetweenVersions() = runTest {
        val w = World(this)
        val new = w.v11("N", A)
        val old = w.v10("O", OLD)
        w.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, new.engine.state.value.readyLinks)
        assertEquals("a 1.0 peer doesn't announce 1.1", 0, new.engine.state.value.links.single().flags and NodeFlags.PROTOCOL_1_1)

        old.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(3_000)
        assertEquals(1, new.ofType(PacketType.INCIDENT_REPORT).size)
        new.engine.publishAck(ack(1, AckStatus.RECEIVED))
        advanceTimeBy(3_000)
        assertEquals(AckStatus.RECEIVED, old.ackStatus(incidentId(1)))

        new.engine.publishReport(incidentId(2), ByteArray(80))
        advanceTimeBy(3_000)
        assertEquals(1, old.received.count { it.type == PacketType.INCIDENT_REPORT })
        old.publishAck(ack(2, AckStatus.ACKNOWLEDGED))
        advanceTimeBy(3_000)
        assertEquals(AckStatus.ACKNOWLEDGED, new.engine.ackStatus(incidentId(2)))

        new.engine.unicast(PacketType.PRIVATE, OLD, ByteArray(60))
        old.unicast(A, ByteArray(60))
        advanceTimeBy(3_000)
        assertEquals(1, old.received.single { it.type == PacketType.PRIVATE }.hops)
        assertEquals(1, new.ofType(PacketType.PRIVATE).single().hops)
        assertNothingMalformed(listOf(new), listOf(old))
    }

    @Test
    fun storeAndForwardBetweenVersionsUsesTheOneZeroSummary() = runTest {
        val w = World(this)
        val new = w.v11("N", A)
        val old = w.v10("O", OLD)
        old.publishReport(incidentId(1), ByteArray(80))
        new.engine.publishReport(incidentId(2), ByteArray(80))
        runCurrent()
        w.net.start()
        advanceTimeBy(10_000)
        assertEquals(1, new.ofType(PacketType.INCIDENT_REPORT).size)
        assertEquals(1, old.received.count { it.type == PacketType.INCIDENT_REPORT })
        assertTrue(PacketType.SUMMARY in old.arrived && PacketType.REQUEST in old.arrived)
        assertFalse(PacketType.SUMMARY2 in old.arrived || PacketType.REQUEST2 in old.arrived)
        assertTrue(new.sentTtls(PacketType.SUMMARY2).isEmpty())
        assertNothingMalformed(listOf(new), listOf(old))
    }

    @Test
    fun whatOnlyOneOneReadsCrossesOneOneAndNeverReachesOneZero() = runTest {
        val w = World(this)
        val (hub, leaves, olds) = star(w, listOf("A" to A, "C" to C), listOf("O" to OLD))
        val (a, c) = leaves
        val old = olds.single()
        a.inject(packet(UNKNOWN, 1))
        a.inject(packet(PacketType.ANNOUNCEMENT, 2))
        a.inject(Packet(PacketType.INCIDENT_ACK, 7, 3L, A, 0L, null, false, ack(1, NEWER_STATUS).encode()))
        a.inject(Packet(PacketType.INCIDENT_ACK, 7, 4L, A, 0L, null, false, ack(2, AckStatus.RESOLVED, byteArrayOf(1)).encode()))
        a.inject(packet(PacketType.INCIDENT_CANCEL, 5, sender = 0L, size = IncidentCancel.SIZE + 4))
        advanceTimeBy(5_000)
        assertEquals(1L, c.arrived[UNKNOWN])
        assertEquals(1L, c.arrived[PacketType.ANNOUNCEMENT])
        assertEquals(2L, c.arrived[PacketType.INCIDENT_ACK])
        assertEquals(1L, c.arrived[PacketType.INCIDENT_CANCEL])
        val withheld = setOf(UNKNOWN, PacketType.ANNOUNCEMENT, PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL)
        assertTrue("1.0 got ${old.arrived}", old.arrived.none { it in withheld })

        // the hub keeps the extended ACK, but doesn't list it to the 1.0 peer, which couldn't take it
        advanceTimeBy(70_000)
        assertTrue(old.arrived.none { it in withheld || it == PacketType.SUMMARY })
        // nor hand it over when asked
        old.request(incidentId(2).toLong())
        advanceTimeBy(2_000)
        assertEquals(AckStatus.RESOLVED, hub.engine.ackStatus(incidentId(2)))
        assertTrue(old.arrived.none { it in withheld })
        assertNothingMalformed(listOf(hub, a, c), listOf(old))
    }

    /**
     * A 1.0 phone knows a peer by the nodeId's prefix it advertises, or by the address it connected to. A 1.1 phone
     * advertises neither for long, and 1.0 can't read its prefix from HELLO: one extra link each time the prefix
     * changes, and no endless reconnecting, whichever nodeId is the lower one.
     */
    @Test
    fun aOneZeroPhoneNextToARotatingPrefixDoesNotKeepReconnecting() = runTest {
        for ((newId, oldId) in listOf(A to OLD, OLD to A)) {
            val w = World(this)
            val new = w.v11("N", newId)
            val old = V10Node(w.bg, w.net, "O", oldId, w.clock, ::verify, connects = true)
            w.net.start()
            advanceTimeBy(60 * 60_000L)
            val rotations = new.radio.prefixes.size - 1
            val connects = new.radio.connectAttempts.size + old.radio.connectAttempts.size
            assertTrue("rotated $rotations times", rotations >= 3)
            assertTrue("$connects connects for $rotations rotations", connects <= 2 * rotations + 2)
            assertEquals(1, new.engine.state.value.readyLinks)
            assertEquals(1, old.linkCount)
            assertNothingMalformed(listOf(new), listOf(old))
        }
    }

    @Test
    fun oneZeroPhonesReachEachOtherThroughAOneOneRelay() = runTest {
        val w = World(this)
        val (hub, _, olds) = star(w, emptyList(), listOf("O" to OLD, "P" to OLD2))
        val (o, p) = olds
        o.publishReport(incidentId(1), ByteArray(80))
        o.unicast(OLD2, ByteArray(60))
        advanceTimeBy(5_000)
        assertEquals(1, p.received.count { it.type == PacketType.INCIDENT_REPORT })
        assertEquals("1.1 relays 1.0's chat ttl as it is", 6, p.received.single { it.type == PacketType.PRIVATE }.ttl)
        assertEquals(1, hub.ofType(PacketType.INCIDENT_REPORT).size)
        assertNothingMalformed(listOf(hub), olds)
    }
}
