package ro.safetyplease.core.control

import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.util.nodePrefix

/**
 * A phone the console heard, by the prefix it advertises; [lastHeardMs] is on the console's wall clock. [verified]: a
 * link with it proved its role with a certificate; its advertising alone proves nothing. An anchor also tells how it
 * is in its [beat], heard at [beatAtMs]: unsigned, so only indicative.
 */
data class HeardPeer(
    val prefix: Int,
    val flags: Int,
    val linked: Boolean,
    val lastHeardMs: Long,
    val verified: Boolean = false,
    val beat: Heartbeat? = null,
    val beatAtMs: Long? = null,
) {
    val anchor: Boolean get() = flags and NodeFlags.ANCHOR != 0
}

/**
 * When the console last heard each phone. A scan forgets a phone 30 s after its last advertisement; the console
 * keeps it, so an anchor that went quiet shows as quiet instead of vanishing, however many phones come after it.
 */
object PeerBook {
    const val MAX_PEERS = 500

    fun update(book: Map<Int, HeardPeer>, state: MeshState, wallMs: Long, monoMs: Long): Map<Int, HeardPeer> {
        val next = book.mapValuesTo(HashMap()) { it.value.copy(linked = false) }
        for (seen in state.seen) {
            val prefix = seen.prefix ?: continue
            val at = wallMs - (monoMs - seen.lastSeenMs)
            val known = next[prefix]
            if (at >= (known?.lastHeardMs ?: Long.MIN_VALUE)) {
                val verified = known?.verified == true && known.flags == seen.flags
                next[prefix] = HeardPeer(prefix, seen.flags, false, at, verified, known?.beat, known?.beatAtMs)
            }
        }
        for (link in state.links) {
            if (link.peerId == 0L) continue
            // keyed like its advertising, so a linked phone doesn't show twice
            val prefix = link.prefix ?: link.peerId.nodePrefix()
            val old = next[prefix]
            next[prefix] = HeardPeer(prefix, link.flags, true, wallMs, link.verified, old?.beat, old?.beatAtMs)
        }
        return trimmed(next)
    }

    /** An anchor's heartbeat, which may come from far away: it advertises its nodeId's prefix, so it is found by it. */
    fun heartbeat(book: Map<Int, HeardPeer>, sender: Long, beat: Heartbeat, wallMs: Long): Map<Int, HeardPeer> {
        val prefix = sender.nodePrefix()
        val old = book[prefix]
        val flags = (old?.flags ?: 0) or NodeFlags.ANCHOR
        val peer = HeardPeer(
            prefix = prefix,
            flags = flags,
            linked = old?.linked ?: false,
            lastHeardMs = maxOf(old?.lastHeardMs ?: wallMs, wallMs),
            // unsigned: it keeps a proof a link gave, but adds no role to it
            verified = old?.verified == true && old.flags == flags,
            beat = beat,
            beatAtMs = wallMs,
        )
        return trimmed(book + (prefix to peer))
    }

    private fun trimmed(book: Map<Int, HeardPeer>): Map<Int, HeardPeer> {
        if (book.size <= MAX_PEERS) return book
        return book.values.sortedWith(compareByDescending<HeardPeer> { it.anchor }.thenByDescending { it.lastHeardMs })
            .take(MAX_PEERS).associateBy { it.prefix }
    }

    /** Linked phones first, then the most recently heard. */
    fun sorted(book: Map<Int, HeardPeer>): List<HeardPeer> =
        book.values.sortedWith(compareByDescending<HeardPeer> { it.linked }.thenByDescending { it.lastHeardMs })

    /** What the console lists: every anchor, since a silent one is the warning, then at most [max] other phones. */
    fun rows(book: Map<Int, HeardPeer>, max: Int): List<HeardPeer> {
        val (anchors, others) = sorted(book).partition { it.anchor }
        return anchors + others.take(max)
    }
}
