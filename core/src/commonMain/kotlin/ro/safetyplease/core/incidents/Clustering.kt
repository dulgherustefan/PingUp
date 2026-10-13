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

/** A crowd report as the signal sees it: the zone it names, when its reporter wrote it, and how much it counts. */
class CrowdReport(val zone: String, val reportedAt: Long, val weight: Int = 1)

/** [count] crowd reports from [zone], written over [minutes] minutes. */
class CrowdSignal(val zone: String, val count: Int, val minutes: Int)

/**
 * Several crowd reports from one zone in a short time say something to the crowd manager, but in front of a stage
 * "it's packed" is normal at every headliner, so this is a line on the console and nothing more: no sound, no alert,
 * nothing for the public. The numbers are a first guess, to be calibrated at the pilot against the false alarms of each show.
 */
object CrowdWatch {
    const val WINDOW_MS = 10 * 60_000L
    const val MIN_REPORTS = 5

    /** "Nu mă pot mișca" says more than "it's packed": it counts as this many reports. */
    const val CANNOT_MOVE_WEIGHT = 2

    /** The zones whose reports of the last [WINDOW_MS] weigh at least [MIN_REPORTS], the busiest first. */
    fun signals(reports: Collection<CrowdReport>, nowMs: Long): List<CrowdSignal> =
        reports.filter { it.zone.isNotBlank() && nowMs - it.reportedAt <= WINDOW_MS }
            .groupBy { it.zone.trim().lowercase() }
            .values
            .filter { zone -> zone.sumOf { it.weight } >= MIN_REPORTS }
            .map { zone ->
                val span = zone.maxOf { it.reportedAt } - zone.minOf { it.reportedAt }
                CrowdSignal(zone.first().zone.trim(), zone.size, maxOf(1L, (span + 59_999) / 60_000).toInt())
            }
            .sortedWith(compareByDescending<CrowdSignal> { it.count }.thenBy { it.zone })
}
