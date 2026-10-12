package ro.safetyplease.ui.common

import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.util.nodePrefix

/** What the UI shows from mesh state; changes only when someone appears, leaves or links, not on every packet. */
data class Nearby(
    val linked: Set<Long> = emptySet(),
    val seenPrefixes: Set<Int> = emptySet(),
    val readyLinks: Int = 0,
    val radio: RadioStatus = RadioStatus(),
) {
    /** Directly linked to us, not just seen in a scan. */
    fun isLinked(nodeId: Long): Boolean = nodeId in linked

    fun isInRange(nodeId: Long): Boolean = isLinked(nodeId) || nodeId.nodePrefix() in seenPrefixes

    companion object {
        fun of(state: MeshState) = Nearby(
            state.links.mapTo(HashSet()) { it.peerId },
            state.seen.mapNotNullTo(HashSet()) { it.prefix },
            state.readyLinks,
            state.radio,
        )
    }
}
