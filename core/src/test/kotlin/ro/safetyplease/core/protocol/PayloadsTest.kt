package ro.safetyplease.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.util.utf8

class PayloadsTest {
    @Test
    fun helloRoundTrip() {
        val h = Hello.decode(Hello(1, 77L, NodeFlags.STAFF or NodeFlags.ACCEPTS_CONNECTIONS, "Ana").encode())!!
        assertEquals(77L, h.nodeId)
        assertEquals(NodeFlags.STAFF or NodeFlags.ACCEPTS_CONNECTIONS, h.flags)
        assertEquals("Ana", h.nickname)
        assertNull(Hello.decode(Hello(1, 77L, 0, null).encode())!!.nickname)
    }

    @Test
    fun helloRejectsGarbage() {
        assertNull(Hello.decode(ByteArray(3)))
        assertNull("nodeId 0", Hello.decode(Hello(1, 0L, 0, null).encode()))
        val longNick = WireWriter().u8(1).i64(5).u8(0).u8(40).bytes(ByteArray(40)).toByteArray()
        assertNull(Hello.decode(longNick))
    }

    @Test
    fun helloSkipsWhatANewerVersionAppends() {
        val h = Hello.decode(Hello(1, 5L, NodeFlags.PROTOCOL_1_1, "x").encode() + byteArrayOf(1, 2, 3))!!
        assertEquals(5L, h.nodeId)
        assertEquals(NodeFlags.PROTOCOL_1_1, h.flags)
        assertEquals("x", h.nickname)
    }

    @Test
    fun summaryAndRequestRoundTrip() {
        val entries = listOf(
            SummaryEntry(1L, true, AckStatus.NONE), SummaryEntry(-5L, false, AckStatus.RESOLVED),
            SummaryEntry(9L, true, AckStatus.CANCELLED),
        )
        assertEquals(entries, SummaryCodec.decode(SummaryCodec.encode(entries)))
        val req = listOf(RequestEntry(1L, true, false), RequestEntry(2L, true, true))
        assertEquals(req, RequestCodec.decode(RequestCodec.encode(req)))
    }

    @Test
    fun fullSummaryFitsInOnePacket() {
        val entries = List(Limits.SUMMARY_ENTRIES) { SummaryEntry(it.toLong(), true, AckStatus.RECEIVED) }
        assertTrue(SummaryCodec.encode(entries).size <= PacketCodec.MAX_PAYLOAD)
    }

