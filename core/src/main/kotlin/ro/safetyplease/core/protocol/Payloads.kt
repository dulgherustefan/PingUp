package ro.safetyplease.core.protocol

import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.utf8
import kotlin.math.ceil
import kotlin.math.roundToInt

object NodeFlags {
    const val STAFF = 0x01
    const val ANCHOR = 0x02

    /** Set by older versions while their report was pending. No longer sent or read; kept so the bit isn't reused. */
    const val PENDING_INCIDENT = 0x04
    const val ACCEPTS_CONNECTIONS = 0x08

    /** Speaks protocol 1.1: tolerates what it doesn't know and reads SUMMARY2 and REQUEST2. */
    const val PROTOCOL_1_1 = 0x10

    /**
     * Advertises a random prefix that rotates, not its nodeId's, and gives it in HELLO to the peers that set this bit
     * too, so they can tell its advertising from a new peer's.
     */
    const val PREFIX_IN_HELLO = 0x20

    /**
     * Reads the authentication fields after the prefix in HELLO and ACKs signed under a certificate: the nonce, and a
     * certificate with a signature over the peer's nonce (PROTOCOL.md, section 6.1).
     */
    const val HELLO_AUTH = 0x40

    /**
     * Advertising only (1.1): a phone keeps a slot for an anchor's session even when it takes no other connections,
     * and closes a link on it whose HELLO isn't an anchor's.
     */
    const val ANCHOR_SLOT = 0x80
}

object Limits {
    const val NICK_BYTES = 24
    const val ZONE_BYTES = 32
    const val DESCRIPTION_CHARS = 140
    const val DESCRIPTION_BYTES = 300
    const val TEAM_BYTES = 24
    const val TEXT_BYTES = 400
    const val GROUP_NAME_BYTES = 32
    const val GROUP_NICK_BYTES = 16
    const val GROUP_MAX_MEMBERS = 8
    const val SUMMARY_ENTRIES = 50

    /** A SUMMARY2 with epochs: 2 + 47 × 10 + 1 bytes still fit in a payload. */
    const val SUMMARY2_EPOCH_ENTRIES = 47

    /** A SUMMARY2 of responses or notices: 2 + 39 × 12 bytes. */
    const val SUMMARY2_RESPONSE_ENTRIES = 39
}

private fun WireWriter.coord(deg: Double) = i32((deg * 1e6).roundToInt())
private fun WireReader.coord(): Double = i32() / 1e6

/** A certificate and its key's signature over the receiver's nonce on this link ([Hello.proofMessage]). */
class HelloProof(val certificate: Certificate, val signature: ByteArray)

/**
 * [prefix] is the one the sender advertises; only peers that set [NodeFlags.PREFIX_IN_HELLO] get it. [nonce] and
 * [proof] follow it, only to peers that set [NodeFlags.HELLO_AUTH]: the sender's challenge on this link, and its
 * answer to the peer's.
 */
class Hello(
    val version: Int,
    val nodeId: Long,
    val flags: Int,
    val nickname: String?,
    val prefix: Int? = null,
    val nonce: ByteArray? = null,
    val proof: HelloProof? = null,
) {
    fun encode(): ByteArray {
        val nick = nickname?.utf8() ?: ByteArray(0)
        require(nick.size <= Limits.NICK_BYTES)
        require(nonce == null || (prefix != null && nonce.size == NONCE_SIZE))
        require(proof == null || nonce != null)
        val w = WireWriter().u8(version).i64(nodeId).u8(flags).u8(nick.size).bytes(nick)
        if (prefix != null) w.i32(prefix)
        if (nonce != null) {
            w.bytes(nonce).u8(if (proof != null) AUTH_PROOF else 0)
            if (proof != null) w.bytes(proof.certificate.encode()).bytes(proof.signature)
        }
        return w.toByteArray()
    }

    companion object {
        private const val PREFIX_SIZE = 4
        const val NONCE_SIZE = 16
        private const val AUTH_PROOF = 0x01
        private val PROOF_DOMAIN = "SP-HELLO-v1".utf8()

        /** What the proof signs: the receiver's nonce, so it can't be replayed on another link, and the signer's nodeId. */
        fun proofMessage(receiverNonce: ByteArray, nodeId: Long): ByteArray =
            PROOF_DOMAIN + receiverNonce + WireWriter(8).i64(nodeId).toByteArray()

        /**
         * The prefix may follow the nickname, the nonce and the auth flags the prefix. Fewer bytes than a field takes,
         * a proof that doesn't parse, auth flags this version doesn't know and anything after the fields it reads come
         * from a newer version and are skipped.
         */
        fun decode(payload: ByteArray): Hello? = parseOrNull {
            val r = WireReader(payload)
            val version = r.u8()
            val nodeId = r.i64()
            val flags = r.u8()
            val nick = r.string(r.u8(), Limits.NICK_BYTES)
            if (nodeId == 0L) throw MalformedException("anonymous hello")
            val prefix = if (r.remaining >= PREFIX_SIZE) r.i32() else null
            val nonce = if (prefix != null && r.remaining > NONCE_SIZE) r.bytes(NONCE_SIZE) else null
            val proof = if (nonce != null && r.u8() and AUTH_PROOF != 0) {
                parseOrNull { HelloProof(Certificate.read(r), r.bytes(Certificate.SIGNATURE_SIZE)) }
            } else null
            Hello(version, nodeId, flags, nick.ifEmpty { null }, prefix, nonce, proof)
        }
    }
}

object AckStatus {
    const val NONE = 0
    const val RECEIVED = 1
    const val ACKNOWLEDGED = 2
    const val RESOLVED = 3

    /** The author withdrew the report and staff confirmed; highest rank, so it supersedes any ACK of its epoch. */
    const val CANCELLED = 4

    /** 1.1: the team reached the person. */
    const val ON_SCENE = 5

    /** 1.1: the team handed the person over to the medical post. */
    const val HANDED_OVER = 6

