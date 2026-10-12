package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.MalformedException
import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toLong
import ro.safetyplease.core.util.utf8

/** What a certificate's key may do; the codes of the three shift roles are those of [StaffRole]. */
object CertRole {
    const val STAFF = 1
    const val ANCHOR = 2
    const val CONTROL = 3
    const val ORGANISER = 0x10
    const val EVENT = 0x11

    /** The key is an X25519 key reports are sealed to for one day, not a signing key. */
    const val REPORT_KEY = 0x12

    /** Root signs organisers, an organiser signs events, an event signs shifts and the days' report keys. */
    fun issues(issuer: Int?, role: Int): Boolean = when (issuer) {
        null -> role == ORGANISER
        ORGANISER -> role == EVENT
        EVENT -> role in setOf(STAFF, ANCHOR, CONTROL, REPORT_KEY)
        else -> false
    }
}

/**
 * A key, what it may do and when, signed by the key above it (PROTOCOL.md, section 9). [issuer] is the [keyId] of
 * the signing key; [name] is the team for a shift, the zone for an anchor.
 */
class Certificate(
    val role: Int,
    val key: ByteArray,
    val validFrom: Long,
    val validUntil: Long,
    val issuer: Long,
    val name: String,
    val signature: ByteArray,
) {
    /** The bytes as received: a name that isn't valid UTF-8 wouldn't encode back to them. */
    private var received: ByteArray? = null

    fun encode(): ByteArray = received?.clone() ?: (unsigned(role, key, validFrom, validUntil, issuer, name) + signature)

    /** What a revocation names: the first 8 bytes of SHA-256 over the whole certificate. */
    val id: Long by lazy { sha256(encode()).toLong() }

    fun signedMessage(): ByteArray = DOMAIN + encode().let { it.copyOfRange(0, it.size - SIGNATURE_SIZE) }

    companion object {
        const val VERSION = 1
        const val KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64
        private val DOMAIN = "SP-CERT-v1".utf8()

        fun keyId(publicKey: ByteArray): Long = sha256(publicKey).toLong()

        private fun unsigned(role: Int, key: ByteArray, from: Long, until: Long, issuer: Long, name: String): ByteArray {
            val nameBytes = name.utf8()
            require(key.size == KEY_SIZE && nameBytes.size <= Limits.TEAM_BYTES)
            return WireWriter(64 + nameBytes.size).u8(VERSION).u8(role).bytes(key).u32(from).u32(until).i64(issuer)
                .u8(nameBytes.size).bytes(nameBytes).toByteArray()
        }

        fun issue(crypto: Crypto, role: Int, key: ByteArray, from: Long, until: Long, name: String, issuer: KeyPair): Certificate {
            val id = keyId(issuer.publicKey)
            val message = DOMAIN + unsigned(role, key, from, until, id, name)
            return Certificate(role, key, from, until, id, name, crypto.sign(message, issuer.secretKey))
        }

        /** Reads one certificate and leaves [r] after it; another version is malformed here. */
        fun read(r: WireReader): Certificate {
            if (r.u8() != VERSION) throw MalformedException("certificate version")
            val role = r.u8()
            val key = r.bytes(KEY_SIZE)
            val from = r.u32()
            val until = r.u32()
            val issuer = r.i64()
            val nameSize = r.u8()
            if (nameSize > Limits.TEAM_BYTES) throw MalformedException("name too long")
            val name = r.bytes(nameSize)
            val signature = r.bytes(SIGNATURE_SIZE)
            val raw = WireWriter(nameSize + 115).u8(VERSION).u8(role).bytes(key).u32(from).u32(until).i64(issuer)
                .u8(nameSize).bytes(name).bytes(signature).toByteArray()
            return Certificate(role, key, from, until, issuer, String(name, Charsets.UTF_8), signature).also { it.received = raw }
        }

        fun decode(bytes: ByteArray): Certificate? = parseOrNull {
            val r = WireReader(bytes)
            read(r).also { r.expectEnd() }
        }
    }
}

/** A shift holder's certificate and the key pair it names: what signs this phone's ACKs and HELLO proofs. */
class Credential(val certificate: Certificate, val key: KeyPair)
