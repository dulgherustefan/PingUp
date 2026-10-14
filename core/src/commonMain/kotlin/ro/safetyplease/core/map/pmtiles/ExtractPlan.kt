package ro.safetyplease.core.map.pmtiles

import ro.safetyplease.core.map.MapArea
import ro.safetyplease.core.map.MapEstimate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tiles an installed pack already holds; a plan leaves them out, which keeps packs disjoint. */
fun interface TileFilter {
    fun holds(tileId: Long): Boolean
}

/**
 * What cutting [area] out of a source archive takes. [entries] are the tiles to fetch by tile id, runs merged, with
 * offsets from the start of the source file; [spans] are the byte ranges that hold them. [tiles] counts tile ids,
 * [contents] the distinct tile bytes among them and [bytes] their size, each counted once however many tiles share it.
 * [heldTiles] and [heldBytes] are what the area has that installed packs already hold, left out.
 */
class ExtractPlan internal constructor(
    val area: MapArea,
    val source: PmtilesHeader,
    val minZoom: Int,
    val maxZoom: Int,
    val entries: Directory,
    val spans: Spans,
    val tiles: Long,
    val contents: Int,
    val bytes: Long,
    val heldTiles: Long,
    val heldBytes: Long,
) {
    /** What the spans fetch: [bytes] and the small gaps they bridge. */
    val fetchBytes: Long get() = spans.bytes

    fun estimate(): MapEstimate = MapEstimate(tiles, bytes, heldBytes)

    /** The header of the pack this plan writes, its sections left to the writer (see [areaHeader]). */
    fun packHeader(): PmtilesHeader = areaHeader(area, minZoom, maxZoom, source.tileType, source.tileCompression)
}

/**
 * A header for tiles of [area] from [minZoom] to [maxZoom], its sections left to the writer: bounds and centre from
 * the area's bbox (all longitudes when it crosses the antimeridian), centre zoom [minZoom].
 */
fun areaHeader(area: MapArea, minZoom: Int, maxZoom: Int, tileType: Int, tileCompression: Int): PmtilesHeader {
    require(area.bbox.size == 4) { "bbox is west, south, east, north" }
    val west = area.bbox[0]
    val east = area.bbox[2]
    val wraps = west > east
    val minLon = if (wraps) -180.0 else west
    val maxLon = if (wraps) 180.0 else east
    val minLat = area.bbox[1].coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    val maxLat = area.bbox[3].coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    var centerLon = if (wraps) (west + east + 360) / 2 else (west + east) / 2
    if (centerLon > 180) centerLon -= 360
    return PmtilesHeader(
        rootOffset = 0, rootLength = 0, metadataOffset = 0, metadataLength = 0,
        leafDirectoryOffset = 0, leafDirectoryLength = 0, tileDataOffset = 0, tileDataLength = 0,
        addressedTiles = 0, tileEntries = 0, tileContents = 0, clustered = true,
        internalCompression = Compression.GZIP,
        tileCompression = tileCompression,
        tileType = tileType,
        minZoom = minZoom,
        maxZoom = maxZoom,
        minLonE7 = floor(minLon * 1e7).toInt(),
        minLatE7 = floor(minLat * 1e7).toInt(),
        maxLonE7 = ceil(maxLon * 1e7).toInt(),
        maxLatE7 = ceil(maxLat * 1e7).toInt(),
        centerZoom = minZoom,
        centerLonE7 = (centerLon * 1e7).roundToInt(),
        centerLatE7 = ((minLat + maxLat) / 2 * 1e7).roundToInt(),
    )
}

/**
 * Plans cutting [area] out of this archive: its tiles from [MapArea.minzoom] to [MapArea.maxzoom] (within the
 * archive's zooms), sorted by id and looked up in the directories, reading only the leaf directories they fall in,
 * consecutive ones in one read. Tiles [held] by installed packs are left out. The byte ranges to fetch are merged
 * across gaps of at most [maxGap] bytes into requests of at most [maxSpan] bytes.
 */
suspend fun PmtilesReader.plan(
    area: MapArea,
    held: TileFilter? = null,
    maxGap: Long = Spans.MAX_GAP,
    maxSpan: Long = Spans.MAX_SPAN,
): ExtractPlan {
    require(maxGap >= 0 && maxSpan > 0)
    val tiles = AreaTiles(area)
    val minZoom = max(area.minzoom, header.minZoom)
    val maxZoom = min(area.maxzoom, header.maxZoom)
    val ids = if (minZoom <= maxZoom) tiles.ids(minZoom, maxZoom) else LongArray(0)
    val sweep = Sweep(this, ids, held, maxSpan)
    if (ids.isNotEmpty()) sweep.directory(root, 0, ids.size, 0)
    val entries = sweep.entries.build()
    val contents = distinctRanges(entries)
    val heldRanges = sweep.heldRanges()
    return ExtractPlan(
        area = area,
        source = header,
        minZoom = if (minZoom <= maxZoom) minZoom else area.minzoom,
        maxZoom = if (minZoom <= maxZoom) maxZoom else area.minzoom,
        entries = entries,
        spans = coalesce(contents.offsets, contents.lengths, contents.size, maxGap, maxSpan),
        tiles = sweep.tiles,
        contents = contents.size,
        bytes = contents.bytes,
        heldTiles = sweep.heldTiles,
        heldBytes = heldRanges.bytes,
    )
}

