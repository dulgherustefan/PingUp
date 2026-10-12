package ro.safetyplease.core.control

import kotlinx.serialization.Serializable
import ro.safetyplease.core.metrics.ReportTiming

/**
 * One incident in the delivery join: what the reporter's record says, on the reporter's clock, next to what the
 * console log says, on the console's clock. The two clocks are never subtracted from each other: phones in airplane
 * mode drift, so delivery is a yes or no here and latency is the reporter's round trip.
 */
@Serializable
data class DeliveryRow(
    val incidentId: String,
    val category: Int,
    val severity: Int,
    val grid: String,
    /** The console was given the reporter's record of this report. */
    val inReporterLog: Boolean,
    /** The console opened the report. */
    val atConsole: Boolean,
    val createdAt: Long? = null,
    val consoleAt: Long? = null,
    val consoleHops: Int? = null,
    val msToReceived: Long? = null,
    val msToAcknowledged: Long? = null,
    val firstAckHops: Int? = null,
    val republished: Int? = null,
)

/** The reporters' records given to the console, one per incident; the newest copy of a record wins. */
@Serializable
data class ReporterRecords(val reports: List<ReportTiming> = emptyList()) {
    fun merged(more: List<ReportTiming>): ReporterRecords {
        val byId = LinkedHashMap<String, ReportTiming>()
        for (r in reports + more) {
            byId.remove(r.incidentId)
            byId[r.incidentId] = r
        }
        return ReporterRecords(byId.values.toList().takeLast(MAX_REPORTS))
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_REPORTS = 5_000
    }
}

/** Joins the console log with the reporters' records by incidentId, for the delivery figure of the pilot. */
object MetricsJoin {
    /** Every report the console opened and every record it was given, in the reporter's order, then the console's. */
    fun join(entries: List<LogEntry>, reports: List<ReportTiming>): List<DeliveryRow> {
        val opened = LinkedHashMap<String, LogEntry>()
        for (e in entries) if (e.event == LogEvent.REPORT && e.incidentId !in opened) opened[e.incidentId] = e
        val records = reports.associateBy { it.incidentId }
        val fromReporters = records.values.sortedBy { it.createdAt }.map { r ->
            val console = opened[r.incidentId]
            DeliveryRow(
                r.incidentId, r.category, r.severity, r.grid, inReporterLog = true, atConsole = console != null,
                createdAt = r.createdAt, consoleAt = console?.at, consoleHops = console?.hops,
                msToReceived = r.msToReceived, msToAcknowledged = r.msToAcknowledged, firstAckHops = r.firstAckHops,
                republished = r.republished,
            )
        }
        val consoleOnly = opened.values.filter { it.incidentId !in records }.map { e ->
            DeliveryRow(
                e.incidentId, e.category, e.severity, e.grid, inReporterLog = false, atConsole = true,
                consoleAt = e.at, consoleHops = e.hops,
            )
        }
        return fromReporters + consoleOnly
    }
}
