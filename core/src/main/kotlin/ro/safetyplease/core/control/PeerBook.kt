package ro.safetyplease.core.control

import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.util.nodePrefix

/** A phone the console heard, by the prefix it advertises; [lastHeardMs] is on the console's wall clock. */
data class HeardPeer(val prefix: Int, val flags: Int, val linked: Boolean, val lastHeardMs: Long) {
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
            if (at >= (next[prefix]?.lastHeardMs ?: Long.MIN_VALUE)) next[prefix] = HeardPeer(prefix, seen.flags, false, at)
        }
        for (link in state.links) {
            if (link.peerId == 0L) continue
            // keyed like its advertising, so a linked phone doesn't show twice
            val prefix = link.prefix ?: link.peerId.nodePrefix()
            next[prefix] = HeardPeer(prefix, link.flags, true, wallMs)
        }
        if (next.size <= MAX_PEERS) return next
        return next.values.sortedWith(compareByDescending<HeardPeer> { it.anchor }.thenByDescending { it.lastHeardMs })
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
