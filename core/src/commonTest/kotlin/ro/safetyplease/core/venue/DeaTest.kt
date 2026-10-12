package ro.safetyplease.core.venue

import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeaTest {
    private val json = """
        {
          "name": "test",
          "bounds": { "minLat": 44.0, "minLon": 28.0, "maxLat": 44.01, "maxLon": 28.01 },
          "zones": [],
          "pois": [
            { "type": "dea", "name": "DEA vest", "lat": 44.005, "lon": 28.001 },
            { "type": "dea", "name": "DEA est", "lat": 44.005, "lon": 28.009 },
            { "type": "wc", "name": "Toalete", "lat": 44.005, "lon": 28.002 }
          ]
        }
    """.trimIndent()

    @Test
    fun deaIsAPlainPoiType() {
        val venue = Venue.parse(json)
        assertEquals(listOf("DEA vest", "DEA est"), venue.spots(Venue.DEA).map { it.name })
        assertEquals(GeoPoint(44.005, 28.009), venue.spots(Venue.DEA)[1].point)
    }

    @Test
    fun nearestDeaWinsAndWithoutAPositionTheFirstOne() {
        val venue = Venue.parse(json)
        assertEquals("DEA est", venue.nearest(Venue.DEA, GeoPoint(44.005, 28.007))?.name)
        assertEquals("DEA vest", venue.nearest(Venue.DEA, GeoPoint(44.005, 28.0025))?.name, "the toilets are closer, but not a DEA")
        assertEquals("DEA vest", venue.nearest(Venue.DEA, null)?.name)
    }

    @Test
    fun aVenueWithoutDeaHasNone() {
        assertNull(Venue.parse(json.replace("\"dea\"", "\"info\"")).nearest(Venue.DEA, GeoPoint(44.005, 28.005)))
    }

    @Test
    fun bundledVenueHasADeaAtTheMedicalPointAndAtTheMainStage() {
        val venue = Venue.parse(repoFile("app/src/main/assets/venue.json").readText())
        val deas = venue.spots(Venue.DEA)
        assertEquals(2, deas.size)
        assertTrue(deas.all { venue.bounds.contains(it.point.lat, it.point.lon) })
        assertEquals(setOf("medical", "main-stage"), deas.mapNotNull { venue.zoneAt(it.point.lat, it.point.lon)?.id }.toSet())
        val stage = venue.zone("main-stage")!!.center
        assertEquals("main-stage", venue.nearest(Venue.DEA, stage)?.point?.let { venue.zoneAt(it.lat, it.lon)?.id })
        val medical = venue.zone("medical")!!.center
        assertEquals("medical", venue.nearest(Venue.DEA, medical)?.point?.let { venue.zoneAt(it.lat, it.lon)?.id })
    }
}
