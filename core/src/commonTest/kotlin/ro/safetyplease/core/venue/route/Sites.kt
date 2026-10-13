package ro.safetyplease.core.venue.route

import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.pointInPolygon
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.math.hypot

/** Test sites laid out in metres east ([x]) and north ([y]) of a corner. */
internal object Sites {
    private const val LAT0 = 44.0
    private const val LON0 = 26.0
    private const val METERS_PER_LAT = 111_320.0
    private val metersPerLon = METERS_PER_LAT * cos(radians(LAT0))

    fun at(x: Double, y: Double) = GeoPoint(LAT0 + y / METERS_PER_LAT, LON0 + x / metersPerLon)

    fun x(p: GeoPoint) = (p.lon - LON0) * metersPerLon

    fun y(p: GeoPoint) = (p.lat - LAT0) * METERS_PER_LAT

    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(at(x0, y0), at(x1, y0), at(x1, y1), at(x0, y1))

    fun zone(id: String, x0: Double, y0: Double, x1: Double, y1: Double, type: String = "") = Zone(id, id, "#336699", rect(x0, y0, x1, y1), type)

    /** A [width] by [height] metre site with nothing on it. */
    fun empty(width: Double = 300.0, height: Double = 200.0) =
        Venue("test", Bounds(LAT0, LON0, LAT0 + height / METERS_PER_LAT, LON0 + width / metersPerLon), emptyList())

    /** Points every half metre along the route. */
    fun samples(route: Route): List<GeoPoint> = route.points.zipWithNext().flatMap { (a, b) ->
        val length = hypot(x(b) - x(a), y(b) - y(a))
        val n = maxOf(1, (length * 2).toInt())
        (0..n).map { at(x(a) + (x(b) - x(a)) * it / n, y(a) + (y(b) - y(a)) * it / n) }
    }

    fun crosses(route: Route, polygon: List<GeoPoint>) = samples(route).any { pointInPolygon(it.lat, it.lon, polygon) }

    /** Whether segment ab crosses segment cd, in metres. */
    fun intersects(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun side(p: GeoPoint, q: GeoPoint, r: GeoPoint) = (x(q) - x(p)) * (y(r) - y(p)) - (y(q) - y(p)) * (x(r) - x(p))
        val d1 = side(c, d, a)
        val d2 = side(c, d, b)
        val d3 = side(a, b, c)
        val d4 = side(a, b, d)
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
    }

    /** The route's segments that cross a fence line. */
    fun fenceCrossings(route: Route, fence: List<GeoPoint>): List<Pair<GeoPoint, GeoPoint>> =
        route.points.zipWithNext().filter { (a, b) -> fence.zipWithNext().any { (c, d) -> intersects(a, b, c, d) } }

    /** Metres from [p] to the segment ab. */
    fun distance(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double =
        segmentDistance(x(p), y(p), x(a), y(a), x(b), y(b))
}
