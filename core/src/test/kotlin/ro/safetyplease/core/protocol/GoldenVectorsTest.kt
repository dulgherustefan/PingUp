package ro.safetyplease.core.protocol

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.SodiumCrypto
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.toLong

/**
 * Golden vectors for wire format v1, versions 1.0 and 1.1 (PROTOCOL.md, section 13). The bytes live in
 * src/test/resources/golden/protocol-v1.txt; the inputs are built here from fixed seeds. Every vector is checked
 * both ways: the encoder must produce the stored bytes and the decoder must give back the inputs. The 1.1 vectors
 * that carry what a newer version may add are built by hand, since no encoder writes them.
 */
class GoldenVectorsTest {
    private val vectors = Golden.vectors

    private fun bytes(name: String): ByteArray = vectors.getValue(name).hexToBytes()

    private val encoders: Map<String, () -> String> = linkedMapOf(
        "key.staff.box" to { Golden.staffPublic.box.toHex() },
        "key.staff.sign" to { Golden.staffPublic.sign.toHex() },
        "key.alice.box" to { Golden.alice.box.publicKey.toHex() },
        "key.alice.sign" to { Golden.alice.sign.publicKey.toHex() },
        "key.bob.box" to { Golden.bob.box.publicKey.toHex() },
        "key.bob.sign" to { Golden.bob.sign.publicKey.toHex() },
        "node.alice" to { Golden.alice.nodeId.toHex() },
        "node.bob" to { Golden.bob.nodeId.toHex() },
        "frame.test" to { PacketCodec.encode(Golden.testPacket).toHex() },
        "frame.private" to { PacketCodec.encode(Golden.privatePacket).toHex() },
        "frame.fragment.0" to { Golden.fragments()[0].toHex() },
        "frame.fragment.1" to { Golden.fragments()[1].toHex() },
        "frame.fragment.2" to { Golden.fragments()[2].toHex() },
        "dedup.private" to { Golden.privatePacket.dedupKey.toHex() },
        "hello" to { Golden.hello.encode().toHex() },
        "hello.nick" to { Golden.helloNick.encode().toHex() },
        "summary" to { SummaryCodec.encode(Golden.summary).toHex() },
        "request" to { RequestCodec.encode(Golden.request).toHex() },
        "incident.body" to { Golden.body.encode().toHex() },
        "incident.body.minimal" to { Golden.minimalBody.encode().toHex() },
        "incident.cancelHash" to { IncidentCancel.hash(Golden.TOKEN).toHex() },
        "incident.report" to { Golden.report().toHex() },
        "incident.ack.signed" to { IncidentAck.signedMessage(Golden.unsignedAck()).toHex() },
        "incident.ack" to { Golden.ack().encode().toHex() },
        "incident.cancel" to { IncidentCancel(Golden.INCIDENT_ID, Golden.TOKEN).encode().toHex() },
        "inner.text" to { InnerCodec.encode(Golden.text).toHex() },
        "inner.text.group" to { InnerCodec.encode(Golden.groupText).toHex() },
        "inner.quick" to { InnerCodec.encode(Golden.quick).toHex() },
        "inner.zone" to { InnerCodec.encode(Golden.zone).toHex() },
        "inner.zone.plain" to { InnerCodec.encode(Golden.plainZone).toHex() },
        "inner.ping" to { InnerCodec.encode(Golden.ping).toHex() },
        "inner.pong" to { InnerCodec.encode(Golden.pong).toHex() },
        "inner.delivered" to { InnerCodec.encode(Golden.delivered).toHex() },
        "inner.invite" to { InnerCodec.encode(Golden.invite).toHex() },
        "private.box" to { Golden.boxed().toHex() },
        "qr.friend" to { QrCodes.encodeFriend(Golden.friendCard) },
        "qr.staff" to { QrCodes.encodeStaff(Golden.staffCard) },
        "hello.v11" to { Golden.hello11.encode().toHex() },
        "hello.extended" to { (Golden.hello.encode() + Golden.EXTRA).toHex() },
        "summary2" to { Summary2Codec.encode(Golden.summary2).toHex() },
        "summary2.unknown" to { Golden.UNKNOWN_KIND_SUMMARY.toHex() },
        "request2" to { Request2Codec.encode(Golden.request2).toHex() },
        "frame.opaque" to { PacketCodec.encode(Golden.opaquePacket).toHex() },
        "frame.opaque.relayed" to { PacketCodec.encode(Golden.opaquePacket.withTtl(PacketType.OPAQUE_MAX_TTL)).toHex() },
        "incident.body.extended" to { Golden.extendedBody().toHex() },
        "incident.ack.future" to { Golden.futureAck().encode().toHex() },
        "incident.ack.assigned" to { Golden.assignedAck().encode().toHex() },
        "incident.ack.on_scene" to { Golden.statusAck(AckStatus.ON_SCENE, 150, "Medical 1").encode().toHex() },
        "incident.ack.handed_over" to { Golden.statusAck(AckStatus.HANDED_OVER, 180, "Medical 1").encode().toHex() },
        "incident.ack.closed" to { Golden.statusAck(AckStatus.CLOSED_BY_CONTROL, 210, "Control").encode().toHex() },
        "incident.ack.unfounded" to { Golden.statusAck(AckStatus.UNFOUNDED, 240, "Control").encode().toHex() },
        "incident.ack.reopened" to { Golden.statusAck(AckStatus.REOPENED, 270, "Control", epoch = 1).encode().toHex() },
        "incident.ack.assigned.epoch" to {
            Golden.statusAck(AckStatus.ACKNOWLEDGED, 300, "Medical", AckFlags.ASSIGNED, epoch = 1).encode().toHex()
        },
        "incident.ack.unknown" to { Golden.unknownAck().encode().toHex() },
        "incident.body.triage" to { Golden.triageBody.encode().toHex() },
        "incident.body.staff" to { Golden.staffBody.encode().toHex() },
        "incident.body.lost_friend" to { Golden.lostFriendBody.encode().toHex() },
        "summary2.epochs" to { Summary2Codec.encode(Golden.summary2Epochs).toHex() },
        "hello.prefix" to { Golden.helloPrefix.encode().toHex() },
    )

