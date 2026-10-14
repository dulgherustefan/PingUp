package ro.safetyplease.core.announce

import ro.safetyplease.core.crypto.KeyChain

/**
 * How this version orders and keeps the console's notices (PROTOCOL.md, section 6.8), the same in the mesh cache and on
 * the board. Notices stand in slots: one per topic, and one of its own for the evacuations and their withdrawals. A
 * notice replaces an older one of its slot by its issue time, then its sequence number; an issue time past the phone's
 * clock and the skew counts only up to there, so no notice, a stolen key's included, holds a slot for longer than the
 * skew. Every evacuation stays in force until a newer withdrawal or its own end, whatever else the console sends.
 */
object NoticeOrder {
    /** The slot of the evacuations and of their withdrawals, apart from the other alerts. */
    const val EVACUATIONS = 0x100 + NoticeTopic.ALERT

    /** Evacuations in force at once in one slot; past that the oldest goes. */
    const val MAX_EVACUATIONS = 8

    fun slot(notice: Notice): Int =
        if (notice.topic == NoticeTopic.ALERT && notice.level == NoticeLevel.EVACUATION) EVACUATIONS else notice.topic

    fun topicOf(slot: Int): Int = if (slot == EVACUATIONS) NoticeTopic.ALERT else slot

    /** The issue time [notice] is ordered by, first taken at [seenSec]: no later than the phone's clock plus the skew. */
    fun orderTime(notice: Notice, seenSec: Long): Long = minOf(notice.issued, seenSec + KeyChain.SKEW_S)

    /** Issued past the phone's clock and the skew: the phone's clock or the console's is wrong. */
    fun uncertain(notice: Notice, seenSec: Long): Boolean = notice.issued > seenSec + KeyChain.SKEW_S

    /** When [notice], first taken at [seenSec], stops holding: at its end, or its own length after it came, if sooner. */
    fun until(notice: Notice, seenSec: Long): Long = minOf(notice.validUntil, seenSec + (notice.validUntil - notice.issued))

    /** Until when a slot remembers the order of a notice: whatever it could refuse has ended on its own by then. */
    fun markUntil(order: Long): Long = order + Notice.MAX_VALID_S

    fun newer(a: Notice, aOrder: Long, b: Notice, bOrder: Long): Boolean = aOrder > bOrder || (aOrder == bOrder && a.seq > b.seq)

    /** A notice a slot holds, in force or kept for its order, with the time it is ordered by. */
    class Held(val notice: Notice, val order: Long)

    /** Whether a notice goes into its slot, and which of the held ones (by index) it ends. */
    class Take(val taken: Boolean, val ends: Set<Int> = emptySet())

    private val REFUSED = Take(false)

    /**
     * Whether [notice], ordered by [order], goes into its slot, which holds [held]. A topic's slot takes a newer notice in
     * place of the one it holds. The evacuations' slot takes an evacuation newer than its latest withdrawal unless a newer
     * evacuation it holds covers it, and ends the older ones it covers; it takes a newer withdrawal, which ends every
     * evacuation older than itself. The same notice again is never taken.
     */
    fun take(notice: Notice, order: Long, held: List<Held>): Take {
        if (held.any { it.notice.signature.contentEquals(notice.signature) }) return REFUSED
        if (slot(notice) != EVACUATIONS) {
            val last = held.indices.maxWithOrNull { a, b -> compare(held[a], held[b]) }
            return if (last == null || newer(notice, order, held[last].notice, held[last].order)) Take(true, held.indices.toSet()) else REFUSED
        }
        val withdrawal = held.indices.filter { held[it].notice.cancelled }.maxWithOrNull { a, b -> compare(held[a], held[b]) }
        if (withdrawal != null && !newer(notice, order, held[withdrawal].notice, held[withdrawal].order)) return REFUSED
        if (notice.cancelled) {
            return Take(true, held.indices.filter { it == withdrawal || held[it].notice.cancelled || newer(notice, order, held[it].notice, held[it].order) }.toSet())
        }
        val evacuations = held.indices.filter { !held[it].notice.cancelled }
        if (evacuations.any { covers(held[it].notice, notice) && newer(held[it].notice, held[it].order, notice, order) }) return REFUSED
        val ends = evacuations.filter { covers(notice, held[it].notice) && newer(notice, order, held[it].notice, held[it].order) }.toMutableSet()
        val left = evacuations.filter { it !in ends }.sortedWith { a, b -> compare(held[a], held[b]) }
        if (left.size >= MAX_EVACUATIONS) ends += left.take(left.size - MAX_EVACUATIONS + 1)
        return Take(true, ends)
    }

    private fun compare(a: Held, b: Held): Int = compareValuesBy(a, b, { it.order }, { it.notice.seq })

    /**
     * Evacuation [a] takes in [b]: its zones include b's and it holds at least as long. Zones read without the venue: a
     * zone set against one of the same zone count, a zone named alone against the same, anything against the whole site.
     */
    fun covers(a: Notice, b: Notice): Boolean {
        if (a.validUntil < b.validUntil) return false
        val setA = a.extras.zones?.takeIf { it.indices.isNotEmpty() }
        val setB = b.extras.zones?.takeIf { it.indices.isNotEmpty() }
        return when {
            setA == null && a.zone.isEmpty() -> true
            setA != null && setB != null -> setA.count == setB.count && setA.indices.containsAll(setB.indices)
            setA == null && setB == null -> a.zone == b.zone
            else -> false
        }
    }

    /** The same words, zones and plan: a notice signed again, which the phone shows as it already does. */
    fun sameContent(a: Notice, b: Notice): Boolean =
        a.topic == b.topic && a.level == b.level && a.flags == b.flags && a.template == b.template && a.zone == b.zone &&
            a.params == b.params && a.extensions.contentEquals(b.extensions)

    /**
     * The id8 a SUMMARY2 of notices lists a notice by when it isn't its topic's highest (PROTOCOL.md, section 6.2): bit 62,
     * the topic, the low 16 bits of the sequence number and the issue time. It goes with version 0, which phones of
     * earlier versions never ask for.
     */
    fun extraId(notice: Notice): Long =
        (1L shl 62) or (notice.topic.toLong() shl 48) or ((notice.seq and 0xFFFF) shl 32) or (notice.issued and 0xFFFFFFFFL)

    fun isExtraId(id8: Long): Boolean = id8 ushr 62 == 1L

    /** What a phone of an earlier version keys a notice by: its topic and sequence number. */
    fun topicKey(topic: Int, seq: Long): Long = (topic.toLong() shl 32) or (seq and 0xFFFFFFFFL)
}