    /** 1.1: the control console closed the incident. */
    const val CLOSED_BY_CONTROL = 7

    /** 1.1: the control console closed it as a false alarm. */
    const val UNFOUNDED = 8

    /** 1.1: the control console opened it again; always the first status of a new epoch. */
    const val REOPENED = 9

    /** The statuses 1.0 knows; it counts any other as malformed. */
    val V1_0 = NONE..CANCELLED

    /** The supersede order within one epoch, lowest first; 1.0's statuses keep their 1.0 order. */
    private val ORDER = intArrayOf(
        NONE, RECEIVED, REOPENED, ACKNOWLEDGED, ON_SCENE, HANDED_OVER, RESOLVED, CLOSED_BY_CONTROL, UNFOUNDED, CANCELLED,
    )

    /** Values above [REOPENED] come from newer versions: kept as numbers, but they have no place in the order. */
    fun isKnown(status: Int): Boolean = status in NONE..REOPENED

    /** Position in the supersede order within an epoch; -1 for a status from a newer version. */
    fun rank(status: Int): Int = ORDER.indexOf(status)

    fun isClosed(status: Int): Boolean =
        status == RESOLVED || status == CLOSED_BY_CONTROL || status == UNFOUNDED || status == CANCELLED

    /** A team has the incident and it isn't closed. */
    fun isTaken(status: Int): Boolean = status == ACKNOWLEDGED || status == ON_SCENE || status == HANDED_OVER

    /** The status a 1.0 phone gets in place of [status]: the closest of its own, or NONE if none fits (a reopen). */
    fun legacy(status: Int): Int = when (status) {
        ON_SCENE, HANDED_OVER -> ACKNOWLEDGED
        CLOSED_BY_CONTROL, UNFOUNDED -> RESOLVED
        REOPENED -> NONE
        else -> status
    }
}

/**
 * The supersede order of an incident's ACKs (PROTOCOL.md, section 8): a later epoch wins, then the higher rank.
 * Positive if the first pair comes later. Callers break ties as section 8 says.
 */
object AckOrder {
    fun compare(epoch: Int, status: Int, otherEpoch: Int, otherStatus: Int): Int =
        if (epoch != otherEpoch) epoch.compareTo(otherEpoch) else AckStatus.rank(status).compareTo(AckStatus.rank(otherStatus))
}

/** What a node has cached for an incident; [id8] is the first 8 bytes of the incidentId. */
data class SummaryEntry(val id8: Long, val hasReport: Boolean, val ackStatus: Int, val epoch: Int = 0)

data class RequestEntry(val id8: Long, val wantReport: Boolean, val wantAck: Boolean)

private const val REPORT_BIT = 0x80
private const val ACK_BIT = 0x01

private fun WireWriter.summaryEntries(entries: List<SummaryEntry>) = apply {
    require(entries.size <= Limits.SUMMARY_ENTRIES)
    u8(entries.size)
    for (e in entries) i64(e.id8).u8((if (e.hasReport) REPORT_BIT else 0) or e.ackStatus)
}

private fun WireReader.summaryEntries(): List<SummaryEntry> {
    val count = u8()
    if (count > Limits.SUMMARY_ENTRIES) throw MalformedException("summary too long")
    return List(count) {
        val id8 = i64()
        val state = u8()
        SummaryEntry(id8, state and REPORT_BIT != 0, state and REPORT_BIT.inv())
    }
}

private fun WireWriter.requestEntries(entries: List<RequestEntry>) = apply {
    require(entries.size <= Limits.SUMMARY_ENTRIES)
    u8(entries.size)
    for (e in entries) i64(e.id8).u8((if (e.wantReport) REPORT_BIT else 0) or (if (e.wantAck) ACK_BIT else 0))
}

/** With [strict], as in 1.0, a want bit other than report and ACK is malformed; otherwise it is ignored. */
private fun WireReader.requestEntries(strict: Boolean): List<RequestEntry> {
    val count = u8()
    if (count > Limits.SUMMARY_ENTRIES) throw MalformedException("request too long")
    return List(count) {
        val id8 = i64()
        val want = u8()
        if (strict && want and (REPORT_BIT or ACK_BIT).inv() != 0) throw MalformedException("want bits")
        RequestEntry(id8, want and REPORT_BIT != 0, want and ACK_BIT != 0)
    }
}

/** The 1.0 summary, still what a 1.0 peer gets: nothing unknown may be in it. */
object SummaryCodec {
    fun encode(entries: List<SummaryEntry>): ByteArray =
        WireWriter(1 + entries.size * 9).summaryEntries(entries).toByteArray()

    fun decode(payload: ByteArray): List<SummaryEntry>? = parseOrNull {
        val r = WireReader(payload)
        val out = r.summaryEntries()
        if (out.any { it.ackStatus !in AckStatus.V1_0 }) throw MalformedException("ack status")
        r.expectEnd()
        out
    }
}

object RequestCodec {
    fun encode(entries: List<RequestEntry>): ByteArray =
        WireWriter(1 + entries.size * 9).requestEntries(entries).toByteArray()

    fun decode(payload: ByteArray): List<RequestEntry>? = parseOrNull {
        val r = WireReader(payload)
        val out = r.requestEntries(strict = true)
        r.expectEnd()
        out
    }
}

/** What a SUMMARY2 or REQUEST2 lists, so store-and-forward can carry new kinds without a new packet type. */
object SummaryKind {
    const val INCIDENTS = 1

    /** The console's latest response to each reporter ([IncidentResponse]). */
    const val RESPONSES = 2

    /** The reporter's latest update of each incident ([IncidentUpdateCodec]). */
    const val UPDATES = 3

    /** The latest notice of each topic ([ro.safetyplease.core.announce.Notice]), to peers that set HELLO_AUTH. */
    const val NOTICES = 4

