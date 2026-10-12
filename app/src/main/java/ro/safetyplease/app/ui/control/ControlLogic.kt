package ro.safetyplease.app.ui.control

import androidx.annotation.StringRes
import ro.safetyplease.app.R
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.incidents.AlertPlanner
import ro.safetyplease.core.incidents.CrowdReport
import ro.safetyplease.core.incidents.CrowdSignal
import ro.safetyplease.core.incidents.CrowdWatch
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/** What the operator can do with an incident, and the log entry each one writes. */
internal enum class Action(val event: LogEvent) {
    ASSIGN(LogEvent.ASSIGN),
    TAKE(LogEvent.TAKE),
    CLOSE(LogEvent.RESOLVE),
    UNFOUNDED(LogEvent.UNFOUNDED),

    /** The reporter's cancel, checked by the operator. */
    CONFIRM_CANCEL(LogEvent.CANCEL),
    REOPEN(LogEvent.REOPEN),
}

/** Open and assigned incidents: the most urgent first, then the one waiting longest. Closed ones: last closed first. */
internal fun queue(cases: Collection<Case>, state: CaseState): List<Case> {
    val shown = cases.filter { it.state == state }
    return if (state == CaseState.CLOSED) shown.sortedByDescending { it.closedAt ?: it.report.reportedAt }
    else shown.sortedWith(compareByDescending<Case> { it.report.severity }.thenBy { it.report.reportedAt })
}

/** The crowd signal for the queue: crowd reports of cases still open or taken, by zone. Closed ones no longer count. */
internal fun crowdSignals(cases: Collection<Case>, nowMs: Long): List<CrowdSignal> = CrowdWatch.signals(
    cases.filter { it.state != CaseState.CLOSED && it.report.category == IncidentCategory.CROWD }
        .map { CrowdReport(it.report.zone, it.report.reportedAt) },
    nowMs,
)

/**
 * The operator's next step comes first: confirming a cancel the reporter sent, when there is one. A closed incident can
 * only be reopened, as long as its epoch can grow; an assigned one can change team.
 */
internal fun actions(case: Case): List<Action> {
    val steps = when (case.state) {
        CaseState.OPEN -> listOf(Action.ASSIGN, Action.TAKE, Action.CLOSE, Action.UNFOUNDED)
        CaseState.ASSIGNED -> listOf(Action.CLOSE, Action.ASSIGN, Action.UNFOUNDED)
        CaseState.CLOSED -> if (case.epoch < IncidentAck.MAX_EPOCH) listOf(Action.REOPEN) else emptyList()
    }
    return if (case.cancelRequested) listOf(Action.CONFIRM_CANCEL) + steps else steps
}

/** The ACK status in one word, for the history and the export. */
@StringRes
internal fun ackWord(status: Int): Int = when (status) {
    AckStatus.RECEIVED -> R.string.control_ack_received
    AckStatus.ACKNOWLEDGED -> R.string.incident_state_taken
    AckStatus.RESOLVED -> R.string.status_resolved
    AckStatus.ON_SCENE -> R.string.incident_state_on_scene
    AckStatus.HANDED_OVER -> R.string.incident_state_handed_over
    AckStatus.CLOSED_BY_CONTROL -> R.string.staff_status_closed
    AckStatus.UNFOUNDED -> R.string.control_state_unfounded
    AckStatus.REOPENED -> R.string.control_state_reopened
    else -> R.string.status_cancelled
}

/**
 * The ACK an action sends, or null when the network is already at least that far in this epoch: then the decision
 * stays in the console log. A reopen always goes out, in the next epoch.
 */
internal fun ackFor(action: Action, network: Int): Int? {
    val status = when (action) {
        Action.ASSIGN, Action.TAKE -> AckStatus.ACKNOWLEDGED
        Action.CLOSE -> AckStatus.CLOSED_BY_CONTROL
        Action.UNFOUNDED -> AckStatus.UNFOUNDED
        Action.CONFIRM_CANCEL -> AckStatus.CANCELLED
        Action.REOPEN -> return AckStatus.REOPENED
    }
    return status.takeIf { AckStatus.rank(network) < AckStatus.rank(it) }
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

/** Open again: reopened on the network, or here before the network has it. */
internal val Case.reopened: Boolean get() = state == CaseState.OPEN && (localOnly || network == AckStatus.REOPENED)

/** Whole minutes left of "Silențios 10 min", rounded up; 0 when the console isn't muted. */
internal fun mutedMinutes(mutedUntil: Long, nowMs: Long): Int {
    if (mutedUntil <= nowMs) return 0
    val minutes = (mutedUntil - nowMs + 59_999) / 60_000
    return minutes.coerceAtMost(AlertPlanner.MUTE_MS / 60_000).toInt()
}

/** Runs the operator's decisions: each one goes into the log first, then out as an ACK when the network can carry it. */
internal class ConsoleActions(private val c: PingUpSession) {
    fun run(case: Case, action: Action, team: String = "", reason: String = "") {
        val name = when (action) {
            Action.ASSIGN -> team.trim().truncateUtf8(Limits.TEAM_BYTES)
            Action.REOPEN -> ""
            else -> c.settings.value.teamName
        }
        if (action == Action.ASSIGN && name.isEmpty()) return
        c.console.decide(case.incidentId, action.event, name, reason)
        val id = case.incidentId
        when (ackFor(action, case.network)) {
            AckStatus.ACKNOWLEDGED -> if (action == Action.ASSIGN) c.incidents.assign(id, name) else c.incidents.acknowledge(id)
            AckStatus.CLOSED_BY_CONTROL -> c.incidents.close(id)
            AckStatus.UNFOUNDED -> c.incidents.unfounded(id)
            AckStatus.CANCELLED -> c.incidents.confirmCancel(id)
            AckStatus.REOPENED -> c.incidents.reopen(id)
        }
    }
}
