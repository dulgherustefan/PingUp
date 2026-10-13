package ro.safetyplease.core.venue

import ro.safetyplease.core.control.reportEntry
import ro.safetyplease.core.data.StaffIncident
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LandmarkTest {
    // about 111 m per 0.001 degrees of latitude
    private val here = GeoPoint(44.0, 26.0)
    private val venue = Venue(
        "test", Bounds(43.99, 25.99, 44.01, 26.01), emptyList(),
        pois = listOf(
            Poi("water", 44.0001, 26.0, "Apă fără număr"),
            Poi("wc", 44.0003, 26.0, "Toalete", number = 4),
            Poi("exit", 44.0002, 26.0, "Poarta", number = 1),
            Poi("info", 44.0005, 26.0, "Info", number = 0x100),
        ),
    )

    @Test
    fun nearestNumberedPointIsTheLandmark() {
        assertEquals("Poarta", venue.nearestLandmark(here)?.name)
    }

    @Test
    fun noLandmarkFartherThanAHundredMetres() {
        val far = venue.copy(pois = listOf(Poi("wc", 44.0015, 26.0, "Toalete", number = 4)))
        assertNull(far.nearestLandmark(here))
        assertEquals(4, far.copy(pois = listOf(Poi("wc", 44.0008, 26.0, "Toalete", number = 4))).nearestLandmark(here)?.number)
    }

    @Test
    fun landmarkIsFoundByItsNumber() {
        assertEquals("Toalete", venue.landmark(4)?.name)
        assertNull(venue.landmark(0))
        assertNull(venue.landmark(0x100), "doesn't fit in the byte")
        assertNull(venue.landmark(9))
    }

    @Test
    fun consoleComputesTheGridSquareFromTheCoordinates() {
        val grid = SiteGrid(Bounds(0.0, 0.0, 1.0, 1.0), columns = 4, rows = 4)
        val incident = StaffIncident(
            "a", 1, 3, "", lat = 0.6, lon = 0.8, reportedAt = 0, receivedAt = 0, hops = 1, accuracyM = 12, landmark = 4,
        )
        val entry = reportEntry(incident, grid)
        assertEquals("D2", entry.grid)
        assertEquals(12, entry.accuracyM)
        assertEquals(4, entry.landmark)
        assertEquals("", reportEntry(incident.copy(lat = null, lon = null), grid).grid)
    }
}
