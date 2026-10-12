package ro.safetyplease.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

private val sodium = LazySodiumJava(SodiumJava())

actual val testCrypto: Crypto by lazy { SodiumCrypto(sodium) }

// The only randomness libsodium takes from Java is the crypto_box nonce
actual fun cryptoWithRandom(random: (Int) -> ByteArray): Crypto = SodiumCrypto(
    object : LazySodiumJava(SodiumJava()) {
        override fun randomBytesBuf(size: Int): ByteArray = random(size)
    },
)

actual fun genericHash(input: ByteArray, size: Int): ByteArray {
    val out = ByteArray(size)
    check(sodium.cryptoGenericHash(out, size, input, input.size.toLong()))
    return out
}
