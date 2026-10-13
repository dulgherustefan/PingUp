package ro.safetyplease.core.control

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogExportTest {
    private val zone = TimeZone.of("Europe/Bucharest")
    private val grid = SiteGrid.of(Bounds(43.948, 28.632, 43.9512, 28.6382))

    /** 2026-10-12 22:15:00 in Bucharest (UTC+3). */
    private val t0 = 1_791_832_500_000L
    private val id = "0a".repeat(16)

    private val names = ExportNames(
        event = { it.name.lowercase() },
        category = { if (it == IncidentCategory.MEDICAL) "Medical" else "Altceva" },
        severity = { if (it == Severity.URGENT) "Urgent" else "Nu e urgent" },
        status = { listOf("", "primit", "preluat", "rezolvat", "anulat")[it] },
        state = { it.name.lowercase() },
        zone = { if (it == "main-stage") "Scena principală" else it },
        answer = { listOf("", "da", "nu", "nu știu")[it] },
    )

    private val report = LogEntry(
        t0 + 20_000, LogEvent.REPORT, id, category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "main-stage",
        grid = "E2", lat = 43.9505, lon = 28.635, reportedAt = t0, hops = 3, description = "A leșinat, lângă bar \"Nord\"",
    )
    private val entries = listOf(
        report,
        LogEntry(t0 + 21_000, LogEvent.ACK, id, status = AckStatus.RECEIVED, team = "Control", signedAt = t0 + 21_000, signature = "ab".repeat(64), own = true),
        LogEntry(t0 + 60_000, LogEvent.ASSIGN, id, team = "Medical 2", reason = "cea mai apropiată"),
        LogEntry(t0 + 61_000, LogEvent.ACK, id, status = AckStatus.ACKNOWLEDGED, team = "Medical 2", signedAt = t0 + 61_000, signature = "cd".repeat(64), own = true),
        LogEntry(t0 + 600_000, LogEvent.ACK, id, status = AckStatus.RESOLVED, team = "Medical 2", signedAt = t0 + 599_000, signature = "ef".repeat(64), hops = 2),
        LogEntry(t0 + 700_000, LogEvent.EXPORT),
    )

    private fun lines(csv: String): List<String> = csv.removePrefix("﻿").split("\r\n").dropLast(1)

    @Test
    fun eventsCsvHasOneRowPerEntryWithOnlyTheFieldsThatApply() {
        val csv = LogExport.eventsCsv(entries, names, zone)
        assertTrue(csv.startsWith("﻿"), "spreadsheets need the mark to read diacritics")
        val rows = lines(csv)
        assertEquals(LogExport.EVENT_COLUMNS.joinToString(","), rows[0])
        assertEquals(entries.size + 1, rows.size)
        assertEquals(
            "1,2026-10-12T22:15:20+03:00,report,$id,E2,Scena principală,Medical,Urgent,,,,,,3,2026-10-12T22:15:00+03:00," +
                "43.950500,28.635000,\"A leșinat, lângă bar \"\"Nord\"\"\",,,,,0,,",
            rows[1],
        )
        assertEquals("2,2026-10-12T22:15:21+03:00,ack,$id,,,,,primit,Control,2026-10-12T22:15:21+03:00,${"ab".repeat(64)},1,0,,,,,,0,,,,,", rows[2])
        assertEquals("3,2026-10-12T22:16:00+03:00,assign,$id,,,,,,Medical 2,,,,,,,,,cea mai apropiată,,,,,,", rows[3])
        assertEquals("5,2026-10-12T22:25:00+03:00,ack,$id,,,,,rezolvat,Medical 2,2026-10-12T22:24:59+03:00,${"ef".repeat(64)},0,2,,,,,,0,,,,,", rows[5])
        assertEquals("6,2026-10-12T22:26:40+03:00,export" + ",".repeat(22), rows[6])
        rows.drop(1).forEach { assertEquals(LogExport.EVENT_COLUMNS.size, cells(it).size, it) }
    }

    @Test
    fun aNoticeRowHasItsZoneTextAndSigningTime() {
        val notice = LogEntry(
            t0, LogEvent.NOTICE, zone = "main-stage", description = "Pauză: revenim la 15:30", team = "Control",
            signedAt = t0 - 1_000, signature = "aa".repeat(64), own = true, topic = 1, level = 2, template = 3, seq = 4, validUntil = t0 + 3_600_000,
        )
        assertEquals(
            "1,2026-10-12T22:15:00+03:00,notice,,,Scena principală,,,,Control,2026-10-12T22:14:59+03:00,${"aa".repeat(64)},1,,,,," +
                "Pauză: revenim la 15:30,,,,,,,",
            lines(LogExport.eventsCsv(listOf(notice), names, zone))[1],
        )
    }

    @Test
    fun incidentsCsvTimesEachStepFromTheReport() {
        val cases = ConsoleView.cases(entries, emptyList(), grid).values
        val rows = lines(LogExport.incidentsCsv(cases, names, zone))
        assertEquals(LogExport.INCIDENT_COLUMNS.joinToString(","), rows[0])
        assertEquals(
            "$id,2026-10-12T22:15:00+03:00,E2,Scena principală,Medical,Urgent,closed,Medical 2,0,0,3,21,60,599," +
                "\"A leșinat, lângă bar \"\"Nord\"\"\"",
            rows[1],
        )
        assertEquals(LogExport.INCIDENT_COLUMNS.size, cells(rows[1]).size)
    }

    @Test
    fun textThatASpreadsheetWouldRunAsAFormulaIsDefused() {
        assertEquals("'=HYPERLINK(\"x\")", LogExport.text("=HYPERLINK(\"x\")"))
        assertEquals("'+40 700", LogExport.text("+40 700"))
        assertEquals("'-1", LogExport.text("-1"))
        assertEquals("'@SUM(A1)", LogExport.text("@SUM(A1)"))
        assertEquals("tricou roșu", LogExport.text("tricou roșu"))
        val hostile = listOf(report.copy(description = "=1+1", reason = "", team = ""))
        assertEquals("'=1+1", cells(lines(LogExport.eventsCsv(hostile, names, zone))[1])[17])
    }

    @Test
    fun jsonKeepsEveryEntryWithItsCodesAndASummaryPerIncident() {
        val cases = ConsoleView.cases(entries, emptyList(), grid).values
        val text = LogExport.json(entries, cases, "Control", "Festival", grid, t0 + 700_000, zone)
        val doc = Json.parseToJsonElement(text).jsonObject
        assertEquals(LogExport.FORMAT, doc.getValue("format").jsonPrimitive.content)
        assertEquals(LogExport.VERSION, doc.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("2026-10-12T22:26:40+03:00", doc.getValue("exportedAt").jsonPrimitive.content)
        assertEquals(10, doc.getValue("grid").jsonObject.getValue("columns").jsonPrimitive.int)
        val logged = doc.getValue("entries").jsonArray
        assertEquals(entries.size, logged.size)
        assertEquals("ACK", logged[1].jsonObject.getValue("event").jsonPrimitive.content)
        assertEquals("ab".repeat(64), logged[1].jsonObject.getValue("signature").jsonPrimitive.content)
        val summary = doc.getValue("incidents").jsonArray.single().jsonObject
        assertEquals("CLOSED", summary.getValue("state").jsonPrimitive.content)
        assertEquals(599L, summary.getValue("secondsToClose").jsonPrimitive.long)
        assertEquals(entries, Json.decodeFromString(ExportDocument.serializer(), text).entries)
    }

    @Test
    fun unknownTimesAreEmptyCells() {
        assertEquals("", LogExport.time(0, zone))
        val open = ConsoleView.cases(listOf(report), emptyList(), grid).values
        val row = cells(lines(LogExport.incidentsCsv(open, names, zone))[1])
        assertEquals(listOf("", "", ""), row.subList(11, 14))
        assertEquals("open", row[6])
    }

    /** Splits one CSV row, honoring quotes. */
    @Test
    fun triageStaffFieldsAndEpochsHaveTheirOwnColumns() {
        val staffReport = report.copy(
            responds = Answer.NO, breathes = Answer.UNKNOWN, fromStaff = true, team = "Medical 1", people = 2, dangerContinues = true,
        )
        val request = LogEntry(t0 + 30_000, LogEvent.CANCEL_REQUEST, id, epoch = 1)
        val rows = lines(LogExport.eventsCsv(listOf(staffReport, request, report), names, zone)).drop(1).map(::cells)
        fun cell(row: List<String>, column: String) = row[LogExport.EVENT_COLUMNS.indexOf(column)]
        val columns = listOf("team", "responds", "breathes", "staff_report", "people", "danger_continues")
        assertEquals(listOf("Medical 1", "nu", "nu știu", "1", "2", "1"), columns.map { cell(rows[0], it) })
        assertEquals("1", cell(rows[1], "epoch"))
        assertEquals(listOf("", "", "", "0", "", ""), columns.map { cell(rows[2], it) }, "a participant's report: nothing asked, not staff")
    }

    private fun cells(row: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < row.length) {
            val ch = row[i]
            when {
                quoted && ch == '"' && row.getOrNull(i + 1) == '"' -> cell.append('"').also { i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> out += cell.toString().also { cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString()
        return out
    }
}
