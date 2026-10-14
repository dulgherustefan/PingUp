package ro.safetyplease.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ro.safetyplease.core.position.FriendAge
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.distanceMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapGeoJsonTest {
    private val venue = Venue(
        name = "Test",
        bounds = Bounds(43.948, 28.632, 43.9512, 28.6382),
        zones = listOf(
            Zone("stage", "Scena", "#E5484D", listOf(GeoPoint(43.951, 28.6344), GeoPoint(43.951, 28.6358), GeoPoint(43.95, 28.6364))),
            Zone("broken", "Fără formă", "#000000", listOf(GeoPoint(43.95, 28.635))),
        ),
        meetingPoint = GeoPoint(43.94893, 28.6351),
        pois = listOf(Poi("medical", 43.9495, 28.6347, "Medical", number = 12), Poi("wc", 43.949, 28.634)),
    )

    private fun features(geoJson: String) = Json.parseToJsonElement(geoJson).jsonObject.let {
        assertEquals("FeatureCollection", it["type"]!!.jsonPrimitive.content)
        it["features"]!!.jsonArray.map { feature -> feature.jsonObject }
    }

    private fun JsonObject.type() = this["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content
    private fun JsonObject.prop(name: String) = this["properties"]!!.jsonObject[name]!!.jsonPrimitive
    private fun JsonObject.ring(): List<GeoPoint> = this["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray.map {
        val (lon, lat) = it.jsonArray.map { c -> c.jsonPrimitive.double }
        GeoPoint(lat, lon)
    }

    @Test
    fun ringIsClosedAndEveryVertexIsTheRadiusAway() {
        val center = GeoPoint(43.95, 28.635)
        val ring = MapGeoJson.ring(center, 25.0)
        assertEquals(49, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.forEach { assertEquals(25.0, distanceMeters(center, it).toDouble(), 1.0) }
    }

    @Test
    fun zonesAreClosedPolygonsInLonLatOrderWithTheirColor() {
        val zones = features(MapGeoJson.zones(venue))
        assertEquals(1, zones.size)
        val zone = zones.single()
        assertEquals("Polygon", zone.type())
        assertEquals("stage", zone.prop("id").content)
        assertEquals("#E5484D", zone.prop("color").content)
        val ring = zone.ring()
        assertEquals(4, ring.size)
        assertEquals(GeoPoint(43.951, 28.6344), ring.first())
        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun poisCarryTheirLandmarkNumberAndTheMeetingPointComesLast() {
        val pois = features(MapGeoJson.pois(venue) { type -> if (type == "medical") "#FF0000" else "#00FF00" })
        assertEquals(listOf("12", "", ""), pois.map { it.prop("label").content })
        assertEquals(listOf("medical", "wc", Venue.MEETING), pois.map { it.prop("type").content })
        assertEquals("#FF0000", pois[0].prop("color").content)
        val coordinates = pois[0]["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(28.6347, 43.9495), coordinates)
    }

    @Test
    fun gridHasEveryEdgeAndOneLabelPerColumnAndRow() {
        val grid = SiteGrid.of(venue.bounds)
        val all = features(MapGeoJson.grid(grid))
        val lines = all.filter { it.type() == "LineString" }
        val labels = all.filter { it.type() == "Point" }.map { it.prop("label").content }
        assertEquals(grid.columns + 1 + grid.rows + 1, lines.size)
        assertEquals((0 until grid.columns).map(grid::columnLabel) + (0 until grid.rows).map(grid::rowLabel), labels)
        // labels sit inside the site, so they stay with the squares they name
        all.filter { it.type() == "Point" }.forEach {
            val (lon, lat) = it["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { c -> c.jsonPrimitive.double }
            assertTrue(venue.bounds.contains(lat, lon))
        }
    }

    @Test
    fun meHasTheNinetyFivePercentRing() {
        val me = features(MapGeoJson.me(MapMe(43.95, 28.635, accuracyM = 10f)))
        assertEquals(listOf("Polygon", "Point"), me.map { it.type() })
        assertEquals(16.0, distanceMeters(GeoPoint(43.95, 28.635), me[0].ring()[5]).toDouble(), 1.0)
        assertTrue(features(MapGeoJson.me(null)).isEmpty())
        me.forEach {
            assertEquals(false, it.prop("grey").boolean)
            assertEquals(false, it.prop("dashed").boolean)
        }
    }

    @Test
    fun anOldPositionIsGreyWithItsGrownRingAndAnUnknownAccuracyIsADashedGuess() {
        val old = features(MapGeoJson.me(MapMe(43.95, 28.635, accuracyM = 10f, ringM = 240f, stale = true)))
        assertEquals(240.0, distanceMeters(GeoPoint(43.95, 28.635), old[0].ring()[5]).toDouble(), 2.0)
        old.forEach { assertEquals(true, it.prop("grey").boolean) }
        val unknown = features(MapGeoJson.me(MapMe(43.95, 28.635, accuracyM = 0f)))
        assertEquals(50.0, distanceMeters(GeoPoint(43.95, 28.635), unknown[0].ring()[5]).toDouble(), 1.0)
        unknown.forEach { assertEquals(true, it.prop("dashed").boolean) }
    }

    @Test
    fun placesOffTheEarthAreNotDrawn() {
        val off = GeoPoint(95.0, 28.6)
        assertTrue(features(MapGeoJson.me(MapMe(off.lat, off.lon, 5f))).isEmpty())
        assertTrue(features(MapGeoJson.target(off)).isEmpty())
        assertTrue(features(MapGeoJson.markers(listOf(MapMarker("k", off, "#FF0000")))).isEmpty())
        assertTrue(features(MapGeoJson.friends(listOf(MapFriend("f", off.lat, off.lon, 10f, FriendAge.NOW)))).isEmpty())
        assertTrue(features(MapGeoJson.routes(listOf(MapRoute(listOf(GeoPoint(43.95, 28.6), off))))).isEmpty())
    }

    @Test
    fun eachFriendIsARingAndADotLookingItsAge() {
        val friends = features(
            MapGeoJson.friends(
                listOf(
                    MapFriend("a", 43.95, 28.635, ringM = 32f, age = FriendAge.NOW, label = "Ana"),
                    MapFriend("b", 43.949, 28.634, ringM = 8f, age = FriendAge.FADED),
                    MapFriend("c", 43.948, 28.633, ringM = 300f, age = FriendAge.STALE),
                ),
            ),
        )
        assertEquals(listOf("Polygon", "Point", "Polygon", "Point", "Polygon", "Point"), friends.map { it.type() })
        assertEquals(listOf("a", "a", "b", "b", "c", "c"), friends.map { it.prop("id").content })
        assertEquals(listOf(1f, 1f, 0.5f, 0.5f, 0.5f, 0.5f), friends.map { it.prop("fade").float })
        assertEquals(listOf(false, false, false, false, true, true), friends.map { it.prop("grey").boolean })
        assertTrue(friends[1].prop("size").float > friends[3].prop("size").float)
        assertEquals("Ana", friends[1].prop("label").content)
        assertEquals(32.0, distanceMeters(GeoPoint(43.95, 28.635), friends[0].ring()[0]).toDouble(), 1.0)
        // older than ten minutes: an outline, with the ring's real size
        assertEquals(listOf(false, false, true, true, true, true), friends.map { it.prop("outline").boolean })
        assertEquals(8.0, distanceMeters(GeoPoint(43.949, 28.634), friends[2].ring()[0]).toDouble(), 1.0)
        assertEquals(300.0, distanceMeters(GeoPoint(43.948, 28.633), friends[4].ring()[0]).toDouble(), 1.0)
    }

    @Test
    fun markersAndTargetArePoints() {
        val markers = features(MapGeoJson.markers(listOf(MapMarker("inc-1", GeoPoint(43.95, 28.635), "#123456"))))
        assertEquals("inc-1", markers.single().prop("key").content)
        assertEquals(1, features(MapGeoJson.target(GeoPoint(43.95, 28.635))).size)
        assertTrue(features(MapGeoJson.target(null)).isEmpty())
    }

    @Test
    fun routesAreLinesWithTheirStyle() {
        val a = GeoPoint(43.949, 28.634)
        val b = GeoPoint(43.950, 28.636)
        val routes = features(MapGeoJson.routes(listOf(MapRoute(listOf(a, b)), MapRoute(listOf(b, a), RouteStyle.ALTERNATIVE), MapRoute(listOf(a)))))
        assertEquals(listOf("LineString", "LineString"), routes.map { it.type() })
        assertEquals(listOf("primary", "alternative"), routes.map { it.prop("style").content })
        val first = routes[0]["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray.map { it.jsonPrimitive.double }
        assertEquals(listOf(28.634, 43.949), first)
    }

    @Test
    fun onlyStyledZonesAreDrawnWithTheirColorAndCover() {
        val styled = features(
            MapGeoJson.zoneStyles(venue, mapOf("stage" to ZoneStyle.CLOSED, "broken" to ZoneStyle.CROWDED, "nowhere" to ZoneStyle.EVACUATE)) {
                "#${it.name}"
            },
        )
        val stage = styled.single()
        assertEquals("stage", stage.prop("id").content)
        assertEquals("closed", stage.prop("style").content)
        assertEquals("#CLOSED", stage.prop("color").content)
        assertEquals(OverlayColors.fillAlpha(ZoneStyle.CLOSED), stage.prop("opacity").float)
        assertEquals(stage.ring().first(), stage.ring().last())
    }

    @Test
    fun overlayPinsCarryTheKeyATapReports() {
        val pins = features(MapGeoJson.overlayPins(listOf(OverlayPin("ana", GeoPoint(43.95, 28.635), PinKind.LAST_SEEN, "Ana · 22:10"))) { it.name })
        val pin = pins.single()
        assertEquals("Point", pin.type())
        assertEquals(listOf("ana", "last_seen", "LAST_SEEN", "Ana · 22:10"), listOf("key", "kind", "color", "label").map { pin.prop(it).content })
    }
}
