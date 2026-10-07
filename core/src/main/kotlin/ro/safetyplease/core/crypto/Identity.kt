package ro.safetyplease.core.crypto

import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toLong

/** A phone's identity: an X25519 pair (encryption) and an Ed25519 pair (signing). */
class Identity(val box: KeyPair, val sign: KeyPair) {
    val nodeId: Long = nodeIdOf(sign.publicKey, box.publicKey)

    fun export(): ByteArray = box.publicKey + box.secretKey + sign.publicKey + sign.secretKey

    companion object {
        private const val SIZE = Crypto.BOX_PUBLIC + Crypto.BOX_SECRET + Crypto.SIGN_PUBLIC + Crypto.SIGN_SECRET

        /** nodeId = first 8 bytes of SHA-256(Ed25519 public key || X25519 public key). */
        fun nodeIdOf(signPublic: ByteArray, boxPublic: ByteArray): Long = sha256(signPublic + boxPublic).toLong()

        fun generate(crypto: Crypto): Identity {
            while (true) {
                val identity = Identity(crypto.boxKeyPair(), crypto.signKeyPair())
                // 0 is reserved for anonymous senders
                if (identity.nodeId != 0L) return identity
            }
        }

        fun import(bytes: ByteArray): Identity? {
            if (bytes.size != SIZE) return null
            var at = 0
            fun take(n: Int) = bytes.copyOfRange(at, at + n).also { at += n }
            val boxPk = take(Crypto.BOX_PUBLIC)
            val boxSk = take(Crypto.BOX_SECRET)
            val signPk = take(Crypto.SIGN_PUBLIC)
            val signSk = take(Crypto.SIGN_SECRET)
            return Identity(KeyPair(boxPk, boxSk), KeyPair(signPk, signSk))
        }
    }
}
