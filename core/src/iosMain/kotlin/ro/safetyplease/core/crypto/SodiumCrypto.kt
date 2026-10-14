package ro.safetyplease.core.crypto

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import sodium.crypto_box_easy
import sodium.crypto_box_keypair
import sodium.crypto_box_open_easy
import sodium.crypto_box_seal
import sodium.crypto_box_seal_open
import sodium.crypto_box_seed_keypair
import sodium.crypto_sign_detached
import sodium.crypto_sign_keypair
import sodium.crypto_sign_seed_keypair
import sodium.crypto_sign_verify_detached
import sodium.randombytes_buf
import sodium.sodium_init

/**
 * libsodium linked statically (tools/build_libsodium_ios.sh), the same calls as lazysodium makes on Android.
 * [randomSource] stands in for randombytes_buf, so a test can fix the nonce; nothing else should pass it.
 */
@OptIn(ExperimentalForeignApi::class)
class SodiumCrypto(private val randomSource: ((Int) -> ByteArray)? = null) : Crypto {
    init {
        check(sodium_init() >= 0) { "libsodium failed to start" }
    }

    override fun random(size: Int): ByteArray {
        randomSource?.let { return it(size) }
        val out = ByteArray(size)
        if (size > 0) out.usePinned { randombytes_buf(it.addressOf(0), size.convert()) }
        return out
    }

    override fun boxKeyPair(): KeyPair = keyPair(Crypto.BOX_PUBLIC, Crypto.BOX_SECRET) { pk, sk -> crypto_box_keypair(pk, sk) }

    override fun signKeyPair(): KeyPair = keyPair(Crypto.SIGN_PUBLIC, Crypto.SIGN_SECRET) { pk, sk -> crypto_sign_keypair(pk, sk) }

    override fun boxKeyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == Crypto.SEED)
        return pinned(seed) { s -> keyPair(Crypto.BOX_PUBLIC, Crypto.BOX_SECRET) { pk, sk -> crypto_box_seed_keypair(pk, sk, s) } }
    }

    override fun signKeyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == Crypto.SEED)
        return pinned(seed) { s -> keyPair(Crypto.SIGN_PUBLIC, Crypto.SIGN_SECRET) { pk, sk -> crypto_sign_seed_keypair(pk, sk, s) } }
    }

    override fun box(plain: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        // libsodium reads 32 bytes from each key, whatever the array holds
        if (theirPublic.size != Crypto.BOX_PUBLIC || mySecret.size != Crypto.BOX_SECRET) return null
        val nonce = random(Crypto.BOX_NONCE)
        val cipher = ByteArray(plain.size + Crypto.BOX_MAC)
        val result = pinned(cipher) { c ->
            pinned(plain) { m ->
                pinned(nonce) { n -> pinned(theirPublic) { pk -> pinned(mySecret) { sk -> crypto_box_easy(c, m, plain.size.convert(), n, pk, sk) } } }
            }
        }
        // libsodium refuses a small-order key: the shared secret would be all zeros
        return if (result == 0) nonce + cipher else null
    }

    override fun boxOpen(data: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray? {
        if (data.size < Crypto.BOX_OVERHEAD || theirPublic.size != Crypto.BOX_PUBLIC) return null
        val nonce = data.copyOfRange(0, Crypto.BOX_NONCE)
        val cipher = data.copyOfRange(Crypto.BOX_NONCE, data.size)
        val plain = ByteArray(cipher.size - Crypto.BOX_MAC)
        val result = pinned(plain) { m ->
            pinned(cipher) { c ->
                pinned(nonce) { n -> pinned(theirPublic) { pk -> pinned(mySecret) { sk -> crypto_box_open_easy(m, c, cipher.size.convert(), n, pk, sk) } } }
            }
        }
        return if (result == 0) plain else null
    }

    override fun seal(plain: ByteArray, recipientPublic: ByteArray): ByteArray? {
        if (recipientPublic.size != Crypto.BOX_PUBLIC) return null
        val cipher = ByteArray(plain.size + Crypto.SEAL_OVERHEAD)
        val result = pinned(cipher) { c -> pinned(plain) { m -> pinned(recipientPublic) { pk -> crypto_box_seal(c, m, plain.size.convert(), pk) } } }
        return if (result == 0) cipher else null
    }

    override fun sealOpen(data: ByteArray, recipientPublic: ByteArray, recipientSecret: ByteArray): ByteArray? {
        if (data.size < Crypto.SEAL_OVERHEAD) return null
        val plain = ByteArray(data.size - Crypto.SEAL_OVERHEAD)
        val result = pinned(plain) { m ->
            pinned(data) { c -> pinned(recipientPublic) { pk -> pinned(recipientSecret) { sk -> crypto_box_seal_open(m, c, data.size.convert(), pk, sk) } } }
        }
        return if (result == 0) plain else null
    }

    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(Crypto.SIGNATURE)
        val result = pinned(signature) { s -> pinned(message) { m -> pinned(secretKey) { sk -> crypto_sign_detached(s, null, m, message.size.convert(), sk) } } }
        check(result == 0)
        return signature
    }

    override fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != Crypto.SIGNATURE || publicKey.size != Crypto.SIGN_PUBLIC) return false
        val result = pinned(signature) { s -> pinned(message) { m -> pinned(publicKey) { pk -> crypto_sign_verify_detached(s, m, message.size.convert(), pk) } } }
        return result == 0
    }

    private fun keyPair(publicSize: Int, secretSize: Int, generate: (CPointer<UByteVar>?, CPointer<UByteVar>?) -> Int): KeyPair {
        val pk = ByteArray(publicSize)
        val sk = ByteArray(secretSize)
        val result = pinned(pk) { p -> pinned(sk) { s -> generate(p, s) } }
        check(result == 0)
        return KeyPair(pk, sk)
    }

    /** An empty array has no address; libsodium accepts a null pointer with length 0. */
    private inline fun <R> pinned(bytes: ByteArray, block: (CPointer<UByteVar>?) -> R): R =
        if (bytes.isEmpty()) block(null) else bytes.asUByteArray().usePinned { block(it.addressOf(0)) }
}
