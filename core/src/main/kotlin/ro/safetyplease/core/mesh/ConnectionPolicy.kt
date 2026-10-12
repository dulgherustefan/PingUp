package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import kotlin.random.Random

data class LinkLimits(val maxOut: Int, val maxIn: Int) {
    val total: Int get() = maxOut + maxIn

    companion object {
        val DEFAULT = LinkLimits(maxOut = 4, maxIn = 3)

        /** An anchor goes to the phones around it itself, in short sessions, and admits a few that come to it. */
        val ANCHOR = LinkLimits(maxOut = 4, maxIn = 3)
        val LEAF = LinkLimits(maxOut = 1, maxIn = 1)
    }
}

/** How a phone takes part in the mesh. */
enum class LinkMode(val limits: LinkLimits) {
    /** Relays everything: staff phones, and participants without staff or an anchor nearby. */
    RELAY(LinkLimits.DEFAULT),

    /** With [MeshConfig.leaves], a participant that had a link with staff or an anchor lately: two links, relays only incidents. */
    LEAF(LinkLimits.LEAF),

    /** Has short sessions with the phones around it, one after another, instead of holding links. */
    ANCHOR(LinkLimits.ANCHOR),
}

/** This phone, as the policy sees it: the prefix it advertises now, its nodeId's, whether it advertises at all, its mode. */
data class Me(val prefix: Int, val nodePrefix: Int, val canAdvertise: Boolean, val mode: LinkMode = LinkMode.RELAY)

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
    /** When the peer last sent us a packet that isn't link-local; null if it never did. Our own writes don't count. */
    val lastInboundMs: Long? = null,
    /** When the link last carried what a session is for (ConnectionPolicy.sessionOver); null before the peer's HELLO. */
    val syncMs: Long? = null,
)

/**
 * The sessions between this phone and anchors (an anchor's: with the phones around it), by the prefix the other end
 * advertises: when each last ended. [ownNewsMs] is when this phone last wrote a report, an ACK or a cancel.
 */
class Sessions(private val endedAt: Map<Int, Long> = emptyMap(), val ownNewsMs: Long? = null) {
    fun lastEnded(prefix: Int?): Long? = prefix?.let { endedAt[it] }
}

/** Decides whom to connect to and which link to close. Stateless, so it can be tested directly. */
object ConnectionPolicy {
    const val FRESH_MS = 15_000L
    const val FALLBACK_MS = 20_000L
    const val INACTIVE_MS = 30_000L

    /** A link with a role is kept from rotation only while the peer sent us something in this time. */
    const val ROLE_HEARD_MS = 5 * 60_000L

    /** A session closes after this long without anything a session is for, and after [SESSION_MAX_MS] in any case. */
    const val SESSION_IDLE_MS = 5_000L
    const val SESSION_MAX_MS = 60_000L

    /** The radio's time to set up a session's link: a GATT collision shouldn't hold up the next phone for long. */
    const val SESSION_SETUP_MS = 10_000L

    /** An anchor goes back to a phone this long after their last session. */
    const val VISIT_INTERVAL_MS = 3 * 60_000L

    /**
     * A phone goes to an anchor that hasn't come to it for this long, plus up to [SESSION_SPREAD_MS], so that phones
     * that found the anchor together don't all connect at once. A phone with a report, an ACK or a cancel of its own
     * goes at once.
     */
    const val SELF_SESSION_MS = 2 * VISIT_INTERVAL_MS
    const val SESSION_SPREAD_MS = 60_000L

    /** After each phone it admits, an anchor stops advertising that it accepts connections for a random while. */
    const val ADMIT_BACKOFF_MIN_MS = 2_000L
    const val ADMIT_BACKOFF_MAX_MS = 6_000L

    /** A participant stays a leaf this long after its last link with staff or an anchor. */
    const val LEAF_HOLD_MS = 10 * 60_000L

    private const val ROLES = NodeFlags.STAFF or NodeFlags.ANCHOR

    /** One end is an anchor and the other isn't: the link lasts as long as the exchange, not as long as both are in range. */
    fun isSession(mode: LinkMode, peerFlags: Int): Boolean = (mode == LinkMode.ANCHOR) != isAnchor(peerFlags)

    private fun isAnchor(flags: Int): Boolean = flags and NodeFlags.ANCHOR != 0

