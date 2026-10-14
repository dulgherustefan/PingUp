package ro.safetyplease.core.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign

/** libsodium through lazysodium: the Java one in the tests, the Android one on the phone (same API). */
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

    override fun box(plain: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (theirPublic.size != Box.PUBLICKEYBYTES || mySecret.size != Box.SECRETKEYBYTES) return null
        val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
        val cipher = ByteArray(plain.size + Box.MACBYTES)
        // libsodium refuses a small-order key: the shared secret would be all zeros
        if (!sodium.cryptoBoxEasy(cipher, plain, plain.size.toLong(), nonce, theirPublic, mySecret)) return null
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

    override fun seal(plain: ByteArray, recipientPublic: ByteArray): ByteArray? {
        if (recipientPublic.size != Box.PUBLICKEYBYTES) return null
        val cipher = ByteArray(plain.size + Box.SEALBYTES)
        if (!sodium.cryptoBoxSeal(cipher, plain, plain.size.toLong(), recipientPublic)) return null
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
