package ro.safetyplease.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.util.utf8
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * Random and mutated bytes for everything that parses input from the network or a QR code. The contract
 * (PROTOCOL.md, sections 1 and 13): a decoder returns a value or null and never throws; the Reassembler returns
 * Complete, Pending or Invalid and keeps at most 8 sets. A successful decode re-encodes to the same bytes,
 * unless a string held invalid UTF-8 and came back with U+FFFD in it.
 */
class DecoderFuzzTest {
    private val random = Random(20_261_012)

    private class Codec<T : Any>(
        val name: String,
        val sample: Random.() -> T,
        val encode: (T) -> ByteArray,
        val decode: (ByteArray) -> T?,
        val strings: (T) -> List<String> = { emptyList() },
    )

    private val codecs = listOf(
        Codec("hello", { hello() }, Hello::encode, { Hello.decode(it) }, { listOfNotNull(it.nickname) }),
        Codec("summary", { summary() }, SummaryCodec::encode, SummaryCodec::decode),
        Codec("request", { request() }, RequestCodec::encode, RequestCodec::decode),
        Codec("body", { body() }, IncidentBody::encode, { IncidentBody.decode(it) }) {
            listOfNotNull(it.zone, it.description, it.nickname)
        },
        Codec("ack", { ack() }, IncidentAck::encode, { IncidentAck.decode(it) }, { listOf(it.teamName) }),
        Codec("cancel", { IncidentCancel(nextBytes(16), nextBytes(16)) }, IncidentCancel::encode, { IncidentCancel.decode(it) }),
        Codec("inner", { inner() }, InnerCodec::encode, InnerCodec::decode, ::stringsOf),
    )

    @Test(timeout = 10_000)
    fun packetDecoderReturnsAFrameOrNull() {
        repeat(ROUNDS) { round ->
            val packet = random.packet()
            val valid = if (random.nextBoolean()) {
                PacketCodec.encode(packet)
            } else {
                PacketCodec.toFrames(packet, random.nextInt(PacketCodec.MIN_FRAME, 515), random.nextInt(0x10000)).random(random)
            }
            val input = if (round % 10 == 0) valid else random.mutate(valid)
            val frame = bounded("frame", input) { PacketCodec.decode(input) } ?: return@repeat
            val p = frame.packet
            assertTrue(p.ttl in 1..PacketCodec.MAX_TTL && p.payload.size <= PacketCodec.MAX_PAYLOAD)
            val fragment = frame.fragment
            if (fragment == null) {
                assertArrayEquals("frame is not canonical", input, PacketCodec.encode(p))
                if (input === valid) assertEquals(packet.fields(), p.fields())
            } else {
                assertTrue(fragment.count in 2..255 && fragment.index < fragment.count && p.payload.isNotEmpty())
            }
        }
    }

    @Test(timeout = 10_000)
    fun payloadDecodersReturnAValueOrNull() {
        for (codec in codecs) fuzz(codec)
    }

    private fun <T : Any> fuzz(codec: Codec<T>) {
        repeat(ROUNDS) { round ->
            val valid = codec.encode(codec.sample(random))
            val input = if (round % 10 == 0) valid else random.mutate(valid)
            val value = bounded(codec.name, input) { codec.decode(input) } ?: return@repeat
            if (codec.strings(value).any { '\uFFFD' in it }) return@repeat
            assertArrayEquals("${codec.name} is not canonical", input, codec.encode(value))
        }
    }

    @Test(timeout = 10_000)
    fun reassemblerStaysBoundedUnderHostileFragments() {
        val reassembler = Reassembler()
        val heads = List(2) { random.packet() }
        var now = 0L
        repeat(ROUNDS / 4) {
            now += random.nextLong(0, 3_000)
            // few fragIds, so sets collide, get evicted and time out
            val frames = if (random.nextBoolean()) {
                PacketCodec.toFrames(random.packet(), random.nextInt(PacketCodec.MIN_FRAME, 200), random.nextInt(12))
            } else {
                List(random.nextInt(1, 20)) { random.forgedFragment(heads.random(random)) }
            }
            for (bytes in frames.filter { random.nextInt(4) != 0 }.shuffled(random)) {
                val input = if (random.nextInt(4) == 0) random.mutate(bytes) else bytes
                val frame = PacketCodec.decode(input) ?: continue
                val result = bounded("reassembler", input, pure = false) { reassembler.accept(frame, now) }
                assertTrue(reassembler.pendingSets <= 8)
                if (result is Reassembler.Result.Complete) assertTrue(result.packet.payload.size <= PacketCodec.MAX_PAYLOAD)
            }
        }
    }

