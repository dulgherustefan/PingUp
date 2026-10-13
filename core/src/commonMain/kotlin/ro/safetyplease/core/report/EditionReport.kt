package ro.safetyplease.core.report

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.ConsoleView
import ro.safetyplease.core.control.DensityReading
import ro.safetyplease.core.control.DensityStep
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.control.MetricsJoin
import ro.safetyplease.core.control.StaffDensity
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.metrics.MeshSnapshot
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.Probe
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.util.sortedByKey
import ro.safetyplease.core.venue.SiteGrid
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** How an incident ended, or where it stood when the report was made. */
enum class Outcome { OPEN, ASSIGNED, RESOLVED, UNFOUNDED, CANCELLED }

/**
 * One incident as the report shows it. Nothing names a person or a phone: no incidentId, team, description or
 * coordinates; the place is a square of the site grid. Seconds are from the reporter's clock to the signer's, as in
 * the log export.
 */
data class ReportRow(
    /** When the console opened the report, on its own clock. */
    val openedAt: Long,
    val grid: String,
    val category: Int,
    val severity: Int,
    val fromStaff: Boolean,
    val outcome: Outcome,
    val sToReceived: Long?,
    val sToAcknowledged: Long?,
    val sToClose: Long?,
    /** 0, or how far the alert widened while nobody had it: 1 to the incident's role, 2 to all staff. */
    val widened: Int,
    val reopened: Int,
    val hops: Int,
)

/** Seconds from the report to one step. Percentiles are nearest-rank, so each is a time that really happened. */
data class Durations(val count: Int, val medianS: Long?, val p90S: Long?) {
    companion object {
        fun of(seconds: List<Long>): Durations {
            val sorted = seconds.sorted()
            return Durations(sorted.size, rank(sorted, 50), rank(sorted, 90))
        }

        private fun rank(sorted: List<Long>, percent: Int): Long? =
            if (sorted.isEmpty()) null else sorted[((sorted.size * percent + 99) / 100 - 1).coerceAtLeast(0)]
    }
}

/** The incidents of one mode, real or exercise, opened inside the report's window. */
class Part(val rows: List<ReportRow>, zone: TimeZone) {
    val total: Int get() = rows.size
    val byCategory: Map<Int, Int> = rows.groupingBy { it.category }.eachCount().sortedByKey()
    val bySeverity: Map<Int, Int> = rows.groupingBy { it.severity }.eachCount().sortedByKey()

    /** Keyed by the start of the local hour, in ms. */
    val byHour: Map<Long, Int> = rows.groupingBy { hourOf(it.openedAt, zone) }.eachCount().sortedByKey()

    /** "" holds the reports without a position. */
    val byGrid: Map<String, Int> = rows.groupingBy { it.grid }.eachCount().sortedByKey()
    val byOutcome: Map<Outcome, Int> = rows.groupingBy { it.outcome }.eachCount().sortedByKey()
    val received = Durations.of(rows.mapNotNull { it.sToReceived?.takeIf { s -> s >= 0 } })
    val acknowledged = Durations.of(rows.mapNotNull { it.sToAcknowledged?.takeIf { s -> s >= 0 } })
    val closed = Durations.of(rows.mapNotNull { it.sToClose?.takeIf { s -> s >= 0 } })
    val widened: Int = rows.count { it.widened >= 1 }
    val widenedToAll: Int = rows.count { it.widened >= 2 }
    val reopened: Int = rows.count { it.reopened > 0 }
    val staffReports: Int = rows.count { it.fromStaff }

    /** Times that come out negative: the phones' clocks disagree, so they stay out of the medians. */
    val skewed: Int = rows.count { r -> listOfNotNull(r.sToReceived, r.sToAcknowledged, r.sToClose).any { it < 0 } }

    companion object {
        @OptIn(ExperimentalTime::class)
        fun hourOf(ms: Long, zone: TimeZone): Long {
            val local = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
            return LocalDateTime(local.date, LocalTime(local.hour, 0)).toInstant(zone).toEpochMilliseconds()
        }
    }
}

/** An anchor as the console last heard it, known only by the zone on its QR. */
data class AnchorSeen(val zone: String, val lastHeardMs: Long, val batteryPercent: Int?)

/** The reporters' records given to the console for the window, and how many of those reports reached it. */
data class Delivery(val records: Int, val reached: Int)

/**
 * How the network held over the window. [measuredMs] is the time the console's own mesh snapshots cover, [isolatedMs]
 * the part of it with no link; the mobile figures come from this phone's network log, if it was switched on.
 */
