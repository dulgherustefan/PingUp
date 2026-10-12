package ro.safetyplease.core.venue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VenueNamesTest {
    private val text = """
        {
          "name": "Test",
          "bounds": { "minLat": 0, "minLon": 0, "maxLat": 1, "maxLon": 1 },
          "zones": [
            { "id": "stage", "name": "Scena", "name_en": "Stage", "color": "#000000",
              "polygon": [ { "lat": 0, "lon": 0 }, { "lat": 0, "lon": 1 }, { "lat": 1, "lon": 0 } ] },
            { "id": "bar", "name": "Bar", "color": "#000000",
              "polygon": [ { "lat": 0, "lon": 0 }, { "lat": 0, "lon": 1 }, { "lat": 1, "lon": 0 } ] },
            { "id": "chill", "name": "Relaxare", "name_en": " ", "color": "#000000",
              "polygon": [ { "lat": 0, "lon": 0 }, { "lat": 0, "lon": 1 }, { "lat": 1, "lon": 0 } ] }
          ],
          "pois": [
            { "type": "exit", "name": "Ieșire", "name_en": "Exit", "lat": 0.5, "lon": 0.5 },
            { "type": "wc", "lat": 0.2, "lon": 0.2 }
          ]
        }
    """.trimIndent()

    @Test
    fun englishNamesReplaceRomanianOnesWhereGiven() {
        val venue = Venue.parse(text, "en")
        assertEquals(listOf("Stage", "Bar", "Relaxare"), venue.zones.map { it.name })
        assertEquals(listOf("Exit", ""), venue.pois.map { it.name })
        assertEquals("Stage", venue.zoneName("stage"))
        assertEquals(listOf("stage", "bar", "chill"), venue.zones.map { it.id })
    }

    @Test
    fun romanianAndUnknownLanguagesKeepTheDefaultNames() {
        val plain = Venue.parse(text)
        assertEquals(plain, Venue.parse(text, "ro"))
        assertEquals(plain, Venue.parse(text, "de"))
        assertEquals(listOf("Scena", "Bar", "Relaxare"), plain.zones.map { it.name })
    }

    @Test
    fun bundledVenueHasEnglishForEveryName() {
        val text = File("../app/src/main/assets/venue.json").readText()
        val root = Json.parseToJsonElement(text).jsonObject
        val named = root.getValue("zones").jsonArray.map { it.jsonObject } +
            root.getValue("pois").jsonArray.map { it.jsonObject }.filter { "name" in it }
        for (entry in named) {
            val english = entry["name_en"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(entry.toString(), english.isNotBlank() && !english.contains(Regex("[ăâîșțĂÂÎȘȚ]")))
        }
        assertEquals("Main stage", Venue.parse(text, "en").zoneName("main-stage"))
    }
}
