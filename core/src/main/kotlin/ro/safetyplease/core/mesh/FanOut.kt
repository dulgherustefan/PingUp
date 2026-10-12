package ro.safetyplease.core.mesh

import kotlin.random.Random

/**
 * Chat goes on a few links, not all: k = ceil(log2 n) of the n, at least one, as in Bitchat. A message spreads
 * through the crowd at a fraction of the cost of a flood, and the outbox resends what didn't arrive.
 */
object FanOut {
    fun size(links: Int): Int = if (links <= 0) 0 else maxOf(1, 32 - Integer.numberOfLeadingZeros(links - 1))

    /** [preferred], the recipient's own link when it is a neighbor, is always among those picked. */
    fun <T> pick(links: List<T>, preferred: T?, random: Random): List<T> {
        val k = size(links.size)
        if (links.size <= k) return links
        if (preferred == null || preferred !in links) return links.shuffled(random).take(k)
        return listOf(preferred) + (links - preferred).shuffled(random).take(k - 1)
    }
}
