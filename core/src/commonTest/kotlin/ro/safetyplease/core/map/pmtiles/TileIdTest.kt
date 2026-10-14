package ro.safetyplease.core.map.pmtiles

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tile ids follow the PMTiles v3 spec: its test vectors, both ways, at every zoom up to 31. */
class TileIdTest {
    @Test
    fun specVectors() {
        val vectors = listOf(
            Tile(0, 0, 0) to 0L,
            Tile(1, 0, 0) to 1L,
            Tile(1, 0, 1) to 2L,
            Tile(1, 1, 1) to 3L,
            Tile(1, 1, 0) to 4L,
            Tile(2, 0, 0) to 5L,
            Tile(12, 3423, 1763) to 19_078_479L,
        )
        for ((tile, id) in vectors) {
            assertEquals(id, TileId.of(tile), "$tile")
            assertEquals(tile, TileId.tile(id), "$id")
        }
    }

    @Test
    fun eachZoomStartsAfterAllTheTilesAbove() {
        var first = 0L
        for (z in 0..TileId.MAX_ZOOM) {
            assertEquals(first, TileId.first(z))
            assertEquals(first, TileId.of(z, 0, 0))
            assertEquals(z, TileId.zoom(first))
            if (z > 0) assertEquals(z - 1, TileId.zoom(first - 1))
            first += 1L shl (2 * z)
        }
    }

    @Test
    fun cornersAndRandomTilesRoundTrip() {
        val random = Random(7)
        for (z in 0..TileId.MAX_ZOOM) {
            val last = ((1L shl z) - 1).toInt()
            val tiles = listOf(Tile(z, 0, 0), Tile(z, last, 0), Tile(z, 0, last), Tile(z, last, last)) +
                List(50) { Tile(z, random.nextLong(last + 1L).toInt(), random.nextLong(last + 1L).toInt()) }
            for (tile in tiles) assertEquals(tile, TileId.tile(TileId.of(tile)))
        }
    }

    @Test
    fun aZoomsIdsAreAllItsTilesOnce() {
        for (z in 0..5) {
            val n = 1 shl z
            val ids = buildSet { for (x in 0 until n) for (y in 0 until n) add(TileId.of(z, x, y)) }
            assertEquals((TileId.first(z) until TileId.first(z) + n * n).toSet(), ids)
        }
    }

    @Test
    fun neighboursAlongTheCurveAreNeighboursOnTheMap() {
        val z = 8
        var previous = TileId.tile(TileId.first(z))
        for (id in TileId.first(z) + 1 until TileId.first(z + 1)) {
            val tile = TileId.tile(id)
            assertEquals(1, kotlin.math.abs(tile.x - previous.x) + kotlin.math.abs(tile.y - previous.y), "$previous → $tile")
            previous = tile
        }
    }

    @Test
    fun tilesOutsideTheirZoomAreRefused() {
        assertFailsWith<IllegalArgumentException> { TileId.of(1, 2, 0) }
        assertFailsWith<IllegalArgumentException> { TileId.of(3, 0, -1) }
        assertFailsWith<IllegalArgumentException> { TileId.of(32, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TileId.tile(-1) }
        assertFailsWith<IllegalArgumentException> { TileId.zoom(TileId.LAST + 1) }
        assertEquals(TileId.MAX_ZOOM, TileId.zoom(TileId.LAST))
        assertEquals(TileId.LAST, TileId.of(TileId.MAX_ZOOM, Int.MAX_VALUE, 0))
    }
}
