package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.NodeFlags

/**
 * STAFF and ANCHOR count for ranking and rotation only once a certificate proved them (PROTOCOL.md, sections 2 and
 * 6.1): advertising proves nothing, and a link proves its peer's roles with an authenticated HELLO.
 */
object ProvenRoles {
    const val ROLES = NodeFlags.STAFF or NodeFlags.ANCHOR

    /** How long a prefix whose link claimed a role it didn't prove is ranked like a participant's. */
    const val UNPROVEN_MS = 30 * 60_000L

    /** The claimed role bits that were proven, and every other bit as claimed. */
    fun linkFlags(claimed: Int, proven: Int): Int = claimed and (ROLES.inv() or proven)

    /** [unprovenUntil]: until when the candidate's prefix stays unproven, if it is. */
    fun candidate(c: Candidate, unprovenUntil: Long?, nowMs: Long): Candidate =
        if (unprovenUntil != null && unprovenUntil > nowMs) c.copy(flags = c.flags and ROLES.inv()) else c
}
