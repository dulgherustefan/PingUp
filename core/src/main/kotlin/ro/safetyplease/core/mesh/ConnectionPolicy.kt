package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.NodeFlags

data class LinkLimits(val maxOut: Int, val maxIn: Int) {
    val total: Int get() = maxOut + maxIn

    companion object {
        val DEFAULT = LinkLimits(maxOut = 4, maxIn = 3)
        val ANCHOR = LinkLimits(maxOut = 3, maxIn = 4)
    }
}

/** This phone, as the initiator rule sees it: the prefix it advertises now, its nodeId's, and whether it advertises at all. */
data class Me(val prefix: Int, val nodePrefix: Int, val canAdvertise: Boolean)

data class Candidate(
    val address: String,
    val prefix: Int?,
    val flags: Int,
    val rssi: Int,
    /** Visible without a link since when; resets when a link with it drops. */
    val sinceMs: Long,
    val lastSeenMs: Long,
)

data class LinkView(
    val id: Int,
    val address: String,
    /** What the peer advertises, so its advertising isn't taken for a new peer; null while unknown. */
    val peerPrefix: Int?,
    val peerFlags: Int,
    val outgoing: Boolean,
    val upAtMs: Long,
    val lastActivityMs: Long,
)

/** Decides whom to connect to and which link to rotate. Stateless, so it can be tested directly. */
object ConnectionPolicy {
    const val FRESH_MS = 15_000L
    const val FALLBACK_MS = 20_000L
    const val INACTIVE_MS = 30_000L

    private const val ROLES = NodeFlags.STAFF or NodeFlags.ANCHOR

    fun eligible(
        me: Me,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        blocked: (Candidate) -> Boolean,
    ): List<Candidate> {
        // roles are self-declared: one link to staff or an anchor is worth seeking, a phone full of them is eclipsed
        val seekRole = links.none { it.peerFlags and ROLES != 0 }
        return candidates
            .asSequence()
            .filter { nowMs - it.lastSeenMs <= FRESH_MS }
            .filter { it.flags and NodeFlags.ACCEPTS_CONNECTIONS != 0 }
            .filterNot(blocked)
            .filter { c -> links.none { it.address == c.address || (c.prefix != null && it.peerPrefix == c.prefix) } }
            .filter { initiates(me, it, nowMs) }
            // MAC rotation briefly leaves two addresses for one peer; keep the most recent
            .groupBy { it.prefix ?: it.address.hashCode() }
            .map { (_, same) -> same.maxByOrNull { it.lastSeenMs }!! }
            .sortedWith(
                compareByDescending<Candidate> { seekRole && it.flags and ROLES != 0 }
                    .thenByDescending { it.rssi }
            )
    }

    /**
     * Initiator rule, so that two phones don't both connect: the lower advertised prefix connects, the other one only
     * after [FALLBACK_MS]. Both prefixes are random and rotate, so the rule follows no phone across the day.
     *
     * A phone without [NodeFlags.PREFIX_IN_HELLO] can't learn our prefix, so it knows a link with us only by our
     * address, and only if it opened the link itself. Otherwise it takes our advertising for a new peer and connects
     * too, and if the duplicate rule (lower nodeId's link wins) then closed its link, it would connect again and
     * again. So we connect to such a phone only when our nodeId is the higher one, which its link beats.
     *
     * A phone that can't advertise can't be found, so it always initiates.
     */
    fun initiates(me: Me, c: Candidate, nowMs: Long): Boolean {
        if (!me.canAdvertise) return true
        val prefix = c.prefix ?: return false
        if (c.flags and NodeFlags.PREFIX_IN_HELLO == 0) return Integer.compareUnsigned(me.nodePrefix, prefix) > 0
        return Integer.compareUnsigned(me.prefix, prefix) <= 0 || nowMs - c.sinceMs >= FALLBACK_MS
    }

    fun pick(
        me: Me,
        limits: LinkLimits,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        blocked: (Candidate) -> Boolean,
    ): Candidate? {
        if (links.count { it.outgoing } >= limits.maxOut || links.size >= limits.total) return null
        return eligible(me, candidates, links, nowMs, blocked).firstOrNull()
    }

    /**
     * When outgoing slots are full and visible peers are unconnected, close our oldest idle outgoing link.
     * One link to staff or an anchor is kept, the one most recently in use; any other rotates like the rest, so a
     * phone that only claims the role can't hold on to a neighbor's slots.
     */
    fun rotationVictim(
        me: Me,
        limits: LinkLimits,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        blocked: (Candidate) -> Boolean,
    ): LinkView? {
        val full = links.count { it.outgoing } >= limits.maxOut || links.size >= limits.total
        if (!full) return null
        if (eligible(me, candidates, links, nowMs, blocked).isEmpty()) return null
        val kept = links.filter { it.peerFlags and ROLES != 0 }
            .maxWithOrNull(compareBy<LinkView> { it.lastActivityMs }.thenByDescending { it.upAtMs })
        return links
            .filter { it.outgoing && it !== kept && nowMs - it.lastActivityMs >= INACTIVE_MS }
            .minByOrNull { it.upAtMs }
    }
}
