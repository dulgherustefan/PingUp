package ro.safetyplease.core.protocol

import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.util.utf8

/**
 * A PRIVATE addressed to [ID] is for the control-room console, not for a phone (PROTOCOL.md, section 6.9): relays
 * pass it on like any PRIVATE, 1.0 ones included, and only a console delivers it. Its payload is sealed to the staff
 * box key and opens to a plaintext whose first byte is the kind.
 */
object ControlChannel {
    /** "SP-CTRL1" in ASCII. A nodeId is a hash, so no phone has this one but by a 2^-64 chance. */
    const val ID = 0x5350_2D43_5452_4C31L

    const val HEARTBEAT = 1
    const val POINT_STATUS = 2
}

/**
 * What an anchor with the mobile network log on measured last (PROTOCOL.md, section 6.9). The codes are the
 * constants below; a [technology] this version doesn't know reads as [TECH_UNKNOWN].
 */
data class MobileReading(
    /** Signal bars, 0..4. */
    val level: Int?,
    val technology: Int,
    val transport: Int,
    val probe: Int,
    /** Android found that the network in use reaches the internet. */
    val validated: Boolean,
) {
    companion object {
        const val TECH_UNKNOWN = 0
        val TECHNOLOGIES = listOf("", "gsm", "cdma", "wcdma", "tdscdma", "lte", "nr")

        const val TRANSPORT_NONE = 0
        const val TRANSPORT_CELLULAR = 1
        const val TRANSPORT_WIFI = 2
        const val TRANSPORT_OTHER = 3

        const val PROBE_NOT_RUN = 0
        const val PROBE_OK = 1
        const val PROBE_FAILED = 2
    }
}

/**
 * What an anchor tells the console about itself. Nothing signs it yet, so it says what the sender claims.
 * Unknown values are null.
 */
data class Heartbeat(
    /** 0..100. */
    val batteryPercent: Int?,
    val charging: Boolean,
    /** Of the battery, in whole degrees Celsius, -127..127. */
    val temperatureC: Int?,
    /** Seconds since the anchor last heard a phone, at most [MAX_QUIET_S]; null if it never did. */
    val quietS: Int?,
    val links: Int,
    /** Phones heard around the anchor, as an index into [CROWD]. */
    val crowd: Int,
    /** The zone on the anchor's QR. */
    val zone: String,
    /** Null when the anchor doesn't log the mobile network. */
    val mobile: MobileReading? = null,
    /** The anchor's square of the site grid, 0-based as in [ro.safetyplease.core.venue.SiteGrid]; null when unknown. */
    val column: Int? = null,
    val row: Int? = null,
) {
    fun encode(): ByteArray {
        val zoneBytes = zone.utf8()
        require(batteryPercent == null || batteryPercent in 0..100)
        require(temperatureC == null || temperatureC in -127..127)
        require(quietS == null || quietS in 0..MAX_QUIET_S)
        require(links in 0..0xFF && crowd in CROWD.indices && zoneBytes.size <= Limits.ZONE_BYTES)
        require((column == null) == (row == null) && (column ?: 0) in 0 until MAX_COLUMNS && (row ?: 0) in 0 until MAX_ROWS)
        val w = WireWriter(10 + zoneBytes.size + SITE_BYTES)
            .u8(ControlChannel.HEARTBEAT)
            .u8(if (charging) CHARGING else 0)
            .u8(batteryPercent ?: UNKNOWN_U8)
            .u8(temperatureC ?: UNKNOWN_TEMPERATURE)
            .u16(quietS ?: UNKNOWN_U16)
            .u8(links)
            .u8(crowd)
            .u8(zoneBytes.size).bytes(zoneBytes)
        if (mobile == null && column == null) return w.toByteArray()
        var data = 0
        if (mobile != null) {
            require((mobile.level ?: 0) in 0..4 && mobile.technology in MobileReading.TECHNOLOGIES.indices)
            require(mobile.transport in 0..3 && mobile.probe in 0..MobileReading.PROBE_FAILED)
            data = MEASURED or mobile.transport or (mobile.probe shl 2) or (if (mobile.validated) VALIDATED else 0)
        }
        return w.u8(mobile?.level ?: UNKNOWN_U8)
            .u8(mobile?.technology ?: MobileReading.TECH_UNKNOWN)
            .u8(data)
            .u8(column ?: UNKNOWN_U8)
            .u8(row ?: UNKNOWN_U8)
            .toByteArray()
    }

    companion object {
        private const val CHARGING = 0x01
        private const val UNKNOWN_U8 = 0xFF
        private const val UNKNOWN_U16 = 0xFFFF
        private const val UNKNOWN_TEMPERATURE = 0x80
        const val MAX_QUIET_S = 0xFFFE

        /** The site block after the zone: signal level, technology, data, column, row. */
        const val SITE_BYTES = 5
        private const val MEASURED = 0x80
        private const val VALIDATED = 0x10
        private const val MAX_COLUMNS = 26
        private const val MAX_ROWS = 99

        /** The fewest phones in each crowd bucket: none, 1-2, 3-5, 6-10, 11-20, 21-50, 51-100, more. */
        val CROWD = intArrayOf(0, 1, 3, 6, 11, 21, 51, 101)

        fun crowdOf(phones: Int): Int = CROWD.indexOfLast { phones >= it }.coerceAtLeast(0)

        /**
         * Null for another kind or a value out of range. Flag bits, a tail after the zone shorter than [SITE_BYTES]
         * and bytes after the site block are a newer version's; a value in the site block it can't use reads as unknown.
         */
        fun decode(plain: ByteArray): Heartbeat? = parseOrNull {
            val r = WireReader(plain)
            if (r.u8() != ControlChannel.HEARTBEAT) throw MalformedException("kind")
            val flags = r.u8()
            val battery = r.u8()
            if (battery > 100 && battery != UNKNOWN_U8) throw MalformedException("battery")
            val temperature = r.u8()
            val quiet = r.u16()
            val links = r.u8()
            val crowd = r.u8()
            if (crowd !in CROWD.indices) throw MalformedException("crowd")
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            val beat = Heartbeat(
                batteryPercent = battery.takeIf { it != UNKNOWN_U8 },
                charging = flags and CHARGING != 0,
                temperatureC = temperature.takeIf { it != UNKNOWN_TEMPERATURE }?.toByte()?.toInt(),
                quietS = quiet.takeIf { it != UNKNOWN_U16 },
                links = links,
                crowd = crowd,
                zone = zone,
            )
            if (r.remaining < SITE_BYTES) return@parseOrNull beat
            val level = r.u8()
            val technology = r.u8()
            val data = r.u8()
            val column = r.u8()
            val row = r.u8()
            val mobile = if (data and MEASURED == 0) {
                null
            } else {
                MobileReading(
                    level = level.takeIf { it <= 4 },
                    technology = technology.takeIf { it in MobileReading.TECHNOLOGIES.indices } ?: MobileReading.TECH_UNKNOWN,
                    transport = data and 0x03,
                    probe = ((data shr 2) and 0x03).takeIf { it <= MobileReading.PROBE_FAILED } ?: MobileReading.PROBE_NOT_RUN,
                    validated = data and VALIDATED != 0,
                )
            }
            val placed = column < MAX_COLUMNS && row < MAX_ROWS
            beat.copy(mobile = mobile, column = column.takeIf { placed }, row = row.takeIf { placed })
        }
    }
}

