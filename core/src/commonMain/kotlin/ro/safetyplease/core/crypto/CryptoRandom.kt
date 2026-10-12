package ro.safetyplease.core.crypto

import kotlin.random.Random

/**
 * A [Random] that takes every bit from [crypto]'s secure generator, for ids and timings nobody should guess. The same
 * on every platform: the JVM's SecureRandom isn't there on iOS, and kotlin.random's default generator isn't secure.
 */
class CryptoRandom(private val crypto: Crypto) : Random() {
    override fun nextBits(bitCount: Int): Int {
        val b = crypto.random(4)
        val bits = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or ((b[2].toInt() and 0xff) shl 16) or (b[3].toInt() shl 24)
        // the top bitCount bits, as kotlin.random's own generators give them; none for 0
        return (bits ushr (32 - bitCount)) and (-bitCount shr 31)
    }

    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        require(fromIndex in 0..array.size && toIndex in fromIndex..array.size) { "$fromIndex..$toIndex out of 0..${array.size}" }
        crypto.random(toIndex - fromIndex).copyInto(array, fromIndex)
        return array
    }
}
