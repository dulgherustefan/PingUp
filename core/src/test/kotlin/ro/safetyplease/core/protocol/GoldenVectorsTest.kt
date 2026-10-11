package ro.safetyplease.core.protocol

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 * Golden vectors for wire format v1 (PROTOCOL.md, section 13). The bytes live in
 * src/test/resources/golden/protocol-v1.txt; the inputs are built here from fixed seeds. Every vector is checked
 * both ways: the encoder must produce the stored bytes and the decoder must give back the inputs.
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

    val body = IncidentBody(
        IncidentCategory.MEDICAL, Severity.URGENT, TIME, "Scena mare", 44.426765, 26.102538,
        "Persoană căzută lângă gard", "Ana", IncidentCancel.hash(TOKEN),
    )
    val minimalBody = IncidentBody(IncidentCategory.CROWD, Severity.LOW, TIME, "", null, null, "", null)

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

    /** 60 bytes with a recipient at the smallest frame (64): chunks of 26, 26 and 8. */
    val fragmented = Packet(
        PacketType.PRIVATE, 7, long("0011223344556677"), alice.nodeId, TIME, bob.nodeId, true, ByteArray(60) { it.toByte() },
    )

    fun fragments(): List<ByteArray> = PacketCodec.toFrames(fragmented, PacketCodec.MIN_FRAME, FRAG_ID)

    val friendCard = FriendCard("Ana", alice.box.publicKey, alice.sign.publicKey)
    val staffCard = StaffCard(seed(0x10), seed(0x30), StaffRole.STAFF, "Medical 1", "")
}

private fun Packet.fields() = listOf(type, ttl, id, sender, timestamp, recipient, encrypted, payload.toHex())

private fun Hello.fields() = listOf(version, nodeId, flags, nickname)

private fun IncidentBody.fields() =
    listOf(category, severity, timestamp, zone, lat, lon, description, nickname, cancelHash?.toHex())

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
