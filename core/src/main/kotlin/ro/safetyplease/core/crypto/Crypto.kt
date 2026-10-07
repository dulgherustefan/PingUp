package ro.safetyplease.core.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign

class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

/** The app's crypto operations. Everything comes from libsodium; nothing is hand-rolled here. */
interface Crypto {
    fun random(size: Int): ByteArray

    fun boxKeyPair(): KeyPair

    fun signKeyPair(): KeyPair

    fun boxKeyPairFromSeed(seed: ByteArray): KeyPair

    fun signKeyPairFromSeed(seed: ByteArray): KeyPair

    /** crypto_box with a random nonce; returns the nonce followed by the ciphertext. */
    fun box(plain: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray

    fun boxOpen(data: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray?

    /** Sealed box: anyone can encrypt to the public key and the sender stays anonymous. */
    fun seal(plain: ByteArray, recipientPublic: ByteArray): ByteArray

    fun sealOpen(data: ByteArray, recipientPublic: ByteArray, recipientSecret: ByteArray): ByteArray?

    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    companion object {
        const val BOX_PUBLIC = Box.PUBLICKEYBYTES
        const val BOX_SECRET = Box.SECRETKEYBYTES
        const val BOX_OVERHEAD = Box.NONCEBYTES + Box.MACBYTES
        const val SEAL_OVERHEAD = Box.SEALBYTES
        const val SIGN_PUBLIC = Sign.PUBLICKEYBYTES
        const val SIGN_SECRET = Sign.SECRETKEYBYTES
        const val SIGNATURE = Sign.BYTES
        const val SEED = 32
    }
}

class SodiumCrypto(private val sodium: LazySodium) : Crypto {
    override fun random(size: Int): ByteArray = sodium.randomBytesBuf(size)

    override fun boxKeyPair(): KeyPair {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check(sodium.cryptoBoxKeypair(pk, sk))
        return KeyPair(pk, sk)
    }

    override fun signKeyPair(): KeyPair {
        val pk = ByteArray(Sign.PUBLICKEYBYTES)
        val sk = ByteArray(Sign.SECRETKEYBYTES)
        check(sodium.cryptoSignKeypair(pk, sk))
        return KeyPair(pk, sk)
    }

    override fun boxKeyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == Crypto.SEED)
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check(sodium.cryptoBoxSeedKeypair(pk, sk, seed))
        return KeyPair(pk, sk)
    }

    override fun signKeyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == Crypto.SEED)
        val pk = ByteArray(Sign.PUBLICKEYBYTES)
        val sk = ByteArray(Sign.SECRETKEYBYTES)
        check(sodium.cryptoSignSeedKeypair(pk, sk, seed))
        return KeyPair(pk, sk)
    }

    override fun box(plain: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray {
        val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
        val cipher = ByteArray(plain.size + Box.MACBYTES)
        check(sodium.cryptoBoxEasy(cipher, plain, plain.size.toLong(), nonce, theirPublic, mySecret))
        return nonce + cipher
    }

    override fun boxOpen(data: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (data.size < Crypto.BOX_OVERHEAD || theirPublic.size != Box.PUBLICKEYBYTES) return null
        val nonce = data.copyOfRange(0, Box.NONCEBYTES)
        val cipher = data.copyOfRange(Box.NONCEBYTES, data.size)
        val plain = ByteArray(cipher.size - Box.MACBYTES)
        val ok = sodium.cryptoBoxOpenEasy(plain, cipher, cipher.size.toLong(), nonce, theirPublic, mySecret)
        return if (ok) plain else null
    }

    override fun seal(plain: ByteArray, recipientPublic: ByteArray): ByteArray {
        val cipher = ByteArray(plain.size + Box.SEALBYTES)
        check(sodium.cryptoBoxSeal(cipher, plain, plain.size.toLong(), recipientPublic))
        return cipher
    }

    override fun sealOpen(data: ByteArray, recipientPublic: ByteArray, recipientSecret: ByteArray): ByteArray? {
        if (data.size < Box.SEALBYTES) return null
        val plain = ByteArray(data.size - Box.SEALBYTES)
        val ok = sodium.cryptoBoxSealOpen(plain, data, data.size.toLong(), recipientPublic, recipientSecret)
        return if (ok) plain else null
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(Sign.BYTES)
        check(sodium.cryptoSignDetached(signature, message, message.size.toLong(), secretKey))
        return signature
    }

    override fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != Sign.BYTES || publicKey.size != Sign.PUBLICKEYBYTES) return false
        return sodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)
    }
}