    /** Kinds that list [HeldEntry]s and are requested by id8. */
    fun isHeld(kind: Int): Boolean = kind == RESPONSES || kind == UPDATES || kind == NOTICES

    /** Kinds whose [HeldEntry.version] is a `u32`; an update's is a `u8`. */
    fun hasLongVersion(kind: Int): Boolean = kind == RESPONSES || kind == NOTICES
}

/**
 * What a node holds of [SummaryKind.RESPONSES] or [SummaryKind.UPDATES] for an incident, or of [SummaryKind.NOTICES]
 * for a topic (the topic as [id8]): [version] is the response's timestamp or the update's or the notice's sequence
 * number, so that only a later one is asked for.
 */
data class HeldEntry(val id8: Long, val version: Long)

/**
 * SUMMARY2 starts with a [kind]. For [SummaryKind.INCIDENTS] the entries are SUMMARY's, except that a status from a
 * newer version is kept, and a flags byte may follow them: with [Summary2Codec.EPOCHS], one epoch per entry comes
 * next. RESPONSES, UPDATES and NOTICES list [held] instead: a count, then per entry the id8 and the timestamp (`u32`),
 * the update's sequence number (`u8`) or the notice's (`u32`). Whatever follows comes from a newer version and is skipped. A kind this version doesn't
 * read decodes with no entries.
 */
data class Summary2(val kind: Int, val entries: List<SummaryEntry>, val held: List<HeldEntry> = emptyList())

/**
 * REQUEST2 answers a SUMMARY2 of the same kind; want bits from a newer version are ignored. RESPONSES, UPDATES and
 * NOTICES ask for [ids]: a count, then the id8 of each.
 */
data class Request2(val kind: Int, val entries: List<RequestEntry>, val ids: List<Long> = emptyList())

object Summary2Codec {
    /** Bit of the flags byte after the entries: an epoch byte per entry follows, in their order. */
    const val EPOCHS = 0x01

    fun encode(summary: Summary2): ByteArray {
        require(summary.kind == SummaryKind.INCIDENTS || summary.entries.isEmpty()) { "only INCIDENTS has entries" }
        require(SummaryKind.isHeld(summary.kind) || summary.held.isEmpty()) { "only RESPONSES, UPDATES and NOTICES hold" }
        val entries = summary.entries
        val w = WireWriter(3 + entries.size * 10 + summary.held.size * 12).u8(summary.kind)
        if (SummaryKind.isHeld(summary.kind)) {
            require(summary.held.size <= maxHeld(summary.kind))
            w.u8(summary.held.size)
            for (h in summary.held) {
                w.i64(h.id8)
                if (SummaryKind.hasLongVersion(summary.kind)) w.u32(h.version) else w.u8(h.version.toInt())
            }
        }
        if (summary.kind == SummaryKind.INCIDENTS) {
            w.summaryEntries(entries)
            // without a reopened incident the bytes stay those of a SUMMARY2 without epochs
            if (entries.any { it.epoch > 0 }) {
                require(entries.size <= Limits.SUMMARY2_EPOCH_ENTRIES && entries.all { it.epoch <= IncidentAck.MAX_EPOCH })
                w.u8(EPOCHS)
                for (e in entries) w.u8(e.epoch)
            }
        }
        return w.toByteArray()
    }

    fun decode(payload: ByteArray): Summary2? = parseOrNull {
        val r = WireReader(payload)
        val kind = r.u8()
        if (SummaryKind.isHeld(kind)) {
            val count = r.u8()
            if (count > maxHeld(kind)) throw MalformedException("summary too long")
            val held = List(count) { HeldEntry(r.i64(), if (SummaryKind.hasLongVersion(kind)) r.u32() else r.u8().toLong()) }
            return@parseOrNull Summary2(kind, emptyList(), held)
        }
        if (kind != SummaryKind.INCIDENTS) return@parseOrNull Summary2(kind, emptyList())
        val entries = r.summaryEntries()
        val flags = if (r.remaining > 0) r.u8() else 0
        if (flags and EPOCHS == 0) return@parseOrNull Summary2(kind, entries)
        if (entries.size > Limits.SUMMARY2_EPOCH_ENTRIES) throw MalformedException("too many entries for epochs")
        Summary2(kind, entries.map { it.copy(epoch = r.u8()) })
    }

    /** The entries one SUMMARY2 can carry: with epochs, each takes a byte more. */
    fun fitting(entries: List<SummaryEntry>): List<SummaryEntry> =
        if (entries.any { it.epoch > 0 }) entries.take(Limits.SUMMARY2_EPOCH_ENTRIES) else entries

    /** How many [HeldEntry]s one SUMMARY2 of [kind] can carry. */
    fun maxHeld(kind: Int): Int = if (SummaryKind.hasLongVersion(kind)) Limits.SUMMARY2_RESPONSE_ENTRIES else Limits.SUMMARY_ENTRIES
}

object Request2Codec {
    fun encode(request: Request2): ByteArray {
        require(request.kind == SummaryKind.INCIDENTS || request.entries.isEmpty()) { "only INCIDENTS has entries" }
        require(SummaryKind.isHeld(request.kind) || request.ids.isEmpty()) { "only RESPONSES, UPDATES and NOTICES ask by id" }
        val w = WireWriter(2 + request.entries.size * 9 + request.ids.size * 8).u8(request.kind)
        if (request.kind == SummaryKind.INCIDENTS) w.requestEntries(request.entries)
        if (SummaryKind.isHeld(request.kind)) {
            require(request.ids.size <= Limits.SUMMARY_ENTRIES)
            w.u8(request.ids.size)
            for (id in request.ids) w.i64(id)
        }
        return w.toByteArray()
    }

    fun decode(payload: ByteArray): Request2? = parseOrNull {
        val r = WireReader(payload)
        val kind = r.u8()
        when {
            kind == SummaryKind.INCIDENTS -> Request2(kind, r.requestEntries(strict = false))
            SummaryKind.isHeld(kind) -> {
                val count = r.u8()
                if (count > Limits.SUMMARY_ENTRIES) throw MalformedException("request too long")
                Request2(kind, emptyList(), List(count) { r.i64() })
            }
            else -> Request2(kind, emptyList())
        }
    }
}

