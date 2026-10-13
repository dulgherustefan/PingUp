package ro.safetyplease.core.control

import ro.safetyplease.core.TestEvent
import ro.safetyplease.core.mesh.AnchorHeartbeat
import ro.safetyplease.core.mesh.DeviceReadings
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.SeenPeer
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.Probe
import ro.safetyplease.core.metrics.Transport
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.MobileReading
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.nodePrefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnchorBeatsTest {
    private val plugged = DeviceReadings(batteryPercent = 80, charging = true, temperatureC = 30)
    private val linked = MeshState(links = listOf(LinkInfo(1, "a", 5L, null, 0, true, 0)))

    private fun seen(vararg lastSeen: Long) = MeshState(seen = lastSeen.mapIndexed { i, at -> SeenPeer("p$i", i, 0, -60, at) })

    @Test
    fun anAnchorBeatsEveryFiveMinutesAndAtOnceWhenItsStateChanges() {
        val beats = AnchorHeartbeat()
        assertNotNull(beats.next(linked, plugged, "B2", 0), "the first at once")
        assertNull(beats.next(linked, plugged, "B2", AnchorHeartbeat.INTERVAL_MS - 1))
        assertNotNull(beats.next(linked, plugged, "B2", AnchorHeartbeat.INTERVAL_MS))

        val start = AnchorHeartbeat.INTERVAL_MS
        val unplugged = plugged.copy(charging = false)
        assertNull(beats.next(linked, unplugged, "B2", start + 30_000), "not within a minute of the last")
        assertEquals(false, beats.next(linked, unplugged, "B2", start + AnchorHeartbeat.MIN_GAP_MS)?.charging)
        val now = start + 3 * AnchorHeartbeat.MIN_GAP_MS
        assertNull(beats.next(linked.copy(seen = seen(now, now, now).seen), unplugged, "B2", now), "the crowd changes all the time")
        assertNotNull(beats.next(linked, unplugged.copy(temperatureC = AnchorHeartbeat.HOT_C), "B2", now), "too hot")
        assertNotNull(beats.next(MeshState(), unplugged.copy(temperatureC = AnchorHeartbeat.HOT_C), "B2", now + AnchorHeartbeat.MIN_GAP_MS), "alone")
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
        assertEquals((2 * AnchorHeartbeat.INTERVAL_MS - 61_000).toInt() / 1000, later.quietS, "phones that left the scan are still the last heard")
        assertEquals(3, Heartbeat.crowdOf(6))
        assertEquals(7, Heartbeat.crowdOf(500))
    }

    @Test
    fun onlyStaffOpensAHeartbeatAndOnlyOnTheControlChannel() {
        val event = TestEvent()
        val beat = Heartbeat(batteryPercent = 64, charging = false, temperatureC = 41, quietS = 12, links = 2, crowd = 5, zone = "C3")
        val sealed = ControlMessages.seal(event.staffCrypto, beat.encode())
        fun to(recipient: Long) = Packet(PacketType.PRIVATE, 6, 1, 0x1234L, 0, recipient, true, sealed)
        val packet = to(ControlChannel.ID)
        assertEquals(beat, ControlMessages.open(event.staffCrypto, event.secret, packet)?.let(Heartbeat::decode))
        assertNull(ControlMessages.open(event.staffCrypto, event.secret, to(5L)), "addressed to a phone")
        assertNull(ControlMessages.open(event.staffCrypto, TestEvent().secret, packet), "another event's staff")
    }

    @Test
    fun theSiteBlockRoundTripsAndOnlyWhatIsKnownTravels() {
        val base = Heartbeat(batteryPercent = 64, charging = false, temperatureC = 41, quietS = 12, links = 2, crowd = 5, zone = "C3")
        val offline = MobileReading(null, MobileReading.TECH_UNKNOWN, MobileReading.TRANSPORT_NONE, MobileReading.PROBE_NOT_RUN, false)
        for (beat in listOf(base, base.copy(column = 25, row = 98), base.copy(mobile = offline), base.copy(mobile = offline, column = 0, row = 0))) {
            assertEquals(beat, Heartbeat.decode(beat.encode()))
        }
        assertEquals(base.encode().size, Heartbeat(64, false, 41, 12, 2, 5, "C3").encode().size, "nothing known adds nothing")
        assertEquals(base.encode().size + Heartbeat.SITE_BYTES, base.copy(mobile = offline).encode().size)

        val odd = base.encode() + byteArrayOf(9, 0x7f, 0xff.toByte(), 30, 2, 0x55)
        val read = Heartbeat.decode(odd)!!
        assertEquals(MobileReading(null, 0, 3, 0, true), read.mobile, "level 9 and technology 0x7f are unknown, probe 3 is not run")
        assertNull(read.column, "column 30 is off the grid")
        assertNull(read.row)
    }

    @Test
    fun anAnchorCarriesItsLastNetworkSampleAndSquareWithoutBeatingMoreOften() {
        val beats = AnchorHeartbeat()
        val sample = NetworkSample(at = 0, level = 3, technology = "nr", transport = Transport.CELLULAR, validated = true, probe = Probe.FAILED)
        val mobile = AnchorHeartbeat.mobileOf(sample)
        assertEquals(MobileReading(3, 6, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_FAILED, true), mobile)
        assertEquals(0, AnchorHeartbeat.mobileOf(sample.copy(technology = "iden")).technology)
        val first = beats.next(linked, plugged, "B2", 0, mobile, 1 to 4)!!
        assertEquals(listOf(mobile, 1, 4), listOf(first.mobile, first.column, first.row))
        val airplane = AnchorHeartbeat.mobileOf(NetworkSample(at = 0))
        assertNull(beats.next(linked, plugged, "B2", AnchorHeartbeat.MIN_GAP_MS, airplane, 1 to 4), "a new reading waits for the next beat")
        assertEquals(CellData.NO_NETWORK, Coverage.data(beats.next(linked, plugged, "B2", AnchorHeartbeat.INTERVAL_MS, airplane, 1 to 4)!!.mobile))
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
        assertEquals(beat, after.beat, "a scan keeps the last beat")
        assertEquals(10_000L, after.lastHeardMs)
    }
}
