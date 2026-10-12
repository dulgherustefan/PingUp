package ro.safetyplease.core.report

import ro.safetyplease.core.control.LogExport
import java.time.ZoneId

/** Words for the report's CSV cells; the app passes its own, in its language. */
class ReportNames(
    val category: (Int) -> String,
    val severity: (Int) -> String,
    val outcome: (Outcome) -> String,
    val real: String,
    val exercise: String,
)

/**
 * The report as two CSV files: one row per incident, and the figures by section. Same CSV rules as the log export
 * (byte order mark, RFC 4180); nothing in them names a person or a phone.
 */
object ReportExport {
    val INCIDENT_COLUMNS = listOf(
        "mode", "opened_at", "grid", "category", "severity", "staff_report", "outcome", "s_to_received",
        "s_to_acknowledged", "s_to_close", "widened", "reopened", "hops",
    )

    val SUMMARY_COLUMNS = listOf("mode", "section", "key", "value")

    fun incidentsCsv(report: EditionReport, names: ReportNames, zone: ZoneId): String {
        val csv = LogExport.Csv(INCIDENT_COLUMNS)
        for ((mode, part) in modes(report, names)) {
            for (r in part.rows) {
                csv.row(
                    mode, LogExport.time(r.openedAt, zone), r.grid, names.category(r.category), names.severity(r.severity),
                    flag(r.fromStaff), names.outcome(r.outcome), cell(r.sToReceived), cell(r.sToAcknowledged), cell(r.sToClose),
                    r.widened.toString(), r.reopened.toString(), r.hops.toString(),
                )
            }
        }
        return csv.toString()
    }

    fun summaryCsv(report: EditionReport, names: ReportNames, zone: ZoneId): String {
        val csv = LogExport.Csv(SUMMARY_COLUMNS)
        csv.row("", "window", "from", LogExport.time(report.from, zone))
        csv.row("", "window", "until", LogExport.time(report.until, zone))
        for ((mode, part) in modes(report, names)) {
            fun row(section: String, key: String, value: Any?) = csv.row(mode, section, key, value?.toString().orEmpty())
            row("total", "incidents", part.total)
            row("total", "staff_reports", part.staffReports)
            // two codes may share a word, as low and medium do in the app: their counts add up
            byWord(part.byCategory, names.category).forEach { (k, v) -> row("category", k, v) }
            byWord(part.bySeverity, names.severity).forEach { (k, v) -> row("severity", k, v) }
            part.byHour.forEach { (k, v) -> row("hour", LogExport.time(k, zone), v) }
            part.byGrid.forEach { (k, v) -> row("grid", k, v) }
            part.byOutcome.forEach { (k, v) -> row("outcome", names.outcome(k), v) }
            for ((step, d) in listOf("received" to part.received, "acknowledged" to part.acknowledged, "closed" to part.closed)) {
                row("s_to_$step", "count", d.count)
                row("s_to_$step", "median", d.medianS)
                row("s_to_$step", "p90", d.p90S)
            }
            row("escalation", "widened_to_role", part.widened)
            row("escalation", "widened_to_all_staff", part.widenedToAll)
            row("escalation", "reopened", part.reopened)
        }
        val n = report.network
        fun row(section: String, key: String, value: Any) = csv.row("", section, key, value.toString())
        row("network", "window_min", n.windowMs / 60_000)
        row("network", "measured_min", n.measuredMs / 60_000)
        row("network", "isolated_min", n.isolatedMs / 60_000)
        row("network", "anchors", n.anchors.size)
        row("network", "anchors_silent", n.silentAnchors.size)
        n.silentAnchors.forEach { row("anchor_silent", LogExport.text(it.zone), LogExport.time(it.lastHeardMs, zone)) }
        row("mobile", "samples", n.mobileSamples)
        row("mobile", "tested", n.mobileTested)
        row("mobile", "worked", n.mobileWorked)
        n.noDataSquares.forEach { row("mobile_no_data", it, 1) }
        row("delivery", "reporter_records", n.delivery.records)
        row("delivery", "reached_console", n.delivery.reached)
        report.limits.forEach { row("limit", it.kind.name.lowercase(), it.value) }
        return csv.toString()
    }

    private fun modes(report: EditionReport, names: ReportNames) = listOf(names.real to report.real, names.exercise to report.exercise)

    fun byWord(counts: Map<Int, Int>, word: (Int) -> String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for ((code, n) in counts) out.merge(word(code), n, Int::plus)
        return out
    }

    private fun cell(value: Long?): String = value?.toString().orEmpty()

    private fun flag(value: Boolean): String = if (value) "1" else "0"
}
