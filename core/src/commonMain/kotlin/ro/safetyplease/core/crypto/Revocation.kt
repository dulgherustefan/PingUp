package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.MalformedException
import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.util.utf8

/**
 * ANNOUNCEMENT of kind 1: certificate ids no phone may trust any more, signed by the organiser's or the event's key
 * ([signer] is its [Certificate.keyId]). The signature takes the last 64 bytes (PROTOCOL.md, section 6.8).
 */
class Revocation(val signer: Long, val issued: Long, val ids: List<Long>, val signature: ByteArray) {
    fun encode(): ByteArray = unsigned(signer, issued, ids) + signature

    companion object {
        const val KIND = 1
        const val MAX_IDS = 32
        private val DOMAIN = "SP-ANN-v1".utf8()

        private fun unsigned(signer: Long, issued: Long, ids: List<Long>): ByteArray {
            require(ids.size in 1..MAX_IDS)
            val w = WireWriter(14 + 8 * ids.size).u8(KIND).i64(signer).u32(issued).u8(ids.size)
            ids.forEach { w.i64(it) }
            return w.toByteArray()
        }

        fun sign(crypto: Crypto, key: KeyPair, issued: Long, ids: List<Long>): Revocation {
            val signer = Certificate.keyId(key.publicKey)
            val signature = crypto.sign(DOMAIN + unsigned(signer, issued, ids), key.secretKey)
            return Revocation(signer, issued, ids, signature)
        }

        /** The bytes the signature covers, as received. */
        fun signedMessage(payload: ByteArray): ByteArray = DOMAIN + payload.copyOfRange(0, payload.size - Certificate.SIGNATURE_SIZE)

        /** Null for another kind of announcement. */
        fun decode(payload: ByteArray): Revocation? = parseOrNull {
            val r = WireReader(payload)
            if (r.u8() != KIND) throw MalformedException("kind")
            val signer = r.i64()
            val issued = r.u32()
            val count = r.u8()
            if (count !in 1..MAX_IDS) throw MalformedException("count")
            val ids = List(count) { r.i64() }
            val signature = r.bytes(Certificate.SIGNATURE_SIZE)
            r.expectEnd()
            Revocation(signer, issued, ids, signature)
        }
    }
}