/** Byte ranges sorted by offset, each offset once. */
internal class Ranges(val offsets: LongArray, val lengths: IntArray, val size: Int) {
    val bytes: Long

    init {
        var total = 0L
        for (i in 0 until size) total += lengths[i]
        bytes = total
    }
}

/** The distinct (offset, length) of [count] ranges, sorted by offset. */
internal fun distinctRanges(offsets: LongArray, lengths: IntArray, count: Int): Ranges {
    val sorted = offsets.copyOf(count)
    val size = sortUnique(sorted, count)
    val sizes = IntArray(size)
    for (i in 0 until count) {
        val k = indexIn(sorted, size, offsets[i])
        sizes[k] = max(sizes[k], lengths[i])
    }
    return Ranges(sorted, sizes, size)
}

internal fun distinctRanges(entries: Directory): Ranges {
    val n = entries.size
    val sorted = LongArray(n) { entries.offset(it) }
    val size = sortUnique(sorted, n)
    val sizes = IntArray(size)
    for (i in 0 until n) {
        val k = indexIn(sorted, size, entries.offset(i))
        sizes[k] = max(sizes[k], entries.length(i))
    }
    return Ranges(sorted, sizes, size)
}

/** Sorts the first [count] values and moves each distinct one to the front; gives how many there are. */
internal fun sortUnique(values: LongArray, count: Int): Int {
    values.sort(0, count)
    var size = 0
    for (i in 0 until count) if (size == 0 || values[i] != values[size - 1]) values[size++] = values[i]
    return size
}

/** Walks the sorted [ids] through the directories, collecting what the plan fetches. */
private class Sweep(
    private val reader: PmtilesReader,
    private val ids: LongArray,
    private val held: TileFilter?,
    private val maxSpan: Long,
) {
    val entries = DirectoryBuilder(1024)
    var tiles = 0L
    var heldTiles = 0L
    private val heldOffsets = LongList()
    private val heldLengths = IntList()
    private val dataStart = reader.header.tileDataOffset
    private val leafStart = reader.header.leafDirectoryOffset

    /** The ids from [from] until [to], all at or past the directory's first entry or absent from it. */
    suspend fun directory(directory: Directory, from: Int, to: Int, depth: Int) {
        if (depth > PmtilesReader.MAX_DEPTH) throw PmtilesException("leaf directories nested deeper than ${PmtilesReader.MAX_DEPTH}")
        var leaves: LeafReads? = null
        var e = directory.floor(ids[from])
        var i = from
        while (i < to) {
            val id = ids[i]
            while (e + 1 < directory.size && directory.tileId(e + 1) <= id) e++
            if (e < 0) {
                i++
                continue
            }
            if (directory.isLeaf(e)) {
                val next = if (e + 1 < directory.size) directory.tileId(e + 1) else Long.MAX_VALUE
                var j = i + 1
                while (j < to && ids[j] < next) j++
                val offset = directory.offset(e)
                val length = directory.length(e)
                val waiting = leaves ?: LeafReads().also { leaves = it }
                if (!waiting.fits(offset, length, maxSpan)) read(waiting, depth)
                waiting.add(offset, length, i, j)
                i = j
            } else {
                read(leaves, depth)
                if (id - directory.tileId(e) < directory.runLength(e)) tile(id, dataStart + directory.offset(e), directory.length(e))
                i++
            }
        }
        read(leaves, depth)
    }

    /** Reads the leaves waiting in [leaves] at once and walks their ids. */
    private suspend fun read(leaves: LeafReads?, depth: Int) {
        if (leaves == null || leaves.size == 0) return
        val bytes = reader.source.read(leafStart + leaves.start, (leaves.end - leaves.start).toIntOrThrow("leaf read"))
        for (k in 0 until leaves.size) {
            val at = (leaves.offsets[k] - leaves.start).toInt()
            val leaf = decodeDirectory(bytes, at, leaves.lengths[k], reader.header.internalCompression)
            directory(leaf, leaves.froms[k], leaves.tos[k], depth + 1)
        }
        leaves.clear()
    }

    private fun tile(id: Long, offset: Long, length: Int) {
        if (length == 0) return
        if (held != null && held.holds(id)) {
            heldTiles++
            heldOffsets.add(offset)
            heldLengths.add(length)
            return
        }
        tiles++
        if (!entries.extend(id, offset, length)) entries.add(id, 1, offset, length)
    }

    fun heldRanges(): Ranges = distinctRanges(heldOffsets.array, heldLengths.array, heldOffsets.size)
}

/**
 * Leaf directories the ids fall in, waiting to be read in one request with the ids each covers: only ones that follow
 * each other in the archive, so no leaf outside the area is fetched.
 */
private class LeafReads {
    val offsets = LongList()
    val lengths = IntList()
    val froms = IntList()
    val tos = IntList()
    var start = 0L
    var end = 0L
    val size: Int get() = offsets.size

    fun fits(offset: Long, length: Int, maxSpan: Long): Boolean =
        size == 0 || (offset == end && offset + length - start <= maxSpan)

    fun add(offset: Long, length: Int, from: Int, to: Int) {
        if (size == 0) start = offset
        offsets.add(offset)
        lengths.add(length)
        froms.add(from)
        tos.add(to)
        end = offset + length
    }

    fun clear() {
        offsets.clear()
        lengths.clear()
        froms.clear()
        tos.clear()
    }
}
