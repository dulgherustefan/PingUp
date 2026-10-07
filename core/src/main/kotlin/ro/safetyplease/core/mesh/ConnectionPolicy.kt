package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.NodeFlags

data class LinkLimits(val maxOut: Int, val maxIn: Int) {
    val total: Int get() = maxOut + maxIn

    companion object {
        val DEFAULT = LinkLimits(maxOut = 4, maxIn = 3)
        val ANCHOR = LinkLimits(maxOut = 3, maxIn = 4)
    }
}

data class Candidate(
    val address: String,
    val prefix: Int?,
    val flags: Int,
    val rssi: Int,
    /** De cand e vizibil fara legatura; se reseteaza cand pica o legatura cu el. */
    val sinceMs: Long,
    val lastSeenMs: Long,
)

data class LinkView(
    val id: Int,
    val address: String,
    val peerPrefix: Int?,
    val peerFlags: Int,
    val outgoing: Boolean,
    val upAtMs: Long,
    val lastActivityMs: Long,
)

/** Decide la cine ne conectam si ce legatura rotim. Fara stare, ca sa poata fi testat direct. */
object ConnectionPolicy {
    const val FRESH_MS = 15_000L
    const val FALLBACK_MS = 20_000L
    const val INACTIVE_MS = 30_000L

    private const val PRIORITY_ROLES = NodeFlags.STAFF or NodeFlags.ANCHOR

    fun eligible(
        myPrefix: Int,
        canAdvertise: Boolean,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        blocked: (Candidate) -> Boolean,
    ): List<Candidate> = candidates
        .asSequence()
        .filter { nowMs - it.lastSeenMs <= FRESH_MS }
        .filter { it.flags and NodeFlags.ACCEPTS_CONNECTIONS != 0 }
        .filterNot(blocked)
        .filter { c -> links.none { it.address == c.address || (c.prefix != null && it.peerPrefix == c.prefix) } }
        .filter { shouldInitiate(myPrefix, canAdvertise, it) || nowMs - it.sinceMs >= FALLBACK_MS }
        // rotatia MAC lasa temporar doua adrese pentru acelasi peer; o pastram pe cea mai recenta
        .groupBy { it.prefix ?: it.address.hashCode() }
        .map { (_, same) -> same.maxByOrNull { it.lastSeenMs }!! }
        .sortedWith(
            compareByDescending<Candidate> { it.flags and PRIORITY_ROLES != 0 }
                .thenByDescending { it.flags and NodeFlags.PENDING_INCIDENT != 0 }
                .thenByDescending { it.rssi }
        )

    /**
     * Regula initiatorului: se conecteaza cel cu nodeId mai mic, altfel apar legaturi duble.
     * Un telefon care nu poate face advertising nu poate fi gasit, deci initiaza mereu.
     */
    fun shouldInitiate(myPrefix: Int, canAdvertise: Boolean, c: Candidate): Boolean =
        !canAdvertise || c.prefix == null || Integer.compareUnsigned(myPrefix, c.prefix) <= 0

    fun pick(
        myPrefix: Int,
        canAdvertise: Boolean,
        limits: LinkLimits,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        blocked: (Candidate) -> Boolean,
    ): Candidate? {
        if (links.count { it.outgoing } >= limits.maxOut || links.size >= limits.total) return null
        return eligible(myPrefix, canAdvertise, candidates, links, nowMs, blocked).firstOrNull()
    }

    /**
     * Cand sloturile de iesire sunt pline si exista peer-i vizibili neconectati, se inchide cea mai veche
     * legatura inactiva initiata de noi. Legaturile cu staff si ancore se pastreaza.
     */
    fun rotationVictim(
        myPrefix: Int,
        canAdvertise: Boolean,
        limits: LinkLimits,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        blocked: (Candidate) -> Boolean,
    ): LinkView? {
        val full = links.count { it.outgoing } >= limits.maxOut || links.size >= limits.total
        if (!full) return null
        if (eligible(myPrefix, canAdvertise, candidates, links, nowMs, blocked).isEmpty()) return null
        return links
            .filter { it.outgoing && nowMs - it.lastActivityMs >= INACTIVE_MS }
            .filter { it.peerFlags and PRIORITY_ROLES == 0 }
            .minByOrNull { it.upAtMs }
    }
}