    @Test
    fun encodersProduceTheStoredBytes() {
        val changed = encoders.mapNotNull { (name, encode) ->
            val actual = encode()
            if (vectors[name] == actual) null else "$name $actual"
        }
        if (changed.isNotEmpty()) {
            fail("wire change; current bytes in the file's format:\n" + changed.joinToString("\n"))
        }
    }

    @Test
    fun fileHoldsExactlyTheCheckedVectors() {
        assertEquals("duplicate names", Golden.lines.size, vectors.size)
        assertEquals(encoders.keys, vectors.keys)
    }

    @Test
    fun keyVectorsAreConsistent() {
        assertEquals(vectors["node.alice"], Identity.nodeIdOf(bytes("key.alice.sign"), bytes("key.alice.box")).toHex())
        assertEquals(vectors["node.bob"], Identity.nodeIdOf(bytes("key.bob.sign"), bytes("key.bob.box")).toHex())
        val staff = StaffCrypto(Golden.crypto, StaffPublicKeys(bytes("key.staff.box"), bytes("key.staff.sign")))
        assertNotNull(staff.secretFromSeeds(Golden.seed(0x10), Golden.seed(0x30)))
    }

    @Test
    fun framesDecode() {
        val test = PacketCodec.decode(bytes("frame.test"))!!
        assertNull(test.fragment)
        assertEquals(Golden.testPacket.fields(), test.packet.fields())
        assertEquals(Golden.privatePacket.fields(), PacketCodec.decode(bytes("frame.private"))!!.packet.fields())
        assertEquals(vectors["dedup.private"], PacketCodec.decode(bytes("frame.private"))!!.packet.dedupKey.toHex())

        val reassembler = Reassembler()
        val results = (0..2).map { i ->
            val frame = PacketCodec.decode(bytes("frame.fragment.$i"))!!
            val f = frame.fragment!!
            assertEquals(listOf(Golden.FRAG_ID, i, 3), listOf(f.fragId, f.index, f.count))
            reassembler.accept(frame, 0)
        }
        assertEquals(Golden.fragmented.fields(), (results.last() as Reassembler.Result.Complete).packet.fields())
    }

