package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.Severity

/**
 * The two-minute rule (roadmap row 8): an urgent incident nobody has acknowledged for [ACK_WITHIN_MS] is overdue. The
 * console shows the time running and marks it; the leads of its role, wherever their zone, then hear it insistently
 * (in [Routing]). Each phone counts from when it opened the report or the console reopened it, as [Routing] does.
 * The console's own alarm already repeats until someone has the incident, so nothing new rings there.
 */
object UrgentEscalation {
    const val ACK_WITHIN_MS = 2 * 60_000L

    /** When the incident becomes overdue; null while it isn't urgent, or once a team has it or it closed. */
    fun deadline(incident: StaffIncident): Long? =
        if (incident.severity == Severity.URGENT && !Routing.handled(incident)) Routing.waitingSince(incident) + ACK_WITHIN_MS else null

    fun overdue(incident: StaffIncident, nowMs: Long): Boolean = deadline(incident)?.let { nowMs >= it } == true

    /**
     * The console's timer for a case: from when the console opened the report ([openedAt]) or saw it reopened
     * ([reopenedAt]), while it is urgent and [open], nobody having taken it on the console or in the field.
     */
    fun consoleDeadline(urgent: Boolean, open: Boolean, openedAt: Long, reopenedAt: Long?): Long? =
        if (urgent && open) (reopenedAt ?: openedAt) + ACK_WITHIN_MS else null
}