data class NetworkHealth(
    val windowMs: Long,
    val measuredMs: Long,
    val isolatedMs: Long,
    val anchors: List<AnchorSeen>,
    val silentAnchors: List<AnchorSeen>,
    val mobileSamples: Int,
    val mobileTested: Int,
    val mobileWorked: Int,
    /** Squares where mobile data was tried and never worked. */
    val noDataSquares: List<String>,
    val delivery: Delivery,
)

/** What the figures leave out. The report always carries these; the always-on ones can't be switched off. */
enum class LimitKind {
    /** Only reports made through the app are counted. */
    ONLY_APP,

    /** How many people installed the app is unknown, so counts are not rates. */
    INSTALL_RATE,

    /** Incidents handled only on the radio or in person are missing. */
    RADIO,

    /** Times subtract two phones' clocks; [Limit.value] negative ones were left out. */
    CLOCKS,

    /** The anchors' state is what this console heard since the app last started. */
    ANCHORS_FROM_MEMORY,

    /** [Limit.value] reports had no position and have no square. */
    NO_POSITION,

    /** The console measured the mesh for only [Limit.value] percent of the window. */
    METRICS_GAPS,

    /** No reporter gave the console their records: whether reports were lost is unknown. */
    NO_REPORTER_RECORDS,

    /** The mobile network log was off on this phone. */
    NO_MOBILE_LOG,

    /** [Limit.value] exercise incidents are counted apart and never in the real figures. */
    EXERCISE_APART,
}

data class Limit(val kind: LimitKind, val value: Long = 0)

/** One mode's console log, with the incidents its phone kept (those opened before it became the console). */
class ModeLog(val entries: List<LogEntry>, val incidents: List<StaffIncident> = emptyList())

/** The end-of-shift or end-of-edition report, over [from]..[until] on the console's clock. */
class EditionReport(
    val from: Long,
    val until: Long,
    val real: Part,
    val exercise: Part,
    val network: NetworkHealth,
    val limits: List<Limit>,
    /** Staff's crowd levels, zone by zone, as they changed over the window. */
    val density: List<DensityStep> = emptyList(),
)

object EditionReports {
    /** Silent for this long, an anchor counts as down; the console's network pane uses the same threshold. */
    const val SILENT_ANCHOR_MS = 10 * 60_000L

    /** Below this share of the window measured, the gaps are named. */
    private const val MEASURED_ENOUGH_PERCENT = 90

    /** An export younger than this closed the shift being reported, so the shift began at the export before it. */
    const val SHIFT_MIN_MS = 60 * 60_000L

    /**
     * Where the shift ending at [now] began: the last export of the log at least [SHIFT_MIN_MS] old, since a shift
     * ends with one, or the log's first entry.
     */
    fun shiftStart(entries: List<LogEntry>, now: Long): Long =
        entries.lastOrNull { it.event == LogEvent.EXPORT && it.at <= now - SHIFT_MIN_MS }?.at
            ?: entries.minOfOrNull { it.at } ?: now

    fun build(
        from: Long,
        until: Long,
        real: ModeLog,
        exercise: ModeLog,
        grid: SiteGrid,
        snapshots: List<MeshSnapshot>,
        samples: List<NetworkSample>,
        records: List<ReportTiming>,
        anchors: List<AnchorSeen>,
        zone: TimeZone,
        density: List<DensityReading> = emptyList(),
    ): EditionReport {
        val realPart = Part(rows(real, grid, from, until), zone)
        val exercisePart = Part(rows(exercise, grid, from, until), zone)
        val network = network(from, until, real, exercise, snapshots, samples, records, anchors)
        return EditionReport(
            from, until, realPart, exercisePart, network, limits(realPart, exercisePart, network, samples),
            StaffDensity.timeline(density, from, until),
        )
    }

    fun rows(log: ModeLog, grid: SiteGrid, from: Long, until: Long): List<ReportRow> {
        val byIncident = log.entries.filter { it.incidentId.isNotEmpty() }.groupBy { it.incidentId }
        return ConsoleView.cases(log.entries, log.incidents, grid).values
            .filter { it.report.at in from..until }
            .sortedBy { it.report.at }
            .map { row(it, byIncident[it.incidentId].orEmpty(), grid, until) }
    }

