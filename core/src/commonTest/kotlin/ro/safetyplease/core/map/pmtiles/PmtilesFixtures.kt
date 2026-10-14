package ro.safetyplease.core.map.pmtiles

import okio.FileSystem
import okio.Path
import okio.SYSTEM
import ro.safetyplease.core.map.MapArea
import ro.safetyplease.core.repoFile

internal val fs = FileSystem.SYSTEM

/** The festival map the app ships, cut by tools/map_extract.py: 183 tiles, zoom 0 to 15. */
internal val SHIPPED: Path = repoFile("app/src/main/assets/maps/20261013/costinesti.pmtiles")

/** The shipped map's bbox, from its manifest. */
internal val COSTINESTI = MapArea(listOf(28.59, 43.92, 28.675, 44.01), 0, 15, "Costinești")

/** 100,313 tiles from zoom 0 to 15: Dobrogea and the east of Muntenia, for [syntheticArchive]. */
internal val SYNTHETIC = MapArea(listOf(26.5, 43.6, 29.7, 45.6), 0, 15, "synthetic")

/** Counts the reads of another source. */
internal class CountingSource(private val inner: RangeSource) : RangeSource {
    override val size: Long get() = inner.size
    val reads = mutableListOf<Pair<Long, Int>>()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        reads += offset to length
        return inner.read(offset, length)
    }
}

/** Bytes made up from their offset, different almost everywhere: stand-ins for tiles. */
internal class PatternSource(override val size: Long) : RangeSource {
    override suspend fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length)
        return ByteArray(length) { pattern(offset + it) }
    }
}

internal fun pattern(at: Long): Byte = ((at * 2654435761L) ushr 11).toByte()

/** Length of the one tile a synthetic archive repeats, as real ones repeat sea and land fills. */
internal const val SEA = 37

/**
 * Writes to [path] an archive with every tile of [area]: tiles in blocks of four ids out of every 24 are all the same
 * "sea" bytes (runs, and references back to earlier bytes), the others distinct, 40 to 136 bytes long.
 */
internal suspend fun syntheticArchive(path: Path, area: MapArea): WrittenArchive {
    val ids = AreaTiles(area).ids()
    val entries = DirectoryBuilder(ids.size)
    var next = SEA.toLong()
    for (id in ids) {
        if ((id / 4) % 6 == 0L) {
            if (!entries.extend(id, 0, SEA)) entries.add(id, 1, 0, SEA)
        } else {
            val length = 40 + (id % 97).toInt()
            entries.add(id, 1, next, length)
            next += length
        }
    }
    val template = areaHeader(area, area.minzoom, area.maxzoom, TileType.MVT, Compression.NONE)
    return PmtilesWriter(fs).write(path, template, entries.build(), PatternSource(next), """{"name":"synthetic"}""")
}

/** Every tile entry of an archive, runs spread out: tile id to (offset, length). */
internal suspend fun PmtilesReader.allTiles(): Map<Long, Pair<Long, Int>> {
    val tiles = LinkedHashMap<Long, Pair<Long, Int>>()
    forEachEntry { tileId, runLength, offset, length ->
        for (k in 0 until runLength) tiles[tileId + k] = offset to length
    }
    return tiles
}

/** A directory's tiles, runs spread out: tile id to (offset, length). */
internal fun Directory.allTiles(): Map<Long, Pair<Long, Int>> {
    val tiles = LinkedHashMap<Long, Pair<Long, Int>>()
    for (i in 0 until size) for (k in 0 until runLength(i)) tiles[tileId(i) + k] = offset(i) to length(i)
    return tiles
}