    @Test
    fun linkLocalPayloadsDecode() {
        assertEquals(Golden.hello.fields(), Hello.decode(bytes("hello"))!!.fields())
        assertEquals(Golden.helloNick.fields(), Hello.decode(bytes("hello.nick"))!!.fields())
        assertEquals(Golden.summary, SummaryCodec.decode(bytes("summary")))
        assertEquals(Golden.request, RequestCodec.decode(bytes("request")))
    }

    @Test
    fun incidentPayloadsDecode() {
        assertEquals(Golden.body.fields(), IncidentBody.decode(bytes("incident.body"))!!.fields())
        assertEquals(Golden.minimalBody.fields(), IncidentBody.decode(bytes("incident.body.minimal"))!!.fields())
        assertArrayEquals(bytes("incident.cancelHash"), IncidentBody.decode(bytes("incident.body"))!!.cancelHash)

        val report = bytes("incident.report")
        assertTrue(IncidentReportCodec.isWellFormed(report))
        assertArrayEquals(Golden.INCIDENT_ID, IncidentReportCodec.incidentId(report))
        val plain = Golden.staff.openReport(IncidentReportCodec.sealed(report), Golden.staffSecret)!!
        assertArrayEquals(bytes("incident.body"), plain)

        val ack = bytes("incident.ack")
        assertTrue(Golden.staff.verifyAck(ack))
        assertArrayEquals(bytes("incident.ack.signed"), IncidentAck.signedMessage(IncidentAck.unsignedPart(ack)))
        assertEquals(Golden.ack().fields(), IncidentAck.decode(ack)!!.fields())

        val cancel = IncidentCancel.decode(bytes("incident.cancel"))!!
        assertArrayEquals(Golden.INCIDENT_ID, cancel.incidentId)
        assertArrayEquals(Golden.TOKEN, cancel.token)
        assertArrayEquals(bytes("incident.cancelHash"), IncidentCancel.hash(cancel.token))
    }

    @Test
    fun chatPayloadsDecode() {
        val inners = mapOf(
            "inner.text" to Golden.text, "inner.text.group" to Golden.groupText, "inner.quick" to Golden.quick,
            "inner.zone" to Golden.zone, "inner.zone.plain" to Golden.plainZone, "inner.ping" to Golden.ping,
            "inner.pong" to Golden.pong, "inner.delivered" to Golden.delivered, "inner.invite" to Golden.invite,
        )
        for ((name, inner) in inners) assertEquals(name, inner.fields(), InnerCodec.decode(bytes(name))!!.fields())

        val opened = Golden.crypto.boxOpen(bytes("private.box"), Golden.alice.box.publicKey, Golden.bob.box.secretKey)!!
        assertArrayEquals(bytes("inner.text"), opened)
        assertArrayEquals(bytes("private.box"), PacketCodec.decode(bytes("frame.private"))!!.packet.payload)
    }

