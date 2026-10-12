package ro.safetyplease.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.Revocation
import ro.safetyplease.core.crypto.ShiftCard
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.util.utf8
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * Random and mutated bytes for everything that parses input from the network or a QR code. The contract
 * (PROTOCOL.md, sections 1 and 13): a decoder returns a value or null and never throws; the Reassembler returns
 * Complete, Pending or Invalid and keeps at most 8 sets. A successful decode re-encodes to the same bytes, unless
 * a string held invalid UTF-8 and came back with U+FFFD in it, or the decoder skips what a newer version may add:
 * then decoding its own encoding must give the same bytes again.
 */
class DecoderFuzzTest {
    private val random = Random(20_261_012)

    private class Codec<T : Any>(
        val name: String,
        val sample: Random.() -> T,
        val encode: (T) -> ByteArray,
        val decode: (ByteArray) -> T?,
        val strings: (T) -> List<String> = { emptyList() },
        val tolerant: Boolean = false,
    )

    private val codecs = listOf(
        Codec("hello", { hello() }, Hello::encode, { Hello.decode(it) }, { listOfNotNull(it.nickname) }, tolerant = true),
        Codec("summary", { summary() }, SummaryCodec::encode, SummaryCodec::decode),
        Codec("request", { request() }, RequestCodec::encode, RequestCodec::decode),
        Codec("summary2", { summary2() }, Summary2Codec::encode, Summary2Codec::decode, tolerant = true),
        Codec("request2", { request2() }, Request2Codec::encode, Request2Codec::decode, tolerant = true),
        Codec(
            "body", { body() }, IncidentBody::encode, { IncidentBody.decode(it) },
            strings = { listOfNotNull(it.zone, it.description, it.nickname) }, tolerant = true,
        ),
        Codec("ack", { ack() }, IncidentAck::encode, { IncidentAck.decode(it) }, { listOf(it.teamName) }),
        Codec(
            "cancel", { IncidentCancel(nextBytes(16), nextBytes(16)) }, IncidentCancel::encode, { IncidentCancel.decode(it) },
            tolerant = true,
        ),
        // a position drops the reserved bits of its flags
        Codec("inner", { inner() }, InnerCodec::encode, InnerCodec::decode, ::stringsOf, tolerant = true),
        Codec("certificate", { certificate() }, Certificate::encode, { Certificate.decode(it) }, { listOf(it.name) }),
        Codec(
            "revocation", { Revocation(nextLong(), nextLong(0, 1L shl 32), List(nextInt(1, Revocation.MAX_IDS + 1)) { nextLong() }, nextBytes(64)) },
            Revocation::encode, { Revocation.decode(it) },
        ),
        Codec("heartbeat", { heartbeat() }, Heartbeat::encode, { Heartbeat.decode(it) }, { listOf(it.zone) }, tolerant = true),
        Codec(
            "point status", { pointStatus() }, PointStatus::encode, { PointStatus.decode(it) },
            { listOfNotNull(it.teamName, it.certificate?.name) }, tolerant = true,
        ),
        Codec(
            "response", { response() }, IncidentResponse::encode, { IncidentResponse.decode(it) },
            { listOfNotNull(it.certificate?.name) }, tolerant = true,
        ),
        Codec("response body", { ResponseBody(nextInt(1, 256), nextInt(0, 256)) }, ResponseBody::encode, { ResponseBody.decode(it) }, tolerant = true),
        Codec("update body", { updateBody() }, UpdateBody::encode, { UpdateBody.decode(it) }, { listOfNotNull(it.zone) }, tolerant = true),
        Codec("notice", { notice() }, Notice::encode, { Notice.decode(it) }, { listOf(it.zone, it.certificate.name) + it.params }, tolerant = true),
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
            val bytes = codec.encode(value)
            if (codec.tolerant) {
                assertArrayEquals("${codec.name} doesn't read back what it writes", bytes, codec.encode(codec.decode(bytes)!!))
            } else {
                assertArrayEquals("${codec.name} is not canonical", input, bytes)
            }
        }
    }

    @Test(timeout = 10_000)
    fun reportAccessorsThrowOnPayloadsThatAreNotWellFormed() {
        // incidentId and sealed slice without checking; callers check isWellFormed first (PROTOCOL.md, section 6.3)
        repeat(ROUNDS / 10) {
            val short = random.nextBytes(random.nextInt(0, INCIDENT_ID_SIZE))
            assertFalse(IncidentReportCodec.isWellFormed(short))
            assertThrows(RuntimeException::class.java) { IncidentReportCodec.incidentId(short) }
            assertThrows(RuntimeException::class.java) { IncidentReportCodec.sealed(short) }
        }
        for (size in 0..INCIDENT_ID_SIZE + IncidentReportCodec.SEAL_OVERHEAD + 1) {
            val payload = random.nextBytes(size)
            val wellFormed = IncidentReportCodec.isWellFormed(payload)
            assertEquals("size $size", size > INCIDENT_ID_SIZE + IncidentReportCodec.SEAL_OVERHEAD, wellFormed)
            if (wellFormed) assertArrayEquals(payload, IncidentReportCodec.incidentId(payload) + IncidentReportCodec.sealed(payload))
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
            val valid = when (random.nextInt(4)) {
                0 -> QrCodes.encodeFriend(FriendCard(random.text(30), identity.box.publicKey, identity.sign.publicKey))
                1 -> {
                    val role = if (random.nextBoolean()) StaffRole.STAFF else StaffRole.ANCHOR
                    QrCodes.encodeStaff(StaffCard(random.nextBytes(32), random.nextBytes(32), role, random.text(30), random.text(40)))
                }
                2 -> {
                    val report = random.certificate().takeIf { random.nextBoolean() }
                    val shift = random.certificate(role = random.nextInt(CertRole.STAFF, CertRole.CONTROL + 1))
                    QrCodes.encodeShift(
                        ShiftCard(
                            shift, random.nextBytes(32), report, random.nextBytes(32).takeIf { report != null && random.nextBoolean() },
                            random.nextBytes(32).takeIf { random.nextBoolean() }, random.text(40),
                        ),
                    )
                }
                else -> QrCodes.encodeRevocation(random.nextBytes(random.nextInt(1, 300)))
            }
            val input = if (round % 10 == 0) valid else random.mutateText(valid)
            val bytes = input.utf8()
            bounded("friend QR", bytes) { QrCodes.decodeFriend(input) }?.let {
                assertTrue(it.boxKey.size == 32 && it.signKey.size == 32)
            }
            bounded("staff QR", bytes) { QrCodes.decodeStaff(input) }?.let {
                assertTrue(it.boxSeed.size == 32 && it.signSeed.size == 32)
            }
            bounded("shift QR", bytes) { QrCodes.decodeShift(input) }?.let {
                assertTrue(it.keySeed.size == 32 && (it.reportSeed == null || it.reportKey != null))
            }
            bounded("revocation QR", bytes) { QrCodes.decodeRevocation(input) }
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
        val root = crypto.signKeyPair()
        val organiser = crypto.signKeyPair()
        val event = crypto.signKeyPair()
        val shiftKey = crypto.signKeyPair()
        val organiserCert = Certificate.issue(crypto, CertRole.ORGANISER, organiser.publicKey, 0, 0xFFFFFFFFL, "", root)
        val eventCert = Certificate.issue(crypto, CertRole.EVENT, event.publicKey, 0, 0xFFFFFFFFL, "", organiser)
        val chain = KeyChain(crypto, object : Clock {
            override fun wallMs() = 1_000L
            override fun monoMs() = 0L
        }, staffSign.publicKey, root.publicKey, listOf(organiserCert, eventCert))
        val shift = Certificate.issue(crypto, CertRole.CONTROL, shiftKey.publicKey, 0, 0xFFFFFFFFL, "Control", event)
        val certAck = staff.signAck(random.nextBytes(16), AckStatus.RESOLVED, 1L, "Control", StaffSecretKeys(null, shiftKey, shift)).encode()
        val revocation = Revocation.sign(crypto, event, 1L, listOf(shift.id)).encode()
        val notice = Notice.sign(crypto, Credential(shift, shiftKey), 1, 2, 1, 0, 3600, 3, "Bar", listOf("15:30")).encode()
        repeat(CRYPTO_ROUNDS) {
            val s = random.mutate(sealed)
            if (!s.contentEquals(sealed)) assertNull(bounded("sealed", s) { staff.openReport(s, secret) })
            val a = random.mutate(ack)
            if (!a.contentEquals(ack)) assertFalse(bounded("ack", a) { staff.verifyAck(a) })
            val c = random.mutate(certAck)
            if (!c.contentEquals(certAck)) assertFalse(bounded("certified ack", c) { chain.checkAck(c) == Verdict.VALID })
            val r = random.mutate(revocation)
            if (!r.contentEquals(revocation)) assertFalse(bounded("revocation", r) { chain.checkAnnouncement(r) == Verdict.VALID })
            val n = random.mutate(notice)
            if (!n.contentEquals(notice)) assertFalse(bounded("notice", n) { chain.checkAnnouncement(n) == Verdict.VALID })
            val b = random.mutate(boxed)
            if (!b.contentEquals(boxed)) assertNull(bounded("box", b) { crypto.boxOpen(b, alice.box.publicKey, bob.box.secretKey) })
        }
    }

    /** Counts the bytes [block] allocates; a [pure] block runs once before, so class loading isn't counted. */
    private fun <T> bounded(name: String, input: ByteArray, pure: Boolean = true, block: () -> T): T {
        val threads = allocationMeter ?: throw AssertionError("this JVM can't measure thread allocation, so the 32 KiB guard would pass anything")
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

        val allocationMeter = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
    }
}

