package ro.safetyplease.core.map.pmtiles

import ro.safetyplease.core.map.MapArea
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator stops here: the north edge of row 0, the south edge of the last row. */
const val MAX_LATITUDE = 85.05112877980659

/** The column of [lon] at zoom [z], as tools/map_extract.py computes it. */
internal fun tileX(lon: Double, z: Int): Int {
    val n = 1L shl z
    return ((lon + 180) / 360 * n).toLong().coerceIn(0L, n - 1).toInt()
}

/** The row of [lat] at zoom [z], as tools/map_extract.py computes it. */
internal fun tileY(lat: Double, z: Int): Int {
    val n = 1L shl z
    val r = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * (PI / 180)
    return ((1 - asinh(tan(r)) / PI) / 2 * n).toLong().coerceIn(0L, n - 1).toInt()
}

/** The latitude of the north edge of row [y] out of [n]. */
internal fun rowLatitude(y: Long, n: Long): Double = atan(sinh(PI * (1 - 2.0 * y / n))) * (180 / PI)

/**
 * The tiles of a [MapArea] zoom by zoom: the bbox's columns and rows as tools/map_extract.py computes them (Web
 * Mercator, split at the antimeridian when west is past east), and, when the area has a polygon, only the tiles that
 * touch it. The polygon is one ring; across the antimeridian its points east of it may use either longitude.
 */
class AreaTiles(val area: MapArea) {
    private val west: Double
    private val south: Double
    private val east: Double
    private val north: Double
    private val wraps: Boolean

    // the ring with longitudes past the antimeridian moved up by 360, so that it is continuous
    private val lons: DoubleArray
    private val lats: DoubleArray
    private val zooms = arrayOfNulls<ZoomRows>(TileId.MAX_ZOOM + 1)

    init {
        require(area.bbox.size == 4) { "bbox is west, south, east, north" }
        west = area.bbox[0]
        south = area.bbox[1]
        east = area.bbox[2]
        north = area.bbox[3]
        require(west in -180.0..180.0 && east in -180.0..180.0) { "longitudes $west, $east" }
        require(south in -90.0..90.0 && north in -90.0..90.0 && south <= north) { "latitudes $south, $north" }
        require(area.minzoom in 0..area.maxzoom && area.maxzoom <= TileId.MAX_ZOOM) { "zooms ${area.minzoom}..${area.maxzoom}" }
        require(area.polygon.isEmpty() || area.polygon.size >= 3) { "a polygon needs three points" }
        wraps = west > east
        // when the bbox wraps, the middle of the longitudes it leaves out splits west of the line from east of it
        val split = (west + east) / 2
        lons = DoubleArray(area.polygon.size) {
            val lon = area.polygon[it].lon
            if (wraps && lon < split) lon + 360 else lon
        }
        lats = DoubleArray(area.polygon.size) { area.polygon[it].lat }
        require(lons.all { it.isFinite() } && lats.all { it.isFinite() }) { "polygon point not a number" }
    }

    fun count(z: Int): Long = rows(z).count

    fun count(minZoom: Int = area.minzoom, maxZoom: Int = area.maxzoom): Long {
        var total = 0L
        for (z in minZoom..maxZoom) total += count(z)
        return total
    }

    /** The area's tile ids from [minZoom] to [maxZoom], ascending. */
    fun ids(minZoom: Int = area.minzoom, maxZoom: Int = area.maxzoom): LongArray {
        val total = count(minZoom, maxZoom)
        require(total <= MAX_TILES) { "$total tiles in one area" }
        val ids = LongArray(total.toInt())
        var n = 0
        for (z in minZoom..maxZoom) {
            val start = n
            val rows = rows(z)
            for (y in rows.y0..rows.y1) {
                for (r in rows.first(y) until rows.end(y)) {
                    for (x in rows.x0(r)..rows.x1(r)) ids[n++] = TileId.hilbert(z, x, y)
                }
            }
            ids.sort(start, n)
        }
        return ids
    }

    /** Whether the tile [tileId] is one of the area's, its zoom included. */
    fun contains(tileId: Long): Boolean {
        if (tileId < 0 || tileId > TileId.LAST) return false
        val z = TileId.zoom(tileId)
        if (z < area.minzoom || z > area.maxzoom) return false
        val xy = TileId.xy(tileId, z)
        return rows(z).contains((xy ushr 32).toInt(), xy.toInt())
    }

    internal fun rows(z: Int): ZoomRows {
        require(z in 0..TileId.MAX_ZOOM) { "zoom $z" }
        zooms[z]?.let { return it }
        val last = ((1L shl z) - 1).toInt()
        val xw = tileX(west, z)
        val xe = tileX(east, z)
        val columns = when {
            !wraps -> intArrayOf(xw, xe)
            xw <= xe + 1 -> intArrayOf(0, last)
            else -> intArrayOf(0, xe, xw, last)
        }
        val y0 = tileY(north, z)
        val y1 = tileY(south, z)
        val rows = if (lons.isEmpty()) ZoomRows(y0, y1, null, columns) else polygonRows(z, y0, y1, columns)
        zooms[z] = rows
        return rows
    }