    @Test(timeout = 10_000)
    fun reassemblyRestoresAnyPacketFromFramesInAnyOrder() {
        repeat(ROUNDS / 10) {
            val packet = random.packet()
            val frames = PacketCodec.toFrames(packet, random.nextInt(PacketCodec.MIN_FRAME, 515), random.nextInt(0x10000))
            assertTrue(frames.size <= 19)
            // the first frame is never repeated, so repeats can't complete a second copy
            val repeats = frames.drop(1).filter { random.nextInt(3) == 0 }
            val stream = (frames + repeats).shuffled(random)
            val reassembler = Reassembler()
            val done = stream.mapNotNull { (reassembler.accept(PacketCodec.decode(it)!!, 0) as? Reassembler.Result.Complete)?.packet }
            assertEquals(1, done.size)
            assertEquals(packet.fields(), done.single().fields())
        }
    }

    @Test(timeout = 10_000)
    fun qrDecodersReturnACardOrNull() {
        val identity = Identity.generate(testCrypto)
        repeat(ROUNDS) { round ->
            val valid = if (random.nextBoolean()) {
                QrCodes.encodeFriend(FriendCard(random.text(30), identity.box.publicKey, identity.sign.publicKey))
            } else {
                val role = if (random.nextBoolean()) StaffRole.STAFF else StaffRole.ANCHOR
                QrCodes.encodeStaff(StaffCard(random.nextBytes(32), random.nextBytes(32), role, random.text(30), random.text(40)))
            }
            val input = if (round % 10 == 0) valid else random.mutateText(valid)
            val bytes = input.utf8()
            bounded("friend QR", bytes) { QrCodes.decodeFriend(input) }?.let {
                assertTrue(it.boxKey.size == 32 && it.signKey.size == 32)
            }
            bounded("staff QR", bytes) { QrCodes.decodeStaff(input) }?.let {
                assertTrue(it.boxSeed.size == 32 && it.signSeed.size == 32)
            }
        }
    }

    @Test(timeout = 10_000)
    fun sealedBoxedAndSignedPayloadsRejectEveryChange() {
        val crypto = testCrypto
        val staffBox = crypto.boxKeyPair()
        val staffSign = crypto.signKeyPair()
        val staff = StaffCrypto(crypto, StaffPublicKeys(staffBox.publicKey, staffSign.publicKey))
        val secret = StaffSecretKeys(staffBox, staffSign)
        val alice = Identity.generate(crypto)
        val bob = Identity.generate(crypto)
        val sealed = staff.sealReport(random.body().encode())
        val ack = staff.signAck(random.nextBytes(16), AckStatus.RESOLVED, 1L, "Medical 1", secret).encode()
        val boxed = crypto.box(InnerCodec.encode(random.inner()), bob.box.publicKey, alice.box.secretKey)
        repeat(CRYPTO_ROUNDS) {
            val s = random.mutate(sealed)
            if (!s.contentEquals(sealed)) assertNull(bounded("sealed", s) { staff.openReport(s, secret) })
            val a = random.mutate(ack)
            if (!a.contentEquals(ack)) assertFalse(bounded("ack", a) { staff.verifyAck(a) })
            val b = random.mutate(boxed)
            if (!b.contentEquals(boxed)) assertNull(bounded("box", b) { crypto.boxOpen(b, alice.box.publicKey, bob.box.secretKey) })
        }
    }

