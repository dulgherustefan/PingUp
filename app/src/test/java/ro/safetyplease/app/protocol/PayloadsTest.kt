package ro.safetyplease.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.core.truncateUtf8
import ro.safetyplease.app.core.utf8
import ro.safetyplease.app.crypto.sha256

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
        assertNull(Hello.decode(Hello(1, 5L, 0, "x").encode() + 1))
        assertNull("nodeId 0", Hello.decode(Hello(1, 0L, 0, null).encode()))
        val longNick = WireWriter().u8(1).i64(5).u8(0).u8(40).bytes(ByteArray(40)).toByteArray()
        assertNull(Hello.decode(longNick))
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
    fun summaryRejectsBadCounts() {
        assertNull(SummaryCodec.decode(byteArrayOf(2, 0, 0)))
        assertNull(SummaryCodec.decode(byteArrayOf(60)))
        assertNull("status necunoscut", SummaryCodec.decode(WireWriter().u8(1).i64(1).u8(5).toByteArray()))
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
        assertNull("raport de dinainte de anulare", old.cancelHash)
        assertEquals("Ana", old.nickname)
        assertNull("hash trunchiat", IncidentBody.decode(with.copyOf(with.size - 1)))
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
        assertNull(IncidentBody.decode(good.clone().also { it[0] = 99 }))
        assertNull(IncidentBody.decode(good.clone().also { it[1] = 0 }))
        assertNull(IncidentBody.decode(good.copyOf(good.size - 1)))
        assertNull(IncidentBody.decode(good + 0))
        assertNull("flag necunoscut", IncidentBody.decode(good.clone().also { it[6] = 0x08 }))
        assertNull("flag de anulare fara hash", IncidentBody.decode(good.clone().also { it[6] = 0x04 }))
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
        assertNull(IncidentCancel.decode(payload + 0))

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
        assertNull("status 5", IncidentAck.decode(payload.clone().also { it[IncidentAck.STATUS_OFFSET] = 5 }))
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
