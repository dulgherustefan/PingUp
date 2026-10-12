package ro.safetyplease.core.mesh

/** At most [limit] events in any [windowMs]. */
class WindowLimit(private val limit: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    fun isFull(nowMs: Long): Boolean = remaining(nowMs) <= 0

    /** How many more events fit in the window now. */
    fun remaining(nowMs: Long): Int {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        return limit - times.size
    }

    /** Records an event if there is room; false if the window is full. */
    fun tryAdd(nowMs: Long): Boolean {
        if (isFull(nowMs)) return false
        times.addLast(nowMs)
        return true
    }
}
