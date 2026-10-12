package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory

/**
 * Reports that came close together: same category and zone, at most [Clustering.WINDOW_MS] after the first.
 * Only a hint for staff: every report stays its own incident, with its own status and actions.
 * [sameEvent] is false for medical, violence and lost people, where each report may be another person.
 */
class DuplicateHint(val incidentIds: List<String>, val sameEvent: Boolean) {
    val count: Int get() = incidentIds.size
}

/** [ACTIVE]: everything not closed yet (unassigned or taken), i.e. what staff still has to do. */
enum class StatusFilter {
    ALL, OPEN, TAKEN, ACTIVE, RESOLVED;

    fun matches(incident: StaffIncident): Boolean = when (this) {
        ALL -> true
        OPEN -> !AckStatus.isTaken(incident.status) && !AckStatus.isClosed(incident.status)
        TAKEN -> AckStatus.isTaken(incident.status)
        ACTIVE -> !AckStatus.isClosed(incident.status)
        RESOLVED -> AckStatus.isClosed(incident.status)
    }
}

object Clustering {
    const val WINDOW_MS = 5 * 60_000L

    /** Two patients in the same zone are two cases, so these are never called the same incident. */
    private val perPerson = setOf(
        IncidentCategory.MEDICAL, IncidentCategory.VIOLENCE, IncidentCategory.LOST_CHILD, IncidentCategory.LOST_FRIEND,
    )

    /**
     * The hint of every open report that came close to other open ones; a report on its own has no entry.
     * Resolved and cancelled reports neither get a hint nor count in one: they are no longer work for staff.
     */
    fun suggestions(incidents: List<StaffIncident>): Map<String, DuplicateHint> {
        val hints = mutableMapOf<String, DuplicateHint>()
        // without a zone we can't tell it's the same place
        val located = incidents.filter { it.zone.isNotEmpty() && StatusFilter.ACTIVE.matches(it) }.sortedBy { it.reportedAt }
        for (group in located.groupBy { it.category to it.zone }.values) {
            val windows = mutableListOf<MutableList<StaffIncident>>()
            for (incident in group) {
                val current = windows.lastOrNull()
                if (current != null && incident.reportedAt - current.first().reportedAt <= WINDOW_MS) current += incident
                else windows += mutableListOf(incident)
            }
            for (window in windows.filter { it.size > 1 }) {
                val hint = DuplicateHint(window.map { it.incidentId }, sameEvent = window.first().category !in perPerson)
                window.forEach { hints[it.incidentId] = hint }
            }
        }
        return hints
    }
}