    private fun polygonRows(z: Int, y0: Int, y1: Int, columns: IntArray): ZoomRows {
        val n = 1L shl z
        val starts = IntArray(y1 - y0 + 2)
        val ranges = IntList()
        val crossings = DoubleList()
        val from = IntList()
        val to = IntList()
        for (y in y0..y1) {
            starts[y - y0] = ranges.size / 2
            val top = rowLatitude(y.toLong(), n)
            val bottom = rowLatitude(y + 1L, n)
            from.clear()
            to.clear()
            // the polygon's part within the row projects onto the edges inside the row and the parts of its two
            // boundary lines inside the polygon
            for (i in lons.indices) {
                val j = if (i + 1 == lons.size) 0 else i + 1
                clipEdge(lons[i], lats[i], lons[j], lats[j], bottom, top, z, from, to)
            }
            insideOnLine(top, z, crossings, from, to)
            insideOnLine(bottom, z, crossings, from, to)
            unionInto(from, to, columns, ranges)
        }
        starts[y1 - y0 + 1] = ranges.size / 2
        return ZoomRows(y0, y1, starts, ranges.toArray())
    }

    /** Adds the columns of the edge's part between latitudes [bottom] and [top]. */
    private fun clipEdge(
        lon1: Double, lat1: Double, lon2: Double, lat2: Double, bottom: Double, top: Double, z: Int, from: IntList, to: IntList,
    ) {
        if (max(lat1, lat2) < bottom || min(lat1, lat2) > top) return
        if (lat1 == lat2) {
            addColumns(min(lon1, lon2), max(lon1, lon2), z, from, to)
            return
        }
        val tb = (bottom - lat1) / (lat2 - lat1)
        val tt = (top - lat1) / (lat2 - lat1)
        val t0 = max(0.0, min(tb, tt))
        val t1 = min(1.0, max(tb, tt))
        if (t0 > t1) return
        val a = lon1 + t0 * (lon2 - lon1)
        val b = lon1 + t1 * (lon2 - lon1)
        addColumns(min(a, b), max(a, b), z, from, to)
    }

    /** Adds the columns of the stretches of latitude [lat] inside the polygon (even-odd rule). */
    private fun insideOnLine(lat: Double, z: Int, crossings: DoubleList, from: IntList, to: IntList) {
        crossings.clear()
        for (i in lons.indices) {
            val j = if (i + 1 == lons.size) 0 else i + 1
            val lat1 = lats[i]
            val lat2 = lats[j]
            if ((lat1 <= lat && lat < lat2) || (lat2 <= lat && lat < lat1)) {
                crossings.add(lons[i] + (lat - lat1) / (lat2 - lat1) * (lons[j] - lons[i]))
            }
        }
        crossings.sort()
        var k = 0
        while (k + 1 < crossings.size) {
            addColumns(crossings[k], crossings[k + 1], z, from, to)
            k += 2
        }
    }

    private fun addColumns(lon0: Double, lon1: Double, z: Int, from: IntList, to: IntList) {
        if (!wraps) {
            from.add(tileX(lon0, z))
            to.add(tileX(lon1, z))
            return
        }
        if (lon0 <= 180) {
            from.add(tileX(lon0, z))
            to.add(tileX(min(lon1, 180.0), z))
        }
        if (lon1 > 180) {
            from.add(tileX(max(lon0, 180.0) - 360, z))
            to.add(tileX(lon1 - 360, z))
        }
    }

    /** Appends to [out] the union of the column ranges [from]..[to], within the bbox's [columns]. */
    private fun unionInto(from: IntList, to: IntList, columns: IntArray, out: IntList) {
        if (from.size == 0) return
        // sorting starts and ends apart still gives the union: a gap opens where the next start passes the ends so far
        from.array.sort(0, from.size)
        to.array.sort(0, to.size)
        var start = from[0]
        for (i in 1..from.size) {
            if (i == from.size || from[i] > to[i - 1] + 1) {
                val end = to[i - 1]
                var c = 0
                while (c < columns.size) {
                    val a = max(start, columns[c])
                    val b = min(end, columns[c + 1])
                    if (a <= b) {
                        out.add(a)
                        out.add(b)
                    }
                    c += 2
                }
                if (i < from.size) start = from[i]
            }
        }
    }

    private companion object {
        const val MAX_TILES = Int.MAX_VALUE - 8L
    }
}

/**
 * The tiles of one zoom: rows [y0] to [y1], each with column ranges (pairs, inclusive, ascending) in [ranges] from
 * [starts] of that row; all rows have all of [ranges] when [starts] is null.
 */
internal class ZoomRows(val y0: Int, val y1: Int, private val starts: IntArray?, private val ranges: IntArray) {
    val count: Long

    init {
        var total = 0L
        for (y in y0..y1) for (r in first(y) until end(y)) total += x1(r) - x0(r) + 1L
        count = total
    }

    fun first(y: Int): Int = if (starts == null) 0 else starts[y - y0]

    fun end(y: Int): Int = if (starts == null) ranges.size / 2 else starts[y - y0 + 1]

    fun x0(r: Int): Int = ranges[2 * r]

    fun x1(r: Int): Int = ranges[2 * r + 1]

    fun contains(x: Int, y: Int): Boolean {
        if (y < y0 || y > y1) return false
        for (r in first(y) until end(y)) {
            if (x < x0(r)) return false
            if (x <= x1(r)) return true
        }
        return false
    }
}
