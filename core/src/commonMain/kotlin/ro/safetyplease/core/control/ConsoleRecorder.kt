package ro.safetyplease.core.control

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.SiteGrid

/**
 * Writes the console log: every report the console opens, every ACK that becomes an incident's state on the network,
 * every cancel the operator has to check and every decision of the operator, with its reason. Entries are only ever
 * appended.
 */
class ConsoleRecorder(
    private val store: ConsoleLogStore,
    private val grid: SiteGrid,
    private val clock: Clock,
    /** The incident as IncidentManager keeps it, so the RECEIVED signed while opening a report comes after the report. */
    private val incident: (incidentId: String) -> StaffIncident?,
) {
    private val logged = store.value.entries.filter { it.event == LogEvent.REPORT }.mapTo(HashSet()) { it.incidentId }

    /** Cancel requests already logged, by incident and epoch. */
    private val requests = store.value.entries.filter { it.event == LogEvent.CANCEL_REQUEST }.mapTo(HashSet()) { it.incidentId to it.epoch }

    private val lock = SynchronizedObject()

    fun sawIncidents(incidents: List<StaffIncident>) = synchronized(lock) {
        val now = clock.wallMs()
        val reports = incidents.filter { it.incidentId !in logged }.map { reportEntry(it, grid) }
        val cancels = incidents.filter { it.cancelRequested && (it.incidentId to it.epoch) !in requests }
            .map { LogEntry(now, LogEvent.CANCEL_REQUEST, it.incidentId, epoch = it.epoch) }
        append(reports + cancels)
    }

    fun sawAck(ack: IncidentAck, own: Boolean, hops: Int) = synchronized(lock) {
        val id = ack.incidentId.toHex()
        val report = if (id in logged) null else incident(id)?.let { reportEntry(it, grid) }
        val entry = LogEntry(
            clock.wallMs(), LogEvent.ACK, id, status = ack.status, epoch = ack.epoch, team = ack.teamName,
            signedAt = ack.timestamp * 1000, signature = ack.signature.toHex(), own = own, hops = hops,
        )
        append(listOfNotNull(report, entry))
    }

    /** [team] is the one assigned, or the console's own when it takes, resolves or closes. */
    fun decide(incidentId: String, event: LogEvent, team: String = "", reason: String = "") = synchronized(lock) {
        require(event in LogEvent.DECISIONS)
        append(listOf(LogEntry(clock.wallMs(), event, incidentId, team = team, reason = reason.trim())))
    }

    fun exported() = synchronized(lock) { append(listOf(LogEntry(clock.wallMs(), LogEvent.EXPORT))) }

    /** A notice this console sent ([own]) or heard from another, with its text as the console read it. */
    fun notice(notice: Notice, text: String, own: Boolean) = synchronized(lock) {
        append(
            listOf(
                LogEntry(
                    clock.wallMs(), if (notice.cancelled) LogEvent.NOTICE_CANCEL else LogEvent.NOTICE, zone = notice.zone,
                    description = text, team = notice.certificate.name, signedAt = notice.issued * 1000,
                    signature = notice.signature.toHex(), own = own, topic = notice.topic, level = notice.level,
                    template = notice.template, seq = notice.seq, validUntil = notice.validUntil * 1000,
                ),
            ),
        )
    }

    private fun append(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        entries.filter { it.event == LogEvent.REPORT }.mapTo(logged) { it.incidentId }
        entries.filter { it.event == LogEvent.CANCEL_REQUEST }.mapTo(requests) { it.incidentId to it.epoch }
        store.append(entries)
    }
}