    @Test
    fun version11VectorsDecode() {
        val hello11 = Hello.decode(bytes("hello.v11"))!!
        assertEquals(Golden.hello11.fields(), hello11.fields())
        assertEquals(NodeFlags.PROTOCOL_1_1, hello11.flags and NodeFlags.PROTOCOL_1_1)
        assertEquals(Golden.hello.fields(), Hello.decode(bytes("hello.extended"))!!.fields())
        assertEquals(Golden.summary2, Summary2Codec.decode(bytes("summary2")))
        assertEquals(Summary2(0x7e, emptyList()), Summary2Codec.decode(bytes("summary2.unknown")))
        assertEquals(Golden.request2, Request2Codec.decode(bytes("request2")))

        val opaque = PacketCodec.decode(bytes("frame.opaque"))!!.packet
        assertEquals(Golden.opaquePacket.fields(), opaque.fields())
        val relayed = PacketCodec.decode(bytes("frame.opaque.relayed"))!!.packet
        assertEquals(PacketType.OPAQUE_MAX_TTL, relayed.ttl)
        assertEquals("only the ttl changes", opaque.dedupKey, relayed.dedupKey)

        assertEquals(Golden.body.fields(), IncidentBody.decode(bytes("incident.body.extended"))!!.fields())

        val future = bytes("incident.ack.future")
        assertTrue("the signature covers the fields 1.1 doesn't read", Golden.staff.verifyAck(future))
        val ack = IncidentAck.decode(future)!!
        assertEquals(Golden.futureAck().fields() + Golden.futureAck().extensions.toHex(), ack.fields() + ack.extensions.toHex())
        // written as a newer version's ACK before status 7 and the epoch had a meaning; 1.1 now reads both
        assertEquals(AckStatus.CLOSED_BY_CONTROL, ack.status)
        assertEquals(0xcd, ack.epoch)
        assertTrue(ack.assigned)

        val unknown = bytes("incident.ack.unknown")
        assertTrue(Golden.staff.verifyAck(unknown))
        val newer = IncidentAck.decode(unknown)!!
        assertEquals(Golden.unknownAck().fields() + "abcd", newer.fields() + newer.extensions.toHex())
        assertFalse("kept as a number, with no place in the order", AckStatus.isKnown(newer.status))
        assertEquals(-1, AckStatus.rank(newer.status))

        val assigned = bytes("incident.ack.assigned")
        assertTrue(Golden.staff.verifyAck(assigned))
        val decoded = IncidentAck.decode(assigned)!!
        assertEquals(Golden.assignedAck().fields(), decoded.fields())
        assertTrue(decoded.assigned)
        assertEquals("01", decoded.extensions.toHex())
        val packet = Packet(PacketType.INCIDENT_ACK, 7, 1L, 2L, Golden.TIME, null, false, assigned)
        assertFalse("never to a 1.0 phone", Compat10.accepts(packet))

        val withPrefix = Hello.decode(bytes("hello.prefix"))!!
        assertEquals(Golden.helloPrefix.fields(), withPrefix.fields())
        assertEquals(0x1a2b3c4d, withPrefix.prefix)
    }

    @Test
    fun fieldStatesEpochsTriageAndStaffReportsDecode() {
        val statuses = mapOf(
            "incident.ack.on_scene" to AckStatus.ON_SCENE, "incident.ack.handed_over" to AckStatus.HANDED_OVER,
            "incident.ack.closed" to AckStatus.CLOSED_BY_CONTROL, "incident.ack.unfounded" to AckStatus.UNFOUNDED,
            "incident.ack.reopened" to AckStatus.REOPENED, "incident.ack.assigned.epoch" to AckStatus.ACKNOWLEDGED,
        )
        for ((name, status) in statuses) {
            val payload = bytes(name)
            assertTrue(name, Golden.staff.verifyAck(payload))
            val ack = IncidentAck.decode(payload)!!
            assertEquals(name, status, ack.status)
            val packet = Packet(PacketType.INCIDENT_ACK, 7, 1L, 2L, Golden.TIME, null, false, payload)
            assertFalse("$name never goes to a 1.0 phone", Compat10.accepts(packet))
        }
        assertEquals("", IncidentAck.decode(bytes("incident.ack.closed"))!!.extensions.toHex())
        val reopened = IncidentAck.decode(bytes("incident.ack.reopened"))!!
        assertEquals("0201", reopened.extensions.toHex())
        assertEquals(1, reopened.epoch)
        val assignedAgain = IncidentAck.decode(bytes("incident.ack.assigned.epoch"))!!
        assertEquals("0301", assignedAgain.extensions.toHex())
        assertTrue(assignedAgain.assigned)
        assertEquals(1, assignedAgain.epoch)

        val triage = IncidentBody.decode(bytes("incident.body.triage"))!!
        assertEquals(Golden.triageBody.fields(), triage.fields())
        assertEquals(Triage(Answer.NO, Answer.UNKNOWN), triage.triage)
        val staff = IncidentBody.decode(bytes("incident.body.staff"))!!
        assertEquals(Golden.staffBody.fields(), staff.fields())
        assertEquals(StaffFields(3, dangerContinues = true), staff.staff)
        assertEquals(IncidentCategory.LOST_FRIEND, IncidentBody.decode(bytes("incident.body.lost_friend"))!!.category)

        val epochs = Summary2Codec.decode(bytes("summary2.epochs"))!!
        assertEquals(Golden.summary2Epochs, epochs)
        assertEquals(listOf(1, 0), epochs.entries.map { it.epoch })
    }