/** Levels of a point's status. Another level is a newer version's; this one drops it. */
object PointLevel {
    const val FREE = 1
    const val BUSY = 2
    const val CLOSED = 3

    val ALL = FREE..CLOSED
}

/**
 * A staff phone tells the console how a point of the site is (PROTOCOL.md, section 6.9): [point] is the number of a
 * POI in the event's venue, [issued] the sender's clock in seconds. Signed like an ACK, with the key of [certificate]
 * or, without one, with the build's staff key; [extensions] are a newer version's, signed and kept as they came.
 */
class PointStatus(
    val point: Int,
    val level: Int,
    val issued: Long,
    val teamName: String,
    val certificate: Certificate?,
    val signature: ByteArray,
    val extensions: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray = unsigned(point, level, issued, teamName, certificate) + extensions + signature

    companion object {
        /** Flag bit: the signer's certificate follows the team name. The other bits are reserved and ignored. */
        const val CERT = 0x01
        const val SIGNATURE_SIZE = 64
        private val DOMAIN = "SP-POINT-v1".utf8()

        fun unsigned(point: Int, level: Int, issued: Long, teamName: String, certificate: Certificate?): ByteArray {
            val team = teamName.utf8()
            require(point in 1..0xFF && level in PointLevel.ALL && issued in 0..0xFFFF_FFFFL && team.size <= Limits.TEAM_BYTES)
            val w = WireWriter(48)
                .u8(ControlChannel.POINT_STATUS)
                .u8(if (certificate != null) CERT else 0)
                .u8(point)
                .u8(level)
                .u32(issued)
                .u8(team.size).bytes(team)
            certificate?.let { w.bytes(it.encode()) }
            return w.toByteArray()
        }

        /** Bytes covered by the signature; the prefix binds it to this message type. */
        fun signedMessage(unsigned: ByteArray): ByteArray = DOMAIN + unsigned

        fun unsignedPart(payload: ByteArray): ByteArray = payload.copyOfRange(0, payload.size - SIGNATURE_SIZE)

        /** Null for another kind, point 0 or a level this version doesn't know. */
        fun decode(plain: ByteArray): PointStatus? = parseOrNull {
            val r = WireReader(plain)
            if (r.u8() != ControlChannel.POINT_STATUS) throw MalformedException("kind")
            val flags = r.u8()
            val point = r.u8()
            if (point == 0) throw MalformedException("point")
            val level = r.u8()
            if (level !in PointLevel.ALL) throw MalformedException("level")
            val issued = r.u32()
            val team = r.string(r.u8(), Limits.TEAM_BYTES)
            val certificate = if (flags and CERT != 0) Certificate.read(r) else null
            if (r.remaining < SIGNATURE_SIZE) throw MalformedException("signature")
            val extensions = r.bytes(r.remaining - SIGNATURE_SIZE)
            PointStatus(point, level, issued, team, certificate, r.bytes(SIGNATURE_SIZE), extensions)
        }
    }
}
