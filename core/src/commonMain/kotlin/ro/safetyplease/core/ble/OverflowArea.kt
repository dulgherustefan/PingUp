package ro.safetyplease.core.ble

/**
 * Where iOS puts the service UUIDs of an app that advertises in the background: not in the advertisement, but in
 * Apple's "overflow area", Apple manufacturer data of type 1 holding a 128-bit mask with one bit per UUID, shared by
 * every app in the background on that iPhone. Only an iPhone scanning for the UUID reads it, so an Android phone finds
 * an iPhone with Ping Up in the background by the bit of our service, and connects to it (PROTOCOL.md, section 2).
 *
 * Apple doesn't document it. The bit is a CRC-8 (polynomial 0x09) of the UUID's 16 bytes, low 7 bits; it gives back
 * David G. Young's table read off iPhones (2020), the NHS COVID-19 app's Android filter and Crownstone's captures
 * (`OverflowAreaTest`). An app whose UUID shares our bit (about 1 in 128) is found too, and the link to it fails.
 */
object OverflowArea {
    /** Apple's company identifier. */
    const val APPLE = 0x004C

    /** The type byte, then the 16 bytes of the mask. */
    const val SIZE = 17

    private const val TYPE = 0x01
    private const val MANUFACTURER_DATA = 0xFF

    /** The bit of [uuid] in the mask: byte `bit / 8`, value `1 shl (bit % 8)`. */
    fun bit(uuid: String): Int = crc8(uuidBytes(uuid)) and 0x7f

    /** What an iPhone advertising only [uuid] in the background sends after Apple's company identifier. */
    fun payload(uuid: String): ByteArray = ByteArray(SIZE).also {
        it[0] = TYPE.toByte()
        set(it, bit(uuid))
    }

    /** The mask of a scan filter on [payload]: the type byte and our bit, so other apps' bits don't matter. */
    fun filterMask(uuid: String): ByteArray = ByteArray(SIZE).also {
        it[0] = 0xff.toByte()
        set(it, bit(uuid))
    }

    /**
     * True if a raw advertising [record] (the advertisement and the scan response as Android hands them over) holds an
     * overflow area with [bit] set. Read here byte by byte: Android's own parsing keeps only the last Apple block of a
     * record (14 and earlier) or joins them (15 and later), and Apple may put other data beside it.
     */
    fun inRecord(record: ByteArray?, bit: Int): Boolean {
        if (record == null) return false
        var i = 0
        while (i < record.size) {
            val length = record[i].toInt() and 0xff
            // zeros pad the advertisement before the scan response on some phones
            if (length == 0) {
                i++
                continue
            }
            val end = i + 1 + length
            if (end > record.size) return false
            if (length >= 3 + SIZE && isOverflowArea(record, i + 1) && record[i + 5 + bit / 8].toInt() and (1 shl (bit % 8)) != 0) return true
            i = end
        }
        return false
    }

    private fun isOverflowArea(record: ByteArray, at: Int): Boolean =
        record[at].toInt() and 0xff == MANUFACTURER_DATA &&
            record[at + 1].toInt() and 0xff == APPLE and 0xff && record[at + 2].toInt() and 0xff == APPLE shr 8 &&
            record[at + 3].toInt() == TYPE

    private fun set(area: ByteArray, bit: Int) {
        area[1 + bit / 8] = (area[1 + bit / 8].toInt() or (1 shl (bit % 8))).toByte()
    }

    private fun uuidBytes(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        require(hex.length == 32) { "not a 128-bit UUID: $uuid" }
        return ByteArray(16) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** CRC-8, polynomial 0x09, initial value 0, most significant bit first, no final XOR. */
    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x09 else crc shl 1 }
            crc = crc and 0xff
        }
        return crc
    }
}
