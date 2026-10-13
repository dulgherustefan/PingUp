package ro.safetyplease.core.protocol

/**
 * What a 1.1 phone from before the report's extension block and inner kind 17 read, as it shipped: the decoders the new
 * fields have to pass through unharmed.
 */
object Frozen11 {
    /** Flag 0x80 and whatever follows the reply key are left unread. */
    fun body(plain: ByteArray): IncidentBody? = parseOrNull {
        val r = WireReader(plain)
        val category = r.u8()
        if (category == 0) throw MalformedException("category")
        val severity = r.u8()
        if (severity !in 1..3) throw MalformedException("severity")
        val timestamp = r.u32()
        val flags = r.u8()
        val zone = r.string(r.u8(), 32)
        val lat = if (flags and 0x01 != 0) r.i32() / 1e6 else null
        val lon = if (flags and 0x01 != 0) r.i32() / 1e6 else null
        val description = r.string(r.u16(), 300)
        val nick = if (flags and 0x02 != 0) r.string(r.u8(), 24) else null
        val cancelHash = if (flags and 0x04 != 0) r.bytes(16) else null
        val triage = if (flags and 0x08 != 0) r.u8().let { Triage(it and 0x03, (it shr 2) and 0x03) } else null
        val staff = if (flags and 0x10 != 0) StaffFields(r.u8(), r.u8() and 0x01 != 0) else null
        val location = if (flags and 0x20 != 0) LocationFields(r.u8(), r.u8()) else null
        val replyKey = if (flags and 0x40 != 0) r.bytes(32) else null
        IncidentBody(category, severity, timestamp, zone, lat, lon, description, nick, cancelHash, triage, staff, location, replyKey)
    }

    /** The last inner kind it knew; a higher one fails to decode like any unknown kind. */
    const val LAST_INNER_KIND = 16
}