object IncidentCategory {
    const val MEDICAL = 1
    const val VIOLENCE = 2

    /** A lost child. 1.0 called it "lost person", so a report from an older phone may be about anyone lost. */
    const val LOST_CHILD = 3
    const val HARASSMENT = 4
    const val CROWD = 5
    const val FIRE = 6
    const val OTHER = 7

    /** 1.1: a friend lost in the crowd, for the control room only. 1.0 staff phones can't read such a report. */
    const val LOST_FRIEND = 8
    val all = listOf(MEDICAL, VIOLENCE, LOST_CHILD, LOST_FRIEND, HARASSMENT, CROWD, FIRE, OTHER)
}

object Severity {
    const val LOW = 1
    const val MEDIUM = 2
    const val URGENT = 3
}

/** An answer to a triage question (1.1), in two bits. */
object Answer {
    const val UNASKED = 0
    const val YES = 1
    const val NO = 2
    const val UNKNOWN = 3
}

/** The two triage questions of a medical report (1.1): does the person respond, do they breathe normally. */
data class Triage(val responds: Int = Answer.UNASKED, val breathes: Int = Answer.UNASKED) {
    init {
        require(responds in Answer.UNASKED..Answer.UNKNOWN && breathes in Answer.UNASKED..Answer.UNKNOWN)
    }

    /** A "no" to either question means the person may be dying. */
    val critical: Boolean get() = responds == Answer.NO || breathes == Answer.NO
}

/** ETHANE-lite fields of a report written by staff (1.1). [people] is 0 when not given. */
data class StaffFields(val people: Int = 0, val dangerContinues: Boolean = false) {
    init {
        require(people in 0..0xFF)
    }
}

/**
 * What leads a team to the reporter beyond the coordinates (1.1): how far off the fix may be, in metres, and the number
 * of the nearest landmark on the site plan. 0 means unknown for either.
 */
data class LocationFields(val accuracyM: Int = 0, val landmark: Int = 0) {
    init {
        require(accuracyM in 0..MAX_ACCURACY_M && landmark in 0..0xFF)
    }

    companion object {
        /** Stands for this far or worse. */
        const val MAX_ACCURACY_M = 0xFF

        /** Rounded up, so the shown radius is never smaller than the measured one; 0 when the fix has no accuracy. */
        fun accuracyM(meters: Double): Int =
            if (meters.isNaN() || meters <= 0.0) 0 else ceil(meters.coerceAtMost(MAX_ACCURACY_M.toDouble())).toInt()
    }
}

const val INCIDENT_ID_SIZE = 16

/**
 * Report content. Only ever travels sealed-box encrypted to the staff key. [cancelHash] is missing
 * from reports written before cancelling existed.
 *
 * Fields after the cancel hash sit each behind a flag bit of its own: 1.1 adds [triage], [staff], [location] and
 * [replyKey]. This version ignores the flags it doesn't know and everything after the fields it reads. A category it
 * doesn't know is kept as a number.
 */
class IncidentBody(
    val category: Int,
    val severity: Int,
    val timestamp: Long,
    val zone: String,
    val lat: Double?,
    val lon: Double?,
    val description: String,
    val nickname: String?,
    val cancelHash: ByteArray? = null,
    val triage: Triage? = null,
    /** Set by staff, whose [nickname] is then the team's name. */
    val staff: StaffFields? = null,
    val location: LocationFields? = null,
    /** The reporter's ephemeral X25519 key: the console seals its responses ([IncidentResponse]) to it. */
    val replyKey: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val zoneBytes = zone.utf8()
        val descBytes = description.utf8()
        val nickBytes = nickname?.utf8()
        require(zoneBytes.size <= Limits.ZONE_BYTES && descBytes.size <= Limits.DESCRIPTION_BYTES)
        require(nickBytes == null || nickBytes.size <= Limits.NICK_BYTES)
        require(cancelHash == null || cancelHash.size == IncidentCancel.HASH_SIZE)
        require(replyKey == null || replyKey.size == REPLY_KEY_SIZE)
        val hasCoords = lat != null && lon != null
        val flags = (if (hasCoords) FLAG_COORDS else 0) or (if (nickBytes != null) FLAG_NICK else 0) or
            (if (cancelHash != null) FLAG_CANCEL else 0) or (if (triage != null) FLAG_TRIAGE else 0) or
            (if (staff != null) FLAG_STAFF else 0) or (if (location != null) FLAG_LOCATION else 0) or
            (if (replyKey != null) FLAG_REPLY else 0)
        val w = WireWriter(70 + zoneBytes.size + descBytes.size)
        w.u8(category).u8(severity).u32(timestamp).u8(flags)
        w.u8(zoneBytes.size).bytes(zoneBytes)
        if (hasCoords) w.coord(lat!!).coord(lon!!)
        w.u16(descBytes.size).bytes(descBytes)
        if (nickBytes != null) w.u8(nickBytes.size).bytes(nickBytes)
        if (cancelHash != null) w.bytes(cancelHash)
        if (triage != null) w.u8(triage.responds or (triage.breathes shl 2))
        if (staff != null) w.u8(staff.people).u8(if (staff.dangerContinues) STAFF_DANGER else 0)
        if (location != null) w.u8(location.accuracyM).u8(location.landmark)
        if (replyKey != null) w.bytes(replyKey)
        return w.toByteArray()
    }

    companion object {
        const val REPLY_KEY_SIZE = 32

        /** The most a body may take for its report to fit in a payload. */
        const val MAX_SIZE = PacketCodec.MAX_PAYLOAD - INCIDENT_ID_SIZE - IncidentReportCodec.SEAL_OVERHEAD

        private const val FLAG_COORDS = 0x01
        private const val FLAG_NICK = 0x02
        private const val FLAG_CANCEL = 0x04
        private const val FLAG_TRIAGE = 0x08
        private const val FLAG_STAFF = 0x10
        private const val FLAG_LOCATION = 0x20
        private const val FLAG_REPLY = 0x40
        private const val STAFF_DANGER = 0x01

        fun decode(plain: ByteArray): IncidentBody? = parseOrNull {
            val r = WireReader(plain)
            val category = r.u8()
            if (category == 0) throw MalformedException("category")
            val severity = r.u8()
            if (severity < Severity.LOW || severity > Severity.URGENT) throw MalformedException("severity")
            val timestamp = r.u32()
            val flags = r.u8()
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            val hasCoords = flags and FLAG_COORDS != 0
            val lat = if (hasCoords) r.coord() else null
            val lon = if (hasCoords) r.coord() else null
            val description = r.string(r.u16(), Limits.DESCRIPTION_BYTES)
            val nick = if (flags and FLAG_NICK != 0) r.string(r.u8(), Limits.NICK_BYTES) else null
            val cancelHash = if (flags and FLAG_CANCEL != 0) r.bytes(IncidentCancel.HASH_SIZE) else null
            // the upper bits of these bytes belong to newer versions
            val triage = if (flags and FLAG_TRIAGE != 0) r.u8().let { Triage(it and 0x03, (it shr 2) and 0x03) } else null
            val staff = if (flags and FLAG_STAFF != 0) StaffFields(r.u8(), r.u8() and STAFF_DANGER != 0) else null
            val location = if (flags and FLAG_LOCATION != 0) LocationFields(r.u8(), r.u8()) else null
            val replyKey = if (flags and FLAG_REPLY != 0) r.bytes(REPLY_KEY_SIZE) else null
            IncidentBody(category, severity, timestamp, zone, lat, lon, description, nick, cancelHash, triage, staff, location, replyKey)
        }
    }
}

