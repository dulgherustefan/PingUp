package ro.safetyplease.core.venue.route

import ro.safetyplease.core.venue.BlockedArea
import ro.safetyplease.core.venue.Gate
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.radians
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The site as square cells of [cellM] metres, [MARGIN_M] past the venue's bounds, for walking. A cell is shut when
 * water, a fence or a blocked area touches it; a fence opens only within [Gate.REACH_M] of a gate's foot on it. With
 * [staff], the staff-only gates open and the areas only staff may enter are walkable. Built once, read from any thread.
 */
class WalkGrid(val venue: Venue, val staff: Boolean) {
    private val metersPerLon = METERS_PER_LAT * cos(radians((venue.bounds.minLat + venue.bounds.maxLat) / 2))
    private val originLat = venue.bounds.minLat - MARGIN_M / METERS_PER_LAT
    private val originLon = venue.bounds.minLon - MARGIN_M / metersPerLon
    private val widthM = (venue.bounds.maxLon - venue.bounds.minLon) * metersPerLon + 2 * MARGIN_M
    private val heightM = (venue.bounds.maxLat - venue.bounds.minLat) * METERS_PER_LAT + 2 * MARGIN_M

    /** [CELL_M], or coarser on a site so large that the grid would pass [MAX_CELLS]. */
    val cellM: Double = max(CELL_M, sqrt(widthM * heightM / MAX_CELLS))
    val cols: Int = max(1, ceil(widthM / cellM).toInt())
    val rows: Int = max(1, ceil(heightM / cellM).toInt())
    val size: Int get() = cols * rows

    private val flags = ByteArray(cols * rows)

    /** The zone of each cell, by index in the venue, the first one listed winning as in [Venue.zoneAt]; -1 for none. */
    internal val zoneOf = IntArray(cols * rows) { -1 }

    /** The gate, by index in the venue, that opens each cell of a fence; -1 elsewhere. */
    internal val gateOf = IntArray(cols * rows) { -1 }

    init {
        venue.water.forEach { fill(it, WATER) }
        venue.blocked.filter { it.access == BlockedArea.NONE || !staff }.forEach { fill(it.polygon, BLOCKED) }
        venue.barriers.forEach { line -> line.zipWithNext { a, b -> markSegment(a, b, FENCE) } }
        venue.gates.forEachIndexed { index, gate -> if (!gate.staffOnly || staff) openGate(index, gate) }
        venue.zones.forEachIndexed { index, zone ->
            forCellsInside(zone.polygon) { cell -> if (zoneOf[cell] < 0) zoneOf[cell] = index }
        }
    }

    fun isOpen(cell: Int): Boolean = flags[cell].toInt() == 0

    /** The cell under [point]; -1 off the grid. */
    fun cellAt(point: GeoPoint): Int {
        val col = floor(x(point)).toInt()
        val row = floor(y(point)).toInt()
        return if (col in 0 until cols && row in 0 until rows) row * cols + col else -1
    }

    /** Position on the grid in cells, east and north from its corner, fractions included. */
    internal fun x(point: GeoPoint): Double = (point.lon - originLon) * metersPerLon / cellM

    internal fun y(point: GeoPoint): Double = (point.lat - originLat) * METERS_PER_LAT / cellM

    internal fun point(x: Double, y: Double): GeoPoint =
        GeoPoint(originLat + y * cellM / METERS_PER_LAT, originLon + x * cellM / metersPerLon)

    internal fun center(cell: Int): GeoPoint = point(cell % cols + 0.5, cell / cols + 0.5)

    /** Every cell whose center is inside [polygon], and every cell its edges touch, so a thin area can't be slipped through. */
    private fun fill(polygon: List<GeoPoint>, flag: Int) {
        forCellsInside(polygon) { set(it, flag) }
        (polygon + polygon.take(1)).zipWithNext { a, b -> markSegment(a, b, flag) }
    }

