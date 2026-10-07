package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.IncidentAck

class StaffPublicKeys(val box: ByteArray, val sign: ByteArray)

class StaffSecretKeys(val box: KeyPair, val sign: KeyPair)

/**
 * Securitatea incidentelor: rapoartele se sigileaza catre cheia X25519 de staff, iar ACK-urile
 * sunt semnate Ed25519. Cheile publice vin cu aplicatia, cele secrete doar din QR-ul de staff.
 */
class StaffCrypto(private val crypto: Crypto, val publicKeys: StaffPublicKeys) {
    fun sealReport(body: ByteArray): ByteArray = crypto.seal(body, publicKeys.box)

    fun openReport(sealed: ByteArray, secret: StaffSecretKeys): ByteArray? =
        crypto.sealOpen(sealed, publicKeys.box, secret.box.secretKey)

    fun signAck(incidentId: ByteArray, status: Int, timestamp: Long, teamName: String, secret: StaffSecretKeys): IncidentAck {
        val unsigned = IncidentAck.unsigned(incidentId, status, timestamp, teamName)
        val signature = crypto.sign(IncidentAck.signedMessage(unsigned), secret.sign.secretKey)
        return IncidentAck(incidentId, status, timestamp, teamName, signature)
    }

    fun verifyAck(payload: ByteArray): Boolean {
        if (payload.size <= IncidentAck.SIGNATURE_SIZE) return false
        val signature = payload.copyOfRange(payload.size - IncidentAck.SIGNATURE_SIZE, payload.size)
        val message = IncidentAck.signedMessage(IncidentAck.unsignedPart(payload))
        return crypto.verify(signature, message, publicKeys.sign)
    }

    /** Null daca semintele nu corespund cheilor publice ale acestui eveniment. */
    fun secretFromSeeds(boxSeed: ByteArray, signSeed: ByteArray): StaffSecretKeys? {
        val box = crypto.boxKeyPairFromSeed(boxSeed)
        val sign = crypto.signKeyPairFromSeed(signSeed)
        if (!box.publicKey.contentEquals(publicKeys.box) || !sign.publicKey.contentEquals(publicKeys.sign)) return null
        return StaffSecretKeys(box, sign)
    }
}
