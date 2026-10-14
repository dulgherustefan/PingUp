package ro.safetyplease.core.map.pmtiles

/** A map tile: zoom [z], column [x] from the west, row [y] from the north. */
data class Tile(val z: Int, val x: Int, val y: Int)

/**
 * PMTiles tile ids: all the tiles of zoom 0, then of zoom 1 and so on, each zoom's tiles in the order of a Hilbert
 * curve, so tiles near each other on the map get ids near each other.
 */
object TileId {
    /** The deepest zoom whose ids fit 64 bits. */
    const val MAX_ZOOM = 31

    /** The id of the last tile of [MAX_ZOOM]. */
    internal const val LAST = ((1L shl (2 * MAX_ZOOM)) - 1) / 3 + (1L shl (2 * MAX_ZOOM)) - 1

    fun of(z: Int, x: Int, y: Int): Long {
        require(z in 0..MAX_ZOOM) { "zoom $z" }
        val last = (1L shl z) - 1
        require(x in 0..last && y in 0..last) { "tile $z/$x/$y outside its zoom" }
        return hilbert(z, x, y)
    }

    fun of(tile: Tile): Long = of(tile.z, tile.x, tile.y)

    /** The first id of zoom [z]: the number of tiles in all the zooms above it. */
    fun first(z: Int): Long {
        require(z in 0..MAX_ZOOM) { "zoom $z" }
        return ((1L shl (2 * z)) - 1) / 3
    }

    fun zoom(id: Long): Int {
        require(id >= 0) { "tile id $id" }
        var first = 0L
        for (z in 0..MAX_ZOOM) {
            val next = first + (1L shl (2 * z))
            if (id < next) return z
            first = next
        }
        throw IllegalArgumentException("tile id $id past zoom $MAX_ZOOM")
    }

    fun tile(id: Long): Tile {
        val z = zoom(id)
        val xy = xy(id, z)
        return Tile(z, (xy ushr 32).toInt(), xy.toInt())
    }

    /** No range checks: [x] and [y] must be within zoom [z]. */
    internal fun hilbert(z: Int, x: Int, y: Int): Long {
        var acc = ((1L shl (2 * z)) - 1) / 3
        var tx = x.toLong()
        var ty = y.toLong()
        var s = (1L shl z) shr 1
        while (s > 0) {
            val rx = if (tx and s != 0L) 1L else 0L
            val ry = if (ty and s != 0L) 1L else 0L
            acc += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                // only the bits below s matter from here on, so the bits this leaves above them are harmless
                if (rx == 1L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val t = tx
                tx = ty
                ty = t
            }
            s = s shr 1
        }
        return acc
    }

    /** x in the high 32 bits, y in the low ones, of the tile [id] at zoom [z]. */
    internal fun xy(id: Long, z: Int): Long {
        val n = 1L shl z
        var t = id - ((1L shl (2 * z)) - 1) / 3
        var x = 0L
        var y = 0L
        var s = 1L
        while (s < n) {
            val rx = (t shr 1) and 1L
            val ry = (t xor rx) and 1L
            if (ry == 0L) {
                if (rx == 1L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x
                x = y
                y = tmp
            }
            x += s * rx
            y += s * ry
            t = t shr 2
            s = s shl 1
        }
        return (x shl 32) or y
    }
}
