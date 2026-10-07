package ro.safetyplease.core.protocol

import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.utf8
import kotlin.math.roundToInt

object NodeFlags {
    const val STAFF = 0x01
    const val ANCHOR = 0x02
    const val PENDING_INCIDENT = 0x04
    const val ACCEPTS_CONNECTIONS = 0x08
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
}

private fun WireWriter.coord(deg: Double) = i32((deg * 1e6).roundToInt())
private fun WireReader.coord(): Double = i32() / 1e6

class Hello(val version: Int, val nodeId: Long, val flags: Int, val nickname: String?) {
    fun encode(): ByteArray {
        val nick = nickname?.utf8() ?: ByteArray(0)
        require(nick.size <= Limits.NICK_BYTES)
        return WireWriter().u8(version).i64(nodeId).u8(flags).u8(nick.size).bytes(nick).toByteArray()
    }

    companion object {
        fun decode(payload: ByteArray): Hello? = parseOrNull {
            val r = WireReader(payload)
            val version = r.u8()
            val nodeId = r.i64()
            val flags = r.u8()
            val nick = r.string(r.u8(), Limits.NICK_BYTES)
            r.expectEnd()
            if (nodeId == 0L) throw MalformedException("anonymous hello")
            Hello(version, nodeId, flags, nick.ifEmpty { null })
        }
    }
}

object AckStatus {
    const val NONE = 0
    const val RECEIVED = 1
    const val ACKNOWLEDGED = 2
    const val RESOLVED = 3

    /** The author withdrew the report and staff confirmed; highest value, so it supersedes any ACK. */
    const val CANCELLED = 4
}

/** What a node has cached for an incident; [id8] is the first 8 bytes of the incidentId. */
data class SummaryEntry(val id8: Long, val hasReport: Boolean, val ackStatus: Int)

object SummaryCodec {
    private const val REPORT_BIT = 0x80

    fun encode(entries: List<SummaryEntry>): ByteArray {
        require(entries.size <= Limits.SUMMARY_ENTRIES)
        val w = WireWriter(1 + entries.size * 9).u8(entries.size)
        for (e in entries) w.i64(e.id8).u8((if (e.hasReport) REPORT_BIT else 0) or e.ackStatus)
        return w.toByteArray()
    }

    fun decode(payload: ByteArray): List<SummaryEntry>? = parseOrNull {
        val r = WireReader(payload)
        val count = r.u8()
        if (count > Limits.SUMMARY_ENTRIES) throw MalformedException("summary too long")
        val out = List(count) {
            val id8 = r.i64()
            val state = r.u8()
            val status = state and REPORT_BIT.inv()
            if (status > AckStatus.CANCELLED) throw MalformedException("ack status")
            SummaryEntry(id8, state and REPORT_BIT != 0, status)
        }
        r.expectEnd()
        out
    }
}

data class RequestEntry(val id8: Long, val wantReport: Boolean, val wantAck: Boolean)

object RequestCodec {
    private const val REPORT_BIT = 0x80
    private const val ACK_BIT = 0x01

    fun encode(entries: List<RequestEntry>): ByteArray {
        require(entries.size <= Limits.SUMMARY_ENTRIES)
        val w = WireWriter(1 + entries.size * 9).u8(entries.size)
        for (e in entries) {
            w.i64(e.id8).u8((if (e.wantReport) REPORT_BIT else 0) or (if (e.wantAck) ACK_BIT else 0))
        }
        return w.toByteArray()
    }

    fun decode(payload: ByteArray): List<RequestEntry>? = parseOrNull {
        val r = WireReader(payload)
        val count = r.u8()
        if (count > Limits.SUMMARY_ENTRIES) throw MalformedException("request too long")
        val out = List(count) {
            val id8 = r.i64()
            val want = r.u8()
            if (want and (REPORT_BIT or ACK_BIT).inv() != 0) throw MalformedException("want bits")
            RequestEntry(id8, want and REPORT_BIT != 0, want and ACK_BIT != 0)
        }
        r.expectEnd()
        out
    }
}

object IncidentCategory {
    const val MEDICAL = 1
    const val VIOLENCE = 2
    const val LOST_PERSON = 3
    const val HARASSMENT = 4
    const val CROWD = 5
    const val FIRE = 6
    const val OTHER = 7
    val all = listOf(MEDICAL, VIOLENCE, LOST_PERSON, HARASSMENT, CROWD, FIRE, OTHER)
}