/** Every known type, the reserved one and a few from a newer version, link-local or not. */
private val TYPES = intArrayOf(
    PacketType.HELLO, PacketType.SUMMARY, PacketType.REQUEST, PacketType.SUMMARY2, PacketType.REQUEST2,
    PacketType.INCIDENT_REPORT, PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL, PacketType.PRIVATE,
    PacketType.ANNOUNCEMENT, PacketType.TEST, 0x06, 0x55, 0xff,
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

private fun Random.hello(): Hello {
    val prefix = if (nextBoolean()) nextInt() else null
    val nonce = if (prefix != null && nextBoolean()) nextBytes(Hello.NONCE_SIZE) else null
    val proof = if (nonce != null && nextBoolean()) HelloProof(certificate(), nextBytes(64)) else null
    return Hello(nextInt(256), nonZeroLong(), nextInt(256), text(Limits.NICK_BYTES).ifEmpty { null }, prefix, nonce, proof)
}

private fun Random.certificate(role: Int = nextInt(256)) =
    Certificate(role, nextBytes(32), nextLong(0, 1L shl 32), nextLong(0, 1L shl 32), nextLong(), text(Limits.TEAM_BYTES), nextBytes(64))

private fun Random.summary() =
    List(nextInt(0, Limits.SUMMARY_ENTRIES + 1)) { SummaryEntry(nextLong(), nextBoolean(), nextInt(0, AckStatus.CANCELLED + 1)) }

private fun Random.request() = List(nextInt(0, Limits.SUMMARY_ENTRIES + 1)) { RequestEntry(nextLong(), nextBoolean(), nextBoolean()) }

/** Mostly incidents, the only kind 1.1 reads, with statuses from newer versions and, now and then, epochs. */
private fun Random.summary2(): Summary2 {
    if (nextInt(4) == 0) return Summary2(nextInt(256), emptyList())
    if (nextInt(4) == 0) {
        val kind = listOf(SummaryKind.RESPONSES, SummaryKind.UPDATES, SummaryKind.NOTICES).random(this)
        val max = if (SummaryKind.hasLongVersion(kind)) 1L shl 32 else 256L
        return Summary2(kind, emptyList(), List(nextInt(0, Summary2Codec.maxHeld(kind) + 1)) { HeldEntry(nextLong(), nextLong(0, max)) })
    }
    val epochs = nextInt(4) == 0
    val count = nextInt(0, (if (epochs) Limits.SUMMARY2_EPOCH_ENTRIES else Limits.SUMMARY_ENTRIES) + 1)
    val entries = List(count) { SummaryEntry(nextLong(), nextBoolean(), nextInt(0, 0x80), if (epochs) nextInt(0, 256) else 0) }
    return Summary2(SummaryKind.INCIDENTS, entries)
}

private fun Random.request2(): Request2 = when (nextInt(4)) {
    0 -> Request2(nextInt(256), emptyList())
    1 -> Request2(
        listOf(SummaryKind.RESPONSES, SummaryKind.UPDATES, SummaryKind.NOTICES).random(this), emptyList(),
        List(nextInt(0, Limits.SUMMARY_ENTRIES + 1)) { nextLong() },
    )
    else -> Request2(SummaryKind.INCIDENTS, request())
}

private fun Random.notice() = Notice(
    nextInt(1, 256), nextInt(256), nextInt(256), nextLong(0, 1L shl 32), nextLong(0, 1L shl 32), nextLong(0, 1L shl 32), nextInt(256),
    text(Limits.ZONE_BYTES), List(nextInt(0, Notice.MAX_PARAMS + 1)) { text(Notice.PARAM_BYTES) }, certificate(), nextBytes(64),
    if (nextInt(4) == 0) nextBytes(nextInt(0, 20)) else ByteArray(0),
)

private fun Random.response() = IncidentResponse(
    nextBytes(16), nextLong(0, 1L shl 32), nextBytes(nextInt(IncidentReportCodec.SEAL_OVERHEAD + 1, 256)),
    if (nextBoolean()) certificate() else null, nextBytes(64), if (nextInt(4) == 0) nextBytes(nextInt(0, 20)) else ByteArray(0),
)

private fun Random.updateBody(): UpdateBody {
    val coords = nextBoolean()
    return UpdateBody(
        nextInt(1, 256), nextLong(0, 1L shl 32), nextBytes(IncidentCancel.TOKEN_SIZE), if (nextBoolean()) text(Limits.ZONE_BYTES) else null,
        if (coords) coordinate(90) else null, if (coords) coordinate(180) else null,
        if (nextBoolean()) LocationFields(nextInt(0, 256), nextInt(0, 256)) else null,
    )
}

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
        triage = if (nextBoolean()) Triage(nextInt(0, 4), nextInt(0, 4)) else null,
        staff = if (nextBoolean()) StaffFields(nextInt(0, 256), nextBoolean()) else null,
        location = if (nextBoolean()) LocationFields(nextInt(0, 256), nextInt(0, 256)) else null,
        replyKey = if (nextBoolean()) nextBytes(IncidentBody.REPLY_KEY_SIZE) else null,
    )
}

