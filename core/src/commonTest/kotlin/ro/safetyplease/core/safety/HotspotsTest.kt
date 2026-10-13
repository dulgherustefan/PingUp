package ro.safetyplease.core.safety

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The console's view of where harassment and unsafe reports cluster, in the last day. */
class HotspotsTest {
    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L

    private val alley = GeoPoint(44.0005, 28.0005)
    private val camping = GeoPoint(44.0095, 28.0195)
    private val venue = Venue(
        "t", Bounds(44.0, 28.0, 44.01, 28.02),
        listOf(
            Zone("aleea", "Aleea spre camping", "#00AA00", listOf(GeoPoint(44.0, 28.0), GeoPoint(44.0, 28.002), GeoPoint(44.001, 28.002), GeoPoint(44.001, 28.0))),
            Zone("camping", "Camping", "#00AA00", listOf(GeoPoint(44.009, 28.018), GeoPoint(44.009, 28.02), GeoPoint(44.01, 28.02), GeoPoint(44.01, 28.018))),
        ),
    )
    private val grid = SiteGrid.of(venue.bounds)

    private fun report(
        id: String,
        agoMs: Long,
        point: GeoPoint? = alley,
        category: Int = IncidentCategory.HARASSMENT,
        description: String = "",
        zone: String = "aleea",
        happenedMinAgo: Int = 0,
        status: Int = AckStatus.NONE,
    ) = StaffIncident(
        id, category, 2, zone, point?.lat, point?.lon, description, null, reportedAt = now - agoMs, receivedAt = now - agoMs, hops = 1,
        status = status, happenedMinAgo = happenedMinAgo,
    )

    private fun of(vararg incidents: StaffIncident) = Hotspots.of(incidents.toList(), grid, venue, now)

    @Test
    fun reportsOfTheSameSquareAddUpByHourWithTheLatestHourLast() {
        val map = of(
            report("a", 10 * minute), report("b", 40 * minute), report("c", 90 * minute), report("d", 5 * hour + 2 * minute),
            report("e", 20 * minute, point = camping, zone = "camping"),
        )
        assertEquals(2, map.cells.size)
        val busiest = map.cells.first()
        assertEquals(grid.cellAt(alley.lat, alley.lon), busiest.cell)
        assertEquals("Aleea spre camping", busiest.zoneName)
        assertEquals(4, busiest.total)
        assertEquals(24, busiest.perHour.size)
        assertEquals(2, busiest.perHour[23], "the hour that ends now holds a and b")
        assertEquals(1, busiest.perHour[22], "an hour and a half ago")
        assertEquals(1, busiest.perHour[18], "five hours ago")
        assertEquals(4, busiest.perHour.sum())
        assertEquals(now - 10 * minute, busiest.lastAtMs)
        assertEquals(1, map.cells.last().total)
        assertEquals(0, map.unplaced)
    }

    @Test
    fun theDiscreetRequestCountsAsUnsafeAndHarassmentAsItself() {
        val map = of(
            report("a", minute), report("b", minute, description = Discreet.describe("Mă simt în nesiguranță")),
            report("c", 2 * minute, description = Discreet.describe("x")),
        )
        val cell = map.cells.single()
        assertEquals(1, cell.harassment)
        assertEquals(2, cell.unsafe)
        assertEquals(3, cell.total)
    }

    @Test
    fun onlyTheLastDayAndOnlyWhatIsAboutSomeoneElsesBehaviourCounts() {
        val map = of(
            report("old", 25 * hour),
            report("edge", 24 * hour),
            report("medical", minute, category = IncidentCategory.MEDICAL),
            report("welfare", minute, description = Welfare.describe("Nu mă simt bine: anxietate.")),
            report("withdrawn", minute, status = AckStatus.CANCELLED),
            report("baseless", minute, status = AckStatus.UNFOUNDED),
            report("counted", minute),
        )
        assertEquals(1, map.total)
        assertEquals(1, map.cells.single().perHour[23])
    }

    @Test
    fun aReportWrittenLaterCountsWhenItHappenedNotWhenItCameIn() {
        val map = of(
            report("later", minute, happenedMinAgo = 5 * 60),
            report("yesterday", minute, happenedMinAgo = 24 * 60 + 30),
            report("now", minute),
        )
        val cell = map.cells.single()
        assertEquals(2, cell.total, "the one from more than a day back is out, whenever it was sent")
        assertEquals(1, cell.perHour[23])
        assertEquals(1, cell.perHour[18], "five hours back, in its own hour")
    }

    @Test
    fun aReportWithoutAPositionFallsBackToItsZoneAndFailingThatIsNotPlaced() {
        val map = of(report("zone", minute, point = null, zone = "camping"), report("nowhere", minute, point = null, zone = ""))
        val middle = venue.zone("camping")!!.center
        assertEquals(grid.cellAt(middle.lat, middle.lon), map.cells.single().cell, "the middle of the camping zone")
        assertEquals(1, map.unplaced)
        assertEquals(2, map.total)
    }

    @Test
    fun theBusiestSquareComesFirstAndTiesGoToTheMostRecent() {
        val map = of(
            report("quiet", minute, point = camping, zone = "camping"),
            report("a", 3 * minute), report("b", 4 * minute),
            report("c", 2 * minute, point = GeoPoint(44.005, 28.01), zone = ""),
        )
        assertEquals(listOf(2, 1, 1), map.cells.map { it.total })
        assertEquals(listOf(now - minute, now - 2 * minute), map.cells.drop(1).map { it.lastAtMs }, "of the two single reports the later first")
    }

    @Test
    fun noReportsIsAnEmptyMap() {
        val map = of()
        assertTrue(map.cells.isEmpty())
        assertEquals(0, map.total)
    }

    @Test
    fun theWelfareRequestStaysDiscreetForTheRoutingButNotForTheHotspots() {
        val text = Welfare.describe("Nu mă simt bine: panică.")
        assertTrue(Discreet.isDiscreet(text), "so it goes to the safeguarding role")
        assertTrue(Welfare.isWelfare(text))
        assertFalse(Welfare.isWelfare(Discreet.describe("Mă simt în nesiguranță")))
        assertEquals(Discreet.ROLE, Routing.roleOf(report("w", minute, description = text)))
    }
}
