package ro.safetyplease.app.crypto

import ro.safetyplease.app.core.sha256
import ro.safetyplease.app.core.toLong

/** Identitatea unui telefon: o pereche X25519 (criptare) si una Ed25519 (semnare). */
class Identity(val box: KeyPair, val sign: KeyPair) {
    val nodeId: Long = nodeIdOf(sign.publicKey, box.publicKey)

    fun export(): ByteArray = box.publicKey + box.secretKey + sign.publicKey + sign.secretKey

    companion object {
        private const val SIZE = Crypto.BOX_PUBLIC + Crypto.BOX_SECRET + Crypto.SIGN_PUBLIC + Crypto.SIGN_SECRET

        /** nodeId = primii 8 octeti din SHA-256(cheia publica Ed25519 || cheia publica X25519). */
        fun nodeIdOf(signPublic: ByteArray, boxPublic: ByteArray): Long = sha256(signPublic + boxPublic).toLong()

        fun generate(crypto: Crypto): Identity {
            while (true) {
                val identity = Identity(crypto.boxKeyPair(), crypto.signKeyPair())
                // 0 e rezervat pentru expeditor anonim
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
