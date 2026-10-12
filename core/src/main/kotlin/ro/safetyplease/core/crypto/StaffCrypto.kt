package ro.safetyplease.core.crypto

import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentResponse
import ro.safetyplease.core.protocol.ResponseBody
import ro.safetyplease.core.util.Clock

/** The build's shared keys: [box] opens reports outside the event's days, [sign] checks ACKs from SPS1 phones. */
class StaffPublicKeys(val box: ByteArray, val sign: ByteArray)

/**
 * [box] opens reports; null on a staff phone that only relays them and sees their ACKs. [sign] signs ACKs: the key
 * [certificate] names, or the build's shared one without a certificate (SPS1). [legacyBox] is the build's report key,
 * kept next to the day's while phones that don't know the day's key are still around.
 */
class StaffSecretKeys(
    val box: KeyPair?,
    val sign: KeyPair,
    val certificate: Certificate? = null,
    val legacyBox: KeyPair? = null,
)

/**
 * Incident security: reports are sealed to the day's X25519 key (or the build's) and ACKs are signed with Ed25519.
 * Public keys ship with the app; secret keys only come from the staff QR. [chain] checks ACKs, certificates and
 * revocations; by default it knows only the build's shared keys.
 */
class StaffCrypto(
    private val crypto: Crypto,
    val publicKeys: StaffPublicKeys,
    val chain: KeyChain = KeyChain(crypto, SystemClock, publicKeys.sign),
) {
    fun sealReport(body: ByteArray): ByteArray = crypto.seal(body, chain.reportKey() ?: publicKeys.box)

    fun openReport(sealed: ByteArray, secret: StaffSecretKeys): ByteArray? =
        listOfNotNull(secret.box, secret.legacyBox).firstNotNullOfOrNull { crypto.sealOpen(sealed, it.publicKey, it.secretKey) }

    /** [flags] are AckFlags bits; they, the [epoch] and the certificate travel in the ACK's extensions, under the signature. */
    fun signAck(
        incidentId: ByteArray,
        status: Int,
        timestamp: Long,
        teamName: String,
        secret: StaffSecretKeys,
        flags: Int = 0,
        epoch: Int = 0,
    ): IncidentAck {
        val extensions = IncidentAck.extensions(flags, epoch, secret.certificate)
        val signed = IncidentAck.unsigned(incidentId, status, timestamp, teamName) + extensions
        val signature = crypto.sign(IncidentAck.signedMessage(signed), secret.sign.secretKey)
        return IncidentAck(incidentId, status, timestamp, teamName, signature, extensions)
    }

    fun verifyAck(payload: ByteArray): Boolean = chain.checkAck(payload) == Verdict.VALID

    /** [body] sealed to the reporter's [replyKey], signed like an ACK: under the certificate if there is one. */
    fun signResponse(incidentId: ByteArray, timestamp: Long, body: ResponseBody, replyKey: ByteArray, secret: StaffSecretKeys): IncidentResponse {
        val sealed = crypto.seal(body.encode(), replyKey)
        val unsigned = IncidentResponse.unsigned(incidentId, timestamp, sealed, secret.certificate)
        val signature = crypto.sign(IncidentResponse.signedMessage(unsigned), secret.sign.secretKey)
        return IncidentResponse(incidentId, timestamp, sealed, secret.certificate, signature)
    }

    /** Null if the seeds don't match this build's public keys. */
    fun secretFromSeeds(boxSeed: ByteArray, signSeed: ByteArray): StaffSecretKeys? {
        val box = crypto.boxKeyPairFromSeed(boxSeed)
        val sign = crypto.signKeyPairFromSeed(signSeed)
        if (!box.publicKey.contentEquals(publicKeys.box) || !sign.publicKey.contentEquals(publicKeys.sign)) return null
        return StaffSecretKeys(box, sign)
    }

    /**
     * The keys in an SPS2 code, or null if a seed doesn't match the key it stands for. Says nothing of the chain:
     * check the certificates with [KeyChain.status]. An anchor's code opens no report and signs no ACK.
     */
    fun credentialFrom(card: ShiftCard): Pair<Credential, StaffSecretKeys?>? {
        val sign = crypto.signKeyPairFromSeed(card.keySeed)
        if (!sign.publicKey.contentEquals(card.certificate.key)) return null
        val box = card.reportSeed?.let { crypto.boxKeyPairFromSeed(it) }
        if (box != null && !box.publicKey.contentEquals(card.reportKey?.key)) return null
        val legacy = card.legacyBoxSeed?.let { crypto.boxKeyPairFromSeed(it) }
        if (legacy != null && !legacy.publicKey.contentEquals(publicKeys.box)) return null
        val credential = Credential(card.certificate, sign)
        if (card.role == StaffRole.ANCHOR) return credential to null
        return credential to StaffSecretKeys(box, sign, card.certificate, legacy)
    }
}

private object SystemClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()

    override fun monoMs(): Long = System.nanoTime() / 1_000_000
}