    @Test
    fun summary2CarriesAKindAndToleratesWhatItDoesNotKnow() {
        val entries = listOf(SummaryEntry(1L, true, AckStatus.NONE), SummaryEntry(-5L, false, AckStatus.CANCELLED))
        val summary = Summary2(SummaryKind.INCIDENTS, entries)
        val bytes = Summary2Codec.encode(summary)
        assertArrayEquals("the kind, then SUMMARY's entries", byteArrayOf(1) + SummaryCodec.encode(entries), bytes)
        assertEquals(summary, Summary2Codec.decode(bytes))
        assertEquals("trailing bytes", summary, Summary2Codec.decode(bytes + byteArrayOf(7, 7)))

        val future = WireWriter().u8(SummaryKind.INCIDENTS).u8(1).i64(9).u8(0x80 or 9).toByteArray()
        assertEquals(listOf(SummaryEntry(9L, true, 9)), Summary2Codec.decode(future)!!.entries)
        assertNull("1.0 SUMMARY stays strict", SummaryCodec.decode(future.copyOfRange(1, future.size)))

        assertEquals(Summary2(0x42, emptyList()), Summary2Codec.decode(byteArrayOf(0x42, 1, 2, 3)))
        assertNull(Summary2Codec.decode(ByteArray(0)))
        assertNull("truncated entries", Summary2Codec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(Summary2Codec.decode(byteArrayOf(1, 60)))
    }

    @Test
    fun request2IgnoresWantBitsItDoesNotKnow() {
        val entries = listOf(RequestEntry(1L, true, false), RequestEntry(2L, false, true))
        val request = Request2(SummaryKind.INCIDENTS, entries)
        val bytes = Request2Codec.encode(request)
        assertArrayEquals(byteArrayOf(1) + RequestCodec.encode(entries), bytes)
        assertEquals(request, Request2Codec.decode(bytes))
        val future = WireWriter().u8(SummaryKind.INCIDENTS).u8(1).i64(3).u8(0x80 or 0x02).toByteArray()
        assertEquals(listOf(RequestEntry(3L, true, false)), Request2Codec.decode(future)!!.entries)
        assertNull("1.0 REQUEST stays strict", RequestCodec.decode(future.copyOfRange(1, future.size)))
        assertEquals(Request2(9, emptyList()), Request2Codec.decode(byteArrayOf(9)))
    }

    @Test
    fun summaryRejectsBadCounts() {
        assertNull(SummaryCodec.decode(byteArrayOf(2, 0, 0)))
        assertNull(SummaryCodec.decode(byteArrayOf(60)))
        assertNull("unknown status", SummaryCodec.decode(WireWriter().u8(1).i64(1).u8(5).toByteArray()))
        assertNull(RequestCodec.decode(WireWriter().u8(1).i64(1).u8(0x40).toByteArray()))
    }

    @Test
    fun incidentBodyRoundTrip() {
        val body = IncidentBody(
            IncidentCategory.MEDICAL, Severity.URGENT, 1_760_000_000L, "main-stage",
            43.951234, 28.634567, "A leșinat cineva lângă gard", "Mihai",
        )
        val d = IncidentBody.decode(body.encode())!!
        assertEquals(IncidentCategory.MEDICAL, d.category)
        assertEquals(Severity.URGENT, d.severity)
        assertEquals("main-stage", d.zone)
        assertEquals(43.951234, d.lat!!, 1e-6)
        assertEquals(28.634567, d.lon!!, 1e-6)
        assertEquals("A leșinat cineva lângă gard", d.description)
        assertEquals("Mihai", d.nickname)
    }

    @Test
    fun anonymousIncidentWithoutCoords() {
        val d = IncidentBody.decode(
            IncidentBody(IncidentCategory.FIRE, Severity.LOW, 5L, "", null, null, "", null).encode()
        )!!
        assertNull(d.lat)
        assertNull(d.nickname)
        assertEquals("", d.zone)
    }

    @Test
    fun incidentBodyCarriesCancelHashOnlyWhenPresent() {
        val hash = IncidentCancel.hash(ByteArray(IncidentCancel.TOKEN_SIZE) { 5 })
        val with = IncidentBody(IncidentCategory.CROWD, Severity.MEDIUM, 9L, "bar", 43.95, 28.63, "împins", "Ana", hash).encode()
        val without = IncidentBody(IncidentCategory.CROWD, Severity.MEDIUM, 9L, "bar", 43.95, 28.63, "împins", "Ana").encode()
        assertEquals(without.size + IncidentCancel.HASH_SIZE, with.size)

        val d = IncidentBody.decode(with)!!
        assertArrayEquals(hash, d.cancelHash)
        assertEquals("Ana", d.nickname)
        assertEquals("împins", d.description)
        assertEquals(28.63, d.lon!!, 1e-6)

        val old = IncidentBody.decode(without)!!
        assertNull("report from before cancelling existed", old.cancelHash)
        assertEquals("Ana", old.nickname)
        assertNull("truncated hash", IncidentBody.decode(with.copyOf(with.size - 1)))
    }

    @Test
    fun largestIncidentStillFitsSealedInOnePacket() {
        val desc = "ș".repeat(Limits.DESCRIPTION_CHARS).truncateUtf8(Limits.DESCRIPTION_BYTES)
        val body = IncidentBody(
            IncidentCategory.OTHER, Severity.MEDIUM, 1L, "z".repeat(Limits.ZONE_BYTES),
            -89.999999, 179.999999, desc, "n".repeat(Limits.NICK_BYTES), ByteArray(IncidentCancel.HASH_SIZE),
        ).encode()
        val total = INCIDENT_ID_SIZE + body.size + IncidentReportCodec.SEAL_OVERHEAD
        assertTrue("total=$total", total <= PacketCodec.MAX_PAYLOAD)
    }

    @Test
    fun incidentBodyRejectsInvalidValues() {
        val good = IncidentBody(IncidentCategory.MEDICAL, Severity.LOW, 1L, "a", null, null, "x", null).encode()
        assertNull(IncidentBody.decode(good.clone().also { it[0] = 0 }))
        assertNull(IncidentBody.decode(good.clone().also { it[1] = 0 }))
        assertNull(IncidentBody.decode(good.clone().also { it[1] = 4 }))
        assertNull(IncidentBody.decode(good.copyOf(good.size - 1)))
        assertNull("cancel flag without hash", IncidentBody.decode(good.clone().also { it[6] = 0x04 }))
    }

    @Test
    fun incidentBodySkipsFieldsAndFlagsOfANewerVersion() {
        val hash = IncidentCancel.hash(ByteArray(IncidentCancel.TOKEN_SIZE))
        val body = IncidentBody(IncidentCategory.FIRE, Severity.URGENT, 9L, "bar", 43.95, 28.63, "fum", "Ana", hash).encode()
        val newer = body.clone().also { it[6] = (it[6].toInt() or 0x08 or 0x80).toByte() } + byteArrayOf(4, 1, 2, 3, 4)
        val d = IncidentBody.decode(newer)!!
        assertEquals("fum", d.description)
        assertEquals("Ana", d.nickname)
        assertArrayEquals(hash, d.cancelHash)
        assertArrayEquals("the fields it knows, as they were", body, d.encode())

        val unknownCategory = IncidentBody.decode(body.clone().also { it[0] = 42 })!!
        assertEquals(42, unknownCategory.category)
    }

    @Test
    fun cancelRoundTripAndTokenHash() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val token = ByteArray(IncidentCancel.TOKEN_SIZE) { (100 + it).toByte() }
        val payload = IncidentCancel(id, token).encode()
        assertEquals(32, payload.size)
        val back = IncidentCancel.decode(payload)!!
        assertArrayEquals(id, back.incidentId)
        assertArrayEquals(token, back.token)
        assertNull(IncidentCancel.decode(payload.copyOf(31)))
        assertArrayEquals("a newer version's trailing bytes are skipped", token, IncidentCancel.decode(payload + 0)!!.token)

        assertArrayEquals(sha256("SP-CANCEL-v1".utf8() + token).copyOf(16), IncidentCancel.hash(token))
        assertFalse(IncidentCancel.hash(token).contentEquals(IncidentCancel.hash(ByteArray(IncidentCancel.TOKEN_SIZE))))
    }

