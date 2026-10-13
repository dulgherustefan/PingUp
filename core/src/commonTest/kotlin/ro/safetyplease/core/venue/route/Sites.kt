package ro.safetyplease.core.venue.route

import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.BlockedArea
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.Gate
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.pointInPolygon
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
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

    /** Points [perMeter] times a metre along the route. */
    fun samples(route: Route, perMeter: Int): List<GeoPoint> = route.points.zipWithNext().flatMap { (a, b) ->
        val n = maxOf(1, (hypot(x(b) - x(a), y(b) - y(a)) * perMeter).toInt())
        (0..n).map { at(x(a) + (x(b) - x(a)) * it / n, y(a) + (y(b) - y(a)) * it / n) }
    }

    /** A 640 m square with fences every 80 m, each with its gate at the other end: the walk zigzags across it all. */
    fun zigzag(): Venue {
        val fences = (1..7).map { listOf(at(-40.0, it * 80.0), at(680.0, it * 80.0)) }
        val gates = (1..7).map { at(if (it % 2 == 1) 620.0 else 20.0, it * 80.0).let { p -> Gate(p.lat, p.lon) } }
        return empty(640.0, 640.0).copy(barriers = fences, gates = gates)
    }

    val zigzagEnds = at(10.0, 10.0) to at(630.0, 630.0)

    /** Small sites with random blocks, a pond and two zones, each in a random state. */
    fun randomSites(seed: Int, count: Int): List<Pair<Venue, MapState>> {
        val random = Random(seed)
        fun box(): List<GeoPoint> {
            val x0 = random.nextDouble(-20.0, 70.0)
            val y0 = random.nextDouble(-20.0, 55.0)
            return rect(x0, y0, x0 + random.nextDouble(3.0, 25.0), y0 + random.nextDouble(3.0, 25.0))
        }
        val states = ZoneState.entries
        return List(count) {
            val zones = listOf("a", "b").map { id ->
                val x0 = random.nextDouble(-20.0, 50.0)
                val y0 = random.nextDouble(-20.0, 40.0)
                zone(id, x0, y0, x0 + random.nextDouble(10.0, 40.0), y0 + random.nextDouble(10.0, 40.0))
            }
            val venue = empty(60.0, 45.0).copy(
                blocked = List(4) { BlockedArea(box(), BlockedArea.NONE) },
                water = listOf(box()),
                zones = zones,
            )
            venue to MapState(zones.associate { it.id to states[random.nextInt(states.size)] })
        }
    }

    fun randomPairs(seed: Int, count: Int): List<Pair<GeoPoint, GeoPoint>> {
        val random = Random(seed)
        fun point() = at(random.nextDouble(-25.0, 85.0), random.nextDouble(-25.0, 70.0))
        return List(count) { point() to point() }
    }

    /** The cheapest walk's cost in cells by plain Dijkstra, with the router's rules: eight neighbours, no cut corners. */
    fun dijkstra(grid: WalkGrid, costs: Costs, start: Int, goal: Int): Double {
        val cost = DoubleArray(grid.size) { Double.POSITIVE_INFINITY }
        val done = BooleanArray(grid.size)
        cost[start] = 0.0
        fun shut(c: Int, r: Int) = costs.of(r * grid.cols + c) == WalkRouter.SHUT
        while (true) {
            var cell = -1
            for (i in 0 until grid.size) if (!done[i] && cost[i] < Double.POSITIVE_INFINITY && (cell < 0 || cost[i] < cost[cell])) cell = i
            if (cell < 0) return Double.POSITIVE_INFINITY
            if (cell == goal) return cost[cell]
            done[cell] = true
            val col = cell % grid.cols
            val row = cell / grid.cols
            for (dc in -1..1) for (dr in -1..1) {
                val c = col + dc
                val r = row + dr
                if ((dc == 0 && dr == 0) || c !in 0 until grid.cols || r !in 0 until grid.rows || shut(c, r)) continue
                if (dc != 0 && dr != 0 && (shut(c, row) || shut(col, r))) continue
                val step = if (dc != 0 && dr != 0) sqrt(2.0) else 1.0
                val next = r * grid.cols + c
                cost[next] = min(cost[next], cost[cell] + step * costs.of(next))
            }
        }
    }
}
