package ro.safetyplease.core.mesh

/**
 * When the mesh last had another phone in reach, a link up or an advertisement heard, on the monotonic clock. An iPhone
 * runs the app in the background only while Bluetooth wakes it, so back in front it says how long it heard no phone:
 * no alert could reach it meanwhile. [sinceMs] is when the mesh started.
 */
class PeerSilence(private val sinceMs: Long) {
    private var lastMs: Long? = null

    /** A phone was heard since the mesh started. */
    val heardAny: Boolean get() = lastMs != null

    fun update(state: MeshState, nowMs: Long) {
        val seen = state.seen.maxOfOrNull { it.lastSeenMs }
        val last = if (state.links.isNotEmpty()) nowMs else seen ?: return
        lastMs = maxOf(lastMs ?: last, last)
    }

    /**
     * Whole minutes with no phone in reach, counted from the last one heard or from the start; null while a link is up
     * or for less than [QUIET_MS], which is just the walk past a quiet corner.
     */
    fun silentMinutes(state: MeshState, nowMs: Long): Int? {
        update(state, nowMs)
        if (state.links.isNotEmpty()) return null
        val quiet = nowMs - (lastMs ?: sinceMs)
        return if (quiet >= QUIET_MS) (quiet / MINUTE_MS).toInt() else null
    }

    companion object {
        const val QUIET_MS = 5 * 60_000L
        private const val MINUTE_MS = 60_000L
    }
}
