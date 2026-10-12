package ro.safetyplease.core.control

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckOrder
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
    /** Latest ACK status on the network, its epoch, and the team it names. */
    val network: Int,
    val epoch: Int,
    val networkTeam: String,
    val receivedAt: Long?,
    val acknowledgedAt: Long?,
    val closedAt: Long?,
    /** The reporter cancelled in this epoch and the operator hasn't decided yet: "Anulat de raportor, de verificat". */
    val cancelRequested: Boolean = false,
) {
    val incidentId: String get() = report.incidentId

    /** The phones in the field see something else: the operator reassigned a taken incident here only. */
    val localOnly: Boolean
        get() = state != ConsoleView.stateOf(network) || (state == CaseState.ASSIGNED && team != networkTeam)

    /** Seconds from the report to [time]; the two clocks may differ by a few seconds. */
    fun secondsTo(time: Long?): Long? = time?.let { (it - report.reportedAt) / 1000 }
}

object ConsoleView {
    fun stateOf(status: Int): CaseState = when {
        AckStatus.isClosed(status) -> CaseState.CLOSED
        AckStatus.isTaken(status) -> CaseState.ASSIGNED
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
            if (incident != null) {
                if (AckOrder.compare(incident.epoch, incident.status, epoch, network) > 0) {
                    onNetwork(incident.status, incident.epoch, incident.teamName, null)
                }
                if (incident.cancelRequested && incident.epoch == epoch) cancelRequested = true
            }
        }.result()
    }

    private class Fold(val report: LogEntry) {
        var state = CaseState.OPEN
        var team = ""
        var unfounded = false
        var network = AckStatus.NONE
        var epoch = 0
        var networkTeam = ""
        var receivedAt: Long? = null
        var acknowledgedAt: Long? = null
        var closedAt: Long? = null
        var cancelRequested = false

        fun apply(e: LogEntry) {
            when (e.event) {
                LogEvent.ACK -> {
                    if (receivedAt == null) receivedAt = e.signedAt
                    val someoneHasIt = AckStatus.isTaken(e.status) || e.status == AckStatus.RESOLVED
                    if (acknowledgedAt == null && someoneHasIt) acknowledgedAt = e.signedAt
                    onNetwork(e.status, e.epoch, e.team, e.signedAt)
                }
                LogEvent.ASSIGN, LogEvent.TAKE -> {
                    if (acknowledgedAt == null) acknowledgedAt = e.at
                    move(CaseState.ASSIGNED, e.team, e.at)
                }
                LogEvent.RESOLVE, LogEvent.CANCEL -> move(CaseState.CLOSED, e.team, e.at)
                LogEvent.UNFOUNDED -> {
                    move(CaseState.CLOSED, e.team, e.at)
                    unfounded = true
                }
                LogEvent.REOPEN -> move(CaseState.OPEN, "", e.at)
                LogEvent.CANCEL_REQUEST -> if (e.epoch == epoch) cancelRequested = true
                LogEvent.REPORT, LogEvent.EXPORT -> Unit
            }
        }

        /**
         * A later ACK is news and moves the case, whatever the operator decided before. An equal one is a better
         * ACK for the same state (an older one, from another team): it only renames the team while the console agrees
         * with the network.
         */
        fun onNetwork(status: Int, ackEpoch: Int, ackTeam: String, at: Long?) {
            val order = AckOrder.compare(ackEpoch, status, epoch, network)
            if (order < 0) return
            val following = state == stateOf(network) && (state != CaseState.ASSIGNED || team == networkTeam)
            if (ackEpoch > epoch) cancelRequested = false
            network = status
            epoch = ackEpoch
            networkTeam = ackTeam
            if (order > 0 || following) move(stateOf(status), ackTeam, at)
            if (order > 0 && status == AckStatus.UNFOUNDED) unfounded = true
        }

        fun move(to: CaseState, by: String, at: Long?) {
            if (to == CaseState.CLOSED && state != CaseState.CLOSED) closedAt = at
            if (to != CaseState.CLOSED) {
                closedAt = null
                unfounded = false
            }
            state = to
            team = if (to == CaseState.OPEN) "" else by
        }

        fun result() = Case(
            report, state, team, unfounded, network, epoch, networkTeam, receivedAt, acknowledgedAt, closedAt,
            cancelRequested && state != CaseState.CLOSED,
        )
    }
}