/** Known statuses and, now and then, a newer version's status and fields. */
private fun Random.ack(): IncidentAck {
    val newer = nextInt(4) == 0
    return IncidentAck(
        nextBytes(16), if (newer) nextInt(1, 256) else nextInt(AckStatus.RECEIVED, AckStatus.CANCELLED + 1),
        nextLong(0, 1L shl 32), text(Limits.TEAM_BYTES), nextBytes(64), if (newer) nextBytes(nextInt(0, 40)) else ByteArray(0),
    )
}

private fun Random.heartbeat() = Heartbeat(
    batteryPercent = if (nextBoolean()) nextInt(0, 101) else null,
    charging = nextBoolean(),
    temperatureC = if (nextBoolean()) nextInt(-127, 128) else null,
    quietS = if (nextBoolean()) nextInt(0, Heartbeat.MAX_QUIET_S + 1) else null,
    links = nextInt(0, 256),
    crowd = nextInt(0, Heartbeat.CROWD.size),
    zone = text(Limits.ZONE_BYTES),
    mobile = if (nextBoolean()) {
        MobileReading(
            if (nextBoolean()) nextInt(0, 5) else null, nextInt(MobileReading.TECHNOLOGIES.size), nextInt(4),
            nextInt(MobileReading.PROBE_FAILED + 1), nextBoolean(),
        )
    } else {
        null
    },
).let { if (nextBoolean()) it.copy(column = nextInt(26), row = nextInt(99)) else it }

