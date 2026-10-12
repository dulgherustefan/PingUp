package ro.safetyplease.app.ui.control

import android.content.res.Resources
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.app.text.quantityText
import ro.safetyplease.app.text.text
import ro.safetyplease.app.text.textArray
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
import ro.safetyplease.ui.control.reopened
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.date_pattern_full
import ro.safetyplease.ui.resources.date_pattern_recent
import ro.safetyplease.ui.resources.edition_anchor_no_zone
import ro.safetyplease.ui.resources.edition_anchor_row
import ro.safetyplease.ui.resources.edition_anchors
import ro.safetyplease.ui.resources.edition_anchors_silent
import ro.safetyplease.ui.resources.edition_cases
import ro.safetyplease.ui.resources.edition_count_of
import ro.safetyplease.ui.resources.edition_delivery
import ro.safetyplease.ui.resources.edition_duration_min
import ro.safetyplease.ui.resources.edition_duration_s
import ro.safetyplease.ui.resources.edition_empty
import ro.safetyplease.ui.resources.edition_exercise_none
import ro.safetyplease.ui.resources.edition_generated
import ro.safetyplease.ui.resources.edition_isolated
import ro.safetyplease.ui.resources.edition_limit_anchors
import ro.safetyplease.ui.resources.edition_limit_clocks
import ro.safetyplease.ui.resources.edition_limit_exercise
import ro.safetyplease.ui.resources.edition_limit_install_rate
import ro.safetyplease.ui.resources.edition_limit_metrics_gaps
import ro.safetyplease.ui.resources.edition_limit_no_mobile
import ro.safetyplease.ui.resources.edition_limit_no_position
import ro.safetyplease.ui.resources.edition_limit_no_records
import ro.safetyplease.ui.resources.edition_limit_only_app
import ro.safetyplease.ui.resources.edition_limit_radio
import ro.safetyplease.ui.resources.edition_measured
import ro.safetyplease.ui.resources.edition_minutes
import ro.safetyplease.ui.resources.edition_mobile_no_data
import ro.safetyplease.ui.resources.edition_mobile_worked
import ro.safetyplease.ui.resources.edition_mode_exercise
import ro.safetyplease.ui.resources.edition_mode_real
import ro.safetyplease.ui.resources.edition_no_square
import ro.safetyplease.ui.resources.edition_outcomes
import ro.safetyplease.ui.resources.edition_reopened
import ro.safetyplease.ui.resources.edition_section_category
import ro.safetyplease.ui.resources.edition_section_escalation
import ro.safetyplease.ui.resources.edition_section_exercise
import ro.safetyplease.ui.resources.edition_section_grid
import ro.safetyplease.ui.resources.edition_section_hour
import ro.safetyplease.ui.resources.edition_section_incidents
import ro.safetyplease.ui.resources.edition_section_limits
import ro.safetyplease.ui.resources.edition_section_network
import ro.safetyplease.ui.resources.edition_section_outcome
import ro.safetyplease.ui.resources.edition_section_severity
import ro.safetyplease.ui.resources.edition_section_times
import ro.safetyplease.ui.resources.edition_staff_reports
import ro.safetyplease.ui.resources.edition_time_acknowledged
import ro.safetyplease.ui.resources.edition_time_closed
import ro.safetyplease.ui.resources.edition_time_none
import ro.safetyplease.ui.resources.edition_time_received
import ro.safetyplease.ui.resources.edition_time_value
import ro.safetyplease.ui.resources.edition_title
import ro.safetyplease.ui.resources.edition_total
import ro.safetyplease.ui.resources.edition_widened_all
import ro.safetyplease.ui.resources.edition_widened_role
import ro.safetyplease.ui.resources.edition_window
import ro.safetyplease.ui.resources.edition_window_value
import ro.safetyplease.ui.text.Labels
import java.text.SimpleDateFormat
import java.util.Date

/** A line of the report: a figure with its [value], or a sentence alone when [value] is empty. */
internal class DocLine(val label: String, val value: String = "")

