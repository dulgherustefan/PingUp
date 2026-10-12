package ro.safetyplease.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoRandomTest {
    /** Hands out the same bytes, little end first, and counts how often it was asked. */
    private class FixedBytes(private vararg val bytes: Int) : Crypto by testCrypto {
        var calls = 0

        override fun random(size: Int): ByteArray {
            calls++
            return ByteArray(size) { bytes[it % bytes.size].toByte() }
        }
    }

    @Test
    fun takesItsBitsFromTheSecureGenerator() {
        val crypto = FixedBytes(0x78, 0x56, 0x34, 0x12)
        val random = CryptoRandom(crypto)
        assertEquals(0x12345678, random.nextInt())
        assertEquals(0x1234, random.nextBits(16), "the top bits, as kotlin.random's generators give them")
        assertEquals(0, random.nextBits(0))
        assertEquals(3, crypto.calls)
    }

    @Test
    fun fillsBytesInOneDraw() {
        val crypto = FixedBytes(1, 2, 3)
        val array = ByteArray(8)
        CryptoRandom(crypto).nextBytes(array, 2, 7)
        assertContentEquals(byteArrayOf(0, 0, 1, 2, 3, 1, 2, 0), array)
        assertEquals(1, crypto.calls)
        assertFailsWith<IllegalArgumentException> { CryptoRandom(crypto).nextBytes(array, 5, 9) }
    }

    @Test
    fun staysInBoundsAndDoesNotRepeat() {
        val random = CryptoRandom(testCrypto)
        repeat(1_000) { assertTrue(random.nextInt(7) in 0 until 7) }
        assertEquals(500, List(500) { random.nextLong() }.toSet().size)
    }
}
