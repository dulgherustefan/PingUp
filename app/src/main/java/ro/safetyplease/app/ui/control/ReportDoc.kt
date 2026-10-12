package ro.safetyplease.app.ui.control

import android.content.res.Resources
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.report.Durations
import ro.safetyplease.core.report.EditionReport
import ro.safetyplease.core.report.EditionReports
import ro.safetyplease.core.report.Limit
import ro.safetyplease.core.report.LimitKind
import ro.safetyplease.core.report.Part
import ro.safetyplease.core.report.ReportExport
import ro.safetyplease.core.report.ReportNames
import ro.safetyplease.core.venue.Venue
import java.text.SimpleDateFormat
import java.util.Date

/** A line of the report: a figure with its [value], or a sentence alone when [value] is empty. */
internal class DocLine(val label: String, val value: String = "")

internal class DocSection(val title: String, val lines: List<DocLine>)

/** The report in the app's language, as both the preview and the PDF show it. */
internal class ReportDoc(val title: String, val subtitle: String, val sections: List<DocSection>)

internal fun reportNames(res: Resources): ReportNames {
    val outcomes = res.getStringArray(R.array.edition_outcomes)
    return ReportNames(
        category = { res.getString(Labels.category(it)) },
        severity = { res.getString(Labels.urgency(it)) },
        outcome = { outcomes[it.ordinal] },
        real = res.getString(R.string.edition_mode_real),
        exercise = res.getString(R.string.edition_mode_exercise),
    )
}

/** "What it covers and what it doesn't" comes first and always: the reader meets the limits before the figures. */
internal fun reportDoc(report: EditionReport, kind: String, res: Resources, venue: Venue, generatedAt: Long): ReportDoc {
    fun s(id: Int, vararg args: Any): String = res.getString(id, *args)
    val full = SimpleDateFormat(s(R.string.date_pattern_full), res.configuration.locales[0])
    val recent = SimpleDateFormat(s(R.string.date_pattern_recent), res.configuration.locales[0])
    fun at(ms: Long) = full.format(Date(ms))
    val names = reportNames(res)
    val n = report.network
    val limits = DocSection(
        s(R.string.edition_section_limits),
        listOf(DocLine(s(R.string.edition_window), s(R.string.edition_window_value, at(report.from), at(report.until)))) +
            report.limits.map { DocLine(limitText(it, res)) },
    )
    val network = DocSection(
        s(R.string.edition_section_network),
        buildList {
            add(DocLine(s(R.string.edition_measured), s(R.string.edition_count_of, minutes(n.measuredMs), minutes(n.windowMs))))
            add(DocLine(s(R.string.edition_isolated), s(R.string.edition_minutes, minutes(n.isolatedMs))))
            add(DocLine(s(R.string.edition_anchors), n.anchors.size.toString()))
            add(DocLine(s(R.string.edition_anchors_silent, minutes(EditionReports.SILENT_ANCHOR_MS)), n.silentAnchors.size.toString()))
            for (anchor in n.silentAnchors) {
                add(DocLine(s(R.string.edition_anchor_row, venue.zoneName(anchor.zone).ifEmpty { "—" }), recent.format(Date(anchor.lastHeardMs))))
            }
            if (n.mobileTested > 0) {
                add(DocLine(s(R.string.edition_mobile_worked), s(R.string.edition_count_of, n.mobileWorked, n.mobileTested)))
            }
            if (n.noDataSquares.isNotEmpty()) add(DocLine(s(R.string.edition_mobile_no_data), n.noDataSquares.joinToString(", ")))
            if (n.delivery.records > 0) {
                add(DocLine(s(R.string.edition_delivery), s(R.string.edition_count_of, n.delivery.reached, n.delivery.records)))
            }
        },
    )
    val exercise = if (report.exercise.total == 0) {
        DocSection(s(R.string.edition_section_exercise), listOf(DocLine(s(R.string.edition_exercise_none))))
    } else {
        val part = report.exercise
        DocSection(s(R.string.edition_section_exercise), overview(part, res) + outcomes(part, names) + times(part, res))
    }
    val real = report.real
    val sections = buildList {
        add(limits)
        add(DocSection(s(R.string.edition_section_incidents), overview(real, res)))
        if (real.total > 0) {
            add(DocSection(s(R.string.edition_section_category), counts(ReportExport.byWord(real.byCategory, names.category))))
            add(DocSection(s(R.string.edition_section_severity), counts(ReportExport.byWord(real.bySeverity, names.severity))))
            add(DocSection(s(R.string.edition_section_outcome), outcomes(real, names)))
            add(DocSection(s(R.string.edition_section_hour), counts(real.byHour.mapKeys { recent.format(Date(it.key)) })))
            add(
                DocSection(
                    s(R.string.edition_section_grid),
                    counts(real.byGrid.mapKeys { it.key.ifEmpty { s(R.string.edition_no_square) } }),
                ),
            )
            add(DocSection(s(R.string.edition_section_times), times(real, res)))
            add(
                DocSection(
                    s(R.string.edition_section_escalation),
                    listOf(
                        DocLine(s(R.string.edition_widened_role, Routing.WIDEN_AFTER_MS / 1000), real.widened.toString()),
                        DocLine(s(R.string.edition_widened_all, 2 * Routing.WIDEN_AFTER_MS / 1000), real.widenedToAll.toString()),
                        DocLine(s(R.string.edition_reopened), real.reopened.toString()),
                    ),
                ),
            )
        }
        add(network)
        add(exercise)
    }
    return ReportDoc(
        "${s(R.string.edition_title)} · $kind",
        listOf(venue.name, s(R.string.edition_generated, at(generatedAt))).filter { it.isNotEmpty() }.joinToString(" · "),
        sections,
    )
}