    private inline fun forCellsInside(polygon: List<GeoPoint>, action: (Int) -> Unit) {
        if (polygon.size < 3) return
        val xs = DoubleArray(polygon.size) { x(polygon[it]) }
        val ys = DoubleArray(polygon.size) { y(polygon[it]) }
        val c0 = max(0, floor(xs.min()).toInt())
        val c1 = min(cols - 1, floor(xs.max()).toInt())
        val r0 = max(0, floor(ys.min()).toInt())
        val r1 = min(rows - 1, floor(ys.max()).toInt())
        for (row in r0..r1) for (col in c0..c1) {
            if (inside(col + 0.5, row + 0.5, xs, ys)) action(row * cols + col)
        }
    }

    /** Every cell the segment passes through: those whose center is within half a cell's diagonal of it. */
    private fun markSegment(a: GeoPoint, b: GeoPoint, flag: Int) {
        val ax = x(a)
        val ay = y(a)
        val bx = x(b)
        val by = y(b)
        forCellsNear(ax, ay, bx, by, HALF_DIAGONAL) { set(it, flag) }
    }

    /**
     * Opens the fence cells around the gate's foot on the nearest fence: a gate drawn a few metres off the line still
     * opens the whole thickness of the fence there, not only the cells within reach of its own point.
     */
    private fun openGate(index: Int, gate: Gate) {
        val gx = x(gate.point)
        val gy = y(gate.point)
        val reach = max(Gate.REACH_M.toDouble(), cellM) / cellM
        var fx = gx
        var fy = gy
        var nearest = reach
        venue.barriers.forEach { line ->
            line.zipWithNext { a, b ->
                val ax = x(a)
                val ay = y(a)
                val bx = x(b)
                val by = y(b)
                val d = segmentDistance(gx, gy, ax, ay, bx, by)
                if (d <= nearest) {
                    nearest = d
                    val dx = bx - ax
                    val dy = by - ay
                    val length2 = dx * dx + dy * dy
                    val t = if (length2 == 0.0) 0.0 else (((gx - ax) * dx + (gy - ay) * dy) / length2).coerceIn(0.0, 1.0)
                    fx = ax + t * dx
                    fy = ay + t * dy
                }
            }
        }
        forCellsNear(fx, fy, fx, fy, reach) { cell ->
            if (flags[cell].toInt() and FENCE != 0) {
                flags[cell] = (flags[cell].toInt() and FENCE.inv()).toByte()
                gateOf[cell] = index
            }
        }
    }

    private inline fun forCellsNear(ax: Double, ay: Double, bx: Double, by: Double, radius: Double, action: (Int) -> Unit) {
        val c0 = max(0, floor(min(ax, bx) - radius).toInt())
        val c1 = min(cols - 1, floor(max(ax, bx) + radius).toInt())
        val r0 = max(0, floor(min(ay, by) - radius).toInt())
        val r1 = min(rows - 1, floor(max(ay, by) + radius).toInt())
        for (row in r0..r1) for (col in c0..c1) {
            if (segmentDistance(col + 0.5, row + 0.5, ax, ay, bx, by) <= radius) action(row * cols + col)
        }
    }

    private fun set(cell: Int, flag: Int) {
        flags[cell] = (flags[cell].toInt() or flag).toByte()
    }

    companion object {
        const val CELL_M = 3.0
        const val MARGIN_M = 30.0
        const val MAX_CELLS = 250_000.0
        internal const val METERS_PER_LAT = 111_320.0

        /** Slightly over half a cell's diagonal, so a line through a cell's corner shuts all four cells around it. */
        private const val HALF_DIAGONAL = 0.71

        private const val WATER = 1
        private const val FENCE = 2
        private const val BLOCKED = 4
    }
}

/** Ray casting on the grid's plane, as [ro.safetyplease.core.venue.pointInPolygon] does on coordinates. */
private fun inside(x: Double, y: Double, xs: DoubleArray, ys: DoubleArray): Boolean {
    var result = false
    var j = xs.size - 1
    for (i in xs.indices) {
        if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) result = !result
        j = i
    }
    return result
}

internal fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val length2 = dx * dx + dy * dy
    val t = if (length2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / length2
    val k = t.coerceIn(0.0, 1.0)
    val ex = px - (ax + k * dx)
    val ey = py - (ay + k * dy)
    return sqrt(ex * ex + ey * ey)
}
