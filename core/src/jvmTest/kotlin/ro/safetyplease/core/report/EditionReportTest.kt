package ro.safetyplease.core.report

import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.metrics.MeshSnapshot
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.Probe
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid

class EditionReportTest {
    private val zone = TimeZone.of("Europe/Bucharest")
    private val grid = SiteGrid.of(Bounds(43.948, 28.632, 43.9512, 28.6382))

    /** 2026-10-12 22:15:00 in Bucharest. */
    private val t0 = 1_791_832_500_000L
    private val until = t0 + 60 * MIN
    private fun id(c: Char) = c.toString().repeat(32)

    private fun report(
        c: Char, reportedAt: Long, category: Int, severity: Int, grid: String, delayMs: Long = 2_000, staff: Boolean = false,
    ) = LogEntry(
        reportedAt + delayMs, LogEvent.REPORT, id(c), category = category, severity = severity, grid = grid,
        reportedAt = reportedAt, hops = 2, description = "Ion, lângă bar", team = if (staff) "Medical 2" else "", fromStaff = staff,
    )

    private fun ack(c: Char, status: Int, signedAt: Long) =
        LogEntry(signedAt, LogEvent.ACK, id(c), status = status, team = "Medical 2", signedAt = signedAt, signature = "ab".repeat(64))

    private fun decision(c: Char, event: LogEvent, at: Long) = LogEntry(at, event, id(c), team = "Medical 2")

    private val real = listOf(
        // before the window
        report('0', t0 - 60 * MIN, IncidentCategory.MEDICAL, Severity.URGENT, "E2"),
        // A: resolved; received 21 s, acknowledged 60 s, closed 599 s
        report('a', t0, IncidentCategory.MEDICAL, Severity.URGENT, "E2", delayMs = 20_000),
        ack('a', AckStatus.RECEIVED, t0 + 21_000),
        decision('a', LogEvent.ASSIGN, t0 + 60_000),
        ack('a', AckStatus.ACKNOWLEDGED, t0 + 61_000),
        ack('a', AckStatus.RESOLVED, t0 + 599_000),
        // B: unfounded after 200 s, nobody took it: widened to all staff
        report('b', t0 + 10 * MIN, IncidentCategory.VIOLENCE, Severity.MEDIUM, "E2", delayMs = 5_000),
        ack('b', AckStatus.RECEIVED, t0 + 10 * MIN + 10_000),
        decision('b', LogEvent.UNFOUNDED, t0 + 10 * MIN + 200_000),
        // E: a staff report from a phone whose clock runs ahead; taken after 30 s
        report('e', t0 + 20 * MIN, IncidentCategory.MEDICAL, Severity.URGENT, "B3", staff = true),
        ack('e', AckStatus.RECEIVED, t0 + 20 * MIN - 5_000),
        decision('e', LogEvent.ASSIGN, t0 + 20 * MIN + 30_000),
        // F: closed, reopened, taken at 130 s, closed at 400 s
        report('f', t0 + 30 * MIN, IncidentCategory.MEDICAL, Severity.MEDIUM, "E2"),
        decision('f', LogEvent.RESOLVE, t0 + 30 * MIN + 61_000),
        decision('f', LogEvent.REOPEN, t0 + 30 * MIN + 120_000),
        decision('f', LogEvent.TAKE, t0 + 30 * MIN + 130_000),
        decision('f', LogEvent.RESOLVE, t0 + 30 * MIN + 400_000),
        // C: no position; acknowledged at 100 s, cancelled at 300 s
        report('c', t0 + 50 * MIN, IncidentCategory.MEDICAL, Severity.URGENT, ""),
        ack('c', AckStatus.RECEIVED, t0 + 50 * MIN + 4_000),
        ack('c', AckStatus.ACKNOWLEDGED, t0 + 50 * MIN + 100_000),
        ack('c', AckStatus.CANCELLED, t0 + 50 * MIN + 300_000),
        // D: still open at the end, five minutes on
        report('d', t0 + 55 * MIN, IncidentCategory.LOST_CHILD, Severity.LOW, "A1"),
        LogEntry(t0 + 58 * MIN, LogEvent.EXPORT),
    )

    private val exercise = listOf(report('x', t0 + 5 * MIN, IncidentCategory.FIRE, Severity.URGENT, "C1"))

    private val snapshots = (1..6).map { i ->
        MeshSnapshot(t0 + i * 5 * MIN, 5 * MIN, Role.CONTROL, links = if (i <= 2) 0 else 3, visiblePeers = 4, cachedReports = 1)
    }

