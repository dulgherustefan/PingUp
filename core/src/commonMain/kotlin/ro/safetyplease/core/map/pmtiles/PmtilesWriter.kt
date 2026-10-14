package ro.safetyplease.core.map.pmtiles

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.BufferedSink
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use
import ro.safetyplease.core.data.withSuffix

/** A written archive: its [path], length in [bytes], [sha256] (hex) of those bytes and [header]. */
data class WrittenArchive(val path: Path, val bytes: Long, val sha256: String, val header: PmtilesHeader)

/**
 * Writes PMTiles v3 archives, clustered: entries sorted by tile id with their runs, each distinct tile content once,
 * in the order the ids first use it. Directories and metadata are compressed with [internalCompression]; the root
 * directory stays within the first 16 KiB, and when all the entries don't fit there they go to leaf directories, as
 * few entries per leaf as lets the root fit.
 */
class PmtilesWriter(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val internalCompression: Int = Compression.GZIP,
) {
    init {
        require(internalCompression == Compression.NONE || internalCompression == Compression.GZIP) {
            "compression $internalCompression"
        }
    }

    /**
     * Writes the [plan]'s tiles, read from [data] by their offsets in the source archive, as a pack of the plan's area,
     * with the source's [sourceMetadata] minus the keys that would override the header's bounds and zooms.
     */
    suspend fun write(target: Path, plan: ExtractPlan, data: RangeSource, sourceMetadata: String): WrittenArchive =
        write(target, plan.packHeader(), plan.entries, data, packMetadata(sourceMetadata))

    /**
     * Writes [entries] (tile entries sorted by id, offsets into [data]) to [target]: first to a temporary file next to
     * it, hashed while written and synced, then renamed over [target]; then [target] is opened again and checked. Of
     * [template] only the tile type and compression, zooms, bounds and centre are used.
     */
    suspend fun write(
        target: Path,
        template: PmtilesHeader,
        entries: Directory,
        data: RangeSource,
        metadata: String,
    ): WrittenArchive {
        require(entries.size > 0) { "no tiles to write" }
        require(template.minZoom in 0..template.maxZoom && template.maxZoom <= TileId.MAX_ZOOM) {
            "zooms ${template.minZoom}..${template.maxZoom}"
        }
        val layout = Layout(entries)
        val tiles = entries.withOffsets(layout.offsets)
        val (root, leaves) = buildDirectories(tiles, internalCompression)
        val meta = deflate(metadata.encodeToByteArray(), internalCompression)
        val metadataOffset = PmtilesHeader.LENGTH.toLong() + root.size
        val leavesOffset = metadataOffset + meta.size
        val header = template.copy(
            rootOffset = PmtilesHeader.LENGTH.toLong(),
            rootLength = root.size.toLong(),
            metadataOffset = metadataOffset,
            metadataLength = meta.size.toLong(),
            leafDirectoryOffset = leavesOffset,
            leafDirectoryLength = leaves.size.toLong(),
            tileDataOffset = leavesOffset + leaves.size,
            tileDataLength = layout.dataLength,
            addressedTiles = layout.addressed,
            tileEntries = entries.size.toLong(),
            tileContents = layout.contents.toLong(),
            clustered = true,
            internalCompression = internalCompression,
        )
        target.parent?.let { fs.createDirectories(it) }
        val tmp = target.withSuffix(".tmp")
        val (hash, bytes) = try {
            fs.delete(tmp)
            val written = fs.openReadWrite(tmp, mustCreate = true).use { handle ->
                val hashing = HashingSink.sha256(handle.sink())
                val digest = hashing.buffer().use { sink ->
                    sink.write(header.encode())
                    sink.write(root)
                    sink.write(meta)
                    sink.write(leaves)
                    writeTiles(sink, entries, layout, data)
                    // down to the file handle: fsync on the JVM and Android, only fflush on iOS
                    sink.flush()
                    hashing.hash.hex()
                }
                digest to handle.size()
            }
            fs.atomicMove(tmp, target)
            written
        } catch (e: Throwable) {
            runCatching { fs.delete(tmp) }
            throw e
        }
        try {
            verify(target, header, tiles, entries, data)
        } catch (e: Throwable) {
            runCatching { fs.delete(target) }
            throw e
        }
        return WrittenArchive(target, bytes, hash, header)
    }

    /** Each content once, in the order of [layout]; neighbouring contents of the source are read together. */
    private suspend fun writeTiles(sink: BufferedSink, entries: Directory, layout: Layout, data: RangeSource) {
        var written = 0L
        var from = 0L
        var length = 0
        for (i in 0 until entries.size) {
            if (!layout.isFirst(i)) continue
            val offset = entries.offset(i)
            val size = entries.length(i)
            if (length > 0 && offset == from + length && length + size.toLong() <= READ_BYTES) {
                length += size
                continue
            }
            currentCoroutineContext().ensureActive()
            if (length > 0) sink.write(data.read(from, length))
            written += length
            from = offset
            length = size
        }
        if (length > 0) sink.write(data.read(from, length))
        written += length
        check(written == layout.dataLength) { "wrote $written of ${layout.dataLength} tile bytes" }
    }

    /** Opens [target] again: the same header and entries, and a few tiles with the bytes [data] has for them. */
    private suspend fun verify(target: Path, header: PmtilesHeader, tiles: Directory, entries: Directory, data: RangeSource) {
        FileRangeSource(fs, target).use { file ->
            val reader = PmtilesReader.open(file)
            if (reader.header != header) throw PmtilesException("$target: header reads back different")
            var k = 0
            reader.forEachEntry { tileId, runLength, offset, length ->
                if (k >= tiles.size || tileId != tiles.tileId(k) || runLength != tiles.runLength(k) ||
                    offset != header.tileDataOffset + tiles.offset(k) || length != tiles.length(k)
                ) {
                    throw PmtilesException("$target: entry $k reads back different")
                }
                k++
            }
            if (k != tiles.size) throw PmtilesException("$target: $k entries of ${tiles.size}")
            val n = tiles.size
            for (i in intArrayOf(0, n / 4, n / 2, 3 * n / 4, n - 1).distinct()) {
                val written = reader.tile(tiles.tileId(i)) ?: throw PmtilesException("$target: tile ${tiles.tileId(i)} missing")
                if (!written.contentEquals(data.read(entries.offset(i), entries.length(i)))) {
                    throw PmtilesException("$target: tile ${tiles.tileId(i)} reads back different")
                }
            }
        }
    }

    private companion object {
        /** Most bytes of neighbouring tiles read from the source at once. */
        const val READ_BYTES = 1024 * 1024
    }
}