    /** Counts the bytes [block] allocates; a [pure] block runs once before, so class loading isn't counted. */
    private fun <T> bounded(name: String, input: ByteArray, pure: Boolean = true, block: () -> T): T {
        if (pure) block()
        val before = threads.currentThreadAllocatedBytes
        val result = block()
        val used = threads.currentThreadAllocatedBytes - before
        if (used > MAX_ALLOCATION) fail("$name allocated $used bytes for ${input.size} input bytes")
        return result
    }

    private companion object {
        const val ROUNDS = 20_000
        const val CRYPTO_ROUNDS = 2_000

        /**
         * About 3x the most any call takes today (under 10 KiB, an exception's stack trace included), and half of
         * what a decoder that sized a buffer from a u16 length field could take.
         */
        const val MAX_ALLOCATION = 32 * 1024

        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    }
}

private val TYPES = intArrayOf(
    PacketType.HELLO, PacketType.SUMMARY, PacketType.REQUEST, PacketType.INCIDENT_REPORT,
    PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL, PacketType.PRIVATE, PacketType.TEST,
)

private val BOUNDARY = intArrayOf(0x00, 0x01, 0x02, 0x7f, 0x80, 0xfe, 0xff)

private val PIECES = listOf("a", "Z", "7", " ", "-", "ă", "ș", "Î", "€", "✓", "🎪")

/** Valid UTF-8 of at most [maxBytes] bytes, mixing one- to four-byte characters. */
private fun Random.text(maxBytes: Int): String =
    buildString { repeat(nextInt(0, maxBytes + 1)) { append(PIECES.random(this@text)) } }.truncateUtf8(maxBytes)

private fun Random.coordinate(limit: Int): Double = nextInt(-limit * 1_000_000, limit * 1_000_000 + 1) / 1e6

private fun Random.nonZeroLong(): Long {
    while (true) {
        val v = nextLong()
        if (v != 0L) return v
    }
}

private fun Random.packet() = Packet(
    type = TYPES.random(this),
    ttl = nextInt(1, PacketCodec.MAX_TTL + 1),
    id = nextLong(),
    sender = nextLong(),
    timestamp = nextLong(0, 1L shl 32),
    recipient = if (nextBoolean()) nextLong() else null,
    encrypted = nextBoolean(),
    payload = nextBytes(nextInt(0, PacketCodec.MAX_PAYLOAD + 1)),
)

private fun Random.hello() = Hello(nextInt(256), nonZeroLong(), nextInt(256), text(Limits.NICK_BYTES).ifEmpty { null })

private fun Random.summary() =
    List(nextInt(0, Limits.SUMMARY_ENTRIES + 1)) { SummaryEntry(nextLong(), nextBoolean(), nextInt(0, AckStatus.CANCELLED + 1)) }

private fun Random.request() = List(nextInt(0, Limits.SUMMARY_ENTRIES + 1)) { RequestEntry(nextLong(), nextBoolean(), nextBoolean()) }

private fun Random.body(): IncidentBody {
    val coords = nextBoolean()
    return IncidentBody(
        category = IncidentCategory.all.random(this),
        severity = nextInt(Severity.LOW, Severity.URGENT + 1),
        timestamp = nextLong(0, 1L shl 32),
        zone = text(Limits.ZONE_BYTES),
        lat = if (coords) coordinate(90) else null,
        lon = if (coords) coordinate(180) else null,
        description = text(Limits.DESCRIPTION_BYTES),
        nickname = if (nextBoolean()) text(Limits.NICK_BYTES) else null,
        cancelHash = if (nextBoolean()) nextBytes(IncidentCancel.HASH_SIZE) else null,
    )
}

private fun Random.ack() = IncidentAck(
    nextBytes(16), nextInt(AckStatus.RECEIVED, AckStatus.CANCELLED + 1), nextLong(0, 1L shl 32), text(Limits.TEAM_BYTES), nextBytes(64),
)

