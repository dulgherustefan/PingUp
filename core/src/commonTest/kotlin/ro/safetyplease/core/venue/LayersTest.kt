package ro.safetyplease.core.venue

import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.protocol.PointLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayersTest {
    private fun square(id: String, name: String, lat: Double, lon: Double, type: String) = Zone(
        id, name, "#336699",
        listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 0.001), GeoPoint(lat + 0.001, lon + 0.001), GeoPoint(lat + 0.001, lon)), type,
    )

    private val venue = Venue(
        name = "Test",
        bounds = Bounds(44.0, 28.0, 44.01, 28.01),
        zones = listOf(
            square("main", "Scenă principală", 44.000, 28.000, "stage"),
            square("second", "Scena 2", 44.003, 28.000, "stage"),
            square("medical", "Punct medical", 44.006, 28.000, "medical"),
        ),
        pois = listOf(
            Poi("water", 44.0005, 28.0020, "Apă potabilă vest", 8),
            Poi("water", 44.0005, 28.0040, "Apă potabilă est", 9),
            Poi("water", 44.0005, 28.0080, "Apă lângă camping", 10),
            Poi("wc", 44.0020, 28.0020, "Toalete scenă", 5),
            Poi("dea", 44.0030, 28.0030, "DEA scena principală", 14),
            Poi(Venue.PHONE, 44.0040, 28.0030, "Telefon fix"),
        ),
    )

    // --- search ---

    @Test
    fun diacriticsAreOptionalBothWays() {
        assertEquals("s", PlaceSearch.normalize("ș"))
        assertEquals("s", PlaceSearch.normalize("ş"))
        assertEquals("scena principala", PlaceSearch.normalize(" Scenă Principală "))
        assertEquals("Scenă principală", PlaceSearch.search("scena princ", venue).first().name)
        assertEquals("Scenă principală", PlaceSearch.search("SCENĂ PRINCIPALĂ", venue).first().name)
        val friends = listOf(NamedPoint(1, "Ștefan", GeoPoint(44.001, 28.001)), NamedPoint(2, "Ana", null))
        assertEquals(listOf("Ștefan"), PlaceSearch.search("stefan", venue, friends = friends).map { it.name })
        assertEquals(listOf("Ștefan"), PlaceSearch.search("Ştefan", venue, friends = friends).map { it.name })
    }

    @Test
    fun aNumberFindsItsLandmark() {
        for (query in listOf("14", "#14", "reper 14", "Reperul 14", "landmark 14")) {
            val first = PlaceSearch.search(query, venue).first()
            assertEquals(FoundKind.LANDMARK, first.kind, query)
            assertEquals(14, first.number, query)
            assertEquals("DEA scena principală", first.name)
        }
        assertEquals(1, PlaceSearch.search("14", venue).count { it.number == 14 })
        assertTrue(PlaceSearch.search("99", venue).none { it.kind == FoundKind.LANDMARK })
    }

    @Test
    fun aServiceWordFindsItsPlacesInEitherLanguage() {
        assertEquals(setOf(8, 9, 10), PlaceSearch.search("apa", venue).filter { it.type == "water" }.map { it.number }.toSet())
        assertEquals(listOf(5), PlaceSearch.search("toilet", venue).map { it.number })
        assertEquals(listOf("Punct medical"), PlaceSearch.search("first aid", venue).map { it.name })
        assertEquals(FoundKind.PLACE, PlaceSearch.search("phone", venue).single().kind)
    }

    @Test
    fun setsAreFoundAtTheirStageWhatIsOnFirst() {
        val schedule = listOf(
            ScheduleItem("second", "DJ Set de seară", 1000, 2000),
            ScheduleItem("main", "Dj Set târziu", 3000, 4000),
        )
        val found = PlaceSearch.search("dj set", venue, schedule, nowSec = 2500)
        assertEquals(listOf("Dj Set târziu", "DJ Set de seară"), found.map { it.name })
        assertEquals(venue.zone("main")!!.center, found.first().point)
        assertEquals(FoundKind.SET, found.first().kind)
    }

    @Test
    fun noQueryNoResults() {
        assertEquals(emptyList(), PlaceSearch.search("  ", venue))
        assertEquals(emptyList(), PlaceSearch.search("zzz", venue))
    }

    // --- service layers ---

    private val now = 10_000_000L
    private fun report(point: Int, level: Int, ageMs: Long = 0) = PointReport(point, level, "Echipa", now - ageMs, (now - ageMs) / 1000)

    @Test
    fun statusComesFromTheBoardWhileFresh() {
        assertEquals(ServiceState.UNKNOWN, ServiceLayer.state(null, now))
        assertEquals(ServiceState.FREE, ServiceLayer.state(report(8, PointLevel.FREE), now))
        assertEquals(ServiceState.BUSY, ServiceLayer.state(report(8, PointLevel.BUSY), now))
        assertEquals(ServiceState.CLOSED, ServiceLayer.state(report(8, PointLevel.CLOSED), now))
        assertEquals(ServiceState.UNKNOWN, ServiceLayer.state(report(8, PointLevel.CLOSED, PointBoard.STALE_MS), now))
    }

    @Test
    fun layersFilterByTypeAndStatus() {
        val board = mapOf(8 to report(8, PointLevel.CLOSED), 9 to report(9, PointLevel.BUSY))
        val water = ServiceLayer.points(venue, setOf("water"), board, now)
        assertEquals(listOf(ServiceState.CLOSED, ServiceState.BUSY, ServiceState.UNKNOWN), water.map { it.state })
        assertEquals(listOf(9, 10), ServiceLayer.points(venue, setOf("water"), board, now, openOnly = true).map { it.number })
        // a medical zone is a medical place, at its middle
        val medical = ServiceLayer.points(venue, setOf("medical", "wc"), board, now)
        assertEquals(listOf("Toalete scenă", "Punct medical"), medical.map { it.name })
        assertEquals(venue.zone("medical")!!.center, medical.last().point)
        assertEquals(listOf("water", "wc", "medical", Venue.DEA, Venue.PHONE), ServiceLayer.present(venue))
    }

    @Test
    fun quietZonesAndEarplugsAreLayersToo() {
        val quiet = venue.copy(
            zones = venue.zones + square("chill", "Pădurea", 44.007, 28.004, ServiceLayer.QUIET),
            pois = venue.pois + Poi(ServiceLayer.EARPLUGS, 44.0011, 28.0061, "Dopuri la intrare"),
        )
        assertEquals(
            listOf("water", "wc", "medical", Venue.DEA, Venue.PHONE, ServiceLayer.QUIET, ServiceLayer.EARPLUGS),
            ServiceLayer.present(quiet),
        )
        val points = ServiceLayer.points(quiet, setOf(ServiceLayer.QUIET, ServiceLayer.EARPLUGS), emptyMap(), now)
        assertEquals(listOf("Pădurea", "Dopuri la intrare"), points.map { it.name })
        assertEquals(quiet.zone("chill")!!.center, points.first().point)
        for (word in listOf("liniste", "Liniștit", "quiet")) {
            assertEquals("Pădurea", PlaceSearch.search(word, quiet).firstOrNull()?.name, word)
        }
        for (word in listOf("dopuri", "earplugs", "antifoane")) {
            assertEquals("Dopuri la intrare", PlaceSearch.search(word, quiet).firstOrNull()?.name, word)
        }
    }

    @Test
    fun theNearestOpenSkipsClosedAndUnreachable() {
        val from = GeoPoint(44.0005, 28.0015)
        val board = mapOf(8 to report(8, PointLevel.CLOSED))
        assertEquals(9, ServiceLayer.nearestOpen(venue, "water", board, now, from, StraightLine)?.number)
        // a walk that can't reach 9 (a fence) leaves 10
        val fenced = WalkDistance { a, b -> if (b.lon == 28.0040) null else StraightLine.meters(a, b) }
        assertEquals(10, ServiceLayer.nearestOpen(venue, "water", board, now, from, fenced)?.number)
        assertNull(ServiceLayer.nearestOpen(venue, "lockers", board, now, from, StraightLine))
    }
}
