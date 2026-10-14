package ro.safetyplease.core.map.pmtiles

import ro.safetyplease.core.map.MapArea
import ro.safetyplease.core.venue.GeoPoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An area's tiles: the same columns and rows as tools/map_extract.py, the antimeridian, Web Mercator's limit, polygons. */
class AreaTilesTest {
    @Test
    fun theShippedBboxHasTheShippedMapsTiles() {
        val tiles = AreaTiles(COSTINESTI)
        assertEquals(listOf(1L, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 12, 35, 117), (0..15).map { tiles.count(it) })
        assertEquals(183L, tiles.count())
        val ids = tiles.ids()
        assertEquals(183, ids.size)
        assertContentEquals(ids.sortedArray(), ids)
        assertEquals(183, ids.toSet().size)
        assertTrue(ids.all { tiles.contains(it) })
        // the map's own corner tiles, as tools/map_extract.py lists them
        assertTrue(tiles.contains(TileId.of(15, tileX(28.59, 15), tileY(44.01, 15))))
        assertTrue(tiles.contains(TileId.of(15, tileX(28.675, 15), tileY(43.92, 15))))
        assertFalse(tiles.contains(TileId.of(15, tileX(28.59, 15) - 1, tileY(44.01, 15))))
        assertFalse(tiles.contains(TileId.of(16, tileX(28.6, 16), tileY(44.0, 16))))
    }

    @Test
    fun anAreaAcrossTheAntimeridianHasBothSides() {
        val across = AreaTiles(MapArea(listOf(170.0, -10.0, -170.0, 10.0), 0, 10, "Fiji"))
        val east = AreaTiles(MapArea(listOf(170.0, -10.0, 180.0, 10.0), 0, 10, "east"))
        val west = AreaTiles(MapArea(listOf(-180.0, -10.0, -170.0, 10.0), 0, 10, "west"))
        assertEquals((east.ids().toSet() + west.ids().toSet()).sorted(), across.ids().toList())
        assertEquals(1L, across.count(0))
        assertEquals(4L, across.count(1))
        // zoom 6: columns 62–63 and 0–1, rows 30–33
        assertEquals(16L, across.count(6))
        assertTrue(across.contains(TileId.of(6, 0, 31)) && across.contains(TileId.of(6, 63, 31)))
        assertFalse(across.contains(TileId.of(6, 32, 31)))
    }

    @Test
    fun latitudesStopAtWebMercatorsLimit() {
        val world = AreaTiles(MapArea(listOf(-180.0, -90.0, 180.0, 90.0), 0, 4, "world"))
        assertEquals(1L + 4 + 16 + 64 + 256, world.count())
        val pole = AreaTiles(MapArea(listOf(-10.0, 86.0, 10.0, 90.0), 3, 3, "pole"))
        assertEquals(setOf(0), pole.ids().map { TileId.tile(it).y }.toSet())
    }

    @Test
    fun aPolygonKeepsTheTilesThatTouchIt() {
        val ring = listOf(
            GeoPoint(44.05, 28.12), GeoPoint(44.43, 28.71), GeoPoint(44.98, 29.31), GeoPoint(45.21, 28.83),
            GeoPoint(44.73, 28.41), GeoPoint(44.62, 27.93), GeoPoint(44.35, 28.29),
        )
        val bbox = listOf(27.9, 44.0, 29.4, 45.3)
        val tiles = AreaTiles(MapArea(bbox, 0, 13, "Dobrogea", ring))
        val box = AreaTiles(MapArea(bbox, 0, 13, "Dobrogea"))
        for (z in 0..13) {
            val expected = box.ids(z, z).filter { touches(TileId.tile(it), ring) }
            assertEquals(expected, tiles.ids(z, z).toList(), "zoom $z")
            assertTrue(expected.size < box.count(z) || z < 10, "zoom $z keeps ${expected.size} of ${box.count(z)}")
        }
        assertTrue(box.ids().all { tiles.contains(it) == touches(TileId.tile(it), ring) })
    }

    @Test
    fun aPolygonAroundTheWholeBboxKeepsItAll() {
        val ring = listOf(GeoPoint(43.0, 27.0), GeoPoint(43.0, 30.0), GeoPoint(46.0, 30.0), GeoPoint(46.0, 27.0))
        val bbox = listOf(27.9, 44.0, 29.4, 45.3)
        assertContentEquals(AreaTiles(MapArea(bbox, 0, 12, "box")).ids(), AreaTiles(MapArea(bbox, 0, 12, "box", ring)).ids())
    }

