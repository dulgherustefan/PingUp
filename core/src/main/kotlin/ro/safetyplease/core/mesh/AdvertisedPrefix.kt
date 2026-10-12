package ro.safetyplease.core.mesh

import kotlin.random.Random

/**
 * The 4 bytes a phone advertises to be told apart from its neighbors. They are random and change about every
 * [intervalMs], each time with a new advertising set and so a new address: a scanner nearby can't follow the phone
 * from one address to the next, as it could by the nodeId's prefix. The interval varies by a fifth either way, so
 * phones nearby don't all change at once.
 */
class AdvertisedPrefix(private val random: Random, nowMs: Long, private val intervalMs: Long = INTERVAL_MS) {
    var value: Int = random.nextInt()
        private set

    private var dueAtMs = schedule(nowMs)

    fun isDue(nowMs: Long): Boolean = nowMs >= dueAtMs

    /** Draws a prefix other than the current one and schedules the next change. */
    fun renew(nowMs: Long) {
        var next: Int
        do next = random.nextInt() while (next == value)
        value = next
        dueAtMs = schedule(nowMs)
    }

    private fun schedule(nowMs: Long): Long = nowMs + intervalMs * 4 / 5 + random.nextLong(intervalMs * 2 / 5 + 1)

    companion object {
        /** About as often as Android rotates the advertising address. */
        const val INTERVAL_MS = 15 * 60_000L
    }
}