/** INCIDENT_REPORT payload: the incidentId in clear (for dedup and ACKs) followed by the sealed body. */
object IncidentReportCodec {
    const val SEAL_OVERHEAD = 48

    fun encode(incidentId: ByteArray, sealed: ByteArray): ByteArray {
        require(incidentId.size == INCIDENT_ID_SIZE)
        return incidentId + sealed
    }

    fun isWellFormed(payload: ByteArray): Boolean = payload.size > INCIDENT_ID_SIZE + SEAL_OVERHEAD

    fun incidentId(payload: ByteArray): ByteArray = payload.copyOfRange(0, INCIDENT_ID_SIZE)

    fun sealed(payload: ByteArray): ByteArray = payload.copyOfRange(INCIDENT_ID_SIZE, payload.size)
}

/** Bits of the first extension byte of an ACK (1.1). Bits this version doesn't know are ignored. */
object AckFlags {
    /** Signed by the control console in the named team's name: an assignment, not the team's own take. */
    const val ASSIGNED = 0x01

    /** The epoch follows the flags byte, as one byte. */
    const val EPOCH = 0x02

    /**
     * The signer's certificate follows the epoch, and the certificate's key signed the ACK, not the build's staff
     * key. Only to peers that set [NodeFlags.HELLO_AUTH]: an older 1.1 peer would check it against the staff key.
     */
    const val CERT = 0x04
}

/**
 * A status above [AckStatus.REOPENED] comes from a newer version and is kept as a number. [extensions] sit between
 * the team name and the signature, which always takes the last 64 bytes and so covers them: the first byte holds
 * [AckFlags], the fields its bits announce follow in bit order, and whatever comes after them is from a newer
 * version and is kept as it came.
 */
class IncidentAck(
    val incidentId: ByteArray,
    val status: Int,
    val timestamp: Long,
    val teamName: String,
    val signature: ByteArray,
    val extensions: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = unsigned(incidentId, status, timestamp, teamName) + extensions + signature

    val flags: Int get() = if (extensions.isEmpty()) 0 else extensions[0].toInt() and 0xFF

    val assigned: Boolean get() = flags and AckFlags.ASSIGNED != 0

    /** How many times control reopened the incident before this ACK; 0 without the field. */
    val epoch: Int get() = if (flags and AckFlags.EPOCH != 0 && extensions.size > 1) extensions[1].toInt() and 0xFF else 0

    val certified: Boolean get() = flags and AckFlags.CERT != 0

    /** The signer's certificate; null without [AckFlags.CERT] or if it doesn't parse. */
    val certificate: Certificate?
        get() {
            if (!certified) return null
            val at = if (flags and AckFlags.EPOCH != 0) 2 else 1
            return parseOrNull { Certificate.read(WireReader(extensions, at)) }
        }

    companion object {
        const val SIGNATURE_SIZE = 64
        const val STATUS_OFFSET = INCIDENT_ID_SIZE
        const val MAX_EPOCH = 0xFF
        private val DOMAIN = "SP-ACK-v1".utf8()

        /**
         * No byte at all without a flag or an epoch, so a plain ACK keeps its 1.0 bytes and can still go to a 1.0
         * phone. [flags] are the other AckFlags; the epoch and the certificate set [AckFlags.EPOCH] and [AckFlags.CERT].
         */
        fun extensions(flags: Int, epoch: Int = 0, certificate: Certificate? = null): ByteArray {
            require(flags in 0..0xFF && flags and (AckFlags.EPOCH or AckFlags.CERT) == 0 && epoch in 0..MAX_EPOCH)
            val all = flags or (if (epoch > 0) AckFlags.EPOCH else 0) or (if (certificate != null) AckFlags.CERT else 0)
            if (all == 0) return ByteArray(0)
            val w = WireWriter().u8(all)
            if (epoch > 0) w.u8(epoch)
            certificate?.let { w.bytes(it.encode()) }
            return w.toByteArray()
        }

        fun unsigned(incidentId: ByteArray, status: Int, timestamp: Long, teamName: String): ByteArray {
            val team = teamName.utf8()
            require(incidentId.size == INCIDENT_ID_SIZE && team.size <= Limits.TEAM_BYTES)
            return WireWriter(32 + team.size).bytes(incidentId).u8(status).u32(timestamp)
                .u8(team.size).bytes(team).toByteArray()
        }

        /** Bytes covered by the signature; the prefix binds the signature to this message type. */
        fun signedMessage(unsigned: ByteArray): ByteArray = DOMAIN + unsigned

        fun decode(payload: ByteArray): IncidentAck? = parseOrNull {
            val r = WireReader(payload)
            val id = r.bytes(INCIDENT_ID_SIZE)
            val status = r.u8()
            if (status == AckStatus.NONE) throw MalformedException("status")
            val timestamp = r.u32()
            val team = r.string(r.u8(), Limits.TEAM_BYTES)
            val extensions = r.bytes(r.remaining - SIGNATURE_SIZE)
            val signature = r.bytes(SIGNATURE_SIZE)
            IncidentAck(id, status, timestamp, team, signature, extensions)
        }

        fun unsignedPart(payload: ByteArray): ByteArray = payload.copyOfRange(0, payload.size - SIGNATURE_SIZE)
    }
}