private fun Random.pointStatus() = PointStatus(
    nextInt(1, 256), nextInt(PointLevel.FREE, PointLevel.CLOSED + 1), nextLong(0, 1L shl 32), text(Limits.TEAM_BYTES),
    if (nextBoolean()) certificate() else null, nextBytes(64), if (nextInt(4) == 0) nextBytes(nextInt(0, 20)) else ByteArray(0),
)

private fun Random.inner(): Inner {
    val msgId = nextLong()
    val group = if (nextBoolean()) nextLong() else null
    return when (nextInt(12)) {
        0 -> Inner.Text(msgId, group, text(Limits.TEXT_BYTES))
        1 -> Inner.Quick(msgId, group, QuickCode.all.random(this))
        2 -> if (nextBoolean()) Inner.Zone(msgId, group, text(Limits.ZONE_BYTES), coordinate(90), coordinate(180))
        else Inner.Zone(msgId, group, text(Limits.ZONE_BYTES), null, null)
        3 -> Inner.Ping(msgId)
        4 -> Inner.Pong(msgId, nextLong())
        5 -> Inner.Delivered(msgId, nextLong())
        6 -> Inner.LeftGroup(msgId, nextLong())
        7 -> Inner.ZoneRequest(msgId)
        8 -> Inner.ZoneUpdate(msgId, text(Limits.ZONE_BYTES).ifEmpty { "z" })
        9 -> Inner.Position(
            if (nextBoolean()) PositionPoint.Grid(nextInt(4096), nextInt(4096)) else PositionPoint.Absolute(coordinate(90), coordinate(180)),
            nextInt(8), nextInt(4), nextInt(0x10000), nextInt(256), nextInt(256), nextInt(256),
        )
        10 -> Inner.ShareSession(nextInt(ShareAction.START, ShareAction.ACCEPT + 1), nextInt(0x10000), nextInt(3), nextInt(256))
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
    is Inner.ZoneUpdate -> listOf(inner.zone)
    is Inner.GroupInvite -> listOf(inner.name) + inner.members.map { it.nickname }
    else -> emptyList()
}

/** One of: random bytes, bit flips, truncation, trailing bytes, a boundary value (often a length), a cut or an insert. */
private fun Random.mutate(valid: ByteArray): ByteArray {
    if (valid.isEmpty()) return nextBytes(nextInt(0, 64))
    return when (nextInt(12)) {
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