    @Test
    fun version10ReadsWhatItDidAndRejectsOnlyWhatOneOneKeepsFromIt() {
        for (name in listOf("frame.test", "frame.private", "frame.fragment.0")) assertNotNull(name, Frozen10.frame(bytes(name)))
        assertNotNull(Frozen10.hello(bytes("hello")))
        assertNotNull("a 1.1 HELLO is a valid 1.0 HELLO", Frozen10.hello(bytes("hello.v11")))
        assertNotNull(Frozen10.summary(bytes("summary")))
        assertNotNull(Frozen10.request(bytes("request")))
        assertNotNull(Frozen10.ack(bytes("incident.ack")))
        assertNotNull(Frozen10.body(bytes("incident.body")))

        assertNull(Frozen10.hello(bytes("hello.extended")))
        assertNull(Frozen10.hello(bytes("hello.prefix")))
        assertNull(Frozen10.frame(bytes("frame.opaque")))
        assertNull(Frozen10.ack(bytes("incident.ack.future")))
        assertNull(Frozen10.ack(bytes("incident.ack.assigned")))
        assertNull(Frozen10.body(bytes("incident.body.extended")))
        for (name in listOf("on_scene", "handed_over", "closed", "unfounded", "reopened", "assigned.epoch", "unknown")) {
            assertNull(name, Frozen10.ack(bytes("incident.ack.$name")))
        }
        for (name in listOf("triage", "staff", "lost_friend")) assertNull(name, Frozen10.body(bytes("incident.body.$name")))
    }

    /** The 1.0 phone of the interop tests writes with these, so they must still give the 1.0 bytes. */
    @Test
    fun version10EncodersWriteTheOneZeroVectors() {
        val fragments = Frozen10.encodeFrames(Golden.fragmented, PacketCodec.MIN_FRAME, Golden.FRAG_ID)
        val frozen = mapOf(
            "frame.test" to Frozen10.encodeFrames(Golden.testPacket, maxFrame = 514, fragId = 0).single(),
            "frame.private" to Frozen10.encodeFrames(Golden.privatePacket, maxFrame = 514, fragId = 0).single(),
            "frame.fragment.0" to fragments[0],
            "frame.fragment.1" to fragments[1],
            "frame.fragment.2" to fragments[2],
            "hello" to Frozen10.encodeHello(Golden.hello.nodeId, Golden.hello.flags, null),
            "hello.nick" to Frozen10.encodeHello(Golden.helloNick.nodeId, Golden.helloNick.flags, "Ana"),
            "summary" to Frozen10.encodeSummary(Golden.summary),
            "request" to Frozen10.encodeRequest(Golden.request),
            "incident.ack" to Frozen10.encodeAck(Golden.ack()),
        )
        assertEquals(3, fragments.size)
        for ((name, bytes) in frozen) assertEquals(name, vectors[name], bytes.toHex())
    }

    @Test
    fun qrCodesDecode() {
        val friend = QrCodes.decodeFriend(vectors.getValue("qr.friend"))!!
        assertEquals(Golden.friendCard.fields(), friend.fields())
        assertEquals(Golden.alice.nodeId, friend.nodeId)
        val staff = QrCodes.decodeStaff(vectors.getValue("qr.staff"))!!
        assertEquals(Golden.staffCard.fields(), staff.fields())
        assertNotNull(Golden.staff.secretFromSeeds(staff.boxSeed, staff.signSeed))
    }
}