    private val samples = listOf(
        NetworkSample(t0 - MIN, "E2", probe = Probe.FAILED),
        NetworkSample(t0 + MIN, "E2", probe = Probe.OK),
        NetworkSample(t0 + 2 * MIN, "A1", probe = Probe.FAILED),
        NetworkSample(t0 + 3 * MIN, "A1", probe = Probe.FAILED),
        NetworkSample(t0 + 4 * MIN, ""),
    )

    private val records = listOf(
        ReportTiming(id('a'), t0, IncidentCategory.MEDICAL, Severity.URGENT),
        ReportTiming(id('l'), t0 + 40 * MIN, IncidentCategory.MEDICAL, Severity.URGENT),
        ReportTiming(id('x'), t0 + 5 * MIN, IncidentCategory.FIRE, Severity.URGENT),
    )

    private val anchors = listOf(AnchorSeen("north", until - 2 * MIN, 80), AnchorSeen("beach", until - 15 * MIN, null))

    private val report = EditionReports.build(
        t0, until, ModeLog(real), ModeLog(exercise), grid, snapshots, samples, records, anchors, zone,
    )

    @Test
    fun countsTheRealIncidentsInTheWindow() {
        val p = report.real
        assertEquals(6, p.total)
        assertEquals(mapOf(IncidentCategory.MEDICAL to 4, IncidentCategory.VIOLENCE to 1, IncidentCategory.LOST_CHILD to 1), p.byCategory)
        assertEquals(mapOf(Severity.LOW to 1, Severity.MEDIUM to 2, Severity.URGENT to 3), p.bySeverity)
        assertEquals(mapOf(t0 - 15 * MIN to 4, t0 + 45 * MIN to 2), p.byHour)
        assertEquals(mapOf("" to 1, "A1" to 1, "B3" to 1, "E2" to 3), p.byGrid)
        assertEquals(
            mapOf(Outcome.OPEN to 1, Outcome.ASSIGNED to 1, Outcome.RESOLVED to 2, Outcome.UNFOUNDED to 1, Outcome.CANCELLED to 1),
            p.byOutcome,
        )
        assertEquals(1, p.staffReports)
        assertEquals(1, p.reopened)
        for (counts in listOf(p.byCategory, p.bySeverity, p.byHour, p.byGrid, p.byOutcome)) assertEquals(p.total, counts.values.sum())
    }

    @Test
    fun timesHaveMediansAndP90AndLeaveOutSkewedClocks() {
        val p = report.real
        assertEquals(Durations(3, 10, 21), p.received)
        assertEquals(Durations(4, 60, 130), p.acknowledged)
        assertEquals(Durations(4, 300, 599), p.closed)
        assertEquals(1, p.skewed)
        assertEquals(listOf(-5L, 30L, null), p.rows.single { it.fromStaff }.let { listOf(it.sToReceived, it.sToAcknowledged, it.sToClose) })
    }

    @Test
    fun escalationsFollowTheRoutingDelays() {
        assertEquals(3, report.real.widened)
        assertEquals(2, report.real.widenedToAll)
        // F waited 61 s before its first close and 10 s after the reopen, never the time it spent closed
        assertEquals(listOf(0, 2, 0, 0, 1, 2), report.real.rows.map { it.widened })
    }

    @Test
    fun aLostFriendNeverWidensAndAReopenedIncidentWaitsFromTheReopen() {
        val log = listOf(
            report('g', t0, IncidentCategory.LOST_FRIEND, Severity.LOW, "A1"),
            report('h', t0, IncidentCategory.MEDICAL, Severity.MEDIUM, "A1"),
            decision('h', LogEvent.RESOLVE, t0 + 30_000),
            decision('h', LogEvent.REOPEN, t0 + 20 * MIN),
            decision('h', LogEvent.TAKE, t0 + 20 * MIN + 100_000),
        )
        val rows = EditionReports.rows(ModeLog(log), grid, t0, until)
        assertEquals(listOf(0, 1), rows.map { it.widened })
        assertEquals(listOf(0, 1), rows.map { it.reopened })
    }

    @Test
    fun exerciseIsCountedApart() {
        assertEquals(1, report.exercise.total)
        assertEquals(mapOf(IncidentCategory.FIRE to 1), report.exercise.byCategory)
        assertFalse(IncidentCategory.FIRE in report.real.byCategory)
    }

    @Test
    fun networkHealth() {
        val n = report.network
        assertEquals(60 * MIN, n.windowMs)
        assertEquals(30 * MIN, n.measuredMs)
        assertEquals(10 * MIN, n.isolatedMs)
        assertEquals(listOf("beach", "north"), n.anchors.map { it.zone })
        assertEquals(listOf("beach"), n.silentAnchors.map { it.zone })
        assertEquals(listOf(4, 3, 1), listOf(n.mobileSamples, n.mobileTested, n.mobileWorked))
        assertEquals(listOf("A1"), n.noDataSquares)
        assertEquals(Delivery(records = 3, reached = 2), n.delivery)
    }