    private fun row(case: Case, entries: List<LogEntry>, grid: SiteGrid, until: Long): ReportRow {
        val r = case.report
        // a lost friend never widens, as in Routing
        val waitedMs = if (Priority.isInformational(r.category)) 0 else longestWait(case, entries, grid, until)
        return ReportRow(
            openedAt = r.at, grid = r.grid, category = r.category, severity = r.severity, fromStaff = r.fromStaff,
            outcome = outcome(case),
            sToReceived = case.secondsTo(case.receivedAt),
            sToAcknowledged = case.secondsTo(case.acknowledgedAt),
            sToClose = case.secondsTo(case.closedAt),
            widened = when {
                waitedMs >= 2 * Routing.WIDEN_AFTER_MS -> 2
                waitedMs >= Routing.WIDEN_AFTER_MS -> 1
                else -> 0
            },
            reopened = entries.count { it.event == LogEvent.REOPEN }, hops = r.hops,
        )
    }

    /**
     * The longest time nobody had it, as [Routing] counts it: from the report, or from the moment it opened again, to
     * the moment a team had it or it closed; to [until] if that never came.
     */
    private fun longestWait(case: Case, entries: List<LogEntry>, grid: SiteGrid, until: Long): Long {
        var since: Long? = case.report.reportedAt
        var longest = 0L
        for ((i, e) in entries.withIndex()) {
            val open = ConsoleView.case(listOf(case.report) + entries.subList(0, i + 1), null, grid)?.state == CaseState.OPEN
            val at = if (e.event == LogEvent.ACK && e.signedAt > 0) e.signedAt else e.at
            val start = since
            if (open && start == null) since = at
            if (!open && start != null) {
                longest = maxOf(longest, at - start)
                since = null
            }
        }
        val start = since
        return if (start == null) longest else maxOf(longest, until - start)
    }

    private fun outcome(case: Case): Outcome = when (case.state) {
        CaseState.OPEN -> Outcome.OPEN
        CaseState.ASSIGNED -> Outcome.ASSIGNED
        CaseState.CLOSED -> when {
            case.unfounded -> Outcome.UNFOUNDED
            case.network == AckStatus.CANCELLED -> Outcome.CANCELLED
            else -> Outcome.RESOLVED
        }
    }

    private fun network(
        from: Long,
        until: Long,
        real: ModeLog,
        exercise: ModeLog,
        snapshots: List<MeshSnapshot>,
        samples: List<NetworkSample>,
        records: List<ReportTiming>,
        anchors: List<AnchorSeen>,
    ): NetworkHealth {
        val windowMs = (until - from).coerceAtLeast(0)
        val inWindow = snapshots.filter { it.at in from..until }
        val measured = inWindow.sumOf { it.periodMs.coerceAtLeast(0) }.coerceAtMost(windowMs)
        val isolated = inWindow.filter { it.links == 0 }.sumOf { it.periodMs.coerceAtLeast(0) }.coerceAtMost(measured)
        val mobile = samples.filter { it.at in from..until }
        val tested = mobile.filter { it.probe != Probe.NOT_RUN }
        val noData = tested.filter { it.grid.isNotEmpty() }.groupBy { it.grid }
            .filterValues { list -> list.none { it.probe == Probe.OK } }.keys.sorted()
        val given = records.filter { it.createdAt in from..until }
        val joined = MetricsJoin.join(real.entries + exercise.entries, given).filter { it.inReporterLog }
        return NetworkHealth(
            windowMs, measured, isolated,
            anchors.sortedBy { it.zone },
            anchors.filter { until - it.lastHeardMs >= SILENT_ANCHOR_MS }.sortedBy { it.zone },
            mobile.size, tested.size, tested.count { it.probe == Probe.OK }, noData,
            Delivery(joined.size, joined.count { it.atConsole }),
        )
    }

    private fun limits(real: Part, exercise: Part, network: NetworkHealth, samples: List<NetworkSample>): List<Limit> = buildList {
        add(Limit(LimitKind.ONLY_APP))
        add(Limit(LimitKind.INSTALL_RATE))
        add(Limit(LimitKind.RADIO))
        add(Limit(LimitKind.CLOCKS, (real.skewed + exercise.skewed).toLong()))
        add(Limit(LimitKind.ANCHORS_FROM_MEMORY))
        val noPosition = real.byGrid[""] ?: 0
        if (noPosition > 0) add(Limit(LimitKind.NO_POSITION, noPosition.toLong()))
        val percent = if (network.windowMs == 0L) 100 else network.measuredMs * 100 / network.windowMs
        if (percent < MEASURED_ENOUGH_PERCENT) add(Limit(LimitKind.METRICS_GAPS, percent))
        if (network.delivery.records == 0) add(Limit(LimitKind.NO_REPORTER_RECORDS))
        if (samples.isEmpty()) add(Limit(LimitKind.NO_MOBILE_LOG))
        if (exercise.total > 0) add(Limit(LimitKind.EXERCISE_APART, exercise.total.toLong()))
    }
}
