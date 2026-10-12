package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.IncidentAck

class StaffPublicKeys(val box: ByteArray, val sign: ByteArray)

class StaffSecretKeys(val box: KeyPair, val sign: KeyPair)

/**
 * Incident security: reports are sealed to the staff X25519 key and ACKs are signed with Ed25519.
 * Public keys ship with the app; secret keys only come from the staff QR.
 */
class StaffCrypto(private val crypto: Crypto, val publicKeys: StaffPublicKeys) {
    fun sealReport(body: ByteArray): ByteArray = crypto.seal(body, publicKeys.box)

    fun openReport(sealed: ByteArray, secret: StaffSecretKeys): ByteArray? =
        crypto.sealOpen(sealed, publicKeys.box, secret.box.secretKey)

    /** [flags] are AckFlags bits; they and the [epoch] travel in the ACK's extensions, under the signature. */
    fun signAck(
        incidentId: ByteArray,
        status: Int,
        timestamp: Long,
        teamName: String,
        secret: StaffSecretKeys,
        flags: Int = 0,
        epoch: Int = 0,
    ): IncidentAck {
        val extensions = IncidentAck.extensions(flags, epoch)
        val signed = IncidentAck.unsigned(incidentId, status, timestamp, teamName) + extensions
        val signature = crypto.sign(IncidentAck.signedMessage(signed), secret.sign.secretKey)
        return IncidentAck(incidentId, status, timestamp, teamName, signature, extensions)
    }

    fun verifyAck(payload: ByteArray): Boolean {
        if (payload.size <= IncidentAck.SIGNATURE_SIZE) return false
        val signature = payload.copyOfRange(payload.size - IncidentAck.SIGNATURE_SIZE, payload.size)
        val message = IncidentAck.signedMessage(IncidentAck.unsignedPart(payload))
        return crypto.verify(signature, message, publicKeys.sign)
    }

    /** Null if the seeds don't match this event's public keys. */
    fun secretFromSeeds(boxSeed: ByteArray, signSeed: ByteArray): StaffSecretKeys? {
        val box = crypto.boxKeyPairFromSeed(boxSeed)
        val sign = crypto.signKeyPairFromSeed(signSeed)
        if (!box.publicKey.contentEquals(publicKeys.box) || !sign.publicKey.contentEquals(publicKeys.sign)) return null
        return StaffSecretKeys(box, sign)
    }
}