/**
 * INCIDENT_CANCEL payload, anonymous like the report. The sealed report body holds only the token's hash,
 * so only the author can cancel; staff confirms with a CANCELLED ACK. Bytes after the token come from a newer
 * version and are skipped.
 */
class IncidentCancel(val incidentId: ByteArray, val token: ByteArray) {
    fun encode(): ByteArray {
        require(incidentId.size == INCIDENT_ID_SIZE && token.size == TOKEN_SIZE)
        return incidentId + token
    }

    companion object {
        const val TOKEN_SIZE = 16
        const val HASH_SIZE = 16
        const val SIZE = INCIDENT_ID_SIZE + TOKEN_SIZE
        private val DOMAIN = "SP-CANCEL-v1".utf8()

        fun hash(token: ByteArray): ByteArray = sha256(DOMAIN + token).copyOf(HASH_SIZE)

        fun decode(payload: ByteArray): IncidentCancel? = parseOrNull {
            val r = WireReader(payload)
            val id = r.bytes(INCIDENT_ID_SIZE)
            val token = r.bytes(TOKEN_SIZE)
            IncidentCancel(id, token)
        }
    }
}

/** The console's predefined answers to a reporter (1.1). A code this version doesn't know is kept as a number. */
object ResponseCode {
    /** "Venim, rămâi pe loc." */
    const val COMING = 1

    /** "Nu te găsim, du-te la reperul X": [ResponseBody.landmark] is X. */
    const val GO_TO_LANDMARK = 2

    /** "Mergi la punctul medical." */
    const val GO_TO_MEDICAL = 3
}

/** What a response carries, sealed to the reporter's key. Bytes after the landmark come from a newer version. */
data class ResponseBody(val code: Int, val landmark: Int = 0) {
    init {
        require(code in 1..0xFF && landmark in 0..0xFF)
    }

    fun encode(): ByteArray = WireWriter(2).u8(code).u8(landmark).toByteArray()

    companion object {
        fun decode(plain: ByteArray): ResponseBody? = parseOrNull {
            val r = WireReader(plain)
            val code = r.u8()
            if (code == 0) throw MalformedException("response code")
            ResponseBody(code, r.u8())
        }
    }
}

/**
 * INCIDENT_RESPONSE payload (1.1): the console's answer to the author of a report, bound to its incidentId. Relays
 * read and check everything but [sealed], the [ResponseBody] sealed to the report's reply key. Signed like an ACK:
 * with the key of [certificate], a control shift's, or without one with the build's staff key. [extensions] sit
 * between the certificate and the signature: a newer version's fields, signed and kept as they came.
 */
class IncidentResponse(
    val incidentId: ByteArray,
    val timestamp: Long,
    val sealed: ByteArray,
    val certificate: Certificate?,
    val signature: ByteArray,
    val extensions: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = unsigned(incidentId, timestamp, sealed, certificate) + extensions + signature

    companion object {
        /** Flag bit: the signer's certificate follows the sealed body. The other bits are reserved and ignored. */
        const val CERT = 0x01
        private val DOMAIN = "SP-RESP-v1".utf8()

        fun unsigned(incidentId: ByteArray, timestamp: Long, sealed: ByteArray, certificate: Certificate?): ByteArray {
            require(incidentId.size == INCIDENT_ID_SIZE && sealed.size in IncidentReportCodec.SEAL_OVERHEAD + 1..0xFF)
            val w = WireWriter().bytes(incidentId).u32(timestamp).u8(if (certificate != null) CERT else 0)
            w.u8(sealed.size).bytes(sealed)
            certificate?.let { w.bytes(it.encode()) }
            return w.toByteArray()
        }

        /** Bytes covered by the signature; the prefix binds it to this message type. */
        fun signedMessage(unsigned: ByteArray): ByteArray = DOMAIN + unsigned

        fun unsignedPart(payload: ByteArray): ByteArray = payload.copyOfRange(0, payload.size - IncidentAck.SIGNATURE_SIZE)

        fun decode(payload: ByteArray): IncidentResponse? = parseOrNull {
            val r = WireReader(payload)
            val id = r.bytes(INCIDENT_ID_SIZE)
            val timestamp = r.u32()
            val flags = r.u8()
            val size = r.u8()
            if (size <= IncidentReportCodec.SEAL_OVERHEAD) throw MalformedException("sealed response")
            val sealed = r.bytes(size)
            val certificate = if (flags and CERT != 0) Certificate.read(r) else null
            val extensions = r.bytes(r.remaining - IncidentAck.SIGNATURE_SIZE)
            IncidentResponse(id, timestamp, sealed, certificate, r.bytes(IncidentAck.SIGNATURE_SIZE), extensions)
        }
    }
}