private fun overview(part: Part, res: Resources): List<DocLine> {
    if (part.total == 0) return listOf(DocLine(res.getString(R.string.edition_empty)))
    return listOf(
        DocLine(res.getString(R.string.edition_total), part.total.toString()),
        DocLine(res.getString(R.string.edition_staff_reports), part.staffReports.toString()),
    )
}

private fun times(part: Part, res: Resources): List<DocLine> = listOf(
    DocLine(res.getString(R.string.edition_time_received), durations(part.received, res)),
    DocLine(res.getString(R.string.edition_time_acknowledged), durations(part.acknowledged, res)),
    DocLine(res.getString(R.string.edition_time_closed), durations(part.closed, res)),
)

private fun outcomes(part: Part, names: ReportNames): List<DocLine> = counts(part.byOutcome.mapKeys { names.outcome(it.key) })

private fun counts(map: Map<String, Int>): List<DocLine> = map.map { (k, v) -> DocLine(k, v.toString()) }

private fun durations(d: Durations, res: Resources): String {
    val median = d.medianS ?: return res.getString(R.string.edition_time_none)
    val cases = res.getQuantityString(R.plurals.edition_cases, d.count, d.count)
    return res.getString(R.string.edition_time_value, seconds(median, res), seconds(d.p90S ?: median, res), cases)
}

private fun seconds(s: Long, res: Resources): String =
    if (s < 60) {
        res.getString(R.string.edition_duration_s, s.toInt())
    } else {
        res.getString(R.string.edition_duration_min, (s / 60).toInt(), (s % 60).toInt())
    }

private fun minutes(ms: Long): Int = (ms / 60_000).toInt()

private fun limitText(limit: Limit, res: Resources): String {
    val v = limit.value.toInt()
    return when (limit.kind) {
        LimitKind.ONLY_APP -> res.getString(R.string.edition_limit_only_app)
        LimitKind.INSTALL_RATE -> res.getString(R.string.edition_limit_install_rate)
        LimitKind.RADIO -> res.getString(R.string.edition_limit_radio)
        LimitKind.CLOCKS -> res.getString(R.string.edition_limit_clocks, v)
        LimitKind.ANCHORS_FROM_MEMORY -> res.getString(R.string.edition_limit_anchors)
        LimitKind.NO_POSITION -> res.getString(R.string.edition_limit_no_position, v)
        LimitKind.METRICS_GAPS -> res.getString(R.string.edition_limit_metrics_gaps, v)
        LimitKind.NO_REPORTER_RECORDS -> res.getString(R.string.edition_limit_no_records)
        LimitKind.NO_MOBILE_LOG -> res.getString(R.string.edition_limit_no_mobile)
        LimitKind.EXERCISE_APART -> res.getString(R.string.edition_limit_exercise, v)
    }
}
