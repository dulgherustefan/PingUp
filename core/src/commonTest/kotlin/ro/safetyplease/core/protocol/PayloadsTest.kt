package ro.safetyplease.core.protocol

import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.util.utf8
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertNull(Hello.decode(Hello(1, 0L, 0, null).encode()), "nodeId 0")
        val longNick = WireWriter().u8(1).i64(5).u8(0).u8(40).bytes(ByteArray(40)).toByteArray()
        assertNull(Hello.decode(longNick))
    }

    @Test
    fun helloSkipsWhatANewerVersionAppends() {
        val h = Hello.decode(Hello(1, 5L, NodeFlags.PROTOCOL_1_1, "x").encode() + byteArrayOf(1, 2, 3))!!
        assertEquals(5L, h.nodeId)
        assertEquals(NodeFlags.PROTOCOL_1_1, h.flags)
        assertEquals("x", h.nickname)
        assertNull(h.prefix, "three bytes are no prefix")
    }

    @Test
    fun helloCarriesTheAdvertisedPrefixAfterTheNickname() {
        val bytes = Hello(1, 5L, NodeFlags.PREFIX_IN_HELLO, "x", prefix = 0x80a0_0001.toInt()).encode()
        assertEquals(Hello(1, 5L, NodeFlags.PREFIX_IN_HELLO, "x").encode().size + 4, bytes.size, "4 bytes after the nickname")
        assertEquals(0x80a0_0001.toInt(), Hello.decode(bytes)!!.prefix)
        val later = Hello.decode(bytes + byteArrayOf(9, 9))!!
        assertEquals(0x80a0_0001.toInt(), later.prefix, "what follows the prefix is skipped")
        assertEquals("x", later.nickname)
        assertNull(Hello.decode(Hello(1, 5L, 0, null).encode())!!.prefix)
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
        assertContentEquals(byteArrayOf(1) + SummaryCodec.encode(entries), bytes, "the kind, then SUMMARY's entries")
        assertEquals(summary, Summary2Codec.decode(bytes))
        assertEquals(summary, Summary2Codec.decode(bytes + byteArrayOf(0x80.toByte(), 7)), "trailing bytes")

        val future = WireWriter().u8(SummaryKind.INCIDENTS).u8(1).i64(9).u8(0x80 or 9).toByteArray()
        assertEquals(listOf(SummaryEntry(9L, true, 9)), Summary2Codec.decode(future)!!.entries)
        assertNull(SummaryCodec.decode(future.copyOfRange(1, future.size)), "1.0 SUMMARY stays strict")

        assertEquals(Summary2(0x42, emptyList()), Summary2Codec.decode(byteArrayOf(0x42, 1, 2, 3)))
        assertFailsWith<IllegalArgumentException>("only INCIDENTS has entries") { Summary2Codec.encode(Summary2(0x42, entries)) }
        assertNull(Summary2Codec.decode(ByteArray(0)))
        assertNull(Summary2Codec.decode(bytes.copyOf(bytes.size - 1)), "truncated entries")
        assertNull(Summary2Codec.decode(byteArrayOf(1, 60)))
    }

    @Test
    fun request2IgnoresWantBitsItDoesNotKnow() {
        val entries = listOf(RequestEntry(1L, true, false), RequestEntry(2L, false, true))
        val request = Request2(SummaryKind.INCIDENTS, entries)
        val bytes = Request2Codec.encode(request)
        assertContentEquals(byteArrayOf(1) + RequestCodec.encode(entries), bytes)
        assertEquals(request, Request2Codec.decode(bytes))
        val future = WireWriter().u8(SummaryKind.INCIDENTS).u8(1).i64(3).u8(0x80 or 0x02).toByteArray()
        assertEquals(listOf(RequestEntry(3L, true, false)), Request2Codec.decode(future)!!.entries)
        assertNull(RequestCodec.decode(future.copyOfRange(1, future.size)), "1.0 REQUEST stays strict")
        assertEquals(Request2(9, emptyList()), Request2Codec.decode(byteArrayOf(9)))
        assertFailsWith<IllegalArgumentException>("only INCIDENTS has entries") { Request2Codec.encode(Request2(9, entries)) }
    }

    @Test
    fun summaryRejectsBadCounts() {
        assertNull(SummaryCodec.decode(byteArrayOf(2, 0, 0)))
        assertNull(SummaryCodec.decode(byteArrayOf(60)))
        assertNull(SummaryCodec.decode(WireWriter().u8(1).i64(1).u8(5).toByteArray()), "unknown status")
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
        assertContentEquals(hash, d.cancelHash)
        assertEquals("Ana", d.nickname)
        assertEquals("împins", d.description)
        assertEquals(28.63, d.lon!!, 1e-6)

        val old = IncidentBody.decode(without)!!
        assertNull(old.cancelHash, "report from before cancelling existed")
        assertEquals("Ana", old.nickname)
        assertNull(IncidentBody.decode(with.copyOf(with.size - 1)), "truncated hash")
    }

    @Test
    fun largestIncidentStillFitsSealedInOnePacket() {
        val desc = "€".repeat(Limits.DESCRIPTION_CHARS).truncateUtf8(Limits.DESCRIPTION_BYTES)
        val body = IncidentBody(
            IncidentCategory.OTHER, Severity.MEDIUM, 1L, "z".repeat(Limits.ZONE_BYTES),
            -89.999999, 179.999999, desc, "n".repeat(Limits.NICK_BYTES), ByteArray(IncidentCancel.HASH_SIZE),
            Triage(Answer.NO, Answer.NO), StaffFields(255, dangerContinues = true), LocationFields(255, 255),
        ).encode()
        assertEquals(396, body.size)
        val total = INCIDENT_ID_SIZE + body.size + IncidentReportCodec.SEAL_OVERHEAD
        assertTrue(total <= PacketCodec.MAX_PAYLOAD, "total=$total")
    }

    @Test
    fun triageAndStaffFieldsFollowTheCancelHashBehindTheirFlags() {
        val hash = IncidentCancel.hash(ByteArray(IncidentCancel.TOKEN_SIZE))
        val plain = IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, 9L, "bar", null, null, "", null, hash)
        val triage = Triage(Answer.YES, Answer.UNKNOWN)
        val staff = StaffFields(4, dangerContinues = true)
        val both = IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, 9L, "bar", null, null, "", "Medical 1", hash, triage, staff)
        val bytes = both.encode()
        assertEquals(0x04 or 0x02 or 0x08 or 0x10, bytes[6].toInt(), "flags: cancel, nickname, triage, staff")
        assertContentEquals(byteArrayOf(0x0d, 4, 1), bytes.copyOfRange(bytes.size - 3, bytes.size))
        val back = IncidentBody.decode(bytes)!!
        assertEquals(triage, back.triage)
        assertEquals(staff, back.staff)
        assertEquals("Medical 1", back.nickname)
        assertNull(IncidentBody.decode(plain.encode())!!.triage)
        assertNull(IncidentBody.decode(plain.encode())!!.staff)
        assertEquals(plain.encode().size + 1, IncidentBody(1, 3, 9L, "bar", null, null, "", null, hash, Triage(Answer.NO)).encode().size)

        // bits a newer version may give these bytes are skipped
        val newer = bytes.copyOf().also { it[it.size - 3] = 0xf6.toByte(); it[it.size - 1] = 0xff.toByte() }
        val read = IncidentBody.decode(newer)!!
        assertEquals(Triage(Answer.NO, Answer.YES), read.triage)
        assertEquals(StaffFields(4, dangerContinues = true), read.staff)
        assertNull(IncidentBody.decode(bytes.copyOf(bytes.size - 1)), "the staff flag without its bytes")
    }

    @Test
    fun theOrderOfStatusesWithinAnEpochIsOneTable() {
        val order = listOf(
            AckStatus.NONE, AckStatus.RECEIVED, AckStatus.REOPENED, AckStatus.ACKNOWLEDGED, AckStatus.ON_SCENE,
            AckStatus.HANDED_OVER, AckStatus.RESOLVED, AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED, AckStatus.CANCELLED,
        )
        assertEquals(order.indices.toList(), order.map(AckStatus::rank))
        assertEquals(AckStatus.V1_0.toList(), AckStatus.V1_0.sortedBy(AckStatus::rank), "1.0's four keep their 1.0 order")
        assertEquals(-1, AckStatus.rank(10))
        assertTrue((0..9).all(AckStatus::isKnown) && !AckStatus.isKnown(10))
        for ((later, earlier) in order.zipWithNext { a, b -> b to a }) assertTrue(AckOrder.compare(0, later, 0, earlier) > 0)
        assertTrue(AckOrder.compare(1, AckStatus.REOPENED, 0, AckStatus.CANCELLED) > 0, "an epoch beats any status of the one before")
        assertEquals(0, AckOrder.compare(2, AckStatus.ON_SCENE, 2, AckStatus.ON_SCENE))

        assertEquals(
            listOf(AckStatus.RESOLVED, AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED, AckStatus.CANCELLED),
            order.filter(AckStatus::isClosed),
        )
        assertEquals(listOf(AckStatus.ACKNOWLEDGED, AckStatus.ON_SCENE, AckStatus.HANDED_OVER), order.filter(AckStatus::isTaken))
    }

    @Test
    fun oneZeroPhonesGetTheClosestStatusTheyKnow() {
        assertEquals(AckStatus.ACKNOWLEDGED, AckStatus.legacy(AckStatus.ON_SCENE))
        assertEquals(AckStatus.ACKNOWLEDGED, AckStatus.legacy(AckStatus.HANDED_OVER))
        assertEquals(AckStatus.RESOLVED, AckStatus.legacy(AckStatus.CLOSED_BY_CONTROL))
        assertEquals(AckStatus.RESOLVED, AckStatus.legacy(AckStatus.UNFOUNDED))
        assertEquals(AckStatus.NONE, AckStatus.legacy(AckStatus.REOPENED), "1.0 can't go back")
        for (status in AckStatus.V1_0) assertEquals(status, AckStatus.legacy(status))
        for (status in (AckStatus.ON_SCENE..AckStatus.REOPENED)) {
            val legacy = AckStatus.legacy(status)
            assertTrue(AckStatus.rank(status) > AckStatus.rank(legacy) || legacy == AckStatus.NONE, "$status ranks above what 1.0 gets for it")
        }
    }

    @Test
    fun ackEpochFollowsTheFlagsByte() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 7 }
        fun ack(extensions: ByteArray) =
            IncidentAck.decode(IncidentAck(id, AckStatus.REOPENED, 99L, "Control", sig, extensions).encode())!!
        assertContentEquals(byteArrayOf(0x02, 3), IncidentAck.extensions(0, epoch = 3))
        assertContentEquals(byteArrayOf(0x03, 1), IncidentAck.extensions(AckFlags.ASSIGNED, epoch = 1))
        assertEquals(0, IncidentAck.extensions(0, epoch = 0).size, "epoch 0 keeps the 1.0 layout")
        assertEquals(3, ack(IncidentAck.extensions(0, epoch = 3)).epoch)
        val assigned = ack(IncidentAck.extensions(AckFlags.ASSIGNED, epoch = 255))
        assertTrue(assigned.assigned)
        assertEquals(255, assigned.epoch)
        assertEquals(0, ack(IncidentAck.extensions(AckFlags.ASSIGNED)).epoch)
        assertEquals(0, ack(byteArrayOf(0x02)).epoch, "the flag without its byte")
        assertEquals(7, ack(byteArrayOf(0x82.toByte(), 7, 1, 2)).epoch, "fields after the epoch are kept")
        assertFailsWith<IllegalArgumentException> { IncidentAck.extensions(AckFlags.EPOCH) }
        assertFailsWith<IllegalArgumentException> { IncidentAck.extensions(0, epoch = 256) }
    }

    @Test
    fun summary2CarriesEpochsOnlyWhenAnIncidentWasReopened() {
        val plain = Summary2(SummaryKind.INCIDENTS, listOf(SummaryEntry(1L, true, AckStatus.CLOSED_BY_CONTROL)))
        assertEquals(2 + 9, Summary2Codec.encode(plain).size, "no epoch, no block")
        val reopened = Summary2(
            SummaryKind.INCIDENTS,
            listOf(SummaryEntry(1L, true, AckStatus.REOPENED, epoch = 2), SummaryEntry(2L, false, AckStatus.ON_SCENE)),
        )
        val bytes = Summary2Codec.encode(reopened)
        assertContentEquals(byteArrayOf(Summary2Codec.EPOCHS.toByte(), 2, 0), bytes.copyOfRange(bytes.size - 3, bytes.size))
        assertEquals(reopened, Summary2Codec.decode(bytes))
        assertEquals(reopened, Summary2Codec.decode(bytes + byteArrayOf(9, 9)), "what follows the epochs is a newer version's")
        assertEquals(plain, Summary2Codec.decode(Summary2Codec.encode(plain) + 0x80.toByte()), "other flag bits are skipped")
        assertNull(Summary2Codec.decode(bytes.copyOf(bytes.size - 1)), "an epoch per entry")

        val full = List(Limits.SUMMARY_ENTRIES) { SummaryEntry(it.toLong(), true, AckStatus.RECEIVED, epoch = if (it == 0) 1 else 0) }
        val fitting = Summary2Codec.fitting(full)
        assertEquals(Limits.SUMMARY2_EPOCH_ENTRIES, fitting.size)
        assertTrue(Summary2Codec.encode(Summary2(SummaryKind.INCIDENTS, fitting)).size <= PacketCodec.MAX_PAYLOAD)
        assertEquals(full.map { it.copy(epoch = 0) }, Summary2Codec.fitting(full.map { it.copy(epoch = 0) }), "without epochs all 50 fit")
        assertFailsWith<IllegalArgumentException> { Summary2Codec.encode(Summary2(SummaryKind.INCIDENTS, full)) }
        val tooMany = Summary2Codec.encode(Summary2(SummaryKind.INCIDENTS, full.map { it.copy(epoch = 0) })) + 1 + ByteArray(50)
        assertNull(Summary2Codec.decode(tooMany), "epochs for more entries than fit in a packet")
        assertNull(SummaryCodec.decode(SummaryCodec.encode(listOf(SummaryEntry(1L, true, AckStatus.ON_SCENE)))), "a 1.0 summary carries 1.0 statuses only")
    }

    @Test
    fun incidentBodyRejectsInvalidValues() {
        val good = IncidentBody(IncidentCategory.MEDICAL, Severity.LOW, 1L, "a", null, null, "x", null).encode()
        assertNull(IncidentBody.decode(good.copyOf().also { it[0] = 0 }))
        assertNull(IncidentBody.decode(good.copyOf().also { it[1] = 0 }))
        assertNull(IncidentBody.decode(good.copyOf().also { it[1] = 4 }))
        assertNull(IncidentBody.decode(good.copyOf(good.size - 1)))
        assertNull(IncidentBody.decode(good.copyOf().also { it[6] = 0x04 }), "cancel flag without hash")
    }

    @Test
    fun incidentBodySkipsFieldsAndFlagsOfANewerVersion() {
        val hash = IncidentCancel.hash(ByteArray(IncidentCancel.TOKEN_SIZE))
        val body = IncidentBody(IncidentCategory.FIRE, Severity.URGENT, 9L, "bar", 43.95, 28.63, "fum", "Ana", hash).encode()
        // behind 0x80, the extension block: an entry of a type this version doesn't know, then a byte it can't read
        val newer = body.copyOf().also { it[6] = (it[6].toInt() or 0x80).toByte() } + byteArrayOf(0x63, 2, 1, 2, 3)
        val d = IncidentBody.decode(newer)!!
        assertEquals("fum", d.description)
        assertEquals("Ana", d.nickname)
        assertContentEquals(hash, d.cancelHash)
        assertContentEquals(body, d.encode(), "the fields it knows, as they were")

        val unknownCategory = IncidentBody.decode(body.copyOf().also { it[0] = 42 })!!
        assertEquals(42, unknownCategory.category)
    }

    @Test
    fun cancelRoundTripAndTokenHash() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val token = ByteArray(IncidentCancel.TOKEN_SIZE) { (100 + it).toByte() }
        val payload = IncidentCancel(id, token).encode()
        assertEquals(32, payload.size)
        val back = IncidentCancel.decode(payload)!!
        assertContentEquals(id, back.incidentId)
        assertContentEquals(token, back.token)
        assertNull(IncidentCancel.decode(payload.copyOf(31)))
        assertContentEquals(token, IncidentCancel.decode(payload + 0)!!.token, "a newer version's trailing bytes are skipped")

        assertContentEquals(sha256("SP-CANCEL-v1".utf8() + token).copyOf(16), IncidentCancel.hash(token))
        assertFalse(IncidentCancel.hash(token).contentEquals(IncidentCancel.hash(ByteArray(IncidentCancel.TOKEN_SIZE))))
    }

    @Test
    fun ackRoundTripAndSignedPart() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 7 }
        val payload = IncidentAck(id, AckStatus.ACKNOWLEDGED, 99L, "Echipa 2", sig).encode()
        val ack = IncidentAck.decode(payload)!!
        assertContentEquals(id, ack.incidentId)
        assertEquals(AckStatus.ACKNOWLEDGED, ack.status)
        assertEquals("Echipa 2", ack.teamName)
        assertContentEquals(sig, ack.signature)
        assertContentEquals(
            IncidentAck.unsigned(id, AckStatus.ACKNOWLEDGED, 99L, "Echipa 2"),
            IncidentAck.unsignedPart(payload),
        )
        assertNull(IncidentAck.decode(payload.copyOf(payload.size - 1)))
        assertNull(IncidentAck.decode(payload.copyOf().also { it[IncidentAck.STATUS_OFFSET] = 0 }), "status 0")
        val cancelled = payload.copyOf().also { it[IncidentAck.STATUS_OFFSET] = AckStatus.CANCELLED.toByte() }
        assertEquals(AckStatus.CANCELLED, IncidentAck.decode(cancelled)!!.status)
        val newer = IncidentAck.decode(payload.copyOf().also { it[IncidentAck.STATUS_OFFSET] = 0x7e })!!
        assertEquals(0x7e, newer.status, "a newer status is kept as a number")
        assertFalse(AckStatus.isKnown(newer.status))
    }

    @Test
    fun ackFlagsAreTheFirstExtensionByte() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 7 }
        fun ack(extensions: ByteArray) =
            IncidentAck.decode(IncidentAck(id, AckStatus.ACKNOWLEDGED, 99L, "Medical", sig, extensions).encode())!!
        assertEquals(0, IncidentAck.extensions(0).size, "no flag, no byte: a plain ACK keeps the 1.0 layout")
        assertFalse(ack(IncidentAck.extensions(0)).assigned)
        val assigned = ack(IncidentAck.extensions(AckFlags.ASSIGNED))
        assertTrue(assigned.assigned)
        assertContentEquals(byteArrayOf(AckFlags.ASSIGNED.toByte()), assigned.extensions)
        val newer = ack(byteArrayOf(0x84.toByte(), 5, 6))
        assertEquals(0x84, newer.flags, "bits and fields of a newer version are kept")
        assertFalse(newer.assigned)
    }

    @Test
    fun ackKeepsANewerVersionsFieldsBeforeTheSignature() {
        val id = ByteArray(INCIDENT_ID_SIZE) { it.toByte() }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 7 }
        val extensions = byteArrayOf(9, 8, 7)
        val payload = IncidentAck(id, AckStatus.RESOLVED, 99L, "Echipa 2", sig, extensions).encode()
        assertContentEquals(IncidentAck.unsigned(id, AckStatus.RESOLVED, 99L, "Echipa 2") + extensions + sig, payload)
        val ack = IncidentAck.decode(payload)!!
        assertContentEquals(extensions, ack.extensions)
        assertContentEquals(sig, ack.signature)
        assertEquals("Echipa 2", ack.teamName)
        assertContentEquals(payload.copyOf(payload.size - sig.size), IncidentAck.unsignedPart(payload), "the signature covers them")
        assertContentEquals(payload, ack.encode())
        assertNull(IncidentAck.decode(payload.copyOf(payload.size - sig.size - 1)), "no room for the signature")
    }

    @Test
    fun onlyWhatOneZeroReadsGoesToAOneZeroPeer() {
        fun packet(type: Int, payload: ByteArray) = Packet(type, 7, 1L, 2L, 3L, null, false, payload)
        val id = ByteArray(INCIDENT_ID_SIZE)
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE)
        val ack = IncidentAck(id, AckStatus.RESOLVED, 1L, "T", sig)
        assertTrue(Compat10.accepts(packet(PacketType.INCIDENT_ACK, ack.encode())))
        for (status in AckStatus.ON_SCENE..AckStatus.REOPENED) {
            assertFalse(Compat10.accepts(packet(PacketType.INCIDENT_ACK, IncidentAck(id, status, 1L, "T", sig).encode())))
        }
        val epoch = IncidentAck(id, AckStatus.RESOLVED, 1L, "T", sig, IncidentAck.extensions(0, epoch = 1))
        assertFalse(Compat10.accepts(packet(PacketType.INCIDENT_ACK, epoch.encode())), "an epoch is an extension")
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
        assertTrue(bytes.size + 40 <= PacketCodec.MAX_PAYLOAD, "size=${bytes.size}")
        val back = InnerCodec.decode(bytes) as Inner.GroupInvite
        assertEquals(members.size, back.members.size)
        assertEquals(2L, back.groupId)
    }

    @Test
    fun theInnerKindsOfLeavingAndOfTheLastZoneRoundTripAndHaveTheirOwnNumbers() {
        val left = InnerCodec.encode(Inner.LeftGroup(1L, 77L))
        assertEquals(8, left[0].toInt())
        assertEquals(77L, (InnerCodec.decode(left) as Inner.LeftGroup).groupId)
        val ask = InnerCodec.encode(Inner.ZoneRequest(2L))
        assertEquals(9, ask[0].toInt())
        assertEquals(2L, (InnerCodec.decode(ask) as Inner.ZoneRequest).msgId)
        val update = InnerCodec.encode(Inner.ZoneUpdate(3L, "scena-principală"))
        assertEquals(10, update[0].toInt())
        assertEquals("scena-principală", (InnerCodec.decode(update) as Inner.ZoneUpdate).zone)
        val longest = InnerCodec.encode(Inner.ZoneUpdate(3L, "z".repeat(Limits.ZONE_BYTES)))
        assertTrue(longest.size + 40 <= PacketCodec.MAX_PAYLOAD, "small enough to need no budget of its own")
    }

    @Test
    fun leavingAndTheLastZoneAreStrictAndTheNextKindIsStillUnknown() {
        assertNull(InnerCodec.decode(InnerCodec.encode(Inner.LeftGroup(1L, 2L)) + 1))
        assertNull(InnerCodec.decode(InnerCodec.encode(Inner.LeftGroup(1L, 2L)).copyOf(12)))
        assertNull(InnerCodec.decode(InnerCodec.encode(Inner.ZoneRequest(1L)) + 1))
        assertNull(InnerCodec.decode(WireWriter().u8(10).i64(1).u8(0).toByteArray()))
        assertNull(InnerCodec.decode(WireWriter().u8(10).i64(1).u8(Limits.ZONE_BYTES + 1).bytes(ByteArray(Limits.ZONE_BYTES + 1) { 'z'.code.toByte() }).toByteArray()))
        assertNull(InnerCodec.decode(InnerCodec.encode(Inner.ZoneUpdate(1L, "bar")) + 1))
        assertNull(InnerCodec.decode(byteArrayOf(11, 0, 0, 0, 0, 0, 0, 0, 1)), "11 is the next free kind")
    }

    @Test
    fun introRoundTripsAndFitsInASealedBox() {
        val nick = "n".repeat(Limits.NICK_BYTES)
        val bytes = InnerCodec.encode(Inner.Intro(8L, ByteArray(32) { 1 }, ByteArray(32) { 2 }, nick, ByteArray(64) { 3 }))
        // sealed box: ephemeral key 32 + MAC 16
        assertTrue(bytes.size + 48 <= PacketCodec.MAX_PAYLOAD, "size=${bytes.size}")
        val back = InnerCodec.decode(bytes) as Inner.Intro
        assertEquals(8L, back.msgId)
        assertContentEquals(ByteArray(32) { 1 }, back.boxKey)
        assertContentEquals(ByteArray(32) { 2 }, back.signKey)
        assertEquals(nick, back.nickname)
        assertContentEquals(ByteArray(64) { 3 }, back.signature)
        assertNull(InnerCodec.decode(bytes.copyOf(bytes.size - 1)), "truncated signature")
        assertNull(InnerCodec.decode(bytes + 0), "trailing byte")
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

    @Test
    fun theReplyKeyFollowsTheLocationFieldsAndTheLongestBodyGivesUpDescriptionForIt() {
        val key = ByteArray(IncidentBody.REPLY_KEY_SIZE) { it.toByte() }
        fun body(desc: String) = IncidentBody(
            IncidentCategory.OTHER, Severity.MEDIUM, 1L, "z".repeat(Limits.ZONE_BYTES),
            -89.999999, 179.999999, desc, "n".repeat(Limits.NICK_BYTES), ByteArray(IncidentCancel.HASH_SIZE),
            Triage(Answer.NO, Answer.NO), StaffFields(255, dangerContinues = true), LocationFields(255, 255), key,
        )
        val full = "€".repeat(Limits.DESCRIPTION_CHARS).truncateUtf8(Limits.DESCRIPTION_BYTES)
        assertEquals(396 + 32, body(full).encode().size)
        val room = IncidentBody.MAX_SIZE - body("").encode().size
        val fitted = body(full.truncateUtf8(room)).encode()
        assertTrue(fitted.size <= IncidentBody.MAX_SIZE, "size=${fitted.size}")
        assertEquals(PacketCodec.MAX_PAYLOAD, INCIDENT_ID_SIZE + IncidentBody.MAX_SIZE + IncidentReportCodec.SEAL_OVERHEAD)
        val decoded = IncidentBody.decode(fitted)!!
        assertContentEquals(key, decoded.replyKey)
        assertEquals(LocationFields(255, 255), decoded.location)
        assertNull(IncidentBody.decode(fitted.copyOf(fitted.size - 1)))
        assertNull(IncidentBody(IncidentCategory.OTHER, Severity.LOW, 1L, "", null, null, "", null).replyKey)
    }

    @Test
    fun responseRoundTripsWithAndWithoutACertificate() {
        val id = ByteArray(INCIDENT_ID_SIZE) { 7 }
        val sealed = ByteArray(IncidentReportCodec.SEAL_OVERHEAD + 2) { 3 }
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE) { 9 }
        val plain = IncidentResponse(id, 1_760_000_100L, sealed, null, sig)
        val bytes = plain.encode()
        assertEquals(16 + 4 + 1 + 1 + sealed.size + 64, bytes.size)
        val d = IncidentResponse.decode(bytes)!!
        assertContentEquals(id, d.incidentId)
        assertEquals(1_760_000_100L, d.timestamp)
        assertContentEquals(sealed, d.sealed)
        assertNull(d.certificate)
        assertContentEquals(bytes.copyOf(bytes.size - 64), IncidentResponse.unsignedPart(bytes))

        val cert = Certificate(3, ByteArray(32) { 1 }, 10L, 20L, 5L, "Control", ByteArray(64) { 2 })
        val signed = IncidentResponse(id, 1L, sealed, cert, sig, byteArrayOf(0x55)).encode()
        assertTrue(signed.size + 24 <= PacketCodec.MAX_PAYLOAD, "the largest still crosses in one payload")
        val c = IncidentResponse.decode(signed)!!
        assertEquals("Control", c.certificate!!.name)
        assertContentEquals(byteArrayOf(0x55), c.extensions, "a newer version's fields are kept")
        assertContentEquals(signed, c.encode())

        assertNull(IncidentResponse.decode(IncidentResponse(id, 1L, sealed, null, sig).encode().also { it[21] = 48 }), "too short to be sealed")
        assertNull(IncidentResponse.decode(bytes.copyOf(bytes.size - 1)))
        assertFailsWith<IllegalArgumentException> { IncidentResponse.unsigned(id, 1L, ByteArray(48), null) }
        assertEquals(ResponseBody(ResponseCode.GO_TO_LANDMARK, 4), ResponseBody.decode(ResponseBody(ResponseCode.GO_TO_LANDMARK, 4).encode() + 1))
        assertEquals(99, ResponseBody.decode(byteArrayOf(99, 0))!!.code, "a code from a newer version is kept")
        assertNull(ResponseBody.decode(byteArrayOf(0, 0)))
    }

    @Test
    fun updateRoundTripsAndSkipsWhatANewerVersionAdds() {
        val token = ByteArray(IncidentCancel.TOKEN_SIZE) { 4 }
        val moved = UpdateBody(UpdateKind.MOVED, 1_760_000_200L, token, "Bar", 43.95, 28.63, LocationFields(8, 3))
        val bytes = moved.encode()
        val d = UpdateBody.decode(bytes)!!
        assertEquals(listOf(UpdateKind.MOVED, 1_760_000_200L, "Bar", 43.95, 28.63, LocationFields(8, 3)), listOf(d.kind, d.timestamp, d.zone, d.lat, d.lon, d.location))
        assertContentEquals(token, d.token)
        val newer = bytes.copyOf().also { it[21] = (it[21].toInt() or 0x80).toByte() } + byteArrayOf(1, 2)
        assertContentEquals(bytes, UpdateBody.decode(newer)!!.encode())
        val still = UpdateBody(UpdateKind.STILL_ACTIVE, 1L, token).encode()
        assertEquals(22, still.size)
        assertNull(UpdateBody.decode(still.copyOf(21)))
        assertNull(UpdateBody.decode(still.copyOf().also { it[0] = 0 }))

        val id = ByteArray(INCIDENT_ID_SIZE) { 1 }
        val sealed = ByteArray(IncidentReportCodec.SEAL_OVERHEAD + still.size)
        val payload = IncidentUpdateCodec.encode(id, 3, sealed)
        assertTrue(IncidentUpdateCodec.isWellFormed(payload))
        assertFalse(IncidentUpdateCodec.isWellFormed(payload.copyOf(INCIDENT_ID_SIZE + 1 + IncidentReportCodec.SEAL_OVERHEAD)))
        assertContentEquals(id, IncidentUpdateCodec.incidentId(payload))
        assertEquals(3, IncidentUpdateCodec.seq(payload))
        assertContentEquals(sealed, IncidentUpdateCodec.sealed(payload))
        assertFailsWith<IllegalArgumentException> { IncidentUpdateCodec.encode(id, 0, sealed) }
    }

    @Test
    fun responsesAndUpdatesHaveSummary2KindsOfTheirOwn() {
        val responses = Summary2(SummaryKind.RESPONSES, emptyList(), List(Limits.SUMMARY2_RESPONSE_ENTRIES) { HeldEntry(it.toLong(), 0xFFFFFFF0L + it % 2) })
        val bytes = Summary2Codec.encode(responses)
        assertTrue(bytes.size <= PacketCodec.MAX_PAYLOAD, "size=${bytes.size}")
        assertEquals(responses, Summary2Codec.decode(bytes))
        assertEquals(responses, Summary2Codec.decode(bytes + 7), "bytes after the entries are skipped")
        val tooMany = bytes.copyOf().also { it[1] = (Limits.SUMMARY2_RESPONSE_ENTRIES + 1).toByte() } + ByteArray(12)
        assertNull(Summary2Codec.decode(tooMany))

        val updates = Summary2(SummaryKind.UPDATES, emptyList(), List(Limits.SUMMARY_ENTRIES) { HeldEntry(-it.toLong(), 255) })
        assertEquals(updates, Summary2Codec.decode(Summary2Codec.encode(updates)))
        assertTrue(Summary2Codec.encode(updates).size <= PacketCodec.MAX_PAYLOAD)
        assertFailsWith<IllegalArgumentException> {
            Summary2Codec.encode(Summary2(SummaryKind.INCIDENTS, emptyList(), listOf(HeldEntry(1L, 1L))))
        }

        val request = Request2(SummaryKind.UPDATES, emptyList(), List(Limits.SUMMARY_ENTRIES) { it * 3L })
        assertEquals(request, Request2Codec.decode(Request2Codec.encode(request) + 1))
        assertNull(Request2Codec.decode(Request2Codec.encode(request).copyOf(10)))
        assertEquals(Request2(SummaryKind.RESPONSES, emptyList(), listOf(5L)), Request2Codec.decode(Request2Codec.encode(Request2(SummaryKind.RESPONSES, emptyList(), listOf(5L)))))
    }

    @Test
    fun responsesAndUpdatesNeverGoToAOneZeroPeerAndACertifiedResponseOnlyToAuth() {
        fun packet(type: Int, payload: ByteArray) = Packet(type, 7, 1L, 2L, 3L, null, false, payload)
        val id = ByteArray(INCIDENT_ID_SIZE)
        val sealed = ByteArray(IncidentReportCodec.SEAL_OVERHEAD + 2)
        val sig = ByteArray(IncidentAck.SIGNATURE_SIZE)
        val plain = packet(PacketType.INCIDENT_RESPONSE, IncidentResponse(id, 1L, sealed, null, sig).encode())
        val cert = Certificate(3, ByteArray(32), 10L, 20L, 5L, "Control", ByteArray(64))
        val certified = packet(PacketType.INCIDENT_RESPONSE, IncidentResponse(id, 1L, sealed, cert, sig).encode())
        val update = packet(PacketType.INCIDENT_UPDATE, IncidentUpdateCodec.encode(id, 1, sealed))
        for (p in listOf(plain, certified, update)) assertFalse(Compat10.accepts(p))
        assertFalse(CompatAuth.needsAuth(plain))
        assertTrue(CompatAuth.needsAuth(certified))
        assertFalse(CompatAuth.needsAuth(update))
    }
}
