package ro.safetyplease.core.mesh

/** At most [limit] events in any [windowMs]. */
class WindowLimit(private val limit: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    fun isFull(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        return times.size >= limit
    }

    /** Records an event if there is room; false if the window is full. */
    fun tryAdd(nowMs: Long): Boolean {
        if (isFull(nowMs)) return false
        times.addLast(nowMs)
        return true
    }
}
