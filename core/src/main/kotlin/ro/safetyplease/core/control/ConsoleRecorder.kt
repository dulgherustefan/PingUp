package ro.safetyplease.core.control

import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.SiteGrid

/**
 * Writes the console log: every report the console opens, every ACK that becomes an incident's state on the network
 * and every decision of the operator, with its reason. Entries are only ever appended.
 */
class ConsoleRecorder(
    private val store: JsonStore<ConsoleLog>,
    private val grid: SiteGrid,
    private val clock: Clock,
    /** The incident as IncidentManager keeps it, so the RECEIVED signed while opening a report comes after the report. */
    private val incident: (incidentId: String) -> StaffIncident?,
) {
    private val logged = store.value.entries.filter { it.event == LogEvent.REPORT }.mapTo(HashSet()) { it.incidentId }

    @Synchronized
    fun sawIncidents(incidents: List<StaffIncident>) = append(incidents.filter { it.incidentId !in logged }.map { reportEntry(it, grid) })

    @Synchronized
    fun sawAck(ack: IncidentAck, own: Boolean, hops: Int) {
        val id = ack.incidentId.toHex()
        val report = if (id in logged) null else incident(id)?.let { reportEntry(it, grid) }
        val entry = LogEntry(
            clock.wallMs(), LogEvent.ACK, id, status = ack.status, team = ack.teamName, signedAt = ack.timestamp * 1000,
            signature = ack.signature.toHex(), own = own, hops = hops,
        )
        append(listOfNotNull(report, entry))
    }

    /** [team] is the one assigned, or the console's own when it takes, resolves or closes. */
    @Synchronized
    fun decide(incidentId: String, event: LogEvent, team: String = "", reason: String = "") {
        require(event in LogEvent.DECISIONS)
        append(listOf(LogEntry(clock.wallMs(), event, incidentId, team = team, reason = reason.trim())))
    }

    @Synchronized
    fun exported() = append(listOf(LogEntry(clock.wallMs(), LogEvent.EXPORT)))

    private fun append(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        entries.filter { it.event == LogEvent.REPORT }.mapTo(logged) { it.incidentId }
        store.update { it.copy(entries = it.entries + entries) }
    }
}
