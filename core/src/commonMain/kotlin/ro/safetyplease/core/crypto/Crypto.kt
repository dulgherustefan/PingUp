package ro.safetyplease.core.crypto

class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

/** The app's crypto operations. Everything comes from libsodium; nothing is hand-rolled here. */
interface Crypto {
    fun random(size: Int): ByteArray

    fun boxKeyPair(): KeyPair

    fun signKeyPair(): KeyPair

    fun boxKeyPairFromSeed(seed: ByteArray): KeyPair

    fun signKeyPairFromSeed(seed: ByteArray): KeyPair

    /**
     * crypto_box with a random nonce; returns the nonce followed by the ciphertext. Null when libsodium refuses the
     * keys: a key of the wrong size, or a small-order point ([BoxKeys.acceptable]), which a peer can send on purpose.
     */
    fun box(plain: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray?

    fun boxOpen(data: ByteArray, theirPublic: ByteArray, mySecret: ByteArray): ByteArray?

    /** Sealed box: anyone can encrypt to the public key and the sender stays anonymous. Null when libsodium refuses the key, as [box]. */
    fun seal(plain: ByteArray, recipientPublic: ByteArray): ByteArray?

    fun sealOpen(data: ByteArray, recipientPublic: ByteArray, recipientSecret: ByteArray): ByteArray?

    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray

    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean

    /** libsodium's sizes, the same on every platform (crypto_box_*, crypto_sign_*). */
    companion object {
        const val BOX_PUBLIC = 32
        const val BOX_SECRET = 32
        const val BOX_NONCE = 24
        const val BOX_MAC = 16
        const val BOX_OVERHEAD = BOX_NONCE + BOX_MAC
        const val SEAL_OVERHEAD = 48
        const val SIGN_PUBLIC = 32
        const val SIGN_SECRET = 64
        const val SIGNATURE = 64
        const val SEED = 32
    }
}