    @Test
    fun limitsAreAlwaysThereAndNameWhatIsMissing() {
        assertEquals(
            listOf(
                Limit(LimitKind.ONLY_APP), Limit(LimitKind.INSTALL_RATE), Limit(LimitKind.RADIO), Limit(LimitKind.CLOCKS, 1),
                Limit(LimitKind.ANCHORS_FROM_MEMORY), Limit(LimitKind.NO_POSITION, 1), Limit(LimitKind.METRICS_GAPS, 50),
                Limit(LimitKind.EXERCISE_APART, 1),
            ),
            report.limits,
        )
        val none = ModeLog(emptyList())
        val empty = EditionReports.build(t0, until, none, none, grid, emptyList(), emptyList(), emptyList(), emptyList(), zone)
        assertEquals(
            listOf(
                LimitKind.ONLY_APP, LimitKind.INSTALL_RATE, LimitKind.RADIO, LimitKind.CLOCKS, LimitKind.ANCHORS_FROM_MEMORY,
                LimitKind.METRICS_GAPS, LimitKind.NO_REPORTER_RECORDS, LimitKind.NO_MOBILE_LOG,
            ),
            empty.limits.map { it.kind },
        )
    }

    @Test
    fun shiftStartsAtTheExportThatClosedThePreviousShift() {
        val log = listOf(
            LogEntry(t0, LogEvent.REPORT, id('a')),
            LogEntry(t0 + 8 * 60 * MIN, LogEvent.EXPORT),
            LogEntry(t0 + 9 * 60 * MIN, LogEvent.REPORT, id('b')),
            LogEntry(t0 + 16 * 60 * MIN, LogEvent.EXPORT),
        )
        assertEquals(t0 + 8 * 60 * MIN, EditionReports.shiftStart(log, t0 + 16 * 60 * MIN + 5 * MIN))
        assertEquals(t0 + 16 * 60 * MIN, EditionReports.shiftStart(log, t0 + 18 * 60 * MIN))
        assertEquals(t0, EditionReports.shiftStart(log.take(1), t0 + MIN))
        assertEquals(t0, EditionReports.shiftStart(emptyList(), t0))
    }

    private val names = ReportNames(
        category = { if (it == IncidentCategory.MEDICAL) "Medical" else "Altceva" },
        severity = { if (it == Severity.URGENT) "Urgent" else "Nu e urgent" },
        outcome = { it.name.lowercase() },
        real = "real",
        exercise = "exercițiu",
    )

    private fun lines(csv: String): List<String> = csv.removePrefix("﻿").split("\r\n").dropLast(1)

    @Test
    fun incidentsCsvHasOneRowPerIncidentAndNoPersonalData() {
        val csv = ReportExport.incidentsCsv(report, names, zone)
        assertTrue(csv.startsWith("﻿"))
        val rows = lines(csv)
        assertEquals(ReportExport.INCIDENT_COLUMNS.joinToString(","), rows[0])
        assertEquals(1 + 6 + 1, rows.size)
        assertEquals("real,2026-10-12T22:15:20+03:00,E2,Medical,Urgent,0,resolved,21,60,599,0,0,2", rows[1])
        assertTrue(rows.last().startsWith("exercițiu,"))
        assertNoPersonalData(csv)
    }

    @Test
    fun summaryCsvCarriesTheFiguresAndTheLimits() {
        val csv = ReportExport.summaryCsv(report, names, zone)
        val rows = lines(csv)
        assertEquals(ReportExport.SUMMARY_COLUMNS.joinToString(","), rows[0])
        assertTrue("real,total,incidents,6" in rows)
        assertTrue("real,severity,Nu e urgent,3" in rows)
        assertTrue("real,severity,Urgent,3" in rows)
        assertTrue("real,category,Altceva,2" in rows)
        assertTrue("real,s_to_acknowledged,median,60" in rows)
        assertTrue("real,s_to_closed,p90,599" in rows)
        assertTrue("real,hour,2026-10-12T22:00:00+03:00,4" in rows)
        assertTrue("exercițiu,total,incidents,1" in rows)
        assertTrue(",network,anchors_silent,1" in rows)
        assertTrue(",delivery,reached_console,2" in rows)
        assertTrue(",limit,only_app,0" in rows)
        assertTrue(",limit,metrics_gaps,50" in rows)
        assertNoPersonalData(csv)
    }

    private fun assertNoPersonalData(csv: String) {
        for (c in "abcdefx0l") assertFalse(id(c) in csv)
        assertFalse("Medical 2" in csv)
        assertFalse("Ion" in csv)
        assertFalse("ab".repeat(64) in csv)
    }

    private companion object {
        const val MIN = 60_000L
    }
}
