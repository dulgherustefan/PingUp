package ro.safetyplease.app.text

import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Severity

/**
 * How long an urgent report may go without a person on staff confirming it before the reporter is told to look
 * for help in person. RECEIVED doesn't count: the first staff phone sends it on its own, maybe from a pocket.
 */
const val ESCALATE_AFTER_MS = 3 * 60_000L

/** When an urgent report starts telling the reporter to look for help in person; null once it no longer can. */
fun escalationAt(report: MyReport): Long? =
    if (report.severity == Severity.URGENT && !report.cancelled && report.status < AckStatus.ACKNOWLEDGED) {
        report.createdAt + ESCALATE_AFTER_MS
    } else {
        null
    }

fun MyReport.needsEscalation(nowMs: Long): Boolean = escalationAt(this)?.let { nowMs >= it } == true

/** Reports whose escalation moment falls after [afterMs] and no later than [untilMs]. */
fun escalationsBetween(mine: List<MyReport>, afterMs: Long, untilMs: Long): List<MyReport> =
    mine.filter { report -> escalationAt(report)?.let { it in (afterMs + 1)..untilMs } == true }

/** The next escalation moment after [afterMs], if any report still has one coming. */
fun nextEscalation(mine: List<MyReport>, afterMs: Long): Long? = mine.mapNotNull(::escalationAt).filter { it > afterMs }.minOrNull()

/**
 * Which escalations to post as notifications. Only moments that pass after [startMs] count, so an old report doesn't
 * ring again after a restart. A moment that passes while the app is in the foreground is held and posted when the app
 * goes to the background, if the report still needs it then.
 */
class EscalationReminder(startMs: Long) {
    private var checkedMs = startMs
    private val held = LinkedHashSet<String>()

    fun due(mine: List<MyReport>, nowMs: Long, foreground: Boolean): List<MyReport> {
        escalationsBetween(mine, checkedMs, nowMs).forEach { held += it.incidentId }
        checkedMs = nowMs
        if (foreground) return emptyList()
        val due = mine.filter { it.incidentId in held && it.needsEscalation(nowMs) }
        held.clear()
        return due
    }
}
