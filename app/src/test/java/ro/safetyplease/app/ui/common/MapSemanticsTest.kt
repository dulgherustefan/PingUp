package ro.safetyplease.app.ui.common

import ro.safetyplease.ui.common.mapNodes
import ro.safetyplease.ui.common.MapWords
import ro.safetyplease.ui.common.MapPin
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone

class MapSemanticsTest {
    private fun square(id: String, name: String, type: String, lat: Double, lon: Double) = Zone(
        id, name, "#000000",
        listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 0.001), GeoPoint(lat + 0.001, lon + 0.001), GeoPoint(lat + 0.001, lon)),
        type,
    )

    // west to east: a stage, a camping and a zone without a type, all on one row
    private val stage = square("stage", "Scena mare", "stage", 44.0, 28.0)
    private val camping = square("camping", "Camping", "camping", 44.0, 28.001)
    private val plain = square("plain", "Parcare", "", 44.0, 28.002)
    private val venue = Venue(
        "test", Bounds(44.0, 28.0, 44.002, 28.003), listOf(stage, camping, plain),
        meetingPoint = GeoPoint(44.0015, 28.0015),
        pois = listOf(Poi("wc", 44.0005, 28.0025, "Toalete est"), Poi("water", 44.0015, 28.0005, "Fântână")),
    )

    private val words = MapWords(
        types = mapOf("stage" to "scenă", "camping" to "camping", "wc" to "toalete", "water" to "apă potabilă"),
        zone = "zonă",
        here = "aici ești",
        meeting = "Punct de întâlnire",
        incident = "Incident",
        urgency = { if (it == Severity.URGENT) "Urgent" else "Nu e urgent" },
        directions = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-vest", "vest", "nord-vest"),
        route = { meters, direction -> "$meters m spre $direction" },
    )

    private fun nodes(position: GeoPoint? = null, myZone: String = "", pins: List<MapPin> = emptyList()) =
        mapNodes(venue, words, position, myZone, pins)

    @Test
    fun zonesComeFirstThenPointsThenTheMeetingPointThenPins() {
        val pin = MapPin(GeoPoint(44.0005, 28.0005), Color.Red, "i1")
        val all = nodes(pins = listOf(pin))
        assertEquals(listOf(stage, camping, plain), all.take(3).map { it.zone })
        assertEquals(listOf("Toalete est", "Fântână, apă potabilă", "Punct de întâlnire"), all.drop(3).take(3).map { it.description })
        assertSame(pin, all.last().pin)
        assertEquals(7, all.size)
    }

    @Test
    fun withoutAPositionOnlyNameAndType() {
        assertEquals(listOf("Scena mare, scenă", "Camping", "Parcare, zonă"), nodes().take(3).map { it.description })
    }

    @Test
    fun theTypeIsNotRepeatedWhenTheNameSaysIt() {
        assertEquals("Camping", nodes()[1].description)
        assertEquals("Toalete est", nodes()[3].description)
    }

    @Test
    fun yourZoneSaysYouAreHereInsteadOfADistance() {
        assertEquals("Camping, aici ești", nodes(myZone = "camping")[1].description)
    }

    @Test
    fun aPositionInsideAZoneMakesItYours() {
        val inStage = GeoPoint(44.0005, 28.0005)
        assertEquals("Scena mare, scenă, aici ești", nodes(position = inStage)[0].description)
    }

    @Test
    fun otherPlacesSayHowFarAndWhichWay() {
        val inStage = GeoPoint(44.0005, 28.0005)
        val all = nodes(position = inStage)
        // the camping's center is 0.001 degrees east, about 80 m at this latitude
        assertEquals("Camping, 80 m spre est", all[1].description)
        assertEquals("rounded to 5 m", "Fântână, apă potabilă, 110 m spre nord", all[4].description)
    }

    @Test
    fun fromAwayNoPlaceSaysHowFar() {
        val home = GeoPoint(44.43, 26.10)
        assertEquals(listOf("Scena mare, scenă", "Camping", "Parcare, zonă"), nodes(position = home).take(3).map { it.description })
    }

    @Test
    fun aStepDoesNotChangeWhatIsRead() {
        val before = nodes(position = GeoPoint(44.0005, 28.0005))[4].description
        val oneMeterNorth = nodes(position = GeoPoint(44.000509, 28.0005))[4].description
        assertEquals(before, oneMeterNorth)
    }

    @Test
    fun aPinSaysItIsAnIncidentAndWhere() {
        val pin = MapPin(GeoPoint(44.0005, 28.0015), Color.Red, "i1")
        assertEquals("Incident, Camping", nodes(pins = listOf(pin)).last().description)
        val outside = MapPin(GeoPoint(44.0018, 28.0028), Color.Red, "i2")
        assertEquals("Incident", nodes(pins = listOf(outside)).last().description)
    }

    @Test
    fun anIncidentPinSaysHowUrgentItIs() {
        val urgent = MapPin(GeoPoint(44.0005, 28.0015), Color.Red, "i1", Severity.URGENT)
        val medium = MapPin(GeoPoint(44.0005, 28.0015), Color.Red, "i2", Severity.MEDIUM)
        assertEquals("Incident, Urgent, Camping", nodes(pins = listOf(urgent)).last().description)
        assertEquals("Incident, Nu e urgent, Camping", nodes(pins = listOf(medium)).last().description)
    }

    @Test
    fun onlyZoneNodesCarryAZone() {
        val all = nodes()
        assertEquals(3, all.count { it.zone != null })
        assertNull(all.last().zone)
    }
}