/** Fixed inputs. Seeds are 32 consecutive byte values starting at the given one. */
private object Golden {
    const val TIME = 1_760_000_000L
    const val FRAG_ID = 0xbeef
    val INCIDENT_ID = "00112233445566778899aabbccddeeff".hexToBytes()
    val TOKEN = ByteArray(IncidentCancel.TOKEN_SIZE) { (0x20 + it).toByte() }
    private val NONCE = ByteArray(24) { (0xe0 + it).toByte() }

    fun seed(first: Int) = ByteArray(32) { (first + it).toByte() }

    private fun long(hex: String) = hex.hexToBytes().toLong()

    val lines: List<String> = Golden::class.java.getResource("/golden/protocol-v1.txt")!!.readText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val vectors: Map<String, String> = lines.associate { it.substringBefore(' ') to it.substringAfter(' ').trim() }

    // The only randomness libsodium takes from Java is the crypto_box nonce; fixing it makes box() deterministic.
    private val sodium = object : LazySodiumJava(SodiumJava()) {
        override fun randomBytesBuf(size: Int): ByteArray {
            check(size == NONCE.size)
            return NONCE.clone()
        }
    }
    val crypto = SodiumCrypto(sodium)

    val staffPublic = StaffPublicKeys(
        crypto.boxKeyPairFromSeed(seed(0x10)).publicKey,
        crypto.signKeyPairFromSeed(seed(0x30)).publicKey,
    )
    val staff = StaffCrypto(crypto, staffPublic)
    val staffSecret = staff.secretFromSeeds(seed(0x10), seed(0x30))!!
    val alice = Identity(crypto.boxKeyPairFromSeed(seed(0x50)), crypto.signKeyPairFromSeed(seed(0x70)))
    val bob = Identity(crypto.boxKeyPairFromSeed(seed(0x90)), crypto.signKeyPairFromSeed(seed(0xb0)))

    val hello = Hello(PacketCodec.VERSION, alice.nodeId, NodeFlags.STAFF or NodeFlags.ACCEPTS_CONNECTIONS, null)
    val helloNick = Hello(PacketCodec.VERSION, alice.nodeId, NodeFlags.PENDING_INCIDENT, "Ana")
    val hello11 = Hello(
        PacketCodec.VERSION, alice.nodeId, NodeFlags.STAFF or NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.PROTOCOL_1_1, null,
    )
    val helloPrefix = Hello(
        PacketCodec.VERSION, alice.nodeId,
        NodeFlags.STAFF or NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.PROTOCOL_1_1 or NodeFlags.PREFIX_IN_HELLO, null,
        prefix = 0x1a2b3c4d,
    )

    /** Bytes a newer version appends to a payload. */
    val EXTRA = "abcd".hexToBytes()
    val UNKNOWN_KIND_SUMMARY = "7e010203".hexToBytes()

    val summary = listOf(
        SummaryEntry(INCIDENT_ID.toLong(), true, AckStatus.NONE),
        SummaryEntry(long("0102030405060708"), false, AckStatus.RESOLVED),
        SummaryEntry(long("fffffffffffffffe"), true, AckStatus.CANCELLED),
    )
    val request = listOf(
        RequestEntry(INCIDENT_ID.toLong(), true, false),
        RequestEntry(long("0102030405060708"), false, true),
        RequestEntry(long("fffffffffffffffe"), true, true),
    )
    val summary2 = Summary2(SummaryKind.INCIDENTS, summary)
    val request2 = Request2(SummaryKind.INCIDENTS, request)

    val body = IncidentBody(
        IncidentCategory.MEDICAL, Severity.URGENT, TIME, "Scena mare", 44.426765, 26.102538,
        "Persoană căzută lângă gard", "Ana", IncidentCancel.hash(TOKEN),
    )
    val minimalBody = IncidentBody(IncidentCategory.CROWD, Severity.LOW, TIME, "", null, null, "", null)

