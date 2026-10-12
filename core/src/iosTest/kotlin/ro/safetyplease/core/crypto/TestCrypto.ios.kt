package ro.safetyplease.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import sodium.crypto_generichash

actual val testCrypto: Crypto by lazy { SodiumCrypto() }

actual fun cryptoWithRandom(random: (Int) -> ByteArray): Crypto = SodiumCrypto(random)

@OptIn(ExperimentalForeignApi::class)
actual fun genericHash(input: ByteArray, size: Int): ByteArray {
    val out = UByteArray(size)
    val result = out.usePinned { o ->
        input.asUByteArray().usePinned { i -> crypto_generichash(o.addressOf(0), size.convert(), i.addressOf(0), input.size.convert(), null, 0u) }
    }
    check(result == 0)
    return out.asByteArray()
}
