package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.HelloProof
import kotlin.random.Random

/** [INVALID] is a forgery and counts as malformed; [REFUSED] is dropped silently, since an honest relay may not know why. */
enum class Verdict { VALID, REFUSED, INVALID }

/** What the engine asks the key chain (`crypto/KeyChain.kt`). Only the ACK check is required. */
fun interface MeshTrust {
    fun checkAck(payload: ByteArray): Verdict

    /** A fresh HELLO nonce; from its own source, so the engine's random draws don't depend on it. */
    fun nonce(): ByteArray = Random.nextBytes(Hello.NONCE_SIZE)

    /** A response to a reporter, checked like an ACK; one that isn't VALID is neither stored nor relayed. */
    fun checkResponse(payload: ByteArray): Verdict = Verdict.REFUSED

    /** A VALID announcement is relayed and delivered like an incident; any other is relayed as an unknown type. */
    fun checkAnnouncement(payload: ByteArray): Verdict = Verdict.REFUSED

    /** This phone's certificate and its signature over [peerNonce]; null without a certificate. */
    fun helloProof(peerNonce: ByteArray, nodeId: Long): HelloProof? = null

    /** The NodeFlags role bits [proof] shows [peerId] holds, if it answers [nonce]; 0 for none. */
    fun provenRoles(proof: HelloProof, nonce: ByteArray, peerId: Long): Int = 0
}
