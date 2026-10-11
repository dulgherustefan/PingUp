package ro.safetyplease.core.control

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.SiteGrid

@Serializable
enum class CaseState { OPEN, ASSIGNED, CLOSED }

/**
 * The console's view of one incident: the state on the network, with the operator's decisions on top.
 * Times are null when the log doesn't know them; [receivedAt] and [acknowledgedAt] are on the signer's clock.
 */
data class Case(
    val report: LogEntry,
    val state: CaseState,
    /** Who has it: the assigned team, or whoever closed it. Empty while open. */
    val team: String,
    val unfounded: Boolean,
    /** Highest ACK status on the network, and the team it names. */
    val network: Int,
    val networkTeam: String,
    val receivedAt: Long?,
    val acknowledgedAt: Long?,
    val closedAt: Long?,
) {
    val incidentId: String get() = report.incidentId

    /** The phones in the field see something else: the operator reopened, reassigned or closed it here only. */
    val localOnly: Boolean
        get() = state != ConsoleView.stateOf(network) || (state == CaseState.ASSIGNED && team != networkTeam)

    /** Seconds from the report to [time]; the two clocks may differ by a few seconds. */
    fun secondsTo(time: Long?): Long? = time?.let { (it - report.reportedAt) / 1000 }
}

object ConsoleView {
    fun stateOf(status: Int): CaseState = when {
        status >= AckStatus.RESOLVED -> CaseState.CLOSED
        status == AckStatus.ACKNOWLEDGED -> CaseState.ASSIGNED
        else -> CaseState.OPEN
    }

    /**
     * Every incident the console can read, folded from its log entries. [incidents] are those IncidentManager keeps:
     * one opened before this phone became the console still shows, with the state it has on the network.
     */
    fun cases(entries: List<LogEntry>, incidents: List<StaffIncident>, grid: SiteGrid): Map<String, Case> {
        val byIncident = entries.filter { it.incidentId.isNotEmpty() }.groupBy { it.incidentId }
        val stored = incidents.associateBy { it.incidentId }
        val out = LinkedHashMap<String, Case>()
        for (id in byIncident.keys + stored.keys) case(byIncident[id].orEmpty(), stored[id], grid)?.let { out[id] = it }
        return out
    }

    /** Null if the console never read the report: an ACK alone says nothing about what happened. */
    fun case(entries: List<LogEntry>, incident: StaffIncident?, grid: SiteGrid): Case? {
        val report = entries.firstOrNull { it.event == LogEvent.REPORT } ?: incident?.let { reportEntry(it, grid) } ?: return null
        return Fold(report).apply {
            entries.forEach(::apply)
            if (incident != null && incident.status > network) onNetwork(incident.status, incident.teamName, null)
        }.result()
    }

    private class Fold(val report: LogEntry) {
        var state = CaseState.OPEN
        var team = ""
        var unfounded = false
        var network = AckStatus.NONE
        var networkTeam = ""
        var receivedAt: Long? = null
        var acknowledgedAt: Long? = null
        var closedAt: Long? = null

        fun apply(e: LogEntry) {
            when (e.event) {
                LogEvent.ACK -> {
                    if (receivedAt == null) receivedAt = e.signedAt
                    if (acknowledgedAt == null && e.status in AckStatus.ACKNOWLEDGED..AckStatus.RESOLVED) acknowledgedAt = e.signedAt
                    onNetwork(e.status, e.team, e.signedAt)
                }
                LogEvent.ASSIGN, LogEvent.TAKE -> {
                    if (acknowledgedAt == null) acknowledgedAt = e.at
                    move(CaseState.ASSIGNED, e.team, e.at)
                }
                LogEvent.RESOLVE -> move(CaseState.CLOSED, e.team, e.at)
                LogEvent.UNFOUNDED -> {
                    move(CaseState.CLOSED, e.team, e.at)
                    unfounded = true
                }
                LogEvent.REOPEN -> {
                    move(CaseState.OPEN, "", e.at)
                    unfounded = false
                }
                LogEvent.REPORT, LogEvent.EXPORT -> Unit
            }
        }

        /**
         * A higher status is news and moves the case, whatever the operator decided before. An equal one is a better
         * ACK for the same state (an older one, from another team): it only renames the team while the console agrees
         * with the network.
         */
        fun onNetwork(status: Int, ackTeam: String, at: Long?) {
            if (status < network) return
            val following = state == stateOf(network) && (state != CaseState.ASSIGNED || team == networkTeam)
            val news = status > network
            network = status
            networkTeam = ackTeam
            if (news || following) move(stateOf(status), ackTeam, at)
        }

        fun move(to: CaseState, by: String, at: Long?) {
            if (to == CaseState.CLOSED && state != CaseState.CLOSED) closedAt = at
            if (to != CaseState.CLOSED) closedAt = null
            state = to
            team = if (to == CaseState.OPEN) "" else by
        }

        fun result() = Case(report, state, team, unfounded, network, networkTeam, receivedAt, acknowledgedAt, closedAt)
    }
}
