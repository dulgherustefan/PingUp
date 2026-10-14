package ro.safetyplease.core.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.nodePrefix
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The experiments in [MeshConfig] that are off by default: what each changes, on small fixed topologies. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshExperimentsTest {
    private class Node(val id: Long, val radio: SimRadio, val engine: MeshEngine) {
        val received = mutableListOf<Packet>()
    }

    private fun TestScope.nodes(count: Int, config: MeshConfig, net: SimNet = SimNet(backgroundScope)): Pair<SimNet, List<Node>> {
        val clock = TestClock(testScheduler)
        val nodes = List(count) { i ->
            val id = (i + 1).toLong() shl 56 or (i + 1).toLong()
            val radio = net.radio("N$i", id.nodePrefix())
            val engine = MeshEngine(backgroundScope, radio, clock, Random(id), id, config, PacketLog(false)) { Verdict.VALID }
            Node(id, radio, engine).also { node ->
                backgroundScope.launch { engine.events.collect { if (it is MeshEvent.Received) node.received += it.packet } }
                engine.start()
            }
        }
        return net to nodes
    }

    /** N0 - N1 - ... - N(count-1): each phone links only with the ones next to it. */
    private fun TestScope.chain(count: Int, config: MeshConfig): List<Node> {
        val (net, nodes) = nodes(count, config)
        for ((i, node) in nodes.withIndex()) {
            val others = nodes.filterIndexed { j, _ -> j !in i - 1..i + 1 }
            node.engine.setIgnoredPrefixes(others.flatMap { listOf(it.radio.prefix, it.id.nodePrefix()) }.toSet())
        }
        net.start()
        advanceTimeBy(45_000)
        return nodes
    }

    private fun incidentId(n: Int) = ByteArray(INCIDENT_ID_SIZE) { (n + it).toByte() }

    @Test
    fun aReportStopsAtSevenHopsUntilTheNextSummary() = runTest {
        val nodes = chain(10, MeshConfig())
        nodes[0].engine.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(5_000)
        assertEquals(1, nodes[7].received.count { it.type == PacketType.INCIDENT_REPORT })
        assertTrue(nodes[9].received.none { it.type == PacketType.INCIDENT_REPORT })
    }

    @Test
    fun cachedPastTtlCarriesAReportBeyondSevenHopsAtOnce() = runTest {
        val nodes = chain(10, MeshConfig(cachedPastTtl = true))
        nodes[0].engine.publishReport(incidentId(1), ByteArray(80))
        advanceTimeBy(5_000)
        assertEquals(1, nodes[9].received.count { it.type == PacketType.INCIDENT_REPORT })
    }

    @Test
    fun routedUnicastAnswersAlongTheReversePath() = runTest {
        for (routed in listOf(false, true)) {
            val (net, nodes) = nodes(9, MeshConfig(routedUnicast = routed))
            // a 3 x 3 grid where only side neighbours hear each other: corner to corner is 4 hops
            net.range = 11.0
            net.grid(nodes.map { it.radio }, columns = 3, spacing = 10.0)
            net.start()
            advanceTimeBy(45_000)
            val (a, b) = nodes.first() to nodes.last()
            a.engine.unicast(PacketType.PRIVATE, b.id, ByteArray(60) { 1 })
            advanceTimeBy(5_000)
            val reply = b.engine.unicast(PacketType.PRIVATE, a.id, ByteArray(60) { 2 })
            advanceTimeBy(5_000)
            assertEquals(1, a.received.count { it.id == reply.id }, "routed=$routed")
            val cost = net.transmissions.getValue(reply.id).count
            // routed: one transmission per hop of the path the request's first copy took, 4 when it was a shortest one
            if (routed) assertTrue(cost in 4..6, "routed: $cost") else assertTrue(cost > 8, "a flood: $cost")
        }
    }

    @Test
    fun removeCopiesTakesOutOnlyThatPacket() {
        val queue = SendQueue()
        fun packet(id: Long) = Packet(PacketType.PRIVATE, 6, id, 7L, 0L, 9L, true, ByteArray(10))
        val kept = packet(1)
        val copy = packet(2)
        queue.offer(Outbound(kept, own = false))
        queue.offer(Outbound(copy, own = false))
        queue.offer(Outbound(copy.withTtl(3), own = false))
        assertEquals(2, queue.removeCopies(copy.dedupKey))
        assertEquals(1, queue.size)
        assertEquals(kept.id, queue.poll()?.packet?.id)
    }
}
