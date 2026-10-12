package ro.safetyplease.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {
    private fun packet(
        payload: ByteArray = byteArrayOf(1, 2, 3),
        recipient: Long? = null,
        ttl: Int = 7,
        type: Int = PacketType.PRIVATE,
    ) = Packet(type, ttl, 0x1122334455667788L, -0x0102030405060708L, 1_760_000_000L, recipient, true, payload)

    @Test
    fun roundTripWithoutRecipient() {
        val bytes = PacketCodec.encode(packet())
        assertEquals(26 + 3, bytes.size)
        val frame = PacketCodec.decode(bytes)!!
        assertNull(frame.fragment)
        val p = frame.packet
        assertEquals(PacketType.PRIVATE, p.type)
        assertEquals(7, p.ttl)
        assertEquals(0x1122334455667788L, p.id)
        assertEquals(-0x0102030405060708L, p.sender)
        assertEquals(1_760_000_000L, p.timestamp)
        assertNull(p.recipient)
        assertTrue(p.encrypted)
        assertArrayEquals(byteArrayOf(1, 2, 3), p.payload)
    }

    @Test
    fun cancelIsAKnownType() {
        val p = Packet(PacketType.INCIDENT_CANCEL, 7, 5L, 0L, 1L, null, false, ByteArray(IncidentCancel.SIZE) { 1 })
        val back = PacketCodec.decode(PacketCodec.encode(p))!!.packet
        assertEquals(PacketType.INCIDENT_CANCEL, back.type)
        assertEquals(0L, back.sender)
        assertEquals("CANCEL", PacketType.name(back.type))
    }

    @Test
    fun roundTripWithRecipient() {
        val bytes = PacketCodec.encode(packet(recipient = 42L))
        assertEquals(26 + 8 + 3, bytes.size)
        assertEquals(42L, PacketCodec.decode(bytes)!!.packet.recipient)
    }

    @Test
    fun headerIsBigEndianAndVersioned() {
        val bytes = PacketCodec.encode(packet())
        assertEquals(1, bytes[0].toInt())
        assertEquals(PacketType.PRIVATE, bytes[1].toInt())
        assertEquals(7, bytes[2].toInt())
        assertEquals(0x11, bytes[4].toInt())
        assertEquals(0x88.toByte(), bytes[11])
        assertEquals(0, bytes[24].toInt())
        assertEquals(3, bytes[25].toInt())
    }

    @Test
    fun maxPayloadFitsExactlyOneWriteAtFullMtu() {
        val p = packet(ByteArray(PacketCodec.MAX_PAYLOAD), recipient = 1L)
        // MTU 517 leaves 514 usable bytes: header 26 + recipient 8 + payload 480
        assertEquals(514, PacketCodec.encodedSize(p))
        assertEquals(1, PacketCodec.toFrames(p, 514, 0).size)
        assertEquals(2, PacketCodec.toFrames(p, 513, 0).size)
    }

    @Test
    fun rejectsMalformedFrames() {
        val good = PacketCodec.encode(packet())
        val malformed = listOf(
            ByteArray(0), good.copyOf(25), good.clone().also { it[2] = 0 }, good.clone().also { it[2] = 8 },
            good.clone().also { it[25] = 2 }, good.clone().also { it[25] = 9 }, good + 0, good.clone().also { it[24] = 0x7f },
        )
        for (bytes in malformed) {
            assertNull(PacketCodec.decode(bytes))
            assertFalse("only malformed", PacketCodec.isForeign(bytes))
        }
    }

    @Test
    fun framesFromANewerVersionAreForeignNotMalformed() {
        val good = PacketCodec.encode(packet())
        assertFalse(PacketCodec.isForeign(good))
        for (bytes in listOf(good.clone().also { it[0] = 2 }, good.clone().also { it[3] = 0x14 }, byteArrayOf(9, 9, 9))) {
            assertNull(PacketCodec.decode(bytes))
            assertTrue(PacketCodec.isForeign(bytes))
        }
        assertFalse("too short to carry flags", PacketCodec.isForeign(byteArrayOf(1, 0x20, 7)))
    }

    @Test
    fun anyTypeDecodes() {
        val unknown = PacketCodec.decode(PacketCodec.encode(packet(type = 0x55)))!!.packet
        assertEquals(0x55, unknown.type)
        assertFalse(PacketType.isKnown(0x55))
        assertFalse(PacketType.isKnown(PacketType.ANNOUNCEMENT))
        assertTrue(PacketType.isKnown(PacketType.SUMMARY2))
        assertEquals("0x55", PacketType.name(0x55))
    }

    @Test
    fun typesBelowSixteenNeverLeaveTheLink() {
        for (type in listOf(PacketType.HELLO, PacketType.SUMMARY, PacketType.REQUEST, PacketType.SUMMARY2, PacketType.REQUEST2, 0x00, 0x0F)) {
            assertTrue(PacketType.isLinkLocal(type))
        }
        for (type in listOf(PacketType.INCIDENT_REPORT, PacketType.PRIVATE, PacketType.ANNOUNCEMENT, PacketType.TEST, 0x10, 0xFF)) {
            assertFalse(PacketType.isLinkLocal(type))
        }
    }

    /** The table in PROTOCOL.md, section 5. */
    @Test
    fun originTtlByType() {
        val table = mapOf(
            PacketType.HELLO to 1, PacketType.SUMMARY to 1, PacketType.REQUEST to 1, PacketType.SUMMARY2 to 1, PacketType.REQUEST2 to 1,
            PacketType.INCIDENT_REPORT to 7, PacketType.INCIDENT_ACK to 7, PacketType.INCIDENT_CANCEL to 7,
            PacketType.PRIVATE to 6, PacketType.ANNOUNCEMENT to 3, PacketType.TEST to 7, 0x55 to 3, 0x06 to 1,
        )
        for ((type, ttl) in table) assertEquals(PacketType.name(type), ttl, PacketType.originTtl(type))
    }

    @Test
    fun rejectsOversizedPayloadEvenWhenLengthMatches() {
        val w = WireWriter()
        w.u8(1).u8(PacketType.TEST).u8(7).u8(0).i64(1).i64(2).u32(3).u16(481).bytes(ByteArray(481))
        assertNull(PacketCodec.decode(w.toByteArray()))
    }

    @Test
    fun fragmentsAndReassembles() {
        val payload = ByteArray(PacketCodec.MAX_PAYLOAD) { (it * 7).toByte() }
        val p = packet(payload, recipient = 9L)
        val frames = PacketCodec.toFrames(p, 100, 0x1234)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= 100 })
        val r = Reassembler()
        var done: Packet? = null
        for (f in frames.shuffled(java.util.Random(1))) {
            val res = r.accept(PacketCodec.decode(f)!!, 0)
            if (res is Reassembler.Result.Complete) done = res.packet
        }
        assertNotNull(done)
        assertArrayEquals(payload, done!!.payload)
        assertEquals(9L, done.recipient)
        assertEquals(p.id, done.id)
        assertEquals(0, r.pendingSets)
    }

    @Test
    fun incompleteFragmentsExpireAfterThirtySeconds() {
        val frames = PacketCodec.toFrames(packet(ByteArray(300)), 100, 1)
        val r = Reassembler()
        r.accept(PacketCodec.decode(frames[0])!!, 0)
        assertEquals(1, r.pendingSets)
        val other = PacketCodec.toFrames(packet(ByteArray(300)), 100, 2)
        r.accept(PacketCodec.decode(other[0])!!, 30_001)
        assertEquals(1, r.pendingSets)
    }

    @Test
    fun reassemblyStateIsBounded() {
        val r = Reassembler(maxSets = 4)
        for (i in 0 until 50) {
            val frames = PacketCodec.toFrames(packet(ByteArray(300)), 100, i)
            r.accept(PacketCodec.decode(frames[0])!!, 0)
        }
        assertEquals(4, r.pendingSets)
    }

    @Test
    fun inconsistentFragmentsAreInvalid() {
        val a = PacketCodec.toFrames(packet(ByteArray(300)), 100, 5)
        val b = PacketCodec.toFrames(packet(ByteArray(300), ttl = 3), 100, 5)
        val r = Reassembler()
        r.accept(PacketCodec.decode(a[0])!!, 0)
        assertTrue(r.accept(PacketCodec.decode(b[1])!!, 0) is Reassembler.Result.Invalid)
    }

    @Test
    fun rejectsFragmentHeaderWithBadCounters() {
        val frame = PacketCodec.toFrames(packet(ByteArray(300), recipient = null), 100, 5)[0]
        assertNull("count 1", PacketCodec.decode(frame.clone().also { it[29] = 1 }))
        assertNull("index >= count", PacketCodec.decode(frame.clone().also { it[28] = 9 }))
    }

    @Test
    fun hopsCountLinksTraversedFromTheTypesStart() {
        val report = PacketType.INCIDENT_REPORT
        assertEquals(1, packet(ttl = 7, type = report).hops)
        assertEquals(3, packet(ttl = 5, type = report).hops)
        assertEquals(7, packet(ttl = 1, type = report).hops)
        assertEquals("chat starts at 6", 1, packet(ttl = 6).hops)
        assertEquals(6, packet(ttl = 1).hops)
        assertEquals("chat from a 1.0 phone started at 7", 1, packet(ttl = 7).hops)
        val announcement = PacketType.ANNOUNCEMENT
        assertEquals(1, packet(ttl = 3, type = announcement).hops)
        assertEquals(3, packet(ttl = 1, type = announcement).hops)
        assertEquals("above the type's start, counted from 7", 2, packet(ttl = 6, type = announcement).hops)
    }

    @Test
    fun dedupKeyIgnoresTtlButNotContent() {
        val p = packet()
        assertEquals(p.dedupKey, p.withTtl(3).dedupKey)
        assertNotEquals(p.dedupKey, packet(payload = byteArrayOf(9)).dedupKey)
        assertNotEquals(p.dedupKey, packet(recipient = 0L).dedupKey)
    }
}
