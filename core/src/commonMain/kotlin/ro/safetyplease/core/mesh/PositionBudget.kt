package ro.safetyplease.core.mesh

/**
 * The air this phone lets its shared positions take (PROTOCOL.md, section 6.6), as the P2 class: at most [share] of the
 * unique packets a link carries at [capacityPerSecond], and none while the other unique packets this phone sees in
 * the window already fill the rest. Reports, ACKs and the other incident packets (P0) and chat (P1) are never limited
 * here. Times are on the monotonic clock.
 */
class PositionBudget(
    capacityPerSecond: Double,
    private val share: Double = SHARE,
    private val windowMs: Long = 60_000,
) {
    private val perWindow = capacityPerSecond * windowMs / 1000
    private val unique = ArrayDeque<Long>()
    private val positions = ArrayDeque<Long>()

    /** Every new packet, sent or received, positions included. */
    fun onUnique(now: Long) {
        trim(now)
        unique.addLast(now)
    }

    /** True, and counted, when a position may go now. */
    fun tryPosition(now: Long): Boolean {
        trim(now)
        if (positions.size + 1 > perWindow * share) return false
        if (unique.size - positions.size >= perWindow * (1 - share)) return false
        positions.addLast(now)
        return true
    }

    private fun trim(now: Long) {
        while (unique.isNotEmpty() && now - unique.first() >= windowMs) unique.removeFirst()
        while (positions.isNotEmpty() && now - positions.first() >= windowMs) positions.removeFirst()
    }

    companion object {
        const val SHARE = 0.25
    }
}
