package ro.safetyplease.core.control

import ro.safetyplease.core.protocol.PointStatus
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue

/**
 * The last status of a point on the console. [atMs] is when it was said: the sender's clock, but never after it
 * arrived, so a phone whose clock runs ahead can't keep a status fresh.
 */
data class PointReport(val point: Int, val level: Int, val team: String, val atMs: Long, val issued: Long)

/** A staff phone's last status of a point, on its own wall clock. */
data class SentPoint(val level: Int, val atMs: Long)

/**
 * The points staff report the status of (PROTOCOL.md, section 6.9): numbered POIs of the [TYPES] in the event's
 * venue. Nothing here goes to the public.
 */
object PointBoard {
    val TYPES = listOf("exit", "wc", "water", "charge", "transport")

    /** A status nobody confirmed for this long is shown as unconfirmed. */
    const val STALE_MS = 30 * 60_000L

    /** The same point again, from one phone, only after this long; the same level only as a confirmation after [CONFIRM_MS]. */
    const val MIN_GAP_MS = 20_000L
    const val CONFIRM_MS = 5 * 60_000L

    fun points(venue: Venue): List<Poi> = venue.pois.filter { it.type in TYPES && it.number in Venue.LANDMARK_NUMBERS }

    /** A team's own points first, then the others, each in the order of the venue. */
    fun forTeam(venue: Venue, team: String): List<Poi> = points(venue).sortedBy { if (team.isNotEmpty() && it.team == team) 0 else 1 }

    /** [status] was verified; it replaces an older one of the same point, and a point the venue doesn't list is dropped. */
    fun offer(board: Map<Int, PointReport>, status: PointStatus, known: Set<Int>, wallMs: Long): Map<Int, PointReport> {
        if (status.point !in known) return board
        val old = board[status.point]
        if (old != null && status.issued <= old.issued) return board
        val at = minOf(status.issued * 1000, wallMs)
        return board + (status.point to PointReport(status.point, status.level, status.teamName, at, status.issued))
    }

    fun isStale(report: PointReport, nowMs: Long): Boolean = nowMs - report.atMs >= STALE_MS

    /** Whether this phone sends [level] for [point] now: a change, or the same level again as a confirmation. */
    fun maySend(sent: Map<Int, SentPoint>, point: Int, level: Int, nowMs: Long): Boolean {
        val last = sent[point] ?: return true
        val since = nowMs - last.atMs
        return since >= MIN_GAP_MS && (last.level != level || since >= CONFIRM_MS)
    }
}
