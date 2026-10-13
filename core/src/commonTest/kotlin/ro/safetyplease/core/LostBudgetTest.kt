package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.position.Regasire
import ro.safetyplease.core.position.Search
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What "M-am pierdut de grup" costs the air: the chat packets of one lost session in a group of five. */
@OptIn(ExperimentalCoroutinesApi::class)
class LostBudgetTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private fun TestPhone.chatSent(): Long = engine.state.value.counters.sentByType[PacketType.PRIVATE] ?: 0

    /**
     * Five phones in one group, each linked only to a relay outside it, so what each writes is exactly what it
     * originates. A gets lost; B and C answer "Vin acolo"; nobody moves until the 15-minute share ends.
     *
     * Per member k = 4: the lost session and its DELIVERED (2), and the positions of the exact share (u = 4: the one
     * sent when the DELIVERED accepts the share, then one per heartbeat of a phone standing still). Per answer j = 2:
     * the yes to A and its DELIVERED (2). No START and no ACCEPT: the DELIVERED stands for them. 2k + k·u + 2j = 28.
     * Before, when each answer shared too and told the whole group, the same scene cost 90.
     */
    @Test
    fun aLostSessionInAGroupOfFiveCostsTwentyEightPackets() = runTest {
        val net = SimNet(backgroundScope)
        val clock = TestClock(testScheduler)
        val event = TestEvent()
        val box = PositionBox(BOX_LAT, BOX_LON)
        val landmark = box.toLatLon(100, 100)
        val venue = Venue(
            name = "Test", bounds = Bounds(BOX_LAT, BOX_LON, BOX_LAT + 0.01, BOX_LON + 0.01), zones = emptyList(),
            meetingPoint = box.toLatLon(10, 10), pois = listOf(Poi("info", landmark.lat, landmark.lon, number = 1)),
        )
        val names = listOf("A", "B", "C", "D", "E")
        val phones = names.mapIndexed { i, name -> TestPhone(name, this, net, clock, tmp.root, event, 2600L + i) }
        val relay = TestPhone("R", this, net, clock, tmp.root, event, 2700L)
        for (i in names.indices) for (j in i + 1 until names.size) net.cut(names[i], names[j])
        val regs = phones.map { phone ->
            val searches = JsonStore(tmp.root / "${phone.name}-searches.json", ListSerializer(Search.serializer()), emptyList(), backgroundScope)
            val at = box.toLatLon(50 + phones.indexOf(phone) * 20, 50)
            phone.chat.onFix(at.lat, at.lon, 5.0, null, PositionMethod.GNSS, clock.wallMs())
            Regasire(
                backgroundScope, phone.chat, phone.positions, clock, venue, phone.nodeId, searches,
                walk = { StraightLine }, mapState = { MapState.OPEN }, myPosition = { GeoFix(at.lat, at.lon, 5f, clock.wallMs()) },
                battery = { 50 }, frame = { phone.positionFrame },
            )
        }
        val a = phones[0]
        for (other in phones.drop(1)) a.befriend(other)
        net.start()
        advanceTimeBy(30_000)
        a.chat.createGroup("Gașca", phones.drop(1).map { it.nodeId })
        advanceTimeBy(30_000)
        val conversation = Conversations.group(a.groups.value.single().id)
        assertEquals(5, relay.engine.state.value.readyLinks, "a star around the relay")

        val before = phones.sumOf { it.chatSent() }
        val unique = net.traffic(PacketType.PRIVATE).packets
        assertEquals(true, regs[0].imLost(conversation))
        advanceTimeBy(10_000)
        for (i in 1..2) regs[i].comeThere(regs[i].alerts.value.single())
        advanceTimeBy(Regasire.SHARE_MIN * 60_000L)

        val sent = phones.sumOf { it.chatSent() } - before
        assertEquals((net.traffic(PacketType.PRIVATE).packets - unique).toLong(), sent, "each packet written once, by its sender")
        assertEquals(28L, sent)
    }
}