/** What the author of a report says later (1.1). A kind this version doesn't know is kept as a number. */
object UpdateKind {
    /** "Încă e nevoie de ajutor." */
    const val STILL_ACTIVE = 1

    /** The reporter moved: the update says where to. */
    const val MOVED = 2

    /** "S-a rezolvat singur." */
    const val RESOLVED_ITSELF = 3
}

/**
 * An update's content, sealed to the staff key like the report. [token] is the report's cancel token: staff check it
 * against the cancel hash of the body, so only the author can update. The place fields sit behind flag bits; flags
 * this version doesn't know and the bytes after the fields it reads come from a newer version and are skipped.
 */
class UpdateBody(
    val kind: Int,
    val timestamp: Long,
    val token: ByteArray,
    val zone: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val location: LocationFields? = null,
) {
    fun encode(): ByteArray {
        val zoneBytes = zone?.utf8()
        require(kind in 1..0xFF && token.size == IncidentCancel.TOKEN_SIZE)
        require(zoneBytes == null || zoneBytes.size <= Limits.ZONE_BYTES)
        val hasCoords = lat != null && lon != null
        val flags = (if (hasCoords) FLAG_COORDS else 0) or (if (zoneBytes != null) FLAG_ZONE else 0) or
            (if (location != null) FLAG_LOCATION else 0)
        val w = WireWriter().u8(kind).u32(timestamp).bytes(token).u8(flags)
        if (lat != null && lon != null) w.coord(lat).coord(lon)
        if (zoneBytes != null) w.u8(zoneBytes.size).bytes(zoneBytes)
        if (location != null) w.u8(location.accuracyM).u8(location.landmark)
        return w.toByteArray()
    }

    companion object {
        private const val FLAG_COORDS = 0x01
        private const val FLAG_ZONE = 0x02
        private const val FLAG_LOCATION = 0x04

        fun decode(plain: ByteArray): UpdateBody? = parseOrNull {
            val r = WireReader(plain)
            val kind = r.u8()
            if (kind == 0) throw MalformedException("update kind")
            val timestamp = r.u32()
            val token = r.bytes(IncidentCancel.TOKEN_SIZE)
            val flags = r.u8()
            val hasCoords = flags and FLAG_COORDS != 0
            val lat = if (hasCoords) r.coord() else null
            val lon = if (hasCoords) r.coord() else null
            val zone = if (flags and FLAG_ZONE != 0) r.string(r.u8(), Limits.ZONE_BYTES) else null
            val location = if (flags and FLAG_LOCATION != 0) LocationFields(r.u8(), r.u8()) else null
            UpdateBody(kind, timestamp, token, zone, lat, lon, location)
        }
    }
}

/**
 * INCIDENT_UPDATE payload (1.1): the incidentId and the update's sequence number in clear, so that relays keep the
 * latest of each incident, then the sealed [UpdateBody]. Anonymous like the report, and relays can't check it either.
 */
object IncidentUpdateCodec {
    /** The first update of a report is 1. */
    const val MAX_SEQ = 0xFF

    fun encode(incidentId: ByteArray, seq: Int, sealed: ByteArray): ByteArray {
        require(incidentId.size == INCIDENT_ID_SIZE && seq in 1..MAX_SEQ)
        return incidentId + seq.toByte() + sealed
    }

    fun isWellFormed(payload: ByteArray): Boolean = payload.size > INCIDENT_ID_SIZE + 1 + IncidentReportCodec.SEAL_OVERHEAD

    /** As with [IncidentReportCodec], these slice without checking: only for a payload [isWellFormed] accepts. */
    fun incidentId(payload: ByteArray): ByteArray = payload.copyOfRange(0, INCIDENT_ID_SIZE)

    fun seq(payload: ByteArray): Int = payload[INCIDENT_ID_SIZE].toInt() and 0xFF

    fun sealed(payload: ByteArray): ByteArray = payload.copyOfRange(INCIDENT_ID_SIZE + 1, payload.size)
}

object QuickCode {
    const val WHERE_ARE_YOU = 1
    const val COMING = 2
    const val LOW_BATTERY = 3
    const val MEET_AT_POINT = 4
    val all = listOf(WHERE_ARE_YOU, COMING, LOW_BATTERY, MEET_AT_POINT)
}

class GroupMemberWire(val nodeId: Long, val boxKey: ByteArray, val nickname: String)

/** The message inside a PRIVATE envelope. [msgId] identifies it across retransmissions. */
sealed interface Inner {
    val msgId: Long

    class Text(override val msgId: Long, val groupId: Long?, val text: String) : Inner
    class Quick(override val msgId: Long, val groupId: Long?, val code: Int) : Inner
    class Zone(
        override val msgId: Long,
        val groupId: Long?,
        val zone: String,
        val lat: Double?,
        val lon: Double?,
    ) : Inner
    class Ping(override val msgId: Long) : Inner
    class Pong(override val msgId: Long, val pingId: Long) : Inner
    class Delivered(override val msgId: Long, val deliveredId: Long) : Inner
    class GroupInvite(
        override val msgId: Long,
        val groupId: Long,
        val name: String,
        val members: List<GroupMemberWire>,
    ) : Inner

    /** The sender left the group [groupId]. */
    class LeftGroup(override val msgId: Long, val groupId: Long) : Inner

    /** "Where are you?": a friend asks for the sender's zone. */
    class ZoneRequest(override val msgId: Long) : Inner

    /** The sender's zone, for a friend's list: not a chat message, and never repeated on a timer. */
    class ZoneUpdate(override val msgId: Long, val zone: String) : Inner
}

object InnerCodec {
    private const val TEXT = 1
    private const val QUICK = 2
    private const val ZONE = 3
    private const val PING = 4
    private const val PONG = 5
    private const val DELIVERED = 6
    private const val GROUP_INVITE = 7
    private const val LEFT_GROUP = 8
    private const val ZONE_REQUEST = 9
    private const val ZONE_UPDATE = 10

