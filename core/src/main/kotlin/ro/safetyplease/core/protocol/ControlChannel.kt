package ro.safetyplease.core.protocol

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
) {
    fun encode(): ByteArray {
        val zoneBytes = zone.utf8()
        require(batteryPercent == null || batteryPercent in 0..100)
        require(temperatureC == null || temperatureC in -127..127)
        require(quietS == null || quietS in 0..MAX_QUIET_S)
        require(links in 0..0xFF && crowd in CROWD.indices && zoneBytes.size <= Limits.ZONE_BYTES)
        return WireWriter(10 + zoneBytes.size)
            .u8(ControlChannel.HEARTBEAT)
            .u8(if (charging) CHARGING else 0)
            .u8(batteryPercent ?: UNKNOWN_U8)
            .u8(temperatureC ?: UNKNOWN_TEMPERATURE)
            .u16(quietS ?: UNKNOWN_U16)
            .u8(links)
            .u8(crowd)
            .u8(zoneBytes.size).bytes(zoneBytes)
            .toByteArray()
    }

    companion object {
        private const val CHARGING = 0x01
        private const val UNKNOWN_U8 = 0xFF
        private const val UNKNOWN_U16 = 0xFFFF
        private const val UNKNOWN_TEMPERATURE = 0x80
        const val MAX_QUIET_S = 0xFFFE

        /** The fewest phones in each crowd bucket: none, 1-2, 3-5, 6-10, 11-20, 21-50, 51-100, more. */
        val CROWD = intArrayOf(0, 1, 3, 6, 11, 21, 51, 101)

        fun crowdOf(phones: Int): Int = CROWD.indexOfLast { phones >= it }.coerceAtLeast(0)

        /** Null for another kind or a value out of range. Flag bits and bytes after the zone are a newer version's. */
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
            Heartbeat(
                batteryPercent = battery.takeIf { it != UNKNOWN_U8 },
                charging = flags and CHARGING != 0,
                temperatureC = temperature.takeIf { it != UNKNOWN_TEMPERATURE }?.toByte()?.toInt(),
                quietS = quiet.takeIf { it != UNKNOWN_U16 },
                links = links,
                crowd = crowd,
                zone = zone,
            )
        }
    }
}