    @Test
    fun ackRoundTripAndSignedPart() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 7 }
        val payload = IncidentAck(id, AckStatus.ACKNOWLEDGED, 99L, "Echipa 2", sig).encode()
        val ack = IncidentAck.decode(payload)!!
        assertArrayEquals(id, ack.incidentId)
        assertEquals(AckStatus.ACKNOWLEDGED, ack.status)
        assertEquals("Echipa 2", ack.teamName)
        assertArrayEquals(sig, ack.signature)
        assertArrayEquals(
            IncidentAck.unsigned(id, AckStatus.ACKNOWLEDGED, 99L, "Echipa 2"),
            IncidentAck.unsignedPart(payload),
        )
        assertNull(IncidentAck.decode(payload.copyOf(payload.size - 1)))
        assertNull("status 0", IncidentAck.decode(payload.clone().also { it[IncidentAck.STATUS_OFFSET] = 0 }))
        val cancelled = payload.clone().also { it[IncidentAck.STATUS_OFFSET] = AckStatus.CANCELLED.toByte() }
        assertEquals(AckStatus.CANCELLED, IncidentAck.decode(cancelled)!!.status)
        val newer = IncidentAck.decode(payload.clone().also { it[IncidentAck.STATUS_OFFSET] = 5 })!!
        assertEquals("a newer status is kept as a number", 5, newer.status)
        assertFalse(AckStatus.isKnown(newer.status))
    }

    @Test
    fun ackKeepsANewerVersionsFieldsBeforeTheSignature() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 7 }
        val extensions = byteArrayOf(9, 8, 7)
        val payload = IncidentAck(id, AckStatus.RESOLVED, 99L, "Echipa 2", sig, extensions).encode()
        assertArrayEquals(IncidentAck.unsigned(id, AckStatus.RESOLVED, 99L, "Echipa 2") + extensions + sig, payload)
        val ack = IncidentAck.decode(payload)!!
        assertArrayEquals(extensions, ack.extensions)
        assertArrayEquals(sig, ack.signature)
        assertEquals("Echipa 2", ack.teamName)
        assertArrayEquals("the signature covers them", payload.copyOf(payload.size - sig.size), IncidentAck.unsignedPart(payload))
        assertArrayEquals(payload, ack.encode())
        assertNull("no room for the signature", IncidentAck.decode(payload.copyOf(payload.size - sig.size - 1)))
    }

    @Test
    fun onlyWhatOneZeroReadsGoesToAOneZeroPeer() {
        fun packet(type: Int, payload: ByteArray) = Packet(type, 7, 1L, 2L, 3L, null, false, payload)
        val id = ByteArray(INCIDENT_ID_SIZE)
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE)
        val ack = IncidentAck(id, AckStatus.RESOLVED, 1L, "T", sig)
        assertTrue(Compat10.accepts(packet(PacketType.INCIDENT_ACK, ack.encode())))
        assertFalse(Compat10.accepts(packet(PacketType.INCIDENT_ACK, IncidentAck(id, 5, 1L, "T", sig).encode())))
        assertFalse(Compat10.accepts(packet(PacketType.INCIDENT_ACK, IncidentAck(id, 1, 1L, "T", sig, byteArrayOf(1)).encode())))
        assertTrue(Compat10.accepts(packet(PacketType.INCIDENT_CANCEL, ByteArray(IncidentCancel.SIZE))))
        assertFalse(Compat10.accepts(packet(PacketType.INCIDENT_CANCEL, ByteArray(IncidentCancel.SIZE + 1))))
        for (type in PacketType.V1_0 - setOf(PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL)) {
            assertTrue(Compat10.accepts(packet(type, ByteArray(80))))
        }
        for (type in listOf(PacketType.SUMMARY2, PacketType.REQUEST2, PacketType.ANNOUNCEMENT, 0x55)) {
            assertFalse(Compat10.accepts(packet(type, ByteArray(80))))
        }
    }

    @Test
    fun innerMessagesRoundTrip() {
        val text = InnerCodec.decode(InnerCodec.encode(Inner.Text(1L, null, "salut, unde ești?"))) as Inner.Text
        assertEquals("salut, unde ești?", text.text)
        assertNull(text.groupId)

        val groupText = InnerCodec.decode(InnerCodec.encode(Inner.Text(2L, 55L, "hei"))) as Inner.Text
        assertEquals(55L, groupText.groupId)

        val quick = InnerCodec.decode(InnerCodec.encode(Inner.Quick(3L, null, QuickCode.COMING))) as Inner.Quick
        assertEquals(QuickCode.COMING, quick.code)

        val zone = InnerCodec.decode(InnerCodec.encode(Inner.Zone(4L, 9L, "food", 43.9, 28.6))) as Inner.Zone
        assertEquals("food", zone.zone)
        assertEquals(9L, zone.groupId)
        assertEquals(28.6, zone.lon!!, 1e-6)

        val bare = InnerCodec.decode(InnerCodec.encode(Inner.Zone(4L, null, "food", null, null))) as Inner.Zone
        assertNull(bare.lat)

        assertTrue(InnerCodec.decode(InnerCodec.encode(Inner.Ping(5L))) is Inner.Ping)
        assertEquals(5L, (InnerCodec.decode(InnerCodec.encode(Inner.Pong(6L, 5L))) as Inner.Pong).pingId)
        assertEquals(1L, (InnerCodec.decode(InnerCodec.encode(Inner.Delivered(7L, 1L))) as Inner.Delivered).deliveredId)
    }

    @Test
    fun largestGroupInviteFitsInsideBox() {
        val members = List(Limits.GROUP_MAX_MEMBERS - 2) {
            GroupMemberWire(it.toLong(), ByteArray(32) { 1 }, "n".repeat(Limits.GROUP_NICK_BYTES))
        }
        val bytes = InnerCodec.encode(Inner.GroupInvite(1L, 2L, "g".repeat(Limits.GROUP_NAME_BYTES), members))
        // nonce 24 + MAC 16
        assertTrue("size=${bytes.size}", bytes.size + 40 <= PacketCodec.MAX_PAYLOAD)
        val back = InnerCodec.decode(bytes) as Inner.GroupInvite
        assertEquals(members.size, back.members.size)
        assertEquals(2L, back.groupId)
    }

    @Test
    fun innerRejectsGarbage() {
        assertNull(InnerCodec.decode(ByteArray(0)))
        assertNull(InnerCodec.decode(byteArrayOf(99, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertNull(InnerCodec.decode(InnerCodec.encode(Inner.Ping(1L)) + 1))
        val bigText = WireWriter().u8(1).i64(1).u8(0).bytes(ByteArray(Limits.TEXT_BYTES + 1)).toByteArray()
        assertNull(InnerCodec.decode(bigText))
        val badQuick = WireWriter().u8(2).i64(1).u8(0).u8(77).toByteArray()
        assertNull(InnerCodec.decode(badQuick))
    }

    @Test
    fun truncateUtf8KeepsWholeCharacters() {
        assertEquals("abc", "abc".truncateUtf8(10))
        assertEquals("ăî", "ăîș".truncateUtf8(5))
        assertEquals(4, "😀😀".truncateUtf8(7).utf8().size)
        assertEquals("", "😀".truncateUtf8(3))
    }
}
