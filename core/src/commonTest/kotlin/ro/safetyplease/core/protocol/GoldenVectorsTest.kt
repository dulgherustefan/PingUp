package ro.safetyplease.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.KeyPair
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.Revocation
import ro.safetyplease.core.crypto.ShiftCard
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.crypto.cryptoWithRandom
import ro.safetyplease.core.crypto.genericHash
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.toLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden vectors for wire format v1, versions 1.0 and 1.1 (PROTOCOL.md, section 13). The bytes live in
 * src/commonTest/resources/golden/protocol-v1.txt; the inputs are built here from fixed seeds. Every vector is checked
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
        "inner.left_group" to { InnerCodec.encode(Golden.leftGroup).toHex() },
        "inner.zone_request" to { InnerCodec.encode(Golden.zoneRequest).toHex() },
        "inner.zone_update" to { InnerCodec.encode(Golden.zoneUpdate).toHex() },
        "inner.intro" to { InnerCodec.encode(Golden.intro).toHex() },
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
        "incident.body.location" to { Golden.locationBody.encode().toHex() },
        "key.root.sign" to { Golden.root.publicKey.toHex() },
        "cert.organiser" to { Golden.organiserCert.encode().toHex() },
        "cert.event" to { Golden.eventCert.encode().toHex() },
        "cert.staff" to { Golden.staffCert.encode().toHex() },
        "cert.staff.id" to { Golden.staffCert.id.toHex() },
        "cert.report_key" to { Golden.reportCert.encode().toHex() },
        "hello.auth.nonce" to { Golden.helloNonce.encode().toHex() },
        "hello.auth" to { Golden.helloAuth().encode().toHex() },
        "incident.ack.cert" to { Golden.certAck().encode().toHex() },
        "announcement.revocation" to { Golden.revocation().encode().toHex() },
        "qr.shift" to { QrCodes.encodeShift(Golden.shiftCard) },
        "qr.revocation" to { QrCodes.encodeRevocation(Golden.revocation().encode()) },
        "event.pack.hash" to { EventPacks.hashOf(Golden.PACK_BODY).toHex() },
        "event.pack.signature" to { Golden.packSignature().toHex() },
        "qr.event" to { QrCodes.encodeEventGate(EventPacks.hashOf(Golden.PACK_BODY)) },
        "frame.control" to { PacketCodec.encode(Golden.controlPacket).toHex() },
        "control.heartbeat" to { Golden.heartbeat.encode().toHex() },
        "control.heartbeat.unknown" to { Golden.unknownHeartbeat.encode().toHex() },
        "control.heartbeat.extended" to { Golden.extendedHeartbeat().toHex() },
        "control.heartbeat.site" to { Golden.siteHeartbeat.encode().toHex() },
        "control.point" to { Golden.point(null).encode().toHex() },
        "control.point.cert" to { Golden.point(Golden.staffCert).encode().toHex() },
        "incident.body.reply" to { Golden.replyBody.encode().toHex() },
        "response.body" to { Golden.responseBody.encode().toHex() },
        "incident.response" to { Golden.response(null).encode().toHex() },
        "cert.control" to { Golden.controlCert.encode().toHex() },
        "incident.response.cert" to { Golden.response(Golden.controlCert).encode().toHex() },
        "update.body" to { Golden.updateBody.encode().toHex() },
        "incident.update" to { Golden.update().toHex() },
        "frame.update" to { PacketCodec.encode(Golden.updatePacket()).toHex() },
        "summary2.responses" to { Summary2Codec.encode(Golden.summary2Responses).toHex() },
        "summary2.updates" to { Summary2Codec.encode(Golden.summary2Updates).toHex() },
        "request2.responses" to { Request2Codec.encode(Golden.request2Responses).toHex() },
        "request2.updates" to { Request2Codec.encode(Golden.request2Updates).toHex() },
        "announcement.notice" to { Golden.notice().encode().toHex() },
        "announcement.notice.cancel" to { Golden.noticeCancel().encode().toHex() },
        "summary2.notices" to { Summary2Codec.encode(Golden.summary2Notices).toHex() },
        "request2.notices" to { Request2Codec.encode(Golden.request2Notices).toHex() },
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
        assertEquals(Golden.lines.size, vectors.size, "duplicate names")
        assertEquals(encoders.keys, vectors.keys)
    }

    @Test
    fun controlChannelDecodes() {
        val frame = PacketCodec.decode(bytes("frame.control"))!!.packet
        assertEquals(Golden.controlPacket.fields(), frame.fields())
        assertEquals(ControlChannel.ID, frame.recipient)
        assertEquals(Golden.heartbeat, Heartbeat.decode(bytes("control.heartbeat")))
        assertEquals(Golden.unknownHeartbeat, Heartbeat.decode(bytes("control.heartbeat.unknown")))
        assertEquals(Golden.heartbeat, Heartbeat.decode(bytes("control.heartbeat.extended")), "unknown flag bits and what follows the zone are skipped")
        assertEquals(Golden.siteHeartbeat, Heartbeat.decode(bytes("control.heartbeat.site")))
        assertEquals(bytes("control.heartbeat").size + Heartbeat.SITE_BYTES, bytes("control.heartbeat.site").size, "the site block adds 5 bytes")

        val chain = Golden.chain()
        for ((name, team) in listOf("control.point" to "Medical 1", "control.point.cert" to "Medical 1")) {
            val plain = bytes(name)
            assertEquals(Verdict.VALID, chain.checkPoint(plain), name)
            val status = PointStatus.decode(plain)!!
            assertEquals(listOf(5, PointLevel.BUSY, Golden.TIME + 60, team), listOf(status.point, status.level, status.issued, status.teamName))
            assertNull(Heartbeat.decode(plain), "a heartbeat it isn't")
            val tampered = plain.copyOf().also { it[3] = PointLevel.CLOSED.toByte() }
            assertEquals(Verdict.INVALID, chain.checkPoint(tampered), name)
        }
        assertEquals(Golden.staffCert.fields(), PointStatus.decode(bytes("control.point.cert"))!!.certificate!!.fields())
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
        assertContentEquals(bytes("incident.cancelHash"), IncidentBody.decode(bytes("incident.body"))!!.cancelHash)

        val report = bytes("incident.report")
        assertTrue(IncidentReportCodec.isWellFormed(report))
        assertContentEquals(Golden.INCIDENT_ID, IncidentReportCodec.incidentId(report))
        val plain = Golden.staff.openReport(IncidentReportCodec.sealed(report), Golden.staffSecret)!!
        assertContentEquals(bytes("incident.body"), plain)

        val ack = bytes("incident.ack")
        assertTrue(Golden.staff.verifyAck(ack))
        assertContentEquals(bytes("incident.ack.signed"), IncidentAck.signedMessage(IncidentAck.unsignedPart(ack)))
        assertEquals(Golden.ack().fields(), IncidentAck.decode(ack)!!.fields())

        val cancel = IncidentCancel.decode(bytes("incident.cancel"))!!
        assertContentEquals(Golden.INCIDENT_ID, cancel.incidentId)
        assertContentEquals(Golden.TOKEN, cancel.token)
        assertContentEquals(bytes("incident.cancelHash"), IncidentCancel.hash(cancel.token))
    }

    @Test
    fun chatPayloadsDecode() {
        val inners = mapOf(
            "inner.text" to Golden.text, "inner.text.group" to Golden.groupText, "inner.quick" to Golden.quick,
            "inner.zone" to Golden.zone, "inner.zone.plain" to Golden.plainZone, "inner.ping" to Golden.ping,
            "inner.pong" to Golden.pong, "inner.delivered" to Golden.delivered, "inner.invite" to Golden.invite,
            "inner.left_group" to Golden.leftGroup, "inner.zone_request" to Golden.zoneRequest, "inner.zone_update" to Golden.zoneUpdate,
            "inner.intro" to Golden.intro,
        )
        for ((name, inner) in inners) assertEquals(inner.fields(), InnerCodec.decode(bytes(name))!!.fields(), name)
        val intro = InnerCodec.decode(bytes("inner.intro")) as Inner.Intro
        val signed = InnerCodec.introSignedBytes(Golden.bob.nodeId, intro.msgId, intro.boxKey, intro.signKey, intro.nickname)
        assertTrue(Golden.crypto.verify(intro.signature, signed, bytes("key.alice.sign")), "alice signed it for bob")

        val opened = Golden.crypto.boxOpen(bytes("private.box"), Golden.alice.box.publicKey, Golden.bob.box.secretKey)!!
        assertContentEquals(bytes("inner.text"), opened)
        assertContentEquals(bytes("private.box"), PacketCodec.decode(bytes("frame.private"))!!.packet.payload)
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
        assertEquals(opaque.dedupKey, relayed.dedupKey, "only the ttl changes")

        assertEquals(Golden.body.fields(), IncidentBody.decode(bytes("incident.body.extended"))!!.fields())

        val future = bytes("incident.ack.future")
        assertTrue(Golden.staff.verifyAck(future), "the signature covers the fields 1.1 doesn't read")
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
        assertFalse(AckStatus.isKnown(newer.status), "kept as a number, with no place in the order")
        assertEquals(-1, AckStatus.rank(newer.status))

        val assigned = bytes("incident.ack.assigned")
        assertTrue(Golden.staff.verifyAck(assigned))
        val decoded = IncidentAck.decode(assigned)!!
        assertEquals(Golden.assignedAck().fields(), decoded.fields())
        assertTrue(decoded.assigned)
        assertEquals("01", decoded.extensions.toHex())
        val packet = Packet(PacketType.INCIDENT_ACK, 7, 1L, 2L, Golden.TIME, null, false, assigned)
        assertFalse(Compat10.accepts(packet), "never to a 1.0 phone")

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
            assertTrue(Golden.staff.verifyAck(payload), name)
            val ack = IncidentAck.decode(payload)!!
            assertEquals(status, ack.status, name)
            val packet = Packet(PacketType.INCIDENT_ACK, 7, 1L, 2L, Golden.TIME, null, false, payload)
            assertFalse(Compat10.accepts(packet), "$name never goes to a 1.0 phone")
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
        val location = IncidentBody.decode(bytes("incident.body.location"))!!
        assertEquals(Golden.locationBody.fields(), location.fields())
        assertEquals(LocationFields(accuracyM = 12, landmark = 4), location.location)

        val epochs = Summary2Codec.decode(bytes("summary2.epochs"))!!
        assertEquals(Golden.summary2Epochs, epochs)
        assertEquals(listOf(1, 0), epochs.entries.map { it.epoch })
    }

    @Test
    fun version10ReadsWhatItDidAndRejectsOnlyWhatOneOneKeepsFromIt() {
        for (name in listOf("frame.test", "frame.private", "frame.fragment.0")) assertNotNull(Frozen10.frame(bytes(name)), name)
        assertNotNull(Frozen10.hello(bytes("hello")))
        assertNotNull(Frozen10.hello(bytes("hello.v11")), "a 1.1 HELLO is a valid 1.0 HELLO")
        assertNotNull(Frozen10.summary(bytes("summary")))
        assertNotNull(Frozen10.request(bytes("request")))
        assertNotNull(Frozen10.ack(bytes("incident.ack")))
        assertNotNull(Frozen10.body(bytes("incident.body")))

        assertNull(Frozen10.hello(bytes("hello.extended")))
        assertNull(Frozen10.hello(bytes("hello.prefix")))
        assertNull(Frozen10.hello(bytes("hello.auth.nonce")))
        assertNull(Frozen10.hello(bytes("hello.auth")))
        assertNull(Frozen10.ack(bytes("incident.ack.cert")))
        assertNull(Frozen10.frame(bytes("frame.opaque")))
        assertNull(Frozen10.ack(bytes("incident.ack.future")))
        assertNull(Frozen10.ack(bytes("incident.ack.assigned")))
        assertNull(Frozen10.body(bytes("incident.body.extended")))
        for (name in listOf("on_scene", "handed_over", "closed", "unfounded", "reopened", "assigned.epoch", "unknown")) {
            assertNull(Frozen10.ack(bytes("incident.ack.$name")), name)
        }
        for (name in listOf("triage", "staff", "lost_friend", "location", "reply")) assertNull(Frozen10.body(bytes("incident.body.$name")), name)
        assertNull(Frozen10.frame(bytes("frame.update")))
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
        for ((name, bytes) in frozen) assertEquals(vectors[name], bytes.toHex(), name)
    }

    @Test
    fun keyChainVectorsDecodeAndVerify() {
        val chain = Golden.chain()
        val organiser = Certificate.decode(bytes("cert.organiser"))!!
        val event = Certificate.decode(bytes("cert.event"))!!
        val staff = Certificate.decode(bytes("cert.staff"))!!
        val report = Certificate.decode(bytes("cert.report_key"))!!
        assertEquals(Golden.organiserCert.fields(), organiser.fields())
        assertEquals(Golden.eventCert.fields(), event.fields())
        assertEquals(Golden.staffCert.fields(), staff.fields())
        assertEquals(Golden.reportCert.fields(), report.fields())
        assertEquals(vectors["cert.staff.id"], staff.id.toHex())
        assertEquals(Certificate.keyId(bytes("key.root.sign")), organiser.issuer)
        for (cert in listOf(organiser, event, staff, report)) assertEquals(CertStatus.VALID, chain.status(cert, Golden.TIME))

        val nonce = Hello.decode(bytes("hello.auth.nonce"))!!
        assertContentEquals(Golden.ALICE_NONCE, nonce.nonce)
        assertNull(nonce.proof)
        val auth = Hello.decode(bytes("hello.auth"))!!
        assertEquals(Golden.helloAuth().fields(), auth.fields())
        assertContentEquals(Golden.ALICE_NONCE, auth.nonce)
        assertEquals(NodeFlags.STAFF, chain.provenRoles(auth.proof!!, Golden.BOB_NONCE, Golden.alice.nodeId))

        val ack = IncidentAck.decode(bytes("incident.ack.cert"))!!
        assertEquals(Golden.certAck().fields(), ack.fields())
        assertEquals(staff.fields(), ack.certificate!!.fields())
        assertEquals(Verdict.VALID, chain.checkAck(bytes("incident.ack.cert")))

        val revocation = Revocation.decode(bytes("announcement.revocation"))!!
        assertEquals(listOf(staff.id, Golden.long("0102030405060708")), revocation.ids)
        assertEquals(Verdict.VALID, chain.checkAnnouncement(bytes("announcement.revocation")))
        assertContentEquals(bytes("announcement.revocation"), QrCodes.decodeRevocation(vectors.getValue("qr.revocation")))

        val card = QrCodes.decodeShift(vectors.getValue("qr.shift"))!!
        assertEquals(staff.fields(), card.certificate.fields())
        assertEquals(report.fields(), card.reportKey!!.fields())
        assertEquals(Golden.seed(0x80).toHex(), card.keySeed.toHex())
        assertEquals(Golden.seed(0xc0).toHex(), card.reportSeed!!.toHex())
        assertNotNull(Golden.staff.credentialFrom(card)!!.second!!.box)
    }

    @Test
    fun eventPackVectorsVerify() {
        val check = EventPacks.check(Golden.pack(), Golden.crypto, bytes("key.root.sign"), Golden.TIME)
        assertEquals(PackStatus.VALID, check.status)
        assertContentEquals(bytes("event.pack.hash"), check.pack!!.hash)
        assertContentEquals(bytes("event.pack.hash"), QrCodes.decodeEventGate(vectors.getValue("qr.event")))
        assertTrue(Golden.crypto.verify(bytes("event.pack.signature"), EventPacks.signedMessage(Golden.PACK_BODY), bytes("cert.organiser").copyOfRange(2, 34)))
    }

    @Test
    fun responsesAndUpdatesDecodeOpenAndVerify() {
        val body = IncidentBody.decode(bytes("incident.body.reply"))!!
        assertEquals(Golden.replyBody.fields(), body.fields())
        assertContentEquals(Golden.reply.publicKey, body.replyKey)

        val chain = Golden.chain()
        for (name in listOf("incident.response", "incident.response.cert")) {
            val payload = bytes(name)
            assertEquals(Verdict.VALID, chain.checkResponse(payload), name)
            val response = IncidentResponse.decode(payload)!!
            assertContentEquals(Golden.INCIDENT_ID, response.incidentId)
            assertEquals(Golden.TIME + 120, response.timestamp)
            val plain = Golden.crypto.sealOpen(response.sealed, Golden.reply.publicKey, Golden.reply.secretKey)!!
            assertContentEquals(bytes("response.body"), plain)
            assertEquals(Golden.responseBody, ResponseBody.decode(plain))
            val tampered = payload.copyOf().also { it[INCIDENT_ID_SIZE + 3] = (it[INCIDENT_ID_SIZE + 3] + 1).toByte() }
            assertEquals(Verdict.INVALID, chain.checkResponse(tampered), name)
        }
        assertEquals(Golden.controlCert.fields(), IncidentResponse.decode(bytes("incident.response.cert"))!!.certificate!!.fields())
        assertEquals(CertStatus.VALID, chain.status(Certificate.decode(bytes("cert.control"))!!, Golden.TIME))
        val byATeam = Golden.response(Golden.staffCert, Golden.shift).encode()
        assertEquals(Verdict.REFUSED, chain.checkResponse(byATeam), "only a control shift answers reporters")

        val update = bytes("incident.update")
        assertTrue(IncidentUpdateCodec.isWellFormed(update))
        assertContentEquals(Golden.INCIDENT_ID, IncidentUpdateCodec.incidentId(update))
        assertEquals(1, IncidentUpdateCodec.seq(update))
        val opened = Golden.staff.openReport(IncidentUpdateCodec.sealed(update), Golden.staffSecret)!!
        assertContentEquals(bytes("update.body"), opened)
        val decoded = UpdateBody.decode(opened)!!
        assertContentEquals(bytes("incident.cancelHash"), IncidentCancel.hash(decoded.token), "the author's token")
        assertEquals(listOf(UpdateKind.MOVED, "Bar", LocationFields(9, 4)), listOf(decoded.kind, decoded.zone, decoded.location))
        val frame = PacketCodec.decode(bytes("frame.update"))!!.packet
        assertEquals(Golden.updatePacket().fields(), frame.fields())
        assertFalse(Compat10.accepts(frame), "never to a 1.0 phone")

        assertEquals(Golden.summary2Responses, Summary2Codec.decode(bytes("summary2.responses")))
        assertEquals(Golden.summary2Updates, Summary2Codec.decode(bytes("summary2.updates")))
        assertEquals(Golden.request2Responses, Request2Codec.decode(bytes("request2.responses")))
        assertEquals(Golden.request2Updates, Request2Codec.decode(bytes("request2.updates")))
    }

    @Test
    fun noticesDecodeAndVerify() {
        val chain = Golden.chain()
        val payload = bytes("announcement.notice")
        assertEquals(Verdict.VALID, chain.checkAnnouncement(payload))
        val notice = Notice.decode(payload)!!
        assertEquals(
            listOf(NoticeTopic.ALERT, NoticeLevel.PAUSE, 0, 1L, Golden.TIME + 60, Golden.TIME + 3660, 3, "Scena mare", listOf("15:30")),
            listOf(notice.topic, notice.level, notice.flags, notice.seq, notice.issued, notice.validUntil, notice.template, notice.zone, notice.params),
        )
        assertEquals(Golden.controlCert.fields(), notice.certificate.fields())
        assertContentEquals(payload, notice.encode(), "re-encodes")
        val tampered = payload.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertEquals(Verdict.INVALID, chain.checkAnnouncement(tampered))
        assertNull(chain.revoke(payload), "not a revocation")

        val cancel = Notice.decode(bytes("announcement.notice.cancel"))!!
        assertTrue(cancel.cancelled)
        assertEquals(listOf(2L, 0, emptyList<String>()), listOf(cancel.seq, cancel.template, cancel.params))
        assertEquals(Verdict.VALID, chain.checkAnnouncement(bytes("announcement.notice.cancel")))

        val byATeam = Golden.notice(Credential(Golden.staffCert, Golden.shift)).encode()
        assertEquals(Verdict.REFUSED, chain.checkAnnouncement(byATeam), "only a control shift sends notices")
        val frame = Packet(PacketType.ANNOUNCEMENT, 7, Golden.long("0123456789abcdef"), Golden.long("0102030405060708"), Golden.TIME, null, false, payload)
        assertFalse(Compat10.accepts(frame), "never to a 1.0 phone")

        assertEquals(Golden.summary2Notices, Summary2Codec.decode(bytes("summary2.notices")))
        assertEquals(Golden.request2Notices, Request2Codec.decode(bytes("request2.notices")))
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

    fun long(hex: String) = hex.hexToBytes().toLong()

    val lines: List<String> = repoFile("core/src/commonTest/resources/golden/protocol-v1.txt").readText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val vectors: Map<String, String> = lines.associate { it.substringBefore(' ') to it.substringAfter(' ').trim() }

    // The only randomness libsodium takes is the crypto_box nonce; fixing it makes box() deterministic.
    val crypto = cryptoWithRandom { size ->
        check(size == NONCE.size)
        NONCE.copyOf()
    }

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
    fun report(): ByteArray = IncidentReportCodec.encode(INCIDENT_ID, seal(body.encode(), staffPublic.box))

    private fun seal(plain: ByteArray, to: ByteArray): ByteArray {
        val ephemeral = crypto.boxKeyPairFromSeed(seed(0xd0))
        val nonce = genericHash(ephemeral.publicKey + to, 24)
        val boxed = cryptoWithRandom { nonce.copyOf() }.box(plain, to, ephemeral.secretKey)
        return ephemeral.publicKey + boxed.copyOfRange(nonce.size, boxed.size)
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

    /** [triageBody] with the fix's accuracy, 12 m, and landmark 4 of the site plan. */
    val locationBody = IncidentBody(
        IncidentCategory.MEDICAL, Severity.URGENT, TIME, "Scena mare", 44.426765, 26.102538,
        "Persoană căzută lângă gard", "Ana", IncidentCancel.hash(TOKEN), Triage(Answer.NO, Answer.UNKNOWN),
        location = LocationFields(accuracyM = 12, landmark = 4),
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
    val leftGroup = Inner.LeftGroup(msgId, groupId)
    val zoneRequest = Inner.ZoneRequest(msgId)
    val zoneUpdate = Inner.ZoneUpdate(msgId, "bar")
    val intro = Inner.Intro(
        msgId, alice.box.publicKey, alice.sign.publicKey, "Ana",
        crypto.sign(InnerCodec.introSignedBytes(bob.nodeId, msgId, alice.box.publicKey, alice.sign.publicKey, "Ana"), alice.sign.secretKey),
    )
    val invite = Inner.GroupInvite(
        msgId, groupId, "Prietenii",
        listOf(
            GroupMemberWire(bob.nodeId, bob.box.publicKey, "Bob"),
            GroupMemberWire(long("3132333435363738"), seed(0x40), "Cora"),
        ),
    )

    fun boxed(): ByteArray = crypto.box(InnerCodec.encode(text), bob.box.publicKey, alice.box.secretKey)

    val testPacket = Packet(
        PacketType.TEST, 7, long("0123456789abcdef"), long("f0e1d2c3b4a59687"), TIME, null, false, "ping".encodeToByteArray(),
    )
    val privatePacket = Packet(PacketType.PRIVATE, 5, long("0123456789abcdef"), alice.nodeId, TIME, bob.nodeId, true, boxed())
    val opaquePacket = Packet(0x55, 7, long("0123456789abcdef"), alice.nodeId, TIME, null, false, "future".encodeToByteArray())

    /** The payload stands for a sealed heartbeat, which is random. */
    val controlPacket = Packet(PacketType.PRIVATE, 6, long("0123456789abcdef"), alice.nodeId, TIME, ControlChannel.ID, true, ByteArray(60) { it.toByte() })
    val heartbeat = Heartbeat(batteryPercent = 78, charging = true, temperatureC = -5, quietS = 300, links = 3, crowd = 4, zone = "B2")
    val unknownHeartbeat = Heartbeat(batteryPercent = null, charging = false, temperatureC = null, quietS = null, links = 0, crowd = 0, zone = "")
    fun extendedHeartbeat(): ByteArray = heartbeat.encode().also { it[1] = (it[1].toInt() or 0x80).toByte() } + EXTRA

    /** [heartbeat] from square C4 with the mobile network: 2 bars of LTE, mobile data, validated, the probe answered. */
    val siteHeartbeat = heartbeat.copy(
        mobile = MobileReading(2, MobileReading.TECHNOLOGIES.indexOf("lte"), MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_OK, true),
        column = 2,
        row = 3,
    )

    /** Point 5 is busy, said by "Medical 1" at TIME + 60: with the build's staff key, or under [cert] with the shift key. */
    fun point(cert: Certificate?): PointStatus =
        staff.signPoint(5, PointLevel.BUSY, TIME + 60, "Medical 1", if (cert == null) staffSecret else StaffSecretKeys(null, shift, cert))

    /** 60 bytes with a recipient at the smallest frame (64): chunks of 26, 26 and 8. */
    val fragmented = Packet(
        PacketType.PRIVATE, 7, long("0011223344556677"), alice.nodeId, TIME, bob.nodeId, true, ByteArray(60) { it.toByte() },
    )

    fun fragments(): List<ByteArray> = PacketCodec.toFrames(fragmented, PacketCodec.MIN_FRAME, FRAG_ID)

    val friendCard = FriendCard("Ana", alice.box.publicKey, alice.sign.publicKey)

    // The key chain: root seed(0x20), organiser seed(0x40), event seed(0x60), a shift of "Medical 1" seed(0x80), the
    // day's report key seed(0xc0) (X25519).
    val root = crypto.signKeyPairFromSeed(seed(0x20))
    private val organiser = crypto.signKeyPairFromSeed(seed(0x40))
    private val event = crypto.signKeyPairFromSeed(seed(0x60))
    val shift = crypto.signKeyPairFromSeed(seed(0x80))
    val organiserCert = Certificate.issue(crypto, CertRole.ORGANISER, organiser.publicKey, TIME - 86_400, TIME + 365 * 86_400, "Fest SRL", root)
    val eventCert = Certificate.issue(crypto, CertRole.EVENT, event.publicKey, TIME - 3600, TIME + 3 * 86_400, "Fest 2025", organiser)
    val staffCert = Certificate.issue(crypto, CertRole.STAFF, shift.publicKey, TIME, TIME + 12 * 3600, "Medical 1", event)
    val reportCert = Certificate.issue(
        crypto, CertRole.REPORT_KEY, crypto.boxKeyPairFromSeed(seed(0xc0)).publicKey, TIME - 3600, TIME + 86_400 - 3600, "", event,
    )

    /** The chain as an app that ships the root, the organiser and the event sees it, at TIME + 60. */
    fun chain() = KeyChain(
        crypto,
        object : Clock {
            override fun wallMs() = (TIME + 60) * 1000
            override fun monoMs() = 0L
        },
        staffPublic.sign, root.publicKey, listOf(organiserCert, eventCert), listOf(reportCert),
    )

    val ALICE_NONCE = ByteArray(Hello.NONCE_SIZE) { (0x40 + it).toByte() }
    val BOB_NONCE = ByteArray(Hello.NONCE_SIZE) { (0x60 + it).toByte() }
    private const val AUTH_FLAGS = NodeFlags.STAFF or NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.PROTOCOL_1_1 or
        NodeFlags.PREFIX_IN_HELLO or NodeFlags.HELLO_AUTH

    val helloNonce = Hello(PacketCodec.VERSION, alice.nodeId, AUTH_FLAGS, null, 0x1a2b3c4d, ALICE_NONCE)

    /** Alice's answer to bob's nonce with the "Medical 1" shift. */
    fun helloAuth(): Hello {
        val signature = crypto.sign(Hello.proofMessage(BOB_NONCE, alice.nodeId), shift.secretKey)
        return Hello(PacketCodec.VERSION, alice.nodeId, AUTH_FLAGS, null, 0x1a2b3c4d, ALICE_NONCE, HelloProof(staffCert, signature))
    }

    fun certAck(): IncidentAck =
        staff.signAck(INCIDENT_ID, AckStatus.ACKNOWLEDGED, TIME + 60, "Medical 1", StaffSecretKeys(null, shift, staffCert))

    fun revocation(): Revocation = Revocation.sign(crypto, event, TIME + 600, listOf(staffCert.id, long("0102030405060708")))

    // The reporter's reply key seed(0xe0) (X25519) and a control shift "Control", seed(0xa0), for responses.
    val reply = crypto.boxKeyPairFromSeed(seed(0xe0))
    private val control = crypto.signKeyPairFromSeed(seed(0xa0))
    val controlCert = Certificate.issue(crypto, CertRole.CONTROL, control.publicKey, TIME, TIME + 12 * 3600, "Control", event)

    /** [locationBody] with the reply key. */
    val replyBody = IncidentBody(
        IncidentCategory.MEDICAL, Severity.URGENT, TIME, "Scena mare", 44.426765, 26.102538,
        "Persoană căzută lângă gard", "Ana", IncidentCancel.hash(TOKEN), Triage(Answer.NO, Answer.UNKNOWN),
        location = LocationFields(accuracyM = 12, landmark = 4), replyKey = reply.publicKey,
    )

    val responseBody = ResponseBody(ResponseCode.GO_TO_LANDMARK, 4)

    /** [responseBody] sealed to the reply key at TIME + 120: with the build's staff key, or under [cert] with [key]. */
    fun response(cert: Certificate?, key: KeyPair = control): IncidentResponse {
        val sealed = seal(responseBody.encode(), reply.publicKey)
        val unsigned = IncidentResponse.unsigned(INCIDENT_ID, TIME + 120, sealed, cert)
        val signer = if (cert == null) staffSecret.sign.secretKey else key.secretKey
        return IncidentResponse(INCIDENT_ID, TIME + 120, sealed, cert, crypto.sign(IncidentResponse.signedMessage(unsigned), signer))
    }

    /** The reporter moved to "Bar", 9 m accuracy, landmark 4, at TIME + 180, with the report's cancel token. */
    val updateBody = UpdateBody(UpdateKind.MOVED, TIME + 180, TOKEN, "Bar", 44.4268, 26.1027, LocationFields(9, 4))

    fun update(): ByteArray = IncidentUpdateCodec.encode(INCIDENT_ID, 1, seal(updateBody.encode(), staffPublic.box))

    fun updatePacket() = Packet(PacketType.INCIDENT_UPDATE, 7, long("0123456789abcdef"), 0L, TIME + 180, null, true, update())

    val summary2Responses = Summary2(SummaryKind.RESPONSES, emptyList(), listOf(HeldEntry(INCIDENT_ID.toLong(), TIME + 120)))
    val summary2Updates = Summary2(
        SummaryKind.UPDATES, emptyList(), listOf(HeldEntry(INCIDENT_ID.toLong(), 1), HeldEntry(long("0102030405060708"), 3)),
    )
    val request2Responses = Request2(SummaryKind.RESPONSES, emptyList(), listOf(INCIDENT_ID.toLong()))
    val request2Updates = Request2(SummaryKind.UPDATES, emptyList(), listOf(INCIDENT_ID.toLong(), long("0102030405060708")))

    /** A pause on "Scena mare" from TIME + 60 for an hour: template 3 with "15:30", sequence 1, under [controlCert]. */
    fun notice(signer: Credential = Credential(controlCert, control)): Notice = Notice.sign(
        crypto, signer, NoticeTopic.ALERT, NoticeLevel.PAUSE, 1, TIME + 60, TIME + 3660, 3, "Scena mare", listOf("15:30"),
    )

    /** The same pause withdrawn at TIME + 120: sequence 2, no template. */
    fun noticeCancel(): Notice = Notice.sign(
        crypto, Credential(controlCert, control), NoticeTopic.ALERT, NoticeLevel.PAUSE, 2, TIME + 120, TIME + 3660, 0, flags = Notice.CANCEL,
    )

    val summary2Notices = Summary2(SummaryKind.NOTICES, emptyList(), listOf(HeldEntry(1, 2), HeldEntry(2, 5)))
    val request2Notices = Request2(SummaryKind.NOTICES, emptyList(), listOf(1L, 2L))

    val shiftCard = ShiftCard(staffCert, seed(0x80), reportCert, seed(0xc0), null, "")

    /** A real event pack for "Fest 2025" with the chain above and an empty venue; the organiser signs it. */
    val PACK_BODY = """{"id":"fest-2025","version":1,"kind":"real","name":"Fest 2025","validFrom":${TIME - 3600},""" +
        """"validUntil":${TIME + 3 * 86_400},"organiser":"${organiserCert.encode().toHex()}","event":"${eventCert.encode().toHex()}",""" +
        """"reportKeys":["${reportCert.encode().toHex()}"],""" +
        """"venue":{"name":"Fest","bounds":{"minLat":44.0,"minLon":26.0,"maxLat":44.01,"maxLon":26.01},"zones":[]}}"""

    fun packSignature(): ByteArray = crypto.sign(EventPacks.signedMessage(PACK_BODY), organiser.secretKey)

    fun pack(): String =
        """{"format":"pingup-event","version":1,"body":${JsonPrimitive(PACK_BODY)},"signature":"${packSignature().toHex()}"}"""
    val staffCard = StaffCard(seed(0x10), seed(0x30), StaffRole.STAFF, "Medical 1", "")
}

private fun Packet.fields() = listOf(type, ttl, id, sender, timestamp, recipient, encrypted, payload.toHex())

private fun Hello.fields() =
    listOf(version, nodeId, flags, nickname, prefix, nonce?.toHex(), proof?.certificate?.encode()?.toHex(), proof?.signature?.toHex())

private fun Certificate.fields() = listOf(role, key.toHex(), validFrom, validUntil, issuer, name, signature.toHex())

private fun IncidentBody.fields() =
    listOf(category, severity, timestamp, zone, lat, lon, description, nickname, cancelHash?.toHex(), triage, staff, location, replyKey?.toHex())

private fun IncidentAck.fields() = listOf(incidentId.toHex(), status, timestamp, teamName, signature.toHex(), extensions.toHex())

private fun Inner.fields(): List<Any?> = when (this) {
    is Inner.Text -> listOf("text", msgId, groupId, text)
    is Inner.Quick -> listOf("quick", msgId, groupId, code)
    is Inner.Zone -> listOf("zone", msgId, groupId, zone, lat, lon)
    is Inner.Ping -> listOf("ping", msgId)
    is Inner.Pong -> listOf("pong", msgId, pingId)
    is Inner.Delivered -> listOf("delivered", msgId, deliveredId)
    is Inner.LeftGroup -> listOf("left_group", msgId, groupId)
    is Inner.ZoneRequest -> listOf("zone_request", msgId)
    is Inner.ZoneUpdate -> listOf("zone_update", msgId, zone)
    is Inner.Intro -> listOf("intro", msgId, boxKey.toHex(), signKey.toHex(), nickname, signature.toHex())
    is Inner.GroupInvite ->
        listOf("invite", msgId, groupId, name, members.map { listOf(it.nodeId, it.boxKey.toHex(), it.nickname) })
}

private fun FriendCard.fields() = listOf(nickname, boxKey.toHex(), signKey.toHex())

private fun StaffCard.fields() = listOf(boxSeed.toHex(), signSeed.toHex(), role, teamName, zone)