object Severity {
    const val LOW = 1
    const val MEDIUM = 2
    const val URGENT = 3
}

const val INCIDENT_ID_SIZE = 16

/**
 * Report content. Only ever travels sealed-box encrypted to the staff key. [cancelHash] is missing
 * from reports written before cancelling existed.
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
) {
    fun encode(): ByteArray {
        val zoneBytes = zone.utf8()
        val descBytes = description.utf8()
        val nickBytes = nickname?.utf8()
        require(zoneBytes.size <= Limits.ZONE_BYTES && descBytes.size <= Limits.DESCRIPTION_BYTES)
        require(nickBytes == null || nickBytes.size <= Limits.NICK_BYTES)
        require(cancelHash == null || cancelHash.size == IncidentCancel.HASH_SIZE)
        val hasCoords = lat != null && lon != null
        val flags = (if (hasCoords) FLAG_COORDS else 0) or (if (nickBytes != null) FLAG_NICK else 0) or
            (if (cancelHash != null) FLAG_CANCEL else 0)
        val w = WireWriter(32 + zoneBytes.size + descBytes.size)
        w.u8(category).u8(severity).u32(timestamp).u8(flags)
        w.u8(zoneBytes.size).bytes(zoneBytes)
        if (hasCoords) w.coord(lat!!).coord(lon!!)
        w.u16(descBytes.size).bytes(descBytes)
        if (nickBytes != null) w.u8(nickBytes.size).bytes(nickBytes)
        if (cancelHash != null) w.bytes(cancelHash)
        return w.toByteArray()
    }

    companion object {
        private const val FLAG_COORDS = 0x01
        private const val FLAG_NICK = 0x02
        private const val FLAG_CANCEL = 0x04

        fun decode(plain: ByteArray): IncidentBody? = parseOrNull {
            val r = WireReader(plain)
            val category = r.u8()
            if (category !in IncidentCategory.all) throw MalformedException("category")
            val severity = r.u8()
            if (severity < Severity.LOW || severity > Severity.URGENT) throw MalformedException("severity")
            val timestamp = r.u32()
            val flags = r.u8()
            if (flags and (FLAG_COORDS or FLAG_NICK or FLAG_CANCEL).inv() != 0) throw MalformedException("flags")
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            val hasCoords = flags and FLAG_COORDS != 0
            val lat = if (hasCoords) r.coord() else null
            val lon = if (hasCoords) r.coord() else null
            val description = r.string(r.u16(), Limits.DESCRIPTION_BYTES)
            val nick = if (flags and FLAG_NICK != 0) r.string(r.u8(), Limits.NICK_BYTES) else null
            val cancelHash = if (flags and FLAG_CANCEL != 0) r.bytes(IncidentCancel.HASH_SIZE) else null
            r.expectEnd()
            IncidentBody(category, severity, timestamp, zone, lat, lon, description, nick, cancelHash)
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

class IncidentAck(
    val incidentId: ByteArray,
    val status: Int,
    val timestamp: Long,
    val teamName: String,
    val signature: ByteArray,
) {
    fun encode(): ByteArray = unsigned(incidentId, status, timestamp, teamName) + signature

    companion object {
        const val SIGNATURE_SIZE = 64
        const val STATUS_OFFSET = INCIDENT_ID_SIZE
        private val DOMAIN = "SP-ACK-v1".utf8()

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
            if (status < AckStatus.RECEIVED || status > AckStatus.CANCELLED) throw MalformedException("status")
            val timestamp = r.u32()
            val team = r.string(r.u8(), Limits.TEAM_BYTES)
            val signature = r.bytes(SIGNATURE_SIZE)
            r.expectEnd()
            IncidentAck(id, status, timestamp, team, signature)
        }

        fun unsignedPart(payload: ByteArray): ByteArray = payload.copyOfRange(0, payload.size - SIGNATURE_SIZE)
    }
}

/**
 * INCIDENT_CANCEL payload, anonymous like the report. The sealed report body holds only the token's hash,
 * so only the author can cancel; staff confirms with a CANCELLED ACK.
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
            r.expectEnd()
            IncidentCancel(id, token)
        }
    }
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
}

object InnerCodec {
    private const val TEXT = 1
    private const val QUICK = 2
    private const val ZONE = 3
    private const val PING = 4
    private const val PONG = 5
    private const val DELIVERED = 6
    private const val GROUP_INVITE = 7

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
