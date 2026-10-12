package ro.safetyplease.app.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.control.Coverage
import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.PointLevel
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class DemoScenarioTest {
    private val venue = Venue.parse(File("src/main/assets/venue.json").readText())
    private val grid = SiteGrid.of(venue.bounds)
    private val now = 1_791_800_000_000L
    private val incidents = DemoScenario.incidents(venue, now)

    @Test
    fun everyIncidentReadsAsTestData() {
        assertEquals(incidents.size, incidents.map { it.incidentId }.toSet().size)
        for (incident in incidents) {
            assertTrue(DemoScenario.isDemo(incident.incidentId))
            assertEquals(32, incident.incidentId.length)
            assertTrue(incident.incidentId.all { it in "0123456789abcdef" })
            assertTrue(incident.description, Recognition.split(incident.description).second.startsWith(DemoScenario.MARK))
        }
    }

    @Test
    fun theShiftCoversCategoriesSeveritiesAndStates() {
        assertEquals(IncidentCategory.all.toSet(), incidents.map { it.category }.toSet())
        assertEquals(setOf(Severity.LOW, Severity.MEDIUM, Severity.URGENT), incidents.map { it.severity }.toSet())
        val statuses = incidents.map { it.status }.toSet()
        for (status in listOf(AckStatus.RECEIVED, AckStatus.ON_SCENE, AckStatus.HANDED_OVER, AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED)) {
            assertTrue("status $status", status in statuses)
        }
        assertTrue(incidents.any { it.assigned && it.status == AckStatus.ACKNOWLEDGED })
        assertEquals(1, incidents.count { it.cancelRequested })
        assertTrue(incidents.any { it.fromStaff && it.people > 0 })
        assertTrue(incidents.any { it.responds == Answer.NO })
        assertTrue(incidents.count { Recognition.split(it.description).first.isNotEmpty() } >= 5)
        assertEquals(incidents.sortedBy { it.receivedAt }, incidents)
        assertTrue(incidents.all { it.reportedAt < it.receivedAt && it.receivedAt <= it.statusAt && it.statusAt <= now })
    }

    @Test
    fun incidentsAreSpreadOverTheSite() {
        val cells = incidents.map { grid.cellAt(it.lat!!, it.lon!!) }
        assertTrue(cells.all { it != null })
        assertTrue(cells.toSet().size >= 6)
        assertTrue(incidents.any { it.landmark != 0 })
    }

    @Test
    fun everyPointHasAStatusAndTwoAnchorsCoverTheirSquares() {
        val points = DemoScenario.points(venue, now)
        assertEquals(PointBoard.points(venue).map { it.number }.toSet(), points.keys)
        assertEquals(setOf(PointLevel.FREE, PointLevel.BUSY, PointLevel.CLOSED), points.values.map { it.level }.toSet())
        assertTrue(points.values.all { now - it.atMs < PointBoard.STALE_MS })

        val anchors = DemoScenario.anchors(venue, grid, now)
        assertEquals(DemoScenario.ANCHOR_PREFIXES.toSet(), anchors.keys)
        assertTrue(anchors.values.all { it.anchor && it.beat?.column != null })
        assertEquals(2, Coverage.squares(anchors.values, now).size)
    }

    @Test
    fun eachDayHasItsOwnReportSeedAndWindow() {
        val master = ByteArray(32) { it.toByte() }
        val zone = ZoneId.of("Europe/Bucharest")
        val day = LocalDate.of(2026, 10, 24)
        assertTrue(DemoChain.daySeed(master, day).contentEquals(DemoChain.daySeed(master, day)))
        assertFalse(DemoChain.daySeed(master, day).contentEquals(DemoChain.daySeed(master, day.plusDays(1))))
        val (from, until) = DemoChain.dayWindow(day, zone)
        assertEquals(86_400L, until - from)
        val (next, nextUntil) = DemoChain.dayWindow(day.plusDays(1), zone)
        assertEquals(until, next)
        // the night summer time ends
        assertEquals(90_000L, nextUntil - next)
    }
}
