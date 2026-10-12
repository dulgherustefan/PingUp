package ro.safetyplease.core.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.metrics.MeshSnapshot
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.Probe
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.metrics.Transport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import java.time.ZoneId

class MetricsExportTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val grid = SiteGrid.of(Bounds(43.948, 28.632, 43.9512, 28.6382))

    /** 2026-10-12 22:15:00 in Bucharest (UTC+3). */
    private val t0 = 1_791_832_500_000L
    private val delivered = "0a".repeat(16)
    private val lost = "0b".repeat(16)
    private val consoleOnly = "0c".repeat(16)

    private val reports = listOf(
        ReportTiming(
            lost, t0 + 60_000, IncidentCategory.CROWD, Severity.MEDIUM, "B3", sent = true, republished = 2,
        ),
        ReportTiming(
            delivered, t0, IncidentCategory.MEDICAL, Severity.URGENT, "E2", AckStatus.ACKNOWLEDGED, sent = true,
            msToReceived = 4_200, msToAcknowledged = 95_000, firstAckHops = 3,
        ),
    )

    private val entries = listOf(
        LogEntry(t0 + 9_000, LogEvent.REPORT, delivered, IncidentCategory.MEDICAL, Severity.URGENT, grid = "E2", hops = 3, reportedAt = t0),
        LogEntry(t0 + 10_000, LogEvent.ACK, delivered, status = AckStatus.RECEIVED, own = true),
        LogEntry(t0 + 120_000, LogEvent.REPORT, consoleOnly, IncidentCategory.FIRE, Severity.URGENT, grid = "A1", hops = 1),
        LogEntry(t0 + 130_000, LogEvent.REPORT, delivered, IncidentCategory.MEDICAL, Severity.URGENT, hops = 5),
    )

    private val snapshot = MeshSnapshot(
        t0, 300_000, Role.ANCHOR, links = 3, visiblePeers = 9, cachedReports = 2, sent = 40, received = 60, relayed = 25,
        dropped = 7, linksOpened = 6, linksClosed = 4,
        sentByType = mapOf(PacketType.INCIDENT_REPORT to 3L, PacketType.PRIVATE to 30L, 0x42 to 2L, 0x43 to 1L),
        receivedByType = mapOf(PacketType.HELLO to 6L),
        drops = mapOf(DropReason.DUPLICATE to 5L, DropReason.REPORT_LIMIT to 2L),
        reportHops = mapOf(1 to 1L, 3 to 2L), ackHops = mapOf(7 to 1L),
    )

    private val samples = listOf(
        NetworkSample(t0, "E2", 3, -97, "lte", Transport.CELLULAR, validated = true, probe = Probe.OK, probeMs = 380),
        NetworkSample(t0 + 120_000, "", null, null, "", Transport.CELLULAR, probe = Probe.FAILED),
        NetworkSample(t0 + 240_000, "F2", 1, -118, "gsm", Transport.WIFI),
    )

    private fun lines(csv: String): List<String> = csv.removePrefix("﻿").split("\r\n").dropLast(1)

    private fun table(csv: String, columns: List<String>): List<Map<String, String>> {
        val rows = lines(csv)
        assertEquals(columns.joinToString(","), rows[0])
        return rows.drop(1).map { row ->
            val cells = row.split(',')
            assertEquals("every row fills every column", columns.size, cells.size)
            columns.zip(cells).toMap()
        }
    }

    // --- the join ---

    @Test
    fun joinPutsEachReportsTwoSidesOnOneRow() {
        val rows = MetricsJoin.join(entries, reports)
        assertEquals(listOf(delivered, lost, consoleOnly), rows.map { it.incidentId })
        val (ok, missing, unknown) = rows
        assertTrue(ok.inReporterLog && ok.atConsole)
        assertEquals("the console's first opening", t0 + 9_000, ok.consoleAt)
        assertEquals(3, ok.consoleHops)
        assertEquals(4_200L, ok.msToReceived)

        assertTrue(missing.inReporterLog)
        assertFalse("never reached the console", missing.atConsole)
        assertNull(missing.consoleAt)
        assertEquals(2, missing.republished)

        assertFalse("nobody gave the console this reporter's record", unknown.inReporterLog)
        assertEquals(IncidentCategory.FIRE, unknown.category)
        assertNull(unknown.createdAt)
    }

    @Test
    fun deliveryCsvHasTheJoinWithoutComparingClocks() {
        val rows = table(MetricsExport.deliveryCsv(MetricsJoin.join(entries, reports), zone), MetricsExport.DELIVERY_COLUMNS)
        assertEquals(3, rows.size)
        assertEquals("1", rows[0]["reporter_record"])
        assertEquals("1", rows[0]["at_console"])
        assertEquals("2026-10-12T22:15:00+03:00", rows[0]["created_at"])
        assertEquals("2026-10-12T22:15:09+03:00", rows[0]["console_at"])
        assertEquals("4200", rows[0]["ms_to_received"])
        assertEquals("0", rows[1]["at_console"])
        assertEquals("", rows[1]["console_at"])
        assertEquals("", rows[2]["created_at"])
        assertTrue(MetricsExport.DELIVERY_COLUMNS.none { "latency" in it || "delay" in it })
    }

    @Test
    fun reporterRecordsMergeByIncidentAndStayBounded() {
        val updated = reports[1].copy(status = AckStatus.RESOLVED)
        val merged = ReporterRecords(reports).merged(listOf(updated))
        assertEquals(listOf(lost, delivered), merged.reports.map { it.incidentId })
        assertEquals(AckStatus.RESOLVED, merged.reports.last().status)

        val many = (0 until ReporterRecords.MAX_REPORTS + 10).map { reports[0].copy(incidentId = "%032x".format(it)) }
        val bounded = ReporterRecords().merged(many)
        assertEquals(ReporterRecords.MAX_REPORTS, bounded.reports.size)
        assertEquals("%032x".format(10), bounded.reports.first().incidentId)
    }

    // --- CSV ---

    @Test
    fun reportsCsvIsInCreationOrderWithTheReportersRoundTrip() {
        val rows = table(MetricsExport.reportsCsv(reports, zone), MetricsExport.REPORT_COLUMNS)
        assertEquals(listOf(delivered, lost), rows.map { it["incident"] })
        assertEquals("4200", rows[0]["ms_to_received"])
        assertEquals("95000", rows[0]["ms_to_acknowledged"])
        assertEquals("3", rows[0]["first_ack_hops"])
        assertEquals("", rows[1]["ms_to_received"])
        assertEquals("2", rows[1]["republished"])
        assertEquals(IncidentCategory.MEDICAL.toString(), rows[0]["category"])
    }

    @Test
    fun meshCsvSpreadsHistogramsAndTypesOverFixedColumns() {
        val row = table(MetricsExport.meshCsv(listOf(snapshot), zone), MetricsExport.MESH_COLUMNS).single()
        assertEquals("300", row["period_s"])
        assertEquals("anchor", row["role"])
        assertEquals("72.0", row["links_opened_per_hour"])
        assertEquals("48.0", row["links_closed_per_hour"])
        assertEquals("5", row["drop_duplicate"])
        assertEquals("2", row["drop_report_limit"])
        assertEquals("0", row["drop_malformed"])
        assertEquals("1", row["report_hops_1"])
        assertEquals("2", row["report_hops_3"])
        assertEquals("1", row["ack_hops_7"])
        assertEquals("3", row["sent_report"])
        assertEquals("30", row["sent_private"])
        assertEquals("types this version doesn't name", "3", row["sent_other"])
        assertEquals("6", row["received_hello"])
    }

    @Test
    fun networkCsvHasOneRowPerSample() {
        val rows = table(MetricsExport.networkCsv(samples, zone), MetricsExport.NETWORK_COLUMNS)
        assertEquals(listOf("ok", "failed", "not_run"), rows.map { it["probe"] })
        assertEquals("380", rows[0]["probe_ms"])
        assertEquals("cellular", rows[0]["transport"])
        assertEquals("1", rows[0]["validated"])
        assertEquals("", rows[1]["level"])
        assertEquals("", rows[1]["grid"])
        assertEquals("wifi", rows[2]["transport"])
    }

    // --- JSON ---

    private fun json(delivery: List<DeliveryRow> = emptyList()) =
        MetricsExport.json(Role.CONTROL, "Beach", grid, reports, listOf(snapshot), samples, delivery, t0, zone)

    @Test
    fun jsonCarriesEverythingAndNamesNoOne() {
        val text = json(MetricsJoin.join(entries, reports))
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(MetricsExport.FORMAT, root.getValue("format").jsonPrimitive.content)
        assertEquals(MetricsExport.VERSION, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("CONTROL", root.getValue("role").jsonPrimitive.content)
        assertEquals(2, root.getValue("reports").jsonArray.size)
        assertEquals(3, root.getValue("delivery").jsonArray.size)
        assertEquals("5", root.getValue("snapshots").jsonArray[0].jsonObject.getValue("drops").jsonObject.getValue("DUPLICATE").jsonPrimitive.content)
        for (word in listOf("nodeId", "nickname", "description", "\"lat\"", "\"lon\"", "teamName", "signature")) {
            assertFalse("$word must not leave the phone", word in text)
        }
    }

    @Test
    fun consoleReadsTheReportersRecordsBack() {
        assertEquals(reports.sortedBy { it.createdAt }, MetricsExport.reportsIn(json()))
    }

    @Test
    fun anythingElseIsNotAMetricsExport() {
        assertNull(MetricsExport.reportsIn("not json"))
        assertNull(MetricsExport.reportsIn("{\"format\":\"pingup-console-log\",\"schemaVersion\":2}"))
        assertNull(MetricsExport.reportsIn(json().replace(MetricsExport.FORMAT, "pingup-console-log")))
        assertNull(MetricsExport.reportsIn(" ".repeat(MetricsExport.MAX_IMPORT_BYTES + 1)))
    }
}