    /** The role worth one link: another anchor for an anchor, staff for the others, since anchors come in sessions. */
    private fun soughtRole(mode: LinkMode): Int = if (mode == LinkMode.ANCHOR) NodeFlags.ANCHOR else NodeFlags.STAFF

    fun admissionBackoffMs(random: Random): Long = random.nextLong(ADMIT_BACKOFF_MIN_MS, ADMIT_BACKOFF_MAX_MS + 1)

    fun eligible(
        me: Me,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        sessions: Sessions,
        blocked: (Candidate) -> Boolean,
    ): List<Candidate> {
        val anchor = me.mode == LinkMode.ANCHOR
        val role = soughtRole(me.mode)
        // roles are self-declared: one link with a role is worth seeking, a phone full of them is eclipsed
        val seekRole = links.none { it.peerFlags and role != 0 }
        return candidates
            .asSequence()
            .filter { nowMs - it.lastSeenMs <= FRESH_MS }
            .filter { it.flags and NodeFlags.ACCEPTS_CONNECTIONS != 0 || (anchor && it.flags and NodeFlags.ANCHOR_SLOT != 0) }
            .filterNot(blocked)
            .filter { c -> links.none { it.address == c.address || (c.prefix != null && it.peerPrefix == c.prefix) } }
            .filter { initiates(me, it, nowMs, sessions) }
            // MAC rotation briefly leaves two addresses for one peer; keep the most recent
            .groupBy { it.prefix ?: it.address.hashCode() }
            .map { (_, same) -> same.maxByOrNull { it.lastSeenMs }!! }
            .sortedWith(
                // only a due session is eligible: a phone's goes first, an anchor's go to the phone it saw least lately
                compareByDescending<Candidate> { !anchor && isAnchor(it.flags) }
                    .thenByDescending { seekRole && it.flags and role != 0 }
                    .thenBy { if (anchor && !isAnchor(it.flags)) sessions.lastEnded(it.prefix) ?: Long.MIN_VALUE else 0L }
                    .thenByDescending { it.rssi }
            )
            .toList()
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
     * A phone that can't advertise can't be found, so it always initiates. Sessions between an anchor and the others
     * follow their own rule, [sessionDue].
     */
    fun initiates(me: Me, c: Candidate, nowMs: Long, sessions: Sessions): Boolean {
        if (isSession(me.mode, c.flags)) return sessionDue(me, c, nowMs, sessions)
        if (!me.canAdvertise) return true
        val prefix = c.prefix ?: return false
        if (c.flags and NodeFlags.PREFIX_IN_HELLO == 0) return Integer.compareUnsigned(me.nodePrefix, prefix) > 0
        return Integer.compareUnsigned(me.prefix, prefix) <= 0 || nowMs - c.sinceMs >= FALLBACK_MS
    }

    /**
     * An anchor goes to each phone it sees, [VISIT_INTERVAL_MS] after their last session, so a phone holding a new
     * report is reached on the next round without saying so in its advertising. A phone goes to an anchor by itself
     * only with news of its own, or when the anchor hasn't come for [SELF_SESSION_MS]; one that can't advertise is
     * never visited and goes every [VISIT_INTERVAL_MS]. A peer whose prefix is unknown is left to connect.
     */
    fun sessionDue(me: Me, c: Candidate, nowMs: Long, sessions: Sessions): Boolean {
        val prefix = c.prefix ?: return false
        val last = sessions.lastEnded(prefix)
        if (me.mode == LinkMode.ANCHOR) return last == null || nowMs - last >= VISIT_INTERVAL_MS
        val news = sessions.ownNewsMs
        if (news != null && (last == null || news > last)) return true
        val wait = if (me.canAdvertise) SELF_SESSION_MS + spreadMs(me.nodePrefix, prefix) else VISIT_INTERVAL_MS
        return nowMs - (last ?: c.sinceMs) >= wait
    }

    /** The same for a phone and an anchor every time, different from phone to phone: nodeIds are random. */
    private fun spreadMs(nodePrefix: Int, anchorPrefix: Int): Long =
        Math.floorMod(nodePrefix xor anchorPrefix, (SESSION_SPREAD_MS / 1000).toInt()) * 1000L

    /** Closes a session once the exchange is over, or after [SESSION_MAX_MS] if the traffic never stops. */
    fun sessionOver(mode: LinkMode, link: LinkView, nowMs: Long): Boolean {
        if (!isSession(mode, link.peerFlags)) return false
        val sync = link.syncMs ?: return false
        return nowMs - sync >= SESSION_IDLE_MS || nowMs - link.upAtMs >= SESSION_MAX_MS
    }

    fun pick(
        me: Me,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        sessions: Sessions,
        blocked: (Candidate) -> Boolean,
    ): Candidate? {
        val full = isFull(me.mode, links)
        val sessionFree = links.none { isSpareSession(me.mode, it.peerFlags) }
        return eligible(me, candidates, links, nowMs, sessions, blocked)
            .firstOrNull { if (isSpareSession(me.mode, it.flags)) sessionFree else !full }
    }

    /**
     * When outgoing slots are full and visible peers wait for one, close our oldest idle outgoing link. Sessions
     * end by themselves. One link with the role worth seeking is kept: the one the peer most recently sent us
     * something on, within [ROLE_HEARD_MS]. What we write to it doesn't count, so a phone that only claims the role
     * and drops everything rotates like any other link and can't hold on to a neighbor's slots.
     */
    fun rotationVictim(
        me: Me,
        candidates: Collection<Candidate>,
        links: Collection<LinkView>,
        nowMs: Long,
        sessions: Sessions,
        blocked: (Candidate) -> Boolean,
    ): LinkView? {
        if (!isFull(me.mode, links)) return null
        if (eligible(me, candidates, links, nowMs, sessions, blocked).all { isSpareSession(me.mode, it.flags) }) return null
        val role = soughtRole(me.mode)
        val held = links.filterNot { isSession(me.mode, it.peerFlags) }
        val kept = held
            .filter { link -> link.peerFlags and role != 0 && link.lastInboundMs.let { it != null && nowMs - it < ROLE_HEARD_MS } }
            .maxWithOrNull(compareBy<LinkView> { it.lastInboundMs }.thenByDescending { it.upAtMs })
        return held
            .filter { it.outgoing && it !== kept && nowMs - it.lastActivityMs >= INACTIVE_MS }
            .minByOrNull { it.upAtMs }
    }

    /**
     * A phone has one session with an anchor at a time, outside its limits, so the anchor reaches it however busy it
     * is ([NodeFlags.ANCHOR_SLOT]). An anchor's sessions are what its limits are for.
     */
    fun isSpareSession(mode: LinkMode, peerFlags: Int): Boolean = mode != LinkMode.ANCHOR && isSession(mode, peerFlags)

    private fun counted(mode: LinkMode, links: Collection<LinkView>): List<LinkView> =
        links.filterNot { isSpareSession(mode, it.peerFlags) }

    private fun isFull(mode: LinkMode, links: Collection<LinkView>): Boolean {
        val counted = counted(mode, links)
        return counted.count { it.outgoing } >= mode.limits.maxOut || counted.size >= mode.limits.total
    }

    /**
     * Links over the mode's limits once a phone became a leaf: the idle ones go, oldest first and those with a role
     * last, until the total fits. Which end opened them doesn't matter for the links already up.
     */
    fun surplus(me: Me, links: Collection<LinkView>, nowMs: Long): List<LinkView> {
        val counted = counted(me.mode, links)
        val extra = counted.size - me.mode.limits.total
        if (extra <= 0) return emptyList()
        return counted

            .filter { !isSession(me.mode, it.peerFlags) && nowMs - it.lastActivityMs >= INACTIVE_MS }
            .sortedWith(compareBy<LinkView> { it.peerFlags and ROLES != 0 }.thenBy { it.upAtMs })
            .take(extra)
    }

    /**
     * What a phone passes on for others. A leaf relays incidents and what goes to the control console; chat and the
     * types it doesn't read go around it, through the phones that relay everything.
     */
    fun relays(mode: LinkMode, packet: Packet): Boolean = mode != LinkMode.LEAF || when (packet.type) {
        PacketType.INCIDENT_REPORT, PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL, PacketType.INCIDENT_RESPONSE,
        PacketType.INCIDENT_UPDATE -> true
        PacketType.PRIVATE -> packet.recipient == ControlChannel.ID
        else -> false
    }

    /** The mode of a phone: an anchor's is fixed; a participant is a leaf while it had a link with staff or an anchor lately. */

    fun mode(anchor: Boolean, staff: Boolean, roleLinkMs: Long?, nowMs: Long): LinkMode = when {
        anchor -> LinkMode.ANCHOR
        !staff && roleLinkMs != null && nowMs - roleLinkMs < LEAF_HOLD_MS -> LinkMode.LEAF
        else -> LinkMode.RELAY
    }
}