    /** [body] with flag 0x80 from a newer version, and that version's field after the cancel hash. */
    fun extendedBody(): ByteArray = body.encode().also { it[6] = (it[6].toInt() or 0x80).toByte() } + EXTRA

    /** crypto_box_seal with a fixed ephemeral key (seed d0..ef), built step by step as libsodium does. */
    fun report(): ByteArray {
        val epk = ByteArray(32)
        val esk = ByteArray(32)
        check(sodium.cryptoBoxSeedKeypair(epk, esk, seed(0xd0)))
        val nonce = ByteArray(24)
        val nonceInput = epk + staffPublic.box
        check(sodium.cryptoGenericHash(nonce, nonce.size, nonceInput, nonceInput.size.toLong()))
        val plain = body.encode()
        val cipher = ByteArray(plain.size + 16)
        check(sodium.cryptoBoxEasy(cipher, plain, plain.size.toLong(), nonce, staffPublic.box, esk))
        return IncidentReportCodec.encode(INCIDENT_ID, epk + cipher)
    }

    fun unsignedAck(): ByteArray = IncidentAck.unsigned(INCIDENT_ID, AckStatus.ACKNOWLEDGED, TIME + 60, "Medical 1")

    fun ack(): IncidentAck = staff.signAck(INCIDENT_ID, AckStatus.ACKNOWLEDGED, TIME + 60, "Medical 1", staffSecret)

    /** Status 7 and two bytes of fields from a newer version, signed with the staff key like any ACK. */
    fun futureAck(): IncidentAck {
        val signed = IncidentAck.unsigned(INCIDENT_ID, 7, TIME + 120, "Medical 1") + EXTRA
        val signature = crypto.sign(IncidentAck.signedMessage(signed), staffSecret.sign.secretKey)
        return IncidentAck(INCIDENT_ID, 7, TIME + 120, "Medical 1", signature, EXTRA)
    }

    /** The control console gives the incident to the role "Medical": ACKNOWLEDGED with the ASSIGNED flag. */
    fun assignedAck(): IncidentAck =
        staff.signAck(INCIDENT_ID, AckStatus.ACKNOWLEDGED, TIME + 90, "Medical", staffSecret, AckFlags.ASSIGNED)

    /** [status] signed at TIME + [after] seconds. */
    fun statusAck(status: Int, after: Long, team: String, flags: Int = 0, epoch: Int = 0): IncidentAck =
        staff.signAck(INCIDENT_ID, status, TIME + after, team, staffSecret, flags, epoch)

    /** A status no version has defined yet, 0x7e, with [EXTRA] as its fields. */
    fun unknownAck(): IncidentAck {
        val signed = IncidentAck.unsigned(INCIDENT_ID, 0x7e, TIME + 330, "Medical 1") + EXTRA
        val signature = crypto.sign(IncidentAck.signedMessage(signed), staffSecret.sign.secretKey)
        return IncidentAck(INCIDENT_ID, 0x7e, TIME + 330, "Medical 1", signature, EXTRA)
    }

    /** [body] with the triage answers: doesn't respond, breathing unknown. */
    val triageBody = IncidentBody(
        IncidentCategory.MEDICAL, Severity.URGENT, TIME, "Scena mare", 44.426765, 26.102538,
        "Persoană căzută lângă gard", "Ana", IncidentCancel.hash(TOKEN), Triage(Answer.NO, Answer.UNKNOWN),
    )

    /** A report by the team "Securitate 1": three people, the danger goes on, the way in described. */
    val staffBody = IncidentBody(
        IncidentCategory.VIOLENCE, Severity.URGENT, TIME, "Poarta 2", null, null, "Acces pe la spatele scenei",
        "Securitate 1", IncidentCancel.hash(TOKEN), staff = StaffFields(3, dangerContinues = true),
    )

    val lostFriendBody = IncidentBody(
        IncidentCategory.LOST_FRIEND, Severity.LOW, TIME, "Scena mare", null, null, "", null, IncidentCancel.hash(TOKEN),
    )

