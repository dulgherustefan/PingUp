package ro.safetyplease.core.map.pmtiles

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Where a tile's bytes are: [offset] from the start of the archive, [length] bytes. */
data class TileLocation(val offset: Long, val length: Int)

/** Gets each tile entry of an archive: a run of [runLength] tile ids from [tileId], all with the same bytes. */
fun interface EntryVisitor {
    fun visit(tileId: Long, runLength: Long, offset: Long, length: Int)
}

/**
 * A PMTiles v3 archive read from a [RangeSource]: the header and the root directory when opened, in one read of the
 * first 16 KiB as the spec allows; leaf directories when a lookup needs them, the last [cachedLeaves] kept.
 */
class PmtilesReader private constructor(
    val source: RangeSource,
    val header: PmtilesHeader,
    val root: Directory,
    private val cachedLeaves: Int,
) {
    private val lock = SynchronizedObject()
    private val leaves = LinkedHashMap<Long, Directory>()

    /** The archive's JSON metadata. */
    suspend fun metadata(): String {
        val length = header.metadataLength.toIntOrThrow("metadata")
        if (length == 0) return "{}"
        val bytes = source.read(header.metadataOffset, length)
        return inflate(bytes, 0, length, header.internalCompression).decodeToString()
    }

    /** The leaf directory of [length] bytes at [offset] from the start of the leaf directories. */
    suspend fun leaf(offset: Long, length: Int): Directory {
        synchronized(lock) {
            val cached = leaves.remove(offset)
            if (cached != null) {
                leaves[offset] = cached
                return cached
            }
        }
        val bytes = source.read(header.leafDirectoryOffset + offset, length)
        val directory = decodeDirectory(bytes, 0, length, header.internalCompression)
        synchronized(lock) {
            leaves[offset] = directory
            while (leaves.size > cachedLeaves) leaves.remove(leaves.keys.first())
        }
        return directory
    }

    suspend fun find(tileId: Long): TileLocation? {
        var directory = root
        repeat(MAX_DEPTH + 1) {
            val i = directory.floor(tileId)
            if (i < 0) return null
            if (!directory.isLeaf(i)) {
                if (tileId - directory.tileId(i) >= directory.runLength(i)) return null
                return TileLocation(header.tileDataOffset + directory.offset(i), directory.length(i))
            }
            directory = leaf(directory.offset(i), directory.length(i))
        }
        throw PmtilesException("leaf directories nested deeper than $MAX_DEPTH")
    }

    /** The tile's bytes as stored, so compressed when the archive's tile compression says so; null when absent. */
    suspend fun tile(tileId: Long): ByteArray? = find(tileId)?.let { source.read(it.offset, it.length) }

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? = tile(TileId.of(z, x, y))

    /** Every tile entry in tile id order, reading all the leaf directories; offsets from the start of the archive. */
    suspend fun forEachEntry(visitor: EntryVisitor) = visit(root, visitor, 0)

    private suspend fun visit(directory: Directory, visitor: EntryVisitor, depth: Int) {
        if (depth > MAX_DEPTH) throw PmtilesException("leaf directories nested deeper than $MAX_DEPTH")
        for (i in 0 until directory.size) {
            if (directory.isLeaf(i)) {
                visit(leaf(directory.offset(i), directory.length(i)), visitor, depth + 1)
            } else {
                visitor.visit(
                    directory.tileId(i), directory.runLength(i), header.tileDataOffset + directory.offset(i), directory.length(i),
                )
            }
        }
    }

    companion object {
        /** The spec keeps the header and root directory within this many bytes from the start. */
        const val FIRST_BYTES = 16_384

        /** Levels of leaf directories under the root that are followed. */
        const val MAX_DEPTH = 4

        suspend fun open(source: RangeSource, cachedLeaves: Int = 64): PmtilesReader {
            require(cachedLeaves >= 1)
            if (source.size < PmtilesHeader.LENGTH) throw PmtilesException("${source.size} bytes is too short for PMTiles")
            val first = source.read(0, minOf(source.size, FIRST_BYTES.toLong()).toInt())
            val header = PmtilesHeader.decode(first)
            header.check(source.size)
            val rootLength = header.rootLength.toIntOrThrow("root directory")
            val root = if (header.rootOffset + rootLength <= first.size) {
                decodeDirectory(first, header.rootOffset.toInt(), rootLength, header.internalCompression)
            } else {
                decodeDirectory(source.read(header.rootOffset, rootLength), 0, rootLength, header.internalCompression)
            }
            return PmtilesReader(source, header, root, cachedLeaves)
        }
    }
}
