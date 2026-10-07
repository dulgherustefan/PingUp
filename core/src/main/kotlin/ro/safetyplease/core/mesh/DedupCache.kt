package ro.safetyplease.core.mesh

/** Recently seen packet ids: a bounded LRU with a time window. */
class DedupCache(
    private val capacity: Int = 4096,
    private val windowMs: Long = 15 * 60_000L,
) {
    private val seen = LinkedHashMap<Long, Long>()

    val size: Int get() = seen.size

    /** True if the id was seen within the window; otherwise records it. */
    fun checkAndAdd(id: Long, nowMs: Long): Boolean {
        val at = seen[id]
        if (at != null && nowMs - at <= windowMs) return true
        add(id, nowMs)
        return false
    }

    fun add(id: Long, nowMs: Long) {
        seen.remove(id)
        seen[id] = nowMs
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (seen.size > capacity || nowMs - e.value > windowMs) it.remove() else break
        }
    }
}