    /** A reopened incident and a closed one. */
    val summary2Epochs = Summary2(
        SummaryKind.INCIDENTS,
        listOf(
            SummaryEntry(INCIDENT_ID.toLong(), true, AckStatus.REOPENED, epoch = 1),
            SummaryEntry(long("0102030405060708"), false, AckStatus.CLOSED_BY_CONTROL),
        ),
    )

    private val msgId = long("0102030405060708")
    private val groupId = long("1112131415161718")
    private val otherId = long("2122232425262728")

    val text = Inner.Text(msgId, null, "Ne vedem la scenă?")
    val groupText = Inner.Text(msgId, groupId, "salut")
    val quick = Inner.Quick(msgId, null, QuickCode.MEET_AT_POINT)
    val zone = Inner.Zone(msgId, groupId, "bar", -22.951916, -43.210487)
    val plainZone = Inner.Zone(msgId, null, "bar", null, null)
    val ping = Inner.Ping(msgId)
    val pong = Inner.Pong(msgId, otherId)
    val delivered = Inner.Delivered(msgId, otherId)
    val invite = Inner.GroupInvite(
        msgId, groupId, "Prietenii",
        listOf(
            GroupMemberWire(bob.nodeId, bob.box.publicKey, "Bob"),
            GroupMemberWire(long("3132333435363738"), seed(0x40), "Cora"),
        ),
    )

    fun boxed(): ByteArray = crypto.box(InnerCodec.encode(text), bob.box.publicKey, alice.box.secretKey)

    val testPacket = Packet(
        PacketType.TEST, 7, long("0123456789abcdef"), long("f0e1d2c3b4a59687"), TIME, null, false, "ping".toByteArray(),
    )
    val privatePacket = Packet(PacketType.PRIVATE, 5, long("0123456789abcdef"), alice.nodeId, TIME, bob.nodeId, true, boxed())
    val opaquePacket = Packet(0x55, 7, long("0123456789abcdef"), alice.nodeId, TIME, null, false, "future".toByteArray())

    /** 60 bytes with a recipient at the smallest frame (64): chunks of 26, 26 and 8. */
    val fragmented = Packet(
        PacketType.PRIVATE, 7, long("0011223344556677"), alice.nodeId, TIME, bob.nodeId, true, ByteArray(60) { it.toByte() },
    )

    fun fragments(): List<ByteArray> = PacketCodec.toFrames(fragmented, PacketCodec.MIN_FRAME, FRAG_ID)

    val friendCard = FriendCard("Ana", alice.box.publicKey, alice.sign.publicKey)
    val staffCard = StaffCard(seed(0x10), seed(0x30), StaffRole.STAFF, "Medical 1", "")
}

private fun Packet.fields() = listOf(type, ttl, id, sender, timestamp, recipient, encrypted, payload.toHex())

private fun Hello.fields() = listOf(version, nodeId, flags, nickname, prefix)

private fun IncidentBody.fields() =
    listOf(category, severity, timestamp, zone, lat, lon, description, nickname, cancelHash?.toHex(), triage, staff)

private fun IncidentAck.fields() = listOf(incidentId.toHex(), status, timestamp, teamName, signature.toHex())

private fun Inner.fields(): List<Any?> = when (this) {
    is Inner.Text -> listOf("text", msgId, groupId, text)
    is Inner.Quick -> listOf("quick", msgId, groupId, code)
    is Inner.Zone -> listOf("zone", msgId, groupId, zone, lat, lon)
    is Inner.Ping -> listOf("ping", msgId)
    is Inner.Pong -> listOf("pong", msgId, pingId)
    is Inner.Delivered -> listOf("delivered", msgId, deliveredId)
    is Inner.GroupInvite ->
        listOf("invite", msgId, groupId, name, members.map { listOf(it.nodeId, it.boxKey.toHex(), it.nickname) })
}

private fun FriendCard.fields() = listOf(nickname, boxKey.toHex(), signKey.toHex())

private fun StaffCard.fields() = listOf(boxSeed.toHex(), signSeed.toHex(), role, teamName, zone)