/** Where each entry's bytes go in the tile data: distinct source offsets in the order the entries first use them. */
private class Layout(entries: Directory) {
    val offsets = LongArray(entries.size)
    private val firsts = LongArray((entries.size + 63) / 64)
    var dataLength = 0L
    var contents = 0
    var addressed = 0L

    init {
        val n = entries.size
        val sources = LongArray(n) { entries.offset(it) }
        val distinct = sortUnique(sources, n)
        val placed = LongArray(distinct)
        placed.fill(-1L)
        for (i in 0 until n) {
            val run = entries.runLength(i)
            require(run > 0) { "entry $i is a leaf directory, not tiles" }
            val k = indexIn(sources, distinct, entries.offset(i))
            if (placed[k] < 0) {
                placed[k] = dataLength
                dataLength += entries.length(i)
                contents++
                firsts[i ushr 6] = firsts[i ushr 6] or (1L shl i)
            }
            offsets[i] = placed[k]
            addressed += run
        }
    }

    /** Whether entry [i] is the first to use its bytes, which are written there. */
    fun isFirst(i: Int): Boolean = firsts[i ushr 6] and (1L shl i) != 0L
}

/** Space for the root directory: the first 16 KiB, past the header. */
internal const val ROOT_BYTES = PmtilesReader.FIRST_BYTES - PmtilesHeader.LENGTH

/**
 * The root directory and the leaf directories (one after another) for [tiles], compressed: all in the root when it
 * fits [rootBytes], otherwise leaves of [firstLeafSize] entries, doubled until the root of leaves fits.
 */
internal fun buildDirectories(
    tiles: Directory,
    compression: Int,
    rootBytes: Int = ROOT_BYTES,
    firstLeafSize: Int = 4096,
): Pair<ByteArray, ByteArray> {
    val whole = deflate(tiles.encode(), compression)
    if (whole.size <= rootBytes) return whole to ByteArray(0)
    var leafSize = firstLeafSize
    while (true) {
        val leaves = ByteOutput()
        val root = DirectoryBuilder((tiles.size + leafSize - 1) / leafSize)
        var i = 0
        while (i < tiles.size) {
            val end = minOf(i + leafSize, tiles.size)
            val leaf = deflate(tiles.encode(i, end), compression)
            root.add(tiles.tileId(i), 0, leaves.size.toLong(), leaf.size)
            leaves.write(leaf)
            i = end
        }
        val rootData = deflate(root.build().encode(), compression)
        if (rootData.size <= rootBytes) return rootData to leaves.toByteArray()
        if (leafSize >= tiles.size) throw IllegalStateException("a root of one leaf takes ${rootData.size} bytes")
        leafSize = if (leafSize > Int.MAX_VALUE / 2) Int.MAX_VALUE else leafSize * 2
    }
}

/** Keys of a source's metadata that MapLibre would take over the pack header's: they describe the whole source. */
private val HEADER_KEYS = setOf("bounds", "center", "minzoom", "maxzoom")

/** The source's metadata JSON for a pack cut out of it: everything (layers, attribution...) but [HEADER_KEYS]. */
fun packMetadata(sourceMetadata: String): String {
    val json = try {
        Json.parseToJsonElement(sourceMetadata) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw PmtilesException("metadata is not a JSON object")
    return JsonObject(json.filterKeys { it !in HEADER_KEYS }).toString()
}
