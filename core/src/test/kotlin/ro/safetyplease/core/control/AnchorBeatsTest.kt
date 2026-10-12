package ro.safetyplease.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.TestEvent
import ro.safetyplease.core.mesh.AnchorHeartbeat
import ro.safetyplease.core.mesh.DeviceReadings
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.SeenPeer
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.nodePrefix

class AnchorBeatsTest {
    private val plugged = DeviceReadings(batteryPercent = 80, charging = true, temperatureC = 30)
    private val linked = MeshState(links = listOf(LinkInfo(1, "a", 5L, null, 0, true, 0)))

    private fun seen(vararg lastSeen: Long) = MeshState(seen = lastSeen.mapIndexed { i, at -> SeenPeer("p$i", i, 0, -60, at) })

    @Test
    fun anAnchorBeatsEveryFiveMinutesAndAtOnceWhenItsStateChanges() {
        val beats = AnchorHeartbeat()
        assertNotNull("the first at once", beats.next(linked, plugged, "B2", 0))
        assertNull(beats.next(linked, plugged, "B2", AnchorHeartbeat.INTERVAL_MS - 1))
        assertNotNull(beats.next(linked, plugged, "B2", AnchorHeartbeat.INTERVAL_MS))

        val start = AnchorHeartbeat.INTERVAL_MS
        val unplugged = plugged.copy(charging = false)
        assertNull("not within a minute of the last", beats.next(linked, unplugged, "B2", start + 30_000))
        assertEquals(false, beats.next(linked, unplugged, "B2", start + AnchorHeartbeat.MIN_GAP_MS)?.charging)
        val now = start + 3 * AnchorHeartbeat.MIN_GAP_MS
        assertNull("the crowd changes all the time", beats.next(linked.copy(seen = seen(now, now, now).seen), unplugged, "B2", now))
        assertNotNull("too hot", beats.next(linked, unplugged.copy(temperatureC = AnchorHeartbeat.HOT_C), "B2", now))
        assertNotNull("alone", beats.next(MeshState(), unplugged.copy(temperatureC = AnchorHeartbeat.HOT_C), "B2", now + AnchorHeartbeat.MIN_GAP_MS))
    }

    @Test
    fun theBeatSaysHowLongTheAnchorHasHeardNoPhoneAndHowManyAreAround() {
        val beats = AnchorHeartbeat()
        val never = beats.next(MeshState(), plugged, "B2", 0)!!
        assertNull(never.quietS)
        assertEquals(0, never.links)
        val crowd = beats.next(seen(1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 61_000), plugged, "B2", AnchorHeartbeat.INTERVAL_MS)!!
        assertEquals(Heartbeat.crowdOf(7), crowd.crowd)
        assertEquals((AnchorHeartbeat.INTERVAL_MS - 61_000).toInt() / 1000, crowd.quietS)
        val later = beats.next(MeshState(), plugged, "B2", 2 * AnchorHeartbeat.INTERVAL_MS)!!
        assertEquals("phones that left the scan are still the last heard", (2 * AnchorHeartbeat.INTERVAL_MS - 61_000).toInt() / 1000, later.quietS)
        assertEquals(3, Heartbeat.crowdOf(6))
        assertEquals(7, Heartbeat.crowdOf(500))
    }

    @Test
    fun onlyStaffOpensAHeartbeatAndOnlyOnTheControlChannel() {
        val event = TestEvent()
        val beat = Heartbeat(batteryPercent = 64, charging = false, temperatureC = 41, quietS = 12, links = 2, crowd = 5, zone = "C3")
        val sealed = AnchorBeats.seal(event.staffCrypto, beat)
        fun to(recipient: Long) = Packet(PacketType.PRIVATE, 6, 1, 0x1234L, 0, recipient, true, sealed)
        val packet = to(ControlChannel.ID)
        assertEquals(beat, AnchorBeats.open(event.staffCrypto, event.secret, packet))
        assertNull("addressed to a phone", AnchorBeats.open(event.staffCrypto, event.secret, to(5L)))
        assertNull("another event's staff", AnchorBeats.open(event.staffCrypto, TestEvent().secret, packet))
    }

    @Test
    fun aHeartbeatKeepsAnAnchorHeardWhereverItIs() {
        val anchor = 0x7a00_0000_0000_0001L
        val beat = Heartbeat(batteryPercent = 15, charging = false, temperatureC = 47, quietS = 0, links = 4, crowd = 6, zone = "A1")
        val book = PeerBook.heartbeat(emptyMap(), anchor, beat, wallMs = 1_000)
        val peer = book.getValue(anchor.nodePrefix())
        assertTrue(peer.anchor)
        assertFalse(peer.linked)
        assertEquals(beat, peer.beat)
        assertEquals(1_000L, peer.lastHeardMs)

        val scanned = MeshState(seen = listOf(SeenPeer("x", anchor.nodePrefix(), NodeFlags.ANCHOR, -70, 5_000)))
        val after = PeerBook.update(book, scanned, wallMs = 10_000, monoMs = 5_000).getValue(anchor.nodePrefix())
        assertEquals("a scan keeps the last beat", beat, after.beat)
        assertEquals(10_000L, after.lastHeardMs)
    }
}