    @Test
    fun aPolygonAcrossTheAntimeridianMayGiveEitherLongitude() {
        val bbox = listOf(170.0, -10.0, -170.0, 10.0)
        val raw = listOf(GeoPoint(-11.0, 169.0), GeoPoint(-11.0, -169.0), GeoPoint(11.0, -169.0), GeoPoint(11.0, 169.0))
        val unwrapped = listOf(GeoPoint(-11.0, 169.0), GeoPoint(-11.0, 191.0), GeoPoint(11.0, 191.0), GeoPoint(11.0, 169.0))
        val all = AreaTiles(MapArea(bbox, 0, 9, "box")).ids()
        assertContentEquals(all, AreaTiles(MapArea(bbox, 0, 9, "raw", raw)).ids())
        assertContentEquals(all, AreaTiles(MapArea(bbox, 0, 9, "unwrapped", unwrapped)).ids())
        // a triangle east of the line only: no columns west of it
        val east = listOf(GeoPoint(-5.0, -178.0), GeoPoint(-5.0, -172.0), GeoPoint(5.0, -175.0))
        val columns = AreaTiles(MapArea(bbox, 9, 9, "east", east)).ids().map { TileId.tile(it).x }
        assertTrue(columns.isNotEmpty() && columns.all { it < 256 }, "$columns")
    }

    @Test
    fun badAreasAreRefused() {
        fun area(bbox: List<Double>, min: Int = 0, max: Int = 3, ring: List<GeoPoint> = emptyList()) =
            AreaTiles(MapArea(bbox, min, max, "x", ring))
        assertFailsWith<IllegalArgumentException> { area(listOf(1.0, 2.0, 3.0)) }
        assertFailsWith<IllegalArgumentException> { area(listOf(1.0, 5.0, 3.0, 2.0)) }
        assertFailsWith<IllegalArgumentException> { area(listOf(-181.0, 2.0, 3.0, 4.0)) }
        assertFailsWith<IllegalArgumentException> { area(listOf(1.0, Double.NaN, 3.0, 4.0)) }
        assertFailsWith<IllegalArgumentException> { area(listOf(1.0, 2.0, 3.0, 4.0), min = 5) }
        assertFailsWith<IllegalArgumentException> { area(listOf(1.0, 2.0, 3.0, 4.0), max = 32) }
        val line = listOf(GeoPoint(2.0, 1.0), GeoPoint(3.0, 2.0))
        assertFailsWith<IllegalArgumentException> { area(listOf(1.0, 2.0, 3.0, 4.0), ring = line) }
    }

    /** Whether the tile's rectangle and the polygon share a point, by brute force. */
    private fun touches(tile: Tile, ring: List<GeoPoint>): Boolean {
        val n = 1L shl tile.z
        val w = tile.x * 360.0 / n - 180
        val e = (tile.x + 1) * 360.0 / n - 180
        val north = rowLatitude(tile.y.toLong(), n)
        val south = rowLatitude(tile.y + 1L, n)
        if (ring.any { it.lon in w..e && it.lat in south..north }) return true
        if (inside(w, south, ring) || inside(e, north, ring)) return true
        val corners = listOf(w to south, e to south, e to north, w to north)
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            for (k in 0 until 4) {
                val c = corners[k]
                val d = corners[(k + 1) % 4]
                if (crosses(a.lon, a.lat, b.lon, b.lat, c.first, c.second, d.first, d.second)) return true
            }
        }
        return false
    }

    private fun inside(lon: Double, lat: Double, ring: List<GeoPoint>): Boolean {
        var inside = false
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            if ((a.lat <= lat && lat < b.lat) || (b.lat <= lat && lat < a.lat)) {
                if (lon < a.lon + (lat - a.lat) / (b.lat - a.lat) * (b.lon - a.lon)) inside = !inside
            }
        }
        return inside
    }

    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun side(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = side(cx, cy, dx, dy, ax, ay)
        val d2 = side(cx, cy, dx, dy, bx, by)
        val d3 = side(ax, ay, bx, by, cx, cy)
        val d4 = side(ax, ay, bx, by, dx, dy)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }
}