private fun Random.inner(): Inner {
    val msgId = nextLong()
    val group = if (nextBoolean()) nextLong() else null
    return when (nextInt(7)) {
        0 -> Inner.Text(msgId, group, text(Limits.TEXT_BYTES))
        1 -> Inner.Quick(msgId, group, QuickCode.all.random(this))
        2 -> if (nextBoolean()) Inner.Zone(msgId, group, text(Limits.ZONE_BYTES), coordinate(90), coordinate(180))
        else Inner.Zone(msgId, group, text(Limits.ZONE_BYTES), null, null)
        3 -> Inner.Ping(msgId)
        4 -> Inner.Pong(msgId, nextLong())
        5 -> Inner.Delivered(msgId, nextLong())
        else -> Inner.GroupInvite(
            msgId, nextLong(), text(Limits.GROUP_NAME_BYTES),
            List(nextInt(0, Limits.GROUP_MAX_MEMBERS - 1)) { GroupMemberWire(nextLong(), nextBytes(32), text(Limits.GROUP_NICK_BYTES)) },
        )
    }
}

/** A fragment with its own counters and length, as a hostile peer could write it: sizes add up past 480 B. */
private fun Random.forgedFragment(head: Packet): ByteArray {
    val count = intArrayOf(2, 3, 255, nextInt(2, 256)).random(this)
    val payload = nextBytes(nextInt(1, PacketCodec.MAX_PAYLOAD + 1))
    var flags = PacketCodec.FLAG_FRAGMENT
    if (head.recipient != null) flags = flags or PacketCodec.FLAG_RECIPIENT
    if (head.encrypted) flags = flags or PacketCodec.FLAG_ENCRYPTED
    val w = WireWriter().u8(PacketCodec.VERSION).u8(head.type).u8(head.ttl).u8(flags)
        .i64(head.id).i64(head.sender).u32(head.timestamp).u16(payload.size)
    head.recipient?.let { w.i64(it) }
    return w.u16(nextInt(4)).u8(nextInt(count)).u8(count).bytes(payload).toByteArray()
}

private fun stringsOf(inner: Inner): List<String> = when (inner) {
    is Inner.Text -> listOf(inner.text)
    is Inner.Zone -> listOf(inner.zone)
    is Inner.GroupInvite -> listOf(inner.name) + inner.members.map { it.nickname }
    else -> emptyList()
}

/** One of: random bytes, bit flips, truncation, trailing bytes, a boundary value (often a length), a cut or an insert. */
private fun Random.mutate(valid: ByteArray): ByteArray {
    if (valid.isEmpty()) return nextBytes(nextInt(0, 64))
    return when (nextInt(7)) {
        0 -> nextBytes(nextInt(0, valid.size + 64))
        1 -> valid.clone().also { b ->
            repeat(nextInt(1, 9)) {
                val bit = nextInt(b.size * 8)
                b[bit / 8] = (b[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            }
        }
        2 -> valid.copyOf(nextInt(0, valid.size))
        3 -> valid + nextBytes(nextInt(1, 64))
        4 -> valid.clone().also { it[nextInt(it.size)] = BOUNDARY.random(this).toByte() }
        5 -> {
            val from = nextInt(valid.size)
            val to = nextInt(from, valid.size + 1)
            valid.copyOfRange(0, from) + valid.copyOfRange(to, valid.size)
        }
        else -> {
            val at = nextInt(valid.size + 1)
            valid.copyOfRange(0, at) + nextBytes(nextInt(1, 32)) + valid.copyOfRange(at, valid.size)
        }
    }
}

private val QR_CHARS = "ABCXYZabcxyz0189-_+/=. \n\tăé€🎪".toList()

private fun Random.mutateText(valid: String): String = when (nextInt(5)) {
    0 -> valid.take(nextInt(valid.length + 1))
    1 -> valid + buildString { repeat(nextInt(1, 600)) { append(QR_CHARS.random(this@mutateText)) } }
    2 -> StringBuilder(valid).apply { setCharAt(nextInt(length), QR_CHARS.random(this@mutateText)) }.toString()
    3 -> valid.substring(0, 5) + buildString { repeat(nextInt(0, 200)) { append(QR_CHARS.random(this@mutateText)) } }
    else -> buildString { repeat(nextInt(0, 700)) { append(QR_CHARS.random(this@mutateText)) } }
}

private fun Packet.fields() = listOf(type, ttl, id, sender, timestamp, recipient, encrypted, payload.contentToString())