    private const val FLAG_GROUP = 0x01
    private const val FLAG_COORDS = 0x02
    const val BOX_KEY_SIZE = 32

    fun encode(inner: Inner): ByteArray {
        val w = WireWriter()
        when (inner) {
            is Inner.Text -> {
                val text = inner.text.utf8()
                require(text.size <= Limits.TEXT_BYTES)
                w.u8(TEXT).i64(inner.msgId).group(inner.groupId, 0).bytes(text)
            }
            is Inner.Quick -> w.u8(QUICK).i64(inner.msgId).group(inner.groupId, 0).u8(inner.code)
            is Inner.Zone -> {
                val zone = inner.zone.utf8()
                require(zone.size <= Limits.ZONE_BYTES)
                val hasCoords = inner.lat != null && inner.lon != null
                w.u8(ZONE).i64(inner.msgId).group(inner.groupId, if (hasCoords) FLAG_COORDS else 0)
                w.u8(zone.size).bytes(zone)
                if (hasCoords) w.coord(inner.lat!!).coord(inner.lon!!)
            }
            is Inner.Ping -> w.u8(PING).i64(inner.msgId)
            is Inner.Pong -> w.u8(PONG).i64(inner.msgId).i64(inner.pingId)
            is Inner.Delivered -> w.u8(DELIVERED).i64(inner.msgId).i64(inner.deliveredId)
            is Inner.GroupInvite -> {
                val name = inner.name.utf8()
                require(name.size <= Limits.GROUP_NAME_BYTES)
                require(inner.members.size <= Limits.GROUP_MAX_MEMBERS - 2)
                w.u8(GROUP_INVITE).i64(inner.msgId).i64(inner.groupId).u8(name.size).bytes(name)
                w.u8(inner.members.size)
                for (m in inner.members) {
                    val nick = m.nickname.utf8()
                    require(m.boxKey.size == BOX_KEY_SIZE && nick.size <= Limits.GROUP_NICK_BYTES)
                    w.i64(m.nodeId).bytes(m.boxKey).u8(nick.size).bytes(nick)
                }
            }
            is Inner.LeftGroup -> w.u8(LEFT_GROUP).i64(inner.msgId).i64(inner.groupId)
            is Inner.ZoneRequest -> w.u8(ZONE_REQUEST).i64(inner.msgId)
            is Inner.ZoneUpdate -> {
                val zone = inner.zone.utf8()
                require(zone.size in 1..Limits.ZONE_BYTES)
                w.u8(ZONE_UPDATE).i64(inner.msgId).u8(zone.size).bytes(zone)
            }
        }
        return w.toByteArray()
    }

    private fun WireWriter.group(groupId: Long?, extraFlags: Int) = apply {
        u8((if (groupId != null) FLAG_GROUP else 0) or extraFlags)
        if (groupId != null) i64(groupId)
    }

    fun decode(plain: ByteArray): Inner? = parseOrNull {
        val r = WireReader(plain)
        val type = r.u8()
        val msgId = r.i64()
        when (type) {
            TEXT -> {
                val (groupId, _) = r.group(0)
                if (r.remaining > Limits.TEXT_BYTES) throw MalformedException("text too long")
                Inner.Text(msgId, groupId, String(r.rest(), Charsets.UTF_8))
            }
            QUICK -> {
                val (groupId, _) = r.group(0)
                val code = r.u8()
                r.expectEnd()
                if (code !in QuickCode.all) throw MalformedException("quick code")
                Inner.Quick(msgId, groupId, code)
            }
            ZONE -> {
                val (groupId, flags) = r.group(FLAG_COORDS)
                val zone = r.string(r.u8(), Limits.ZONE_BYTES)
                val hasCoords = flags and FLAG_COORDS != 0
                val lat = if (hasCoords) r.coord() else null
                val lon = if (hasCoords) r.coord() else null
                r.expectEnd()
                Inner.Zone(msgId, groupId, zone, lat, lon)
            }
            PING -> {
                r.expectEnd()
                Inner.Ping(msgId)
            }
            PONG -> {
                val pingId = r.i64()
                r.expectEnd()
                Inner.Pong(msgId, pingId)
            }
            DELIVERED -> {
                val deliveredId = r.i64()
                r.expectEnd()
                Inner.Delivered(msgId, deliveredId)
            }
            GROUP_INVITE -> {
                val groupId = r.i64()
                val name = r.string(r.u8(), Limits.GROUP_NAME_BYTES)
                val count = r.u8()
                if (count > Limits.GROUP_MAX_MEMBERS - 2) throw MalformedException("too many members")
                val members = List(count) {
                    val nodeId = r.i64()
                    val key = r.bytes(BOX_KEY_SIZE)
                    val nick = r.string(r.u8(), Limits.GROUP_NICK_BYTES)
                    GroupMemberWire(nodeId, key, nick)
                }
                r.expectEnd()
                Inner.GroupInvite(msgId, groupId, name, members)
            }
            LEFT_GROUP -> {
                val groupId = r.i64()
                r.expectEnd()
                Inner.LeftGroup(msgId, groupId)
            }
            ZONE_REQUEST -> {
                r.expectEnd()
                Inner.ZoneRequest(msgId)
            }
            ZONE_UPDATE -> {
                val zone = r.string(r.u8(), Limits.ZONE_BYTES)
                r.expectEnd()
                if (zone.isEmpty()) throw MalformedException("empty zone")
                Inner.ZoneUpdate(msgId, zone)
            }
            else -> throw MalformedException("inner type")
        }
    }

    private fun WireReader.group(allowedExtra: Int): Pair<Long?, Int> {
        val flags = u8()
        if (flags and (FLAG_GROUP or allowedExtra).inv() != 0) throw MalformedException("inner flags")
        val groupId = if (flags and FLAG_GROUP != 0) i64() else null
        return groupId to flags
    }
}
