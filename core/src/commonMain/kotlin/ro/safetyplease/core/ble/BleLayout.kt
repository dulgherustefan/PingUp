package ro.safetyplease.core.ble

import ro.safetyplease.core.protocol.NodeFlags

/**
 * The Bluetooth layout every Ping Up phone keeps, Android or iPhone: one service with one characteristic, and the
 * advertising data under it (PROTOCOL.md, section 2). Changing any of it breaks compatibility with older versions.
 */
object BleLayout {
    const val SERVICE = "f24a82e1-f0bc-471f-9cae-a9598a2a4dc4"

    /** Write without response, central to server; notify, server to central. One frame per value. */
    const val CHARACTERISTIC = "c5d82591-cfb9-45dd-bea5-8447e1811ba6"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** What an ATT value leaves of the MTU. */
    const val ATT_OVERHEAD = 3
}

/** What a phone's advertising says of it: its [flags] and the [prefix] it goes by now. */
data class AdvertFields(val flags: Int, val prefix: Int?)

/** The six bytes of service data: version, flags, then the prefix, most significant byte first. */
object AdvertData {
    const val VERSION = 1
    const val SIZE = 6

    fun encode(flags: Int, prefix: Int): ByteArray = byteArrayOf(
        VERSION.toByte(), flags.toByte(),
        (prefix ushr 24).toByte(), (prefix ushr 16).toByte(), (prefix ushr 8).toByte(), prefix.toByte(),
    )

    /**
     * No data, too little or another version: the phone accepts connections and its prefix is unknown. An iPhone's
     * advertising is always like that, since iOS doesn't let an app advertise service data.
     */
    fun decode(data: ByteArray?): AdvertFields {
        if (data == null || data.size < SIZE || data[0].toInt() and 0xff != VERSION) return AdvertFields(NodeFlags.ACCEPTS_CONNECTIONS, null)
        val prefix = (data[2].toInt() and 0xff shl 24) or (data[3].toInt() and 0xff shl 16) or (data[4].toInt() and 0xff shl 8) or (data[5].toInt() and 0xff)
        return AdvertFields(data[1].toInt() and 0xff, prefix)
    }
}
