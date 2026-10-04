package ro.safetyplease.app.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.core.Clock
import ro.safetyplease.app.core.nodePrefix
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.app.protocol.IncidentAck
import ro.safetyplease.app.protocol.NodeFlags
import ro.safetyplease.app.protocol.Packet
import ro.safetyplease.app.protocol.PacketCodec
import ro.safetyplease.app.protocol.PacketType
import kotlin.random.Random

private const val BAD_SIGNATURE: Byte = 0x66

@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineTest {
    private class Node(
        val name: String,
        val id: Long,
        net: SimNet,
        scope: CoroutineScope,
        clock: Clock,
        maxFrame: Int = 514,
        canAdvertise: Boolean = true,
        config: MeshConfig = MeshConfig(allowTestPackets = true),
    ) {
        val radio = net.radio(name, id.nodePrefix(), maxFrame, canAdvertise)
        val engine = MeshEngine(scope, radio, clock, Random(id), id, config, PacketLog(false)) { payload ->
            payload.last() != BAD_SIGNATURE
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
    }

    private companion object {
        const val A = 0x1000_0000_0000_000AL
        const val B = 0x2000_0000_0000_000BL
        const val C = 0x3000_0000_0000_000CL
        const val D = 0x4000_0000_0000_000DL
    }

    private class World(scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val bg = scope.backgroundScope
        val net = SimNet(bg)
        fun node(name: String, id: Long, maxFrame: Int = 514, canAdvertise: Boolean = true) =
            Node(name, id, net, bg, clock, maxFrame, canAdvertise)
    }

    /** Lantul din scenariul de acceptanta: A ignora C si D, B ignora D, deci A - B - C - D. */
    private fun TestScope.chain(world: World = World(this), midFrame: Int = 514): List<Node> {
        val a = world.node("A", A)
        val b = world.node("B", B, maxFrame = midFrame)
        val c = world.node("C", C)
        val d = world.node("D", D)
        a.engine.setIgnoredPrefixes(setOf(C.nodePrefix(), D.nodePrefix()))
        b.engine.setIgnoredPrefixes(setOf(D.nodePrefix()))
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
        assertArrayEquals(payload, d.ofType(PacketType.TEST).single().payload)
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
        assertTrue("accepted=$accepted", accepted in 1..30)
        assertTrue(b.engine.state.value.dropped >= 70)
    }

    @Test
    fun peerSendingGarbageIsBannedForTenMinutes() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, b.readyLinks)
        val link = a.radio.links.keys.single()
        repeat(5) { a.radio.inject(link, byteArrayOf(9, 9, 9)) }
        advanceTimeBy(2_000)
        assertEquals(0, b.readyLinks)
        advanceTimeBy(8 * 60_000L)
        assertEquals("inca ignorat", 0, b.readyLinks)
        val attempts = a.radio.connectAttempts.size
        assertTrue("reincercari rare, nu in bucla: $attempts", attempts < 20)
        advanceTimeBy(4 * 60_000L)
        assertEquals(1, b.readyLinks)
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
        assertTrue("position=$position", position in 1..15)
    }

    @Test
    fun simultaneousLinksCollapseToTheOneFromLowerId() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        world.net.start()
        advanceTimeBy(5_000)
        b.radio.connect("A")
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
    fun higherIdConnectsWhenLowerNeverDoes() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        val b = world.node("B", B)
        a.engine.setIgnoredPrefixes(setOf(B.nodePrefix()))
        world.net.start()
        advanceTimeBy(15_000)
        assertTrue(b.engine.state.value.links.isEmpty())
        advanceTimeBy(15_000)
        assertEquals(1, b.engine.state.value.links.size)
        assertEquals("A il tine pe mut", 0, a.readyLinks)
    }

    @Test
    fun advertisedFlagsFollowRoleAndFreeSlots() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        world.net.start()
        runCurrent()
        assertEquals(NodeFlags.ACCEPTS_CONNECTIONS, a.radio.flags)
        a.engine.setRole(staff = true, anchor = false)
        a.engine.setPendingIncident(true)
        assertEquals(NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.STAFF or NodeFlags.PENDING_INCIDENT, a.radio.flags)
    }

    @Test
    fun radioRestartForgetsStalePeers() = runTest {
        val world = World(this)
        val a = world.node("A", A)
        world.node("B", B)
        // A il vede pe B fara sa aiba legatura cu el, ca testul sa prinda doar golirea de la oprirea radioului
        a.engine.setIgnoredPrefixes(setOf(B.nodePrefix()))
        world.net.start()
        advanceTimeBy(5_000)
        assertEquals(1, a.engine.state.value.seen.size)
        assertTrue(a.engine.state.value.links.isEmpty())
        a.radio.powerOff()
        runCurrent()
        assertTrue("dupa oprirea radioului nu mai ramane niciun candidat", a.engine.state.value.seen.isEmpty())
        a.engine.setIgnoredPrefixes(emptySet())
        a.radio.powerOn()
        advanceTimeBy(5_000)
        assertEquals("se conecteaza dupa advertising proaspat", 1, a.readyLinks)
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
        assertEquals("fara conectari in gol la un peer disparut", attempts, a.radio.connectAttempts.size)
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
}
