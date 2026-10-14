package ro.safetyplease.core.venue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a route or a team goes for a zone: inside it, also around a bend, and never a point that isn't a number. */
class ZoneCenterTest {
    private fun zone(polygon: List<GeoPoint>) = Zone("z", "Z", "#336699", polygon)

    /** What the center always was: the average of the corners. */
    private fun average(polygon: List<GeoPoint>) = GeoPoint(polygon.map { it.lat }.average(), polygon.map { it.lon }.average())

    @Test
    fun aConvexZoneKeepsTheAverageOfItsCorners() {
        val square = listOf(GeoPoint(44.0, 26.0), GeoPoint(44.0, 26.002), GeoPoint(44.002, 26.002), GeoPoint(44.002, 26.0))
        assertEquals(average(square), zone(square).center)
    }

    @Test
    fun theCenterOfAZoneAroundABendIsInsideIt() {
        // a "U" open to the north, along the water: the average of its corners falls in the gap
        val u = listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 3.0), GeoPoint(3.0, 3.0), GeoPoint(3.0, 2.0),
            GeoPoint(1.0, 2.0), GeoPoint(1.0, 1.0), GeoPoint(3.0, 1.0), GeoPoint(3.0, 0.0),
        )
        assertFalse(pointInPolygon(1.75, 1.5, u))
        val center = zone(u).center
        assertTrue(pointInPolygon(center.lat, center.lon, u), "$center")
        // an "L" whose corners average out to its missing corner
        val l = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(1.0, 10.0), GeoPoint(1.0, 1.0), GeoPoint(10.0, 1.0), GeoPoint(10.0, 0.0))
        val inL = zone(l).center
        assertTrue(pointInPolygon(inL.lat, inL.lon, l), "$inL")
    }

    @Test
    fun aZoneWithoutCornersIsRefusedAsItIsRead() {
        assertFails { zone(emptyList()) }
        val text = """{"name":"T","bounds":{"minLat":44.0,"minLon":26.0,"maxLat":44.01,"maxLon":26.01},""" +
            """"zones":[{"id":"x","name":"X","color":"#000000","polygon":[]}]}"""
        assertFails { Venue.parse(text) }
        // two corners make no area, but no harm either: the middle of the two
        val line = listOf(GeoPoint(44.0, 26.0), GeoPoint(44.0, 26.002))
        assertEquals(average(line), zone(line).center)
    }
}
