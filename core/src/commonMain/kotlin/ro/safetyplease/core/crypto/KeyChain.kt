package ro.safetyplease.core.crypto

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.mesh.MeshTrust
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.HelloProof
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentResponse
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.PointStatus
import ro.safetyplease.core.util.Clock
import kotlin.concurrent.Volatile

enum class CertStatus { VALID, UNKNOWN, FORGED, REVOKED, OUTSIDE_WINDOW }

/**
 * The trust chain of one event (PROTOCOL.md, section 9): the Ping Up [root] key ships with the app and signs
 * organisers, an organiser signs the event, the event signs shift certificates and the days' report keys. [issuers]
 * are the organiser and event certificates the app ships; those that don't lead to the root are ignored.
 * [legacySign] is the build's shared staff key, which still signs the ACKs of phones activated with an SPS1 code.
 */
class KeyChain(
    private val crypto: Crypto,
    private val clock: Clock,
    private val legacySign: ByteArray,
    private val root: ByteArray? = null,
    issuers: List<Certificate> = emptyList(),
    reportKeys: List<Certificate> = emptyList(),
) : MeshTrust {
    private val rootId: Long? = root?.let { Certificate.keyId(it) }

    /** Organiser and event certificates by the id of their key, each signed by the key above it. */
    private val trusted: Map<Long, Certificate> = buildMap {
        var added = true
        while (added) {
            added = false
            for (c in issuers) {
                val key = Certificate.keyId(c.key)
                if (key in this) continue
                val parent = if (c.issuer == rootId) null else this[c.issuer] ?: continue
                if (!CertRole.issues(parent?.role, c.role)) continue
                if (!crypto.verify(c.signature, c.signedMessage(), parent?.key ?: root!!)) continue
                put(key, c)
                added = true
            }
        }
    }

    private val lock = SynchronizedObject()

    @Volatile
    private var reportKeys: List<Certificate> = reportKeys.filter { it.role == CertRole.REPORT_KEY }

    @Volatile
    private var revoked: Set<Long> = emptySet()

    /** This phone's shift certificate and key, for HELLO proofs; null for a participant or an SPS1 phone. */
    @Volatile
    var credential: Credential? = null

    private fun nowSec(): Long = clock.wallMs() / 1000

    /** Whether [cert] leads to the root, isn't revoked and holds now, within [SKEW_S], as does every key above it. */
    fun status(cert: Certificate, now: Long = nowSec()): CertStatus {
        var c = cert
        var leaf = true
        while (true) {
            if (c.id in revoked) return CertStatus.REVOKED
            if (now < c.validFrom - SKEW_S || now > c.validUntil + SKEW_S) return CertStatus.OUTSIDE_WINDOW
            val parent = if (root != null && c.issuer == rootId) null else trusted[c.issuer] ?: return CertStatus.UNKNOWN
            if (!CertRole.issues(parent?.role, c.role)) return CertStatus.UNKNOWN
            // the keys above were checked when the chain was built
            if (leaf && !crypto.verify(c.signature, c.signedMessage(), parent?.key ?: root!!)) return CertStatus.FORGED
            if (parent == null) return CertStatus.VALID
            c = parent
            leaf = false
        }
    }

    fun isRevoked(id: Long): Boolean = id in revoked

    /**
     * An ACK without [ro.safetyplease.core.protocol.AckFlags.CERT] is checked against the build's staff key, as in 1.0.
     * One with a certificate is checked against the certificate's key, and the certificate against the chain; then the
     * role: a team signs only in its own name and never what is the console's ([CONTROL_ONLY], assignments), an anchor
     * signs nothing. An ACK dated after the receiver's clock plus [SKEW_S] or before the certificate is refused.
     */
    override fun checkAck(payload: ByteArray): Verdict {
        val ack = IncidentAck.decode(payload) ?: return Verdict.INVALID
        val message = IncidentAck.signedMessage(IncidentAck.unsignedPart(payload))
        if (!ack.certified) return if (crypto.verify(ack.signature, message, legacySign)) Verdict.VALID else Verdict.INVALID
        val cert = ack.certificate ?: return Verdict.REFUSED
        val now = nowSec()
        when (status(cert, now)) {
            CertStatus.VALID -> Unit
            CertStatus.FORGED -> return Verdict.INVALID
            else -> return Verdict.REFUSED
        }
        if (!crypto.verify(ack.signature, message, cert.key)) return Verdict.INVALID
        val allowed = when (cert.role) {
            CertRole.CONTROL -> true
            CertRole.STAFF -> ack.teamName == cert.name && !ack.assigned && ack.status !in CONTROL_ONLY
            else -> false
        }
        if (!allowed || ack.timestamp > now + SKEW_S || ack.timestamp < cert.validFrom - SKEW_S) return Verdict.REFUSED
        return Verdict.VALID
    }

    /**
     * A response to a reporter: as an ACK, against the build's staff key without a certificate; with one, only a
     * control shift may sign it, within its shift.
     */
    override fun checkResponse(payload: ByteArray): Verdict {
        val response = IncidentResponse.decode(payload) ?: return Verdict.INVALID
        val message = IncidentResponse.signedMessage(IncidentResponse.unsignedPart(payload))
        val cert = response.certificate
            ?: return if (crypto.verify(response.signature, message, legacySign)) Verdict.VALID else Verdict.INVALID
        val now = nowSec()
        when (status(cert, now)) {
            CertStatus.VALID -> Unit
            CertStatus.FORGED -> return Verdict.INVALID
            else -> return Verdict.REFUSED
        }
        if (!crypto.verify(response.signature, message, cert.key)) return Verdict.INVALID
        val late = response.timestamp > now + SKEW_S || response.timestamp < cert.validFrom - SKEW_S
        return if (cert.role != CertRole.CONTROL || late) Verdict.REFUSED else Verdict.VALID
    }

    /**
     * A point's status, opened from the control channel: as an ACK, against the build's staff key without a
     * certificate; with one, a team signs only in its own name and a console anything, within the shift.
     */
    fun checkPoint(plain: ByteArray): Verdict {
        val status = PointStatus.decode(plain) ?: return Verdict.INVALID
        val message = PointStatus.signedMessage(PointStatus.unsignedPart(plain))
        val cert = status.certificate
            ?: return if (crypto.verify(status.signature, message, legacySign)) Verdict.VALID else Verdict.INVALID
        val now = nowSec()
        when (status(cert, now)) {
            CertStatus.VALID -> Unit
            CertStatus.FORGED -> return Verdict.INVALID
            else -> return Verdict.REFUSED
        }
        if (!crypto.verify(status.signature, message, cert.key)) return Verdict.INVALID
        val allowed = when (cert.role) {
            CertRole.CONTROL -> true
            CertRole.STAFF -> status.teamName == cert.name
            else -> false
        }
        if (!allowed || status.issued > now + SKEW_S || status.issued < cert.validFrom - SKEW_S) return Verdict.REFUSED
        return Verdict.VALID
    }

    /**
     * A revocation signed by the root, the organiser or the event, or a notice signed under a control shift; other
     * kinds are this version's to relay only.
     */
    override fun checkAnnouncement(payload: ByteArray): Verdict = when (payload.firstOrNull()?.toInt()) {
        Revocation.KIND -> checkRevocation(payload)
        Notice.KIND -> checkNotice(payload)
        else -> Verdict.REFUSED
    }

    private fun checkRevocation(payload: ByteArray): Verdict {
        val revocation = Revocation.decode(payload) ?: return Verdict.REFUSED
        val key = signerKey(revocation.signer) ?: return Verdict.REFUSED
        return if (crypto.verify(revocation.signature, Revocation.signedMessage(payload), key)) Verdict.VALID else Verdict.INVALID
    }

    /**
     * Only a control shift signs a notice, and only within its shift: the certificate is checked at the notice's issue,
     * so one issued just before the shift ends still holds for the phones that get it later, at most
     * [Notice.MAX_VALID_S] from its issue. A revoked certificate refuses it whenever it comes. Whether it is still in
     * force, and newer than the one kept, is the cache's to decide.
     */
    private fun checkNotice(payload: ByteArray): Verdict {
        val notice = Notice.decode(payload) ?: return Verdict.INVALID
        val cert = notice.certificate
        if (notice.issued > nowSec() + SKEW_S) return Verdict.REFUSED
        when (status(cert, notice.issued)) {
            CertStatus.VALID -> Unit
            CertStatus.FORGED -> return Verdict.INVALID
            else -> return Verdict.REFUSED
        }
        if (!crypto.verify(notice.signature, Notice.signedMessage(payload), cert.key)) return Verdict.INVALID
        val span = notice.validUntil - notice.issued
        return if (cert.role != CertRole.CONTROL || span !in 1..Notice.MAX_VALID_S) Verdict.REFUSED else Verdict.VALID
    }

    private fun signerKey(id: Long): ByteArray? {
        if (root != null && id == rootId) return root
        return trusted[id]?.takeIf { status(it) == CertStatus.VALID }?.key
    }

    /** Ids a revocation this phone verified before named; they stay revoked whatever the chain says now. */
    fun revokeIds(ids: Collection<Long>) = synchronized(lock) {
        revoked = revoked + ids
    }

    /** Applies a valid revocation; the ids it newly revokes, or null if it isn't one. */
    fun revoke(payload: ByteArray): List<Long>? = synchronized(lock) {
        val revocation = Revocation.decode(payload) ?: return null
        if (checkRevocation(payload) != Verdict.VALID) return null
        val fresh = revocation.ids.filter { it !in revoked }
        if (fresh.isNotEmpty()) revoked = revoked + fresh
        return fresh
    }

    override fun nonce(): ByteArray = crypto.random(Hello.NONCE_SIZE)

    override fun helloProof(peerNonce: ByteArray, nodeId: Long): HelloProof? {
        val own = credential?.takeIf { status(it.certificate) == CertStatus.VALID } ?: return null
        return HelloProof(own.certificate, crypto.sign(Hello.proofMessage(peerNonce, nodeId), own.key.secretKey))
    }

    override fun provenRoles(proof: HelloProof, nonce: ByteArray, peerId: Long): Int {
        val cert = proof.certificate
        if (status(cert) != CertStatus.VALID) return 0
        if (!crypto.verify(proof.signature, Hello.proofMessage(nonce, peerId), cert.key)) return 0
        return when (cert.role) {
            CertRole.STAFF, CertRole.CONTROL -> NodeFlags.STAFF
            CertRole.ANCHOR -> NodeFlags.ANCHOR
            else -> 0
        }
    }

    /** The day's key reports are sealed to; null outside every day the event planned, when the build's key is used. */
    fun reportKey(now: Long = nowSec()): ByteArray? = reportKeys
        .filter { now in it.validFrom..it.validUntil && status(it, now) == CertStatus.VALID }
        .maxByOrNull { it.validFrom }?.key

    /** A day's report key that came in a staff code. */
    fun addReportKey(cert: Certificate) = synchronized(lock) {
        if (cert.role == CertRole.REPORT_KEY && reportKeys.none { it.id == cert.id }) reportKeys = reportKeys + cert
    }

    companion object {
        /** How far apart two phones' clocks may be: certificates hold this much before and after their window. */
        const val SKEW_S = 10 * 60L

        private val CONTROL_ONLY = setOf(AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED, AckStatus.REOPENED)
    }
}
