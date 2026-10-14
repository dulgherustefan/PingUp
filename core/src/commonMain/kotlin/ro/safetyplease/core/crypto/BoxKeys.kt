package ro.safetyplease.core.crypto

/**
 * X25519 public keys from the outside (a friend code, an intro, a group invite, a report's reply key) are checked here
 * before they are kept. libsodium refuses to box or seal to a point of small order, whose shared secret is all zeros,
 * so nothing could ever be written to such a key: it is refused where it comes in.
 */
object BoxKeys {
    /**
     * The encodings of the small-order points, as libsodium lists them in has_small_order: 0, 1, the two points of
     * order 8, and p - 1, p, p + 1. The top bit of the last byte is not part of the point.
     */
    internal val SMALL_ORDER: List<ByteArray> = listOf(
        ByteArray(32),
        ByteArray(32).also { it[0] = 1 },
        bytes(
            0xe0, 0xeb, 0x7a, 0x7c, 0x3b, 0x41, 0xb8, 0xae, 0x16, 0x56, 0xe3, 0xfa, 0xf1, 0x9f, 0xc4, 0x6a,
            0xda, 0x09, 0x8d, 0xeb, 0x9c, 0x32, 0xb1, 0xfd, 0x86, 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8, 0x00,
        ),
        bytes(
            0x5f, 0x9c, 0x95, 0xbc, 0xa3, 0x50, 0x8c, 0x24, 0xb1, 0xd0, 0xb1, 0x55, 0x9c, 0x83, 0xef, 0x5b,
            0x04, 0x44, 0x5c, 0xc4, 0x58, 0x1c, 0x8e, 0x86, 0xd8, 0x22, 0x4e, 0xdd, 0xd0, 0x9f, 0x11, 0x57,
        ),
        prime(0xec),
        prime(0xed),
        prime(0xee),
    )

    /** A key of [Crypto.BOX_PUBLIC] bytes that isn't a small-order point. */
    fun acceptable(key: ByteArray?): Boolean =
        key != null && key.size == Crypto.BOX_PUBLIC && SMALL_ORDER.none { sameOver255Bits(it, key) }

    private fun sameOver255Bits(a: ByteArray, b: ByteArray): Boolean {
        for (i in 0 until 31) if (a[i] != b[i]) return false
        return (a[31].toInt() and 0x7f) == (b[31].toInt() and 0x7f)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** p = 2^255 - 19 and its neighbours, little-endian: [low], then 0xff, and 0x7f last. */
    private fun prime(low: Int) = ByteArray(32) { 0xff.toByte() }.also {
        it[0] = low.toByte()
        it[31] = 0x7f
    }
}
