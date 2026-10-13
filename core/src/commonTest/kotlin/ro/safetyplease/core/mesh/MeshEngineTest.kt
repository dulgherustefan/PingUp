package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.delete
import ro.safetyplease.core.exists
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.writeText
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BAD_SIGNATURE: Byte = 0x66

@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private class Node(
        val name: String,
        val id: Long,
        net: SimNet,
        scope: CoroutineScope,
        clock: Clock,
        maxFrame: Int = 514,
        canAdvertise: Boolean = true,
        config: MeshConfig = MeshConfig(allowTestPackets = true),
        cache: JsonStore<CachedIncidents>? = null,
    ) {
        val radio = net.radio(name, id.nodePrefix(), maxFrame, canAdvertise)
        val engine = MeshEngine(scope, radio, clock, Random(id), id, config, PacketLog(false), cache) { payload ->
            if (payload.last() != BAD_SIGNATURE) Verdict.VALID else Verdict.INVALID
        }
        val received = mutableListOf<Packet>()
        val sent = mutableListOf<Long>()
        val linked = mutableSetOf<Long>()

        init {
            scope.launch {
                engine.events.collect {
                    when (it) {
                        is MeshEvent.Received -> received += it.packet
                        is MeshEvent.Sent -> sent += it.packetId
                        is MeshEvent.PeerLinked -> linked += it.peerId
                        is MeshEvent.PeerUnlinked -> Unit
                    }
                }
            }
            engine.start()
        }

        val readyLinks: Int get() = engine.state.value.readyLinks
        fun ofType(type: Int) = received.filter { it.type == type }

        /** By the prefix the others advertise now, so no link opens, and by their nodeId's, so one that opens is closed. */
        fun ignore(vararg others: Node) =
            engine.setIgnoredPrefixes(others.flatMap { listOf(it.radio.prefix, it.id.nodePrefix()) }.toSet())
    }

    private companion object {
        const val A = 0x1000_0000_0000_000AL
        const val B = 0x2000_0000_0000_000BL
        const val C = 0x3000_0000_0000_000CL
        const val D = 0x4000_0000_0000_000DL
        const val V = 0x5000_0000_0000_000EL
    }

    private class World(scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val bg = scope.backgroundScope
        val net = SimNet(bg)
        fun node(
            name: String,
            id: Long,
            maxFrame: Int = 514,
            canAdvertise: Boolean = true,
            cache: JsonStore<CachedIncidents>? = null,
            config: MeshConfig = MeshConfig(allowTestPackets = true),
        ) = Node(name, id, net, bg, clock, maxFrame, canAdvertise, config, cache)
    }

    /** The acceptance scenario chain: A and C, A and D, B and D ignore each other, so A - B - C - D. */
    private fun TestScope.chain(world: World = World(this), midFrame: Int = 514): List<Node> {
        val a = world.node("A", A)
        val b = world.node("B", B, maxFrame = midFrame)
        val c = world.node("C", C)
        val d = world.node("D", D)
        a.ignore(c, d)
        b.ignore(d)
        c.ignore(a)
        d.ignore(a, b)
        world.net.start()
        advanceTimeBy(30_000)
        return listOf(a, b, c, d)
    }

    private fun incidentId(n: Int) = ByteArray(INCIDENT_ID_SIZE) { (n + it).toByte() }

    private fun ack(n: Int, status: Int, team: String = "T1", ts: Long = 100, signature: Byte = 1) =
        IncidentAck(incidentId(n), status, ts, team, ByteArray(IncidentAck.SIGNATURE_SIZE) { signature })

    @Test
    fun testPacketCrossesForcedChainInThreeHops() = runTest {
        val (a, b, c, d) = chain()
        val before = testScheduler.currentTime
        a.engine.broadcast(PacketType.TEST, byteArrayOf(42))
        advanceTimeBy(15_000)
        assertEquals(1, b.ofType(PacketType.TEST).single().hops)
        assertEquals(2, c.ofType(PacketType.TEST).single().hops)
        val atD = d.ofType(PacketType.TEST).single()
        assertEquals(3, atD.hops)
        assertEquals(A, atD.sender)
        assertTrue(testScheduler.currentTime - before <= 15_000)
        assertTrue(a.received.isEmpty())
    }

    @Test
    fun ignoredPeersOnlyReachUsThroughTheChain() = runTest {
        val (a, _, c, d) = chain()
        d.engine.broadcast(PacketType.TEST, byteArrayOf(1))
        c.engine.broadcast(PacketType.TEST, byteArrayOf(2))
        advanceTimeBy(5_000)
        val atA = a.ofType(PacketType.TEST)
        assertEquals(2, atA.size)
        assertEquals(3, atA.first { it.sender == D }.hops)
        assertEquals(2, atA.first { it.sender == C }.hops)
    }

    @Test
    fun unicastIsConsumedByRecipientAndInvisibleToRelays() = runTest {
        val (a, b, c, d) = chain()
        val packet = d.engine.unicast(PacketType.PRIVATE, A, ByteArray(60) { 3 })
        advanceTimeBy(5_000)
        assertEquals(packet.id, a.ofType(PacketType.PRIVATE).single().id)
        assertEquals(3, a.received.single().hops)
        assertTrue(b.received.isEmpty() && c.received.isEmpty())
        assertTrue(packet.id in d.sent)
    }

    @Test
    fun maxPayloadSurvivesSmallMtuLinks() = runTest {
        val (a, _, _, d) = chain(midFrame = 100)
        val payload = ByteArray(PacketCodec.MAX_PAYLOAD) { (it * 31).toByte() }
        a.engine.broadcast(PacketType.TEST, payload)
        advanceTimeBy(5_000)
        assertContentEquals(payload, d.ofType(PacketType.TEST).single().payload)
    }

    @Test
    fun incidentReachesStaffAndAckComesBack() = runTest {
        val (a, _, _, d) = chain()
        a.engine.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(5_000)
        val report = d.ofType(PacketType.INCIDENT_REPORT).single()
        assertEquals(0L, report.sender)
        assertEquals(3, report.hops)
        d.engine.publishAck(ack(1, AckStatus.RECEIVED))
        advanceTimeBy(5_000)
        assertEquals(3, a.ofType(PacketType.INCIDENT_ACK).single().hops)
        assertEquals(AckStatus.RECEIVED, a.engine.ackStatus(incidentId(1)))
    }

    @Test
    fun sameIncidentInNewPacketIsNotDeliveredTwice() = runTest {
        val (a, b, _, d) = chain()
        a.engine.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(5_000)
        b.engine.publishReport(incidentId(1), ByteArray(80) { 9 })
        advanceTimeBy(5_000)
        assertEquals(1, d.ofType(PacketType.INCIDENT_REPORT).size)
    }

    @Test
    fun cancelCrossesTheChainAnonymouslyAndBadCopiesStopAtFirstHop() = runTest {
        val (a, b, _, d) = chain()
        val packet = a.engine.publishCancel(incidentId(1), ByteArray(IncidentCancel.TOKEN_SIZE) { 4 })
        advanceTimeBy(5_000)
        val atD = d.ofType(PacketType.INCIDENT_CANCEL).single()
        assertEquals(packet.id, atD.id)
        assertEquals(0L, atD.sender)
        assertEquals(3, atD.hops)
        assertTrue(packet.id in a.sent)

        val link = a.radio.links.entries.single { it.value.radio === b.radio }.key
        a.radio.inject(link, PacketCodec.encode(packet))
        val short = Packet(PacketType.INCIDENT_CANCEL, 7, 99L, 0L, 0L, null, false, ByteArray(IncidentCancel.SIZE - 1))
        a.radio.inject(link, PacketCodec.encode(short))
        advanceTimeBy(5_000)
        assertEquals(1, b.ofType(PacketType.INCIDENT_CANCEL).size, "the duplicate and the truncated one stop at B")
        assertEquals(1, d.ofType(PacketType.INCIDENT_CANCEL).size)
    }

    @Test
    fun forgottenReportIsNotServedToNewPeers() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        a.engine.publishReport(incidentId(3), ByteArray(80))
        a.engine.forgetReport(incidentId(3))
        assertEquals(0, a.engine.state.value.cachedReports)
        world.net.start()
        advanceTimeBy(70_000)
        assertEquals(1, b.readyLinks)
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun storeAndForwardDeliversWhenStaffComesBack() = runTest {
        val (a, _, _, d) = chain()
        d.radio.powerOff()
        advanceTimeBy(5_000)
        a.engine.publishReport(incidentId(7), ByteArray(80))
        advanceTimeBy(120_000)
        assertTrue(d.received.isEmpty())
        d.radio.powerOn()
        advanceTimeBy(30_000)
        assertEquals(1, d.ofType(PacketType.INCIDENT_REPORT).size)
    }

    @Test
    fun reportWrittenWhileAloneLeavesWithFirstLink() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        a.engine.publishReport(incidentId(3), ByteArray(80))
        runCurrent()
        assertTrue(a.sent.isEmpty())
        world.net.start()
        advanceTimeBy(10_000)
        assertEquals(1, b.ofType(PacketType.INCIDENT_REPORT).size)
    }

    @Test
    fun periodicSummaryHealsALostFrame() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        var dropped = false
        a.radio.tamper = { frame ->
            if (!dropped && frame[1].toInt() == PacketType.INCIDENT_REPORT) {
                dropped = true
                null
            } else frame
        }
        a.engine.publishReport(incidentId(5), ByteArray(80))
        advanceTimeBy(5_000)
        assertTrue(dropped && b.received.isEmpty())
        advanceTimeBy(70_000)
        assertEquals(1, b.ofType(PacketType.INCIDENT_REPORT).size)
    }

    @Test
    fun higherAckSupersedesAndStaleOnesAreNotRelayed() = runTest {
        val (a, _, c, d) = chain()
        d.engine.publishAck(ack(1, AckStatus.RECEIVED))
        advanceTimeBy(3_000)
        d.engine.publishAck(ack(1, AckStatus.ACKNOWLEDGED, team = "Medical 2"))
        advanceTimeBy(3_000)
        c.engine.publishAck(ack(1, AckStatus.RECEIVED, team = "Alta echipa"))
        advanceTimeBy(3_000)
        assertEquals(2, a.ofType(PacketType.INCIDENT_ACK).size)
        assertEquals(AckStatus.ACKNOWLEDGED, a.engine.ackStatus(incidentId(1)))
    }

    @Test
    fun forgedAckIsDroppedAtFirstHop() = runTest {
        val (a, b, c, d) = chain()
        a.engine.publishAck(ack(1, AckStatus.RESOLVED, signature = BAD_SIGNATURE))
        advanceTimeBy(5_000)
        assertTrue(b.received.isEmpty() && c.received.isEmpty() && d.received.isEmpty())
        assertEquals(AckStatus.NONE, d.engine.ackStatus(incidentId(1)))
    }

    @Test
    fun paceKeepsBurstUnderReceiverLimit() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        repeat(60) { a.engine.broadcast(PacketType.TEST, byteArrayOf(it.toByte())) }
        advanceTimeBy(20_000)
        assertEquals(60, b.ofType(PacketType.TEST).size)
        assertEquals(0L, b.engine.state.value.dropped)
    }

    @Test
    fun floodingPeerIsRateLimited() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        val link = a.radio.links.keys.single()
        repeat(100) {
            val p = Packet(PacketType.TEST, 7, 1000L + it, A, 0, null, false, byteArrayOf(1))
            a.radio.inject(link, PacketCodec.encode(p))
        }
        advanceTimeBy(1_500)
        val accepted = b.ofType(PacketType.TEST).size
        assertTrue(accepted in 1..30, "accepted=$accepted")
        assertTrue(b.engine.state.value.dropped >= 70)
    }

    @Test
    fun duplicatesCostNoReceiveTokens() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        val link = a.radio.links.keys.single()
        val copy = PacketCodec.encode(Packet(PacketType.TEST, 7, 500L, A, 0, null, false, byteArrayOf(1)))
        repeat(100) { a.radio.inject(link, copy) }
        advanceTimeBy(100)
        repeat(20) {
            val p = Packet(PacketType.TEST, 7, 1000L + it, A, 0, null, false, byteArrayOf(2))
            a.radio.inject(link, PacketCodec.encode(p))
        }
        advanceTimeBy(100)
        assertEquals(21, b.ofType(PacketType.TEST).size, "the 99 copies left the burst for new packets")
        val drops = b.engine.state.value.counters.drops
        assertEquals(99L, drops[DropReason.DUPLICATE])
        assertNull(drops[DropReason.RATE_LIMIT])
    }

    @Test
    fun relaySkipsLinksThatAlreadyGaveUsTheCopy() = runTest {
        val world = World(this)
        val hub = world.node("H", 0x0100_0000_0000_0001L)
        val a = world.node("A", A)
        val c = world.node("C", C)
        val d = world.node("D", D)
        val leaves = listOf(a, c, d)
        for (x in leaves) for (y in leaves) if (x !== y) world.net.outOfRange += setOf(x.name, y.name)
        world.net.start()
        advanceTimeBy(10_000)
        assertEquals(3, hub.readyLinks)
        val frame = PacketCodec.encode(Packet(PacketType.TEST, 7, 77L, A, 0, null, false, byteArrayOf(5)))
        a.radio.inject(a.radio.links.keys.single(), frame)
        // the second copy lands inside the hub's relay jitter
        advanceTimeBy(20)
        c.radio.inject(c.radio.links.keys.single(), frame)
        advanceTimeBy(2_000)
        assertEquals(1, hub.ofType(PacketType.TEST).size)
        assertEquals(1, d.ofType(PacketType.TEST).size)
        assertTrue(a.received.isEmpty() && c.received.isEmpty(), "no echo to the links it came from")
    }

    @Test
    fun countersFollowTheTraffic() = runTest {
        val (a, b, c, d) = chain()
        d.radio.powerOff()
        advanceTimeBy(5_000)
        a.engine.publishReport(incidentId(1), ByteArray(80))
        a.engine.broadcast(PacketType.TEST, byteArrayOf(1))
        advanceTimeBy(5_000)
        d.radio.powerOn()
        advanceTimeBy(30_000)

        val atA = a.engine.state.value.counters
        assertEquals(1L, atA.sentByType[PacketType.INCIDENT_REPORT])
        assertTrue((atA.sentByType[PacketType.HELLO] ?: 0) >= 1)
        val atB = b.engine.state.value
        assertEquals(1L, atB.counters.receivedByType[PacketType.INCIDENT_REPORT])
        assertEquals(1L, atB.counters.receivedByType[PacketType.TEST])
        assertEquals(2L, atB.relayed)
        assertTrue(c.engine.state.value.counters.storeAndForward >= 1, "C hands the report to D when it comes back")
        val atD = d.engine.state.value
        assertEquals(1, d.ofType(PacketType.INCIDENT_REPORT).size)
        assertEquals(1, atD.cachedReports)
        assertTrue(atD.readyLinks >= 1)
        assertTrue((atD.counters.receivedByType[PacketType.SUMMARY2] ?: 0) >= 1)
        for (state in listOf(a, b, c, d).map { it.engine.state.value }) {
            assertEquals(state.counters.drops.values.sum(), state.dropped)
        }
    }

    @Test
    fun anAndroidPhoneReachesAnIphoneInTheBackgroundAndNeitherEndConnectsAgain() = runTest {
        // both orders of nodeIds: the duplicate rule keeps the link the lower one opened and closes the other
        for ((android, iphone) in listOf(A to B, B to A)) {
            val world = World(this)
            val a = world.node("A", android)
            val i = world.node("I", iphone, canAdvertise = false)
            i.radio.iphone = true
            world.net.start()
            advanceTimeBy(5_000)
            assertEquals(1, a.readyLinks)
            assertEquals(1, i.readyLinks)
            advanceTimeBy(5 * 60_000L)
            assertEquals(1, a.readyLinks)
            assertEquals(1, i.readyLinks)
            // each end connects from another address than the one it advertises: the end whose link lost would
            // otherwise go back to the advertised one, only to open the same duplicate
            val attempts = a.radio.connectAttempts + i.radio.connectAttempts
            assertTrue(attempts.size <= 3, "$attempts")
        }
    }

    @Test
    fun peerSendingGarbageIsBannedForTenMinutes() = runTest {
        val world = World(this)
        // A doesn't advertise, so the only way back is from the address B banned
        val a = world.node("A", A, canAdvertise = false)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, b.readyLinks)
        val link = a.radio.links.keys.single()
        // version 1 and cut short: garbage with another version byte would be a newer version's frame
        repeat(5) { a.radio.inject(link, byteArrayOf(1, 9, 9)) }
        advanceTimeBy(2_000)
        assertEquals(0, b.readyLinks)
        advanceTimeBy(8 * 60_000L)
        assertEquals(0, b.readyLinks, "still ignored")
        val attempts = a.radio.connectAttempts.size
        assertTrue(attempts < 20, "rare retries, not a loop: $attempts")
        val drops = b.engine.state.value.counters.drops
        assertEquals(5L, drops[DropReason.MALFORMED])
        assertTrue(b.engine.state.value.counters.refusedBanned >= 1)
        assertEquals(drops.values.sum(), b.engine.state.value.dropped, "refusals aren't packets")
        advanceTimeBy(4 * 60_000L)
        assertEquals(1, b.readyLinks)
    }

    @Test
    fun theBanFallsOnTheLinkNotOnTheNodeIdItClaims() = runTest {
        val world = World(this)
        val b = world.node("B", B)
        world.net.start()
        // a phone that claims to be V and sends garbage, to get V banned
        val framer = world.net.radio("X", 0x7777_7777, canAdvertise = false)
        framer.connect("B", 20_000)
        advanceTimeBy(1_000)
        val link = framer.links.keys.single()
        val hello = Hello(PacketCodec.VERSION, V, NodeFlags.PROTOCOL_1_1, null).encode()
        framer.inject(link, PacketCodec.encode(Packet(PacketType.HELLO, 1, 9L, V, 0, null, false, hello)))
        repeat(5) { framer.inject(link, byteArrayOf(1, 9, 9)) }
        advanceTimeBy(2_000)
        assertTrue(b.engine.state.value.links.isEmpty())
        framer.connect("B", 20_000)
        advanceTimeBy(2_000)
        assertTrue(framer.links.isEmpty() && b.engine.state.value.counters.refusedBanned >= 1, "its address stays out")

        val victim = world.node("V", V)
        advanceTimeBy(30_000)
        assertEquals(V, b.engine.state.value.links.single().peerId)
        assertEquals(1, victim.readyLinks)
    }

    @Test
    fun incidentsOvertakeQueuedChat() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        repeat(40) { a.engine.unicast(PacketType.PRIVATE, B, ByteArray(50)) }
        advanceTimeBy(500)
        a.engine.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(30_000)
        assertEquals(41, b.received.size)
        val position = b.received.indexOfFirst { it.type == PacketType.INCIDENT_REPORT }
        assertTrue(position in 1..15, "position=$position")
    }

    @Test
    fun packetsLostToAFullQueueAreCounted() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        repeat(200) { a.engine.broadcast(PacketType.TEST, byteArrayOf(it.toByte())) }
        advanceTimeBy(1_000)
        assertEquals(72L, a.engine.state.value.queueFull, "128 fit in the queue")
    }

    @Test
    fun simultaneousLinksCollapseToTheOneFromLowerId() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        // a second link the other way round, whichever end the prefixes made connect first
        if (a.engine.state.value.links.single().outgoing) b.radio.connect("A", 20_000) else a.radio.connect("B", 20_000)
        advanceTimeBy(5_000)
        assertEquals(1, a.engine.state.value.links.size)
        assertEquals(1, b.engine.state.value.links.size)
        assertTrue(a.engine.state.value.links.single().outgoing)
        assertFalse(b.engine.state.value.links.single().outgoing)
        a.engine.broadcast(PacketType.TEST, byteArrayOf(1))
        advanceTimeBy(1_000)
        assertEquals(1, b.ofType(PacketType.TEST).size)
    }

    @Test
    fun leafThatCannotAdvertiseStillJoins() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val leaf = world.node("Z", D, canAdvertise = false)
        world.net.start()
        advanceTimeBy(4_000)
        assertEquals(1, leaf.readyLinks)
        assertTrue(leaf.engine.state.value.links.single().outgoing)
        assertEquals(1, a.readyLinks)
    }

    @Test
    fun higherPrefixConnectsWhenLowerNeverDoes() = runTest {
        val world = World(this)
        val (low, high) = listOf(world.node("A", A), world.node("B", B))
            .sortedWith { x, y -> x.radio.prefix.toUInt().compareTo(y.radio.prefix.toUInt()) }
        low.ignore(high)
        world.net.start()
        advanceTimeBy(15_000)
        assertTrue(high.radio.connectAttempts.isEmpty())
        advanceTimeBy(15_000)
        assertEquals(1, high.radio.connectAttempts.size)
        assertEquals(0, low.readyLinks, "the lower one closes the ignored peer's link")
    }

    @Test
    fun flagsFollowRoleAndFreeSlotsAndHelloTellsTheSame() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        runCurrent()
        val always = NodeFlags.PROTOCOL_1_1 or NodeFlags.PREFIX_IN_HELLO or NodeFlags.HELLO_AUTH or NodeFlags.ACCEPTS_CONNECTIONS
        assertEquals(always or NodeFlags.ANCHOR_SLOT, a.radio.flags)
        a.engine.setRole(staff = true, anchor = false)
        assertEquals(always or NodeFlags.ANCHOR_SLOT or NodeFlags.STAFF, a.radio.flags)
        advanceTimeBy(5_000)
        assertEquals(always or NodeFlags.STAFF, b.engine.state.value.links.single().flags, "the anchor slot is only advertised")
        a.engine.setRole(staff = false, anchor = true)
        assertEquals(always or NodeFlags.ANCHOR, a.radio.flags, "an anchor keeps no slot for another")
    }

    @Test
    fun radioRestartForgetsStalePeers() = runTest {
        val world = World(this)
        // A sees B without a link to it, so the test only catches the clearing when the radio stops;
        // B can't see A, which doesn't advertise
        val a = world.node("A", A, canAdvertise = false)
        val b = world.node("B", B)
        a.ignore(b)
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, a.engine.state.value.seen.size)
        assertTrue(a.engine.state.value.links.isEmpty())
        a.radio.powerOff()
        runCurrent()
        assertTrue(a.engine.state.value.seen.isEmpty(), "no candidates left after the radio stops")
        a.engine.setIgnoredPrefixes(emptySet())
        a.radio.powerOn()
        advanceTimeBy(5_000)
        assertEquals(1, a.readyLinks, "connects after fresh advertising")
    }

    @Test
    fun lostPeerIsNotRetriedUntilSeenAgain() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, a.readyLinks)
        val attempts = a.radio.connectAttempts.size
        world.net.cut("A", "B")
        advanceTimeBy(14_000)
        assertEquals(attempts, a.radio.connectAttempts.size, "no blind connects to a vanished peer")
        assertTrue(a.engine.state.value.seen.isEmpty())
        world.net.heal("A", "B")
        advanceTimeBy(5_000)
        assertEquals(1, a.readyLinks)
        assertEquals(1, b.readyLinks)
    }

    @Test
    fun rotationEventuallyReachesEveryVisiblePeer() = runTest {
        val world = World(this)
        val hub = world.node("H", 0x0100_0000_0000_0001L)
        val others = List(9) { world.node("P$it", ((it + 2).toLong() shl 56) or it.toLong()) }
        for (x in others) for (y in others) if (x !== y) world.net.outOfRange += setOf(x.name, y.name)
        world.net.start()
        advanceTimeBy(40_000)
        assertEquals(7, hub.engine.state.value.links.size)
        assertFalse(hub.radio.flags and NodeFlags.ACCEPTS_CONNECTIONS != 0)
        advanceTimeBy(10 * 60_000L)
        assertEquals(others.map { it.id }.toSet(), hub.linked)
    }

    // --- advertised prefix ---

    @Test
    fun theAdvertisedPrefixRotatesAndNeighborsLearnItBeforeTheyCouldMistakeIt() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, a.readyLinks)
        val connects = a.radio.connectAttempts.size + b.radio.connectAttempts.size
        advanceTimeBy(40 * 60_000L)
        assertTrue(a.radio.prefixes.size >= 3 && b.radio.prefixes.size >= 3, "rotated: ${a.radio.prefixes}")
        assertFalse(A.nodePrefix() in a.radio.prefixes || B.nodePrefix() in b.radio.prefixes, "never the nodeId's")
        assertEquals(connects, a.radio.connectAttempts.size + b.radio.connectAttempts.size, "no second link for a new prefix")
        assertEquals(1, a.readyLinks)
        assertEquals(a.radio.prefix, b.engine.state.value.links.single().prefix)
        assertEquals(b.radio.prefix, a.engine.state.value.links.single().prefix)
    }

    @Test
    fun anAnchorKeepsItsNodeIdPrefixAndLeavesItWithTheRole() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        world.net.start()
        a.engine.setRole(staff = false, anchor = true)
        advanceTimeBy(40 * 60_000L)
        assertEquals(A.nodePrefix(), a.radio.prefix)
        assertEquals(2, a.radio.prefixes.size)
        a.engine.setRole(staff = false, anchor = false)
        advanceTimeBy(1_000)
        assertTrue(a.radio.prefix != A.nodePrefix() && a.radio.prefix !in a.radio.prefixes.dropLast(1))
    }

    @Test
    fun aRadioThatComesBackOnAdvertisesANewPrefix() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        world.net.start()
        advanceTimeBy(1_000)
        val before = a.radio.prefix
        a.radio.powerOff()
        runCurrent()
        a.radio.powerOn()
        advanceTimeBy(1_000)
        assertTrue(a.radio.prefix != before)
    }

    // --- persistent store-and-forward ---

    private fun TestScope.cacheStore(name: String = "cache.json") =
        JsonStore(tmp.root / name, CachedIncidents.serializer(), CachedIncidents(), backgroundScope)

    @Test
    fun aRelayKilledAndStartedAgainStillCarriesTheReportsAndAcks() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val relay = world.node("R", B, cache = cacheStore())
        world.net.start()
        advanceTimeBy(5_000)
        a.engine.publishReport(incidentId(1), ByteArray(80))
        a.engine.publishAck(ack(1, AckStatus.RECEIVED))
        advanceTimeBy(2_000)
        assertEquals(AckStatus.RECEIVED, relay.engine.ackStatus(incidentId(1)))
        a.radio.powerOff()
        relay.radio.powerOff()
        advanceTimeBy(1_000)

        val again = world.node("R2", B, cache = cacheStore())
        assertEquals(1, again.engine.cachedReports().size)
        assertEquals(AckStatus.RECEIVED, again.engine.ackStatus(incidentId(1)))
        val c = world.node("C", C)
        advanceTimeBy(10_000)
        assertEquals(1, c.ofType(PacketType.INCIDENT_REPORT).size, "the restarted relay hands it on")
        assertEquals(AckStatus.RECEIVED, c.engine.ackStatus(incidentId(1)))
    }

    @Test
    fun aDamagedCacheFileIsDeletedAndSavingStartsOver() = runTest {
        val file = (tmp.root / "cache.json").apply { writeText("{\"schemaVersion\":1,\"data\":{\"incidents\":[{") }
        val world = World(this)
        val store = cacheStore()
        val a = world.node("A", A, cache = store)
        assertEquals(0, a.engine.state.value.cachedReports)
        assertFalse(store.unreadable)
        assertFalse(file.exists())
        a.engine.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(1_000)
        assertEquals(1, a.engine.state.value.cachedReports)
        val again = cacheStore()
        assertFalse(again.unreadable)
        assertEquals(1, again.value.incidents.size)
    }

    // --- chat fan-out ---

    /** A sender and four leaves, each linked only to the hub, which relays to the four links besides the sender's. */
    private fun TestScope.star(fanOut: Boolean): List<Node> {
        val world = World(this)
        val hub = world.node("H", A, config = MeshConfig(allowTestPackets = true, chatFanOut = fanOut))
        val edge = listOf("S" to B, "L1" to C, "L2" to D, "L3" to V, "L4" to 0x6000_0000_0000_000FL).map { (name, id) -> world.node(name, id) }
        for (x in edge) for (y in edge) if (x !== y) world.net.outOfRange += setOf(x.name, y.name)
        world.net.start()
        advanceTimeBy(60_000)
        assertEquals(5, hub.readyLinks)
        return edge
    }

    private fun Node.got(type: Int): Long = engine.state.value.counters.receivedByType[type] ?: 0L

    @Test
    fun withoutTheFanOutARelayFloodsChatOnEveryLink() = runTest {
        assertFalse(MeshConfig().chatFanOut, "off by default")
        val edge = star(fanOut = false)
        val (sender, leaves) = edge.first() to edge.drop(1)
        repeat(4) { sender.engine.unicast(PacketType.PRIVATE, leaves.first().id, ByteArray(40)) }
        advanceTimeBy(5_000)
        assertEquals(listOf(4L, 4L, 4L, 4L), leaves.map { it.got(PacketType.PRIVATE) })
    }

    @Test
    fun withTheFanOutARelayPassesChatOnLog2LinksTheRecipientsAmongThemAndIncidentsStillFlood() = runTest {
        val edge = star(fanOut = true)
        val (sender, leaves) = edge.first() to edge.drop(1)
        repeat(4) { sender.engine.unicast(PacketType.PRIVATE, leaves.first().id, ByteArray(40)) }
        advanceTimeBy(5_000)
        val chat = leaves.map { it.got(PacketType.PRIVATE) }
        assertEquals(8L, chat.sum(), "ceil(log2 4) = 2 of the 4 links, for each of 4 messages: $chat")
        assertEquals(4L, chat.first(), "the recipient's link every time")

        sender.engine.publishReport(incidentId(1), ByteArray(80))
        sender.engine.publishAck(ack(1, AckStatus.RECEIVED))
        advanceTimeBy(5_000)
        assertEquals(listOf(1L, 1L, 1L, 1L), leaves.map { it.got(PacketType.INCIDENT_REPORT) })
        assertEquals(listOf(1L, 1L, 1L, 1L), leaves.map { it.got(PacketType.INCIDENT_ACK) })
    }

    // --- demo ignore ---

    @Test
    fun anIgnoredPhoneIsCutOffAsSoonAsItsHelloTellsWhoItIs() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        // as the demo does, by the nodeId's prefix only, which B never advertises
        a.engine.setIgnoredPrefixes(setOf(B.nodePrefix()))
        world.net.start()
        var linkedSeconds = 0
        repeat(20 * 60) {
            advanceTimeBy(1_000)
            if (a.engine.state.value.links.any { it.peerId == B }) linkedSeconds++
        }
        assertEquals(0, linkedSeconds)
        assertTrue(b.radio.prefixes.size >= 2, "through ${b.radio.prefixes.size} prefixes")
        val connects = a.radio.connectAttempts.size + b.radio.connectAttempts.size
        assertTrue(connects < 50, "rare retries, not a loop: $connects")

        a.engine.setIgnoredPrefixes(emptySet())
        advanceTimeBy(3 * 60_000L)
        assertEquals(1, a.readyLinks, "linked again once no longer ignored")
    }

    @Test
    fun anIgnoredAnchorIsNeverConnectedTo() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val anchor = world.node("N", B)
        anchor.engine.setRole(staff = false, anchor = true)
        a.ignore(anchor)
        anchor.ignore(a)
        world.net.start()
        advanceTimeBy(5 * 60_000L)
        assertTrue(a.radio.connectAttempts.none { it.startsWith("N") })
        assertEquals(0, a.readyLinks)
    }

    // --- a new advertising set ---

    @Test
    fun aRadioThatHasToStartANewAdvertisingSetGetsANewPrefixThatNeighborsLearn() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, a.readyLinks)
        val prefixes = a.radio.prefixes.size
        val connects = a.radio.connectAttempts.size + b.radio.connectAttempts.size
        a.radio.events.trySend(RadioEvent.PrefixWanted)
        advanceTimeBy(5_000)
        assertEquals(prefixes + 1, a.radio.prefixes.size)
        assertEquals(a.radio.prefixes.last(), b.engine.state.value.links.single().prefix)
        assertEquals(connects, a.radio.connectAttempts.size + b.radio.connectAttempts.size, "no second link for it")

        a.engine.setRole(staff = false, anchor = true)
        runCurrent()
        a.radio.events.trySend(RadioEvent.PrefixWanted)
        advanceTimeBy(1_000)
        assertEquals(A.nodePrefix(), a.radio.prefixes.last(), "an anchor keeps its nodeId's")
    }
}
