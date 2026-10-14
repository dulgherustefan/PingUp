package ro.safetyplease.core.map.pmtiles

import okio.IOException

/** An archive that breaks the PMTiles v3 format, or uses a part of it this code doesn't read. */
class PmtilesException(message: String) : IOException(message)

/** Compression codes of the header: of the directories and metadata (internal), or of each tile. */
object Compression {
    const val UNKNOWN = 0
    const val NONE = 1
    const val GZIP = 2
    const val BROTLI = 3
    const val ZSTD = 4
}

/** Tile type codes of the header. */
object TileType {
    const val UNKNOWN = 0
    const val MVT = 1
    const val PNG = 2
    const val JPEG = 3
    const val WEBP = 4
    const val AVIF = 5
}

/**
 * The 127-byte little-endian header of a PMTiles v3 archive. Offsets and lengths are bytes from the start of the file,
 * positions degrees × 10^7; compression and tile type are the spec's codes ([Compression], [TileType]).
 */
data class PmtilesHeader(
    val rootOffset: Long,
    val rootLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirectoryOffset: Long,
    val leafDirectoryLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: Int,
    val tileCompression: Int,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLonE7: Int,
    val minLatE7: Int,
    val maxLonE7: Int,
    val maxLatE7: Int,
    val centerZoom: Int,
    val centerLonE7: Int,
    val centerLatE7: Int,
) {
    fun encode(): ByteArray {
        for (code in intArrayOf(internalCompression, tileCompression, tileType, minZoom, maxZoom, centerZoom)) {
            require(code in 0..255) { "$code doesn't fit a header byte" }
        }
        val out = ByteArray(LENGTH)
        MAGIC.copyInto(out)
        out[7] = VERSION.toByte()
        val numbers = longArrayOf(
            rootOffset, rootLength, metadataOffset, metadataLength, leafDirectoryOffset, leafDirectoryLength,
            tileDataOffset, tileDataLength, addressedTiles, tileEntries, tileContents,
        )
        for (i in numbers.indices) out.putLongLe(8 + 8 * i, numbers[i])
        out[96] = if (clustered) 1 else 0
        out[97] = internalCompression.toByte()
        out[98] = tileCompression.toByte()
        out[99] = tileType.toByte()
        out[100] = minZoom.toByte()
        out[101] = maxZoom.toByte()
        out.putIntLe(102, minLonE7)
        out.putIntLe(106, minLatE7)
        out.putIntLe(110, maxLonE7)
        out.putIntLe(114, maxLatE7)
        out[118] = centerZoom.toByte()
        out.putIntLe(119, centerLonE7)
        out.putIntLe(123, centerLatE7)
        return out
    }

    /** Throws unless every section lies inside an archive of [size] bytes and the directories can be read. */
    internal fun check(size: Long) {
        section("root directory", rootOffset, rootLength, size)
        section("metadata", metadataOffset, metadataLength, size)
        section("leaf directories", leafDirectoryOffset, leafDirectoryLength, size)
        section("tile data", tileDataOffset, tileDataLength, size)
        if (rootLength == 0L) throw PmtilesException("no root directory")
        if (internalCompression != Compression.NONE && internalCompression != Compression.GZIP) {
            throw PmtilesException("directory compression $internalCompression not supported")
        }
        if (minZoom > maxZoom || maxZoom > TileId.MAX_ZOOM) throw PmtilesException("zooms $minZoom..$maxZoom")
    }

    private fun section(name: String, offset: Long, length: Long, size: Long) {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw PmtilesException("$name at $offset+$length outside the $size bytes of the archive")
        }
    }

    companion object {
        const val LENGTH = 127
        const val VERSION = 3
        private val MAGIC = "PMTiles".encodeToByteArray()

        /** The header at the start of [bytes]. */
        fun decode(bytes: ByteArray): PmtilesHeader {
            if (bytes.size < LENGTH) throw PmtilesException("${bytes.size} bytes is too short for a header")
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) throw PmtilesException("not a PMTiles archive")
            val version = bytes[7].toInt() and 0xff
            if (version != VERSION) throw PmtilesException("PMTiles version $version, only $VERSION is read")
            val n = LongArray(11) { bytes.longLe(8 + 8 * it) }
            if (n.any { it < 0 }) throw PmtilesException("header number past 2^63")
            fun u8(at: Int) = bytes[at].toInt() and 0xff
            return PmtilesHeader(
                rootOffset = n[0],
                rootLength = n[1],
                metadataOffset = n[2],
                metadataLength = n[3],
                leafDirectoryOffset = n[4],
                leafDirectoryLength = n[5],
                tileDataOffset = n[6],
                tileDataLength = n[7],
                addressedTiles = n[8],
                tileEntries = n[9],
                tileContents = n[10],
                clustered = u8(96) == 1,
                internalCompression = u8(97),
                tileCompression = u8(98),
                tileType = u8(99),
                minZoom = u8(100),
                maxZoom = u8(101),
                minLonE7 = bytes.intLe(102),
                minLatE7 = bytes.intLe(106),
                maxLonE7 = bytes.intLe(110),
                maxLatE7 = bytes.intLe(114),
                centerZoom = u8(118),
                centerLonE7 = bytes.intLe(119),
                centerLatE7 = bytes.intLe(123),
            )
        }
    }
}