internal class DocSection(val title: String, val lines: List<DocLine>)

/** The report in the app's language, as both the preview and the PDF show it. */
internal class ReportDoc(val title: String, val subtitle: String, val sections: List<DocSection>)

internal fun reportNames(res: Resources): ReportNames {
    val outcomes = res.textArray(Res.array.edition_outcomes)
    return ReportNames(
        category = { res.text(Labels.category(it)) },
        severity = { res.text(Labels.urgency(it)) },
        outcome = { outcomes[it.ordinal] },
        real = res.text(Res.string.edition_mode_real),
        exercise = res.text(Res.string.edition_mode_exercise),
    )
}

/** "What it covers and what it doesn't" comes first and always: the reader meets the limits before the figures. */
internal fun reportDoc(report: EditionReport, kind: String, res: Resources, venue: Venue, generatedAt: Long): ReportDoc {
    fun s(id: StringResource, vararg args: Any): String = res.text(id, *args)
    val full = SimpleDateFormat(s(Res.string.date_pattern_full), res.configuration.locales[0])
    val recent = SimpleDateFormat(s(Res.string.date_pattern_recent), res.configuration.locales[0])
    fun at(ms: Long) = full.format(Date(ms))
    val names = reportNames(res)
    val n = report.network
    val limits = DocSection(
        s(Res.string.edition_section_limits),
        listOf(DocLine(s(Res.string.edition_window), s(Res.string.edition_window_value, at(report.from), at(report.until)))) +
            report.limits.map { DocLine(limitText(it, res)) },
    )
    val network = DocSection(
        s(Res.string.edition_section_network),
        buildList {
            add(DocLine(s(Res.string.edition_measured), s(Res.string.edition_count_of, minutes(n.measuredMs), minutes(n.windowMs))))
            add(DocLine(s(Res.string.edition_isolated), s(Res.string.edition_minutes, minutes(n.isolatedMs))))
            add(DocLine(s(Res.string.edition_anchors), n.anchors.size.toString()))
            add(DocLine(s(Res.string.edition_anchors_silent, minutes(EditionReports.SILENT_ANCHOR_MS)), n.silentAnchors.size.toString()))
            for (anchor in n.silentAnchors) {
                add(DocLine(s(Res.string.edition_anchor_row, venue.zoneName(anchor.zone).ifEmpty { s(Res.string.edition_anchor_no_zone) }), recent.format(Date(anchor.lastHeardMs))))
            }
            if (n.mobileTested > 0) {
                add(DocLine(s(Res.string.edition_mobile_worked), s(Res.string.edition_count_of, n.mobileWorked, n.mobileTested)))
            }
            if (n.noDataSquares.isNotEmpty()) add(DocLine(s(Res.string.edition_mobile_no_data), n.noDataSquares.joinToString(", ")))
            if (n.delivery.records > 0) {
                add(DocLine(s(Res.string.edition_delivery), s(Res.string.edition_count_of, n.delivery.reached, n.delivery.records)))
            }
        },
    )
    val exercise = if (report.exercise.total == 0) {
        DocSection(s(Res.string.edition_section_exercise), listOf(DocLine(s(Res.string.edition_exercise_none))))
    } else {
        val part = report.exercise
        DocSection(s(Res.string.edition_section_exercise), overview(part, res) + outcomes(part, names) + times(part, res))
    }
    val real = report.real
    val sections = buildList {
        add(limits)
        add(DocSection(s(Res.string.edition_section_incidents), overview(real, res)))
        if (real.total > 0) {
            add(DocSection(s(Res.string.edition_section_category), counts(ReportExport.byWord(real.byCategory, names.category))))
            add(DocSection(s(Res.string.edition_section_severity), counts(ReportExport.byWord(real.bySeverity, names.severity))))
            add(DocSection(s(Res.string.edition_section_outcome), outcomes(real, names)))
            add(DocSection(s(Res.string.edition_section_hour), counts(real.byHour.mapKeys { recent.format(Date(it.key)) })))
            add(
                DocSection(
                    s(Res.string.edition_section_grid),
                    counts(real.byGrid.mapKeys { it.key.ifEmpty { s(Res.string.edition_no_square) } }),
                ),
            )
            add(DocSection(s(Res.string.edition_section_times), times(real, res)))
            add(
                DocSection(
                    s(Res.string.edition_section_escalation),
                    listOf(
                        DocLine(s(Res.string.edition_widened_role, Routing.WIDEN_AFTER_MS / 1000), real.widened.toString()),
                        DocLine(s(Res.string.edition_widened_all, 2 * Routing.WIDEN_AFTER_MS / 1000), real.widenedToAll.toString()),
                        DocLine(s(Res.string.edition_reopened), real.reopened.toString()),
                    ),
                ),
            )
        }
        add(network)
        add(exercise)
    }
    return ReportDoc(
        "${s(Res.string.edition_title)} · $kind",
        listOf(venue.name, s(Res.string.edition_generated, at(generatedAt))).filter { it.isNotEmpty() }.joinToString(" · "),
        sections,
    )
}

