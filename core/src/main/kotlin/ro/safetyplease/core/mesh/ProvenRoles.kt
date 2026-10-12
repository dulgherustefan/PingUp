package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.NodeFlags

/**
 * STAFF counts for ranking and rotation only once a certificate proved it (PROTOCOL.md, sections 2 and 6.1):
 * advertising proves nothing, and a link proves its peer's roles with an authenticated HELLO. ANCHOR goes by the claim,
 * since a phone's link with an anchor is a session, outside its limits and short, so a fake anchor holds no slot.
 */
object ProvenRoles {
    const val ROLES = NodeFlags.STAFF or NodeFlags.ANCHOR

    private const val RANKED = NodeFlags.STAFF

    /** How long a prefix whose link claimed STAFF without proving it is ranked like a participant's. */
    const val UNPROVEN_MS = 30 * 60_000L

    /** STAFF only if proven, and every other bit as claimed. */
    fun linkFlags(claimed: Int, proven: Int): Int = claimed and (RANKED.inv() or proven)

    /** The peer claims a role and proved every role it claims. */
    fun verified(claimed: Int, proven: Int): Boolean = claimed and ROLES != 0 && claimed and ROLES and proven.inv() == 0

    /** [unprovenUntil]: until when the candidate's prefix stays unproven, if it is. */
    fun candidate(c: Candidate, unprovenUntil: Long?, nowMs: Long): Candidate =
        if (unprovenUntil != null && unprovenUntil > nowMs) c.copy(flags = c.flags and RANKED.inv()) else c
}
