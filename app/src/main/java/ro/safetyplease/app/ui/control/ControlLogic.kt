package ro.safetyplease.app.ui.control

import androidx.annotation.StringRes
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.R
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/** What the operator can do with an incident, and the log entry each one writes. */
internal enum class Action(val event: LogEvent) {
    ASSIGN(LogEvent.ASSIGN),
    TAKE(LogEvent.TAKE),
    RESOLVE(LogEvent.RESOLVE),
    UNFOUNDED(LogEvent.UNFOUNDED),
    REOPEN(LogEvent.REOPEN),
}

/** Open and assigned incidents: the most urgent first, then the one waiting longest. Closed ones: last closed first. */
internal fun queue(cases: Collection<Case>, state: CaseState): List<Case> {
    val shown = cases.filter { it.state == state }
    return if (state == CaseState.CLOSED) shown.sortedByDescending { it.closedAt ?: it.report.reportedAt }
    else shown.sortedWith(compareByDescending<Case> { it.report.severity }.thenBy { it.report.reportedAt })
}

/** The operator's next step comes first. A closed incident can only be reopened; an assigned one can change team. */
internal fun actions(case: Case): List<Action> = when (case.state) {
    CaseState.OPEN -> listOf(Action.ASSIGN, Action.TAKE, Action.RESOLVE, Action.UNFOUNDED)
    CaseState.ASSIGNED -> listOf(Action.RESOLVE, Action.ASSIGN, Action.UNFOUNDED)
    CaseState.CLOSED -> listOf(Action.REOPEN)
}

/** The ACK status in one word, for the history and the export. */
@StringRes
internal fun ackWord(status: Int): Int = when (status) {
    AckStatus.RECEIVED -> R.string.control_ack_received
    AckStatus.ACKNOWLEDGED -> R.string.incident_state_taken
    AckStatus.RESOLVED -> R.string.status_resolved
    else -> R.string.status_cancelled
}

/**
 * The ACK an action sends, or null when the network is already at least that far, or has no word for it:
 * then the decision stays in the console log.
 */
internal fun ackFor(action: Action, network: Int): Int? = when (action) {
    Action.ASSIGN, Action.TAKE -> AckStatus.ACKNOWLEDGED.takeIf { network < it }
    Action.RESOLVE, Action.UNFOUNDED -> AckStatus.RESOLVED.takeIf { network < it }
    Action.REOPEN -> null
}

/** Teams to offer when assigning: those heard in ACKs and assignments, latest first, then the roles; never the console. */
internal fun teamChoices(entries: List<LogEntry>, roles: List<String>, own: String, max: Int = 8): List<String> {
    val heard = entries.asReversed().asSequence()
        .filter { it.event == LogEvent.ACK || it.event == LogEvent.ASSIGN }
        .map { it.team.trim() }
    return (heard + roles.asSequence())
        .filter { it.isNotEmpty() && !it.equals(own.trim(), ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .take(max)
        .toList()
}

/** Where the incident goes on the map: the reported position, else the middle of its zone. */
internal fun casePoint(case: Case, venue: Venue): GeoPoint? {
    val lat = case.report.lat
    val lon = case.report.lon
    return if (lat != null && lon != null) GeoPoint(lat, lon) else venue.zone(case.report.zone)?.center
}

internal val Case.urgent: Boolean get() = report.severity == Severity.URGENT

/** Runs the operator's decisions: each one goes into the log first, then out as an ACK when the network can carry it. */
internal class ConsoleActions(private val c: AppContainer) {
    fun run(case: Case, action: Action, team: String = "", reason: String = "") {
        val name = when (action) {
            Action.ASSIGN -> team.trim().truncateUtf8(Limits.TEAM_BYTES)
            Action.REOPEN -> ""
            else -> c.settings.value.teamName
        }
        if (action == Action.ASSIGN && name.isEmpty()) return
        c.console.decide(case.incidentId, action.event, name, reason)
        when (ackFor(action, case.network)) {
            AckStatus.ACKNOWLEDGED ->
                if (action == Action.ASSIGN) c.incidents.assign(case.incidentId, name) else c.incidents.acknowledge(case.incidentId)
            AckStatus.RESOLVED -> c.incidents.resolve(case.incidentId)
        }
    }
}
