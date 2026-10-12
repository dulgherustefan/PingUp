package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.nodePrefix
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AnchorSessionTest {
    private class Node(name: String, val id: Long, net: SimNet, scope: CoroutineScope, private val clock: Clock, config: MeshConfig) {
        val radio = net.radio(name, id.nodePrefix())
        val engine = MeshEngine(scope, radio, clock, Random(id), id, config, PacketLog(false)) { Verdict.VALID }
        val received = mutableListOf<Packet>()

        /** When each session or link with a peer came up, by the peer's nodeId. */
        val linkedAt = HashMap<Long, MutableList<Long>>()

        init {
            scope.launch {
                engine.events.collect {
                    when (it) {
                        is MeshEvent.Received -> received += it.packet
                        is MeshEvent.PeerLinked -> linkedAt.getOrPut(it.peerId) { mutableListOf() } += clock.monoMs()
                        else -> Unit
                    }
                }
            }
            engine.start()
        }

        fun ignore(vararg others: Node) =
            engine.setIgnoredPrefixes(others.flatMap { listOf(it.radio.prefix, it.id.nodePrefix()) }.toSet())
    }

    private class World(scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val bg = scope.backgroundScope
        val net = SimNet(bg)
        fun node(name: String, id: Long, config: MeshConfig = MeshConfig()) = Node(name, id, net, bg, clock, config)
    }

    private fun incident(n: Int) = ByteArray(INCIDENT_ID_SIZE) { (n + it).toByte() }

    @Test
    fun oneAnchorReachesEachOfFiftyPhonesInTurnAndAReportReachesItAtOnce() = runTest {
        val world = World(this)
        val anchor = world.node("anchor", 0x7a00_0000_0000_0001L)
        anchor.engine.setRole(staff = false, anchor = true)
        val random = Random(50)
        val phones = List(50) { world.node("p$it", random.nextLong()) }
        // hears the others, but links only with the anchor
        val loner = world.node("loner", random.nextLong())
        loner.ignore(*phones.toTypedArray())
        phones.forEach { it.ignore(loner) }
        world.net.start()

        advanceTimeBy(RUN_MS)
        for (p in phones) {
            val times = p.linkedAt[anchor.id].orEmpty()
            val gaps = (listOf(0L) + times + RUN_MS).zipWithNext { x, y -> y - x }
            assertTrue("sessions at $times", gaps.max() <= ROUND_MS)
        }
        assertTrue("the anchor holds no more than its limits", anchor.engine.state.value.links.size <= LinkMode.ANCHOR.limits.total)

        // a report of its own takes it to the anchor at once, without waiting for a visit
        loner.engine.publishReport(incident(1), ByteArray(80))
        advanceTimeBy(15_000)
        assertEquals(1, anchor.engine.cachedReports().size)
        advanceTimeBy(ROUND_MS)
        assertEquals("and every phone", phones.size, phones.count { p -> p.received.any { it.type == PacketType.INCIDENT_REPORT } })
    }

    @Test
    fun aLeafRelaysReportsAndControlTrafficButNotChat() = runTest {
        val world = World(this)
        val staff = world.node("S", 0x1000_0000_0000_0001L)
        val leaf = world.node("L", 0x2000_0000_0000_0002L, MeshConfig(leaves = true))
        val far = world.node("F", 0x3000_0000_0000_0003L)
        staff.engine.setRole(staff = true, anchor = false)
        staff.ignore(far)
        far.ignore(staff)
        world.net.start()
        advanceTimeBy(30_000)
        assertEquals(2, leaf.engine.state.value.readyLinks)

        far.engine.unicast(PacketType.PRIVATE, staff.id, ByteArray(40))
        far.engine.unicast(PacketType.PRIVATE, ControlChannel.ID, ByteArray(60))
        far.engine.publishReport(incident(2), ByteArray(80))
        advanceTimeBy(5_000)
        assertEquals("chat doesn't cross a leaf", 0, staff.received.count { it.recipient == staff.id })
        assertEquals(1, staff.received.count { it.recipient == ControlChannel.ID })
        assertEquals(1, staff.received.count { it.type == PacketType.INCIDENT_REPORT })
    }

    @Test
    fun aPhoneWithoutStaffOrAnAnchorNearbyRelaysEverything() = runTest {
        val world = World(this)
        val a = world.node("A", 0x1000_0000_0000_0001L)
        val b = world.node("B", 0x2000_0000_0000_0002L)
        val c = world.node("C", 0x3000_0000_0000_0003L)
        a.ignore(c)
        c.ignore(a)
        world.net.start()
        advanceTimeBy(30_000)
        c.engine.unicast(PacketType.PRIVATE, a.id, ByteArray(40))
        advanceTimeBy(5_000)
        assertEquals(1, a.received.count { it.recipient == a.id })
        assertEquals(2, b.engine.state.value.readyLinks)
    }

    private companion object {
        /** The longest a phone waits for the anchor: a visit every [ConnectionPolicy.VISIT_INTERVAL_MS], plus going round 50 phones. */
        const val ROUND_MS = ConnectionPolicy.VISIT_INTERVAL_MS + 60_000L
        const val RUN_MS = 12 * 60_000L
    }
}