private fun overview(part: Part, res: Resources): List<DocLine> {
    if (part.total == 0) return listOf(DocLine(res.text(Res.string.edition_empty)))
    return listOf(
        DocLine(res.text(Res.string.edition_total), part.total.toString()),
        DocLine(res.text(Res.string.edition_staff_reports), part.staffReports.toString()),
    )
}

private fun times(part: Part, res: Resources): List<DocLine> = listOf(
    DocLine(res.text(Res.string.edition_time_received), durations(part.received, res)),
    DocLine(res.text(Res.string.edition_time_acknowledged), durations(part.acknowledged, res)),
    DocLine(res.text(Res.string.edition_time_closed), durations(part.closed, res)),
)

private fun outcomes(part: Part, names: ReportNames): List<DocLine> = counts(part.byOutcome.mapKeys { names.outcome(it.key) })

private fun counts(map: Map<String, Int>): List<DocLine> = map.map { (k, v) -> DocLine(k, v.toString()) }

private fun durations(d: Durations, res: Resources): String {
    val median = d.medianS ?: return res.text(Res.string.edition_time_none)
    val cases = res.quantityText(Res.plurals.edition_cases, d.count, d.count)
    return res.text(Res.string.edition_time_value, seconds(median, res), seconds(d.p90S ?: median, res), cases)
}

private fun seconds(s: Long, res: Resources): String =
    if (s < 60) {
        res.text(Res.string.edition_duration_s, s.toInt())
    } else {
        res.text(Res.string.edition_duration_min, (s / 60).toInt(), (s % 60).toInt())
    }

private fun minutes(ms: Long): Int = (ms / 60_000).toInt()

private fun limitText(limit: Limit, res: Resources): String {
    val v = limit.value.toInt()
    return when (limit.kind) {
        LimitKind.ONLY_APP -> res.text(Res.string.edition_limit_only_app)
        LimitKind.INSTALL_RATE -> res.text(Res.string.edition_limit_install_rate)
        LimitKind.RADIO -> res.text(Res.string.edition_limit_radio)
        LimitKind.CLOCKS -> res.text(Res.string.edition_limit_clocks, v)
        LimitKind.ANCHORS_FROM_MEMORY -> res.text(Res.string.edition_limit_anchors)
        LimitKind.NO_POSITION -> res.text(Res.string.edition_limit_no_position, v)
        LimitKind.METRICS_GAPS -> res.text(Res.string.edition_limit_metrics_gaps, "$v%")
        LimitKind.NO_REPORTER_RECORDS -> res.text(Res.string.edition_limit_no_records)
        LimitKind.NO_MOBILE_LOG -> res.text(Res.string.edition_limit_no_mobile)
        LimitKind.EXERCISE_APART -> res.text(Res.string.edition_limit_exercise, v)
    }
}
