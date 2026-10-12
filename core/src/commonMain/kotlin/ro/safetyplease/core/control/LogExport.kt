package ro.safetyplease.core.control

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.datetime.format.format
import kotlinx.datetime.offsetAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.util.formatDecimal
import ro.safetyplease.core.venue.SiteGrid
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Words for the CSV cells that hold codes; the app passes its own. */
class ExportNames(
    val event: (LogEvent) -> String,
    val category: (Int) -> String,
    val severity: (Int) -> String,
    val status: (Int) -> String,
    val state: (CaseState) -> String,
    val zone: (String) -> String,
    /** A triage answer, by its [ro.safetyplease.core.protocol.Answer] code. */
    val answer: (Int) -> String,
)

@Serializable
class GridInfo(val columns: Int, val rows: Int, val cellMeters: Int)

/** One incident in the export: where the console left it, and how long each step took. */
@Serializable
class IncidentSummary(
    val incidentId: String,
    val state: CaseState,
    val team: String,
    val unfounded: Boolean,
    val localOnly: Boolean,
    val network: Int,
    val epoch: Int,
    val networkTeam: String,
    val cancelRequested: Boolean,
    val secondsToReceived: Long?,
    val secondsToAcknowledged: Long?,
    val secondsToClose: Long?,
)

/** The whole log in JSON: every entry with its codes, and a summary per incident. */
@Serializable
class ExportDocument(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: String,
    val console: String,
    val venue: String,
    val grid: GridInfo,
    val entries: List<LogEntry>,
    val incidents: List<IncidentSummary>,
)

/**
 * The console log for the organizer's systems, at the end of a shift: an event CSV (one row per entry), an incident
 * CSV (one row per incident, with times) and a JSON with everything. Column names are a stable format, the cells are
 * words. Times are local, ISO 8601, to the second.
 */
object LogExport {
    const val FORMAT = "pingup-console-log"
    const val VERSION = 2

    private val codec = Json { encodeDefaults = true }
    /** ISO 8601 to the second with the local offset: 2026-07-10T21:04:05+03:00, or Z at UTC. */
    private val iso = DateTimeComponents.Format {
        date(LocalDate.Formats.ISO)
        char('T')
        hour()
        char(':')
        minute()
        char(':')
        second()
        offset(UtcOffset.Formats.ISO)
    }

    val EVENT_COLUMNS = listOf(
        "row", "time", "event", "incident", "grid", "zone", "category", "severity", "status", "team", "signed_at",
        "signature", "signed_here", "hops", "reported_at", "lat", "lon", "description", "reason", "epoch", "responds", "breathes",
        "staff_report", "people", "danger_continues",
    )

    val INCIDENT_COLUMNS = listOf(
        "incident", "reported_at", "grid", "zone", "category", "severity", "state", "team", "unfounded", "local_only",
        "hops", "s_to_received", "s_to_acknowledged", "s_to_close", "description",
    )

    fun eventsCsv(entries: List<LogEntry>, names: ExportNames, zone: TimeZone): String {
        val csv = Csv(EVENT_COLUMNS)
        entries.forEachIndexed { index, e ->
            val report = e.event == LogEvent.REPORT
            val ack = e.event == LogEvent.ACK
            val notice = e.event == LogEvent.NOTICE || e.event == LogEvent.NOTICE_CANCEL
            csv.row(
                (index + 1).toString(), time(e.at, zone), names.event(e.event), e.incidentId, e.grid,
                if (report || notice) text(names.zone(e.zone)) else "",
                if (report) names.category(e.category) else "",
                if (report) names.severity(e.severity) else "",
                if (ack) names.status(e.status) else "",
                text(e.team),
                if (ack || notice) time(e.signedAt, zone) else "",
                e.signature,
                if (ack || notice) flag(e.own) else "",
                if (report || ack) e.hops.toString() else "",
                if (report) time(e.reportedAt, zone) else "",
                coord(e.lat), coord(e.lon), text(e.description), text(e.reason),
                if (ack || e.event == LogEvent.CANCEL_REQUEST) e.epoch.toString() else "",
                if (report && e.responds != Answer.UNASKED) names.answer(e.responds) else "",
                if (report && e.breathes != Answer.UNASKED) names.answer(e.breathes) else "",
                if (report) flag(e.fromStaff) else "",
                if (report && e.people > 0) e.people.toString() else "",
                if (report && e.fromStaff) flag(e.dangerContinues) else "",
            )
        }
        return csv.toString()
    }

    fun incidentsCsv(cases: Collection<Case>, names: ExportNames, zone: TimeZone): String {
        val csv = Csv(INCIDENT_COLUMNS)
        for (c in cases.sortedBy { it.report.reportedAt }) {
            val r = c.report
            csv.row(
                c.incidentId, time(r.reportedAt, zone), r.grid, text(names.zone(r.zone)), names.category(r.category),
                names.severity(r.severity), names.state(c.state), text(c.team), flag(c.unfounded), flag(c.localOnly),
                r.hops.toString(), seconds(c.secondsTo(c.receivedAt)), seconds(c.secondsTo(c.acknowledgedAt)),
                seconds(c.secondsTo(c.closedAt)), text(r.description),
            )
        }
        return csv.toString()
    }

    fun json(
        entries: List<LogEntry>,
        cases: Collection<Case>,
        console: String,
        venue: String,
        grid: SiteGrid,
        exportedAt: Long,
        zone: TimeZone,
    ): String = codec.encodeToString(
        ExportDocument.serializer(),
        ExportDocument(
            FORMAT, VERSION, time(exportedAt, zone), console, venue, GridInfo(grid.columns, grid.rows, SiteGrid.CELL_METERS), entries,
            cases.sortedBy { it.report.reportedAt }.map {
                IncidentSummary(
                    it.incidentId, it.state, it.team, it.unfounded, it.localOnly, it.network, it.epoch, it.networkTeam, it.cancelRequested,
                    it.secondsTo(it.receivedAt), it.secondsTo(it.acknowledgedAt), it.secondsTo(it.closedAt),
                )
            },
        ),
    )

    @OptIn(ExperimentalTime::class)
    fun time(ms: Long, zone: TimeZone): String {
        if (ms <= 0) return ""
        val instant = Instant.fromEpochMilliseconds(ms - ms % 1000)
        return iso.format { setDateTimeOffset(instant, zone.offsetAt(instant)) }
    }

    private fun seconds(value: Long?): String = value?.toString().orEmpty()

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun coord(value: Double?): String = value?.let { formatDecimal(it, 6) }.orEmpty()

    /**
     * Text that came from a phone or a keyboard: a spreadsheet would run a cell starting with = + - or @ as a formula,
     * so such a cell starts with an apostrophe.
     */
    fun text(value: String): String = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

    /** RFC 4180, with a byte order mark so spreadsheets read the diacritics. */
    internal class Csv(header: List<String>) {
        private val out = StringBuilder("﻿")

        init {
            row(*header.toTypedArray())
        }

        fun row(vararg cells: String) {
            cells.joinTo(out, ",") { if (it.any { ch -> ch in ",\"\r\n" }) "\"" + it.replace("\"", "\"\"") + "\"" else it }
            out.append("\r\n")
        }

        override fun toString() = out.toString()
    }
}
