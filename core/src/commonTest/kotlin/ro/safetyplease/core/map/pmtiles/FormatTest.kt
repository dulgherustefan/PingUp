package ro.safetyplease.core.map.pmtiles

import kotlinx.coroutines.test.runTest
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The PMTiles v3 primitives: the header, varints, directories and their compression, against the spec and the shipped map. */
class FormatTest {
    private val header = PmtilesHeader(
        rootOffset = 127, rootLength = 534, metadataOffset = 661, metadataLength = 1181,
        leafDirectoryOffset = 1842, leafDirectoryLength = 0, tileDataOffset = 1842, tileDataLength = 1_112_632,
        addressedTiles = 183, tileEntries = 155, tileContents = 138, clustered = true,
        internalCompression = Compression.GZIP, tileCompression = Compression.GZIP, tileType = TileType.MVT,
        minZoom = 0, maxZoom = 15, minLonE7 = 285_900_000, minLatE7 = 439_200_000, maxLonE7 = 286_750_000,
        maxLatE7 = 440_100_000, centerZoom = 15, centerLonE7 = 286_325_000, centerLatE7 = 439_650_000,
    )

    @Test
    fun theShippedMapsHeaderReads() {
        val bytes = fs.read(SHIPPED) { readByteArray(PmtilesHeader.LENGTH.toLong()) }
        assertEquals(header, PmtilesHeader.decode(bytes))
        assertContentEquals(bytes, header.encode())
    }

    @Test
    fun headerRoundTripsEveryField() {
        val odd = PmtilesHeader(
            rootOffset = 1, rootLength = 2, metadataOffset = 3, metadataLength = 4, leafDirectoryOffset = 5,
            leafDirectoryLength = 6, tileDataOffset = 7, tileDataLength = Long.MAX_VALUE, addressedTiles = 9,
            tileEntries = 10, tileContents = 11, clustered = false, internalCompression = Compression.NONE,
            tileCompression = Compression.ZSTD, tileType = TileType.WEBP, minZoom = 3, maxZoom = 255,
            minLonE7 = -1_800_000_000, minLatE7 = -850_511_288, maxLonE7 = 1_800_000_000, maxLatE7 = 850_511_288,
            centerZoom = 200, centerLonE7 = -1, centerLatE7 = Int.MIN_VALUE,
        )
        val bytes = odd.encode()
        assertEquals(PmtilesHeader.LENGTH, bytes.size)
        assertContentEquals("PMTiles".encodeToByteArray(), bytes.copyOf(7))
        assertEquals(3, bytes[7].toInt())
        assertEquals(odd, PmtilesHeader.decode(bytes))
    }

    @Test
    fun notPmtilesOrAnotherVersionIsRefused() {
        val bytes = header.encode()
        assertFailsWith<PmtilesException> { PmtilesHeader.decode(bytes.copyOf(126)) }
        assertFailsWith<PmtilesException> { PmtilesHeader.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertFailsWith<PmtilesException> { PmtilesHeader.decode(bytes.copyOf().also { it[7] = 2 }) }
        assertFailsWith<PmtilesException> { header.copy(tileDataLength = 2_000_000).check(1_114_474) }
        assertFailsWith<PmtilesException> { header.copy(internalCompression = Compression.BROTLI).check(1_114_474) }
        header.check(1_114_474)
    }

    @Test
    fun varintsAreUnsignedLeb128() {
        val cases = mapOf(
            0L to "00", 1L to "01", 127L to "7f", 128L to "8001", 300L to "ac02", 16_384L to "808001",
            Long.MAX_VALUE to "ffffffffffffffff7f", -1L to "ffffffffffffffffff01",
        )
        for ((value, hex) in cases) {
            val out = ByteOutput()
            out.varint(value)
            val bytes = out.toByteArray()
            assertEquals(hex, bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, "$value")
            assertEquals(value, ByteInput(bytes, 0, bytes.size).varint())
        }
        assertFailsWith<PmtilesException> { ByteInput(byteArrayOf(0x80.toByte()), 0, 1).varint() }
        assertFailsWith<PmtilesException> { ByteInput(ByteArray(11) { 0x80.toByte() }, 0, 11).varint() }
    }

    @Test
    fun directoryRoundTrips() {
        val random = Random(3)
        val builder = DirectoryBuilder()
        var id = 0L
        var offset = 0L
        repeat(500) {
            id += 1 + random.nextInt(3) + if (random.nextInt(20) == 0) random.nextLong(1L shl 40) else 0
            val length = 1 + random.nextInt(70_000)
            // most follow the previous tile's bytes, some point back, a few are leaf directories
            val at = if (random.nextInt(5) == 0) random.nextLong(offset + 1) else offset
            val run = if (random.nextInt(30) == 0) 0L else 1L + if (random.nextInt(10) == 0) random.nextLong(5000) else 0
            builder.add(id, run, at, length)
            offset = maxOf(offset, at + length)
        }
        builder.add(id + 1, 0xffffffffL, offset, Int.MAX_VALUE)
        val directory = builder.build()
        val decoded = Directory.decode(directory.encode())
        assertEquals(directory.size, decoded.size)
        for (i in 0 until directory.size) {
            assertEquals(directory.tileId(i), decoded.tileId(i))
            assertEquals(directory.runLength(i), decoded.runLength(i))
            assertEquals(directory.offset(i), decoded.offset(i))
            assertEquals(directory.length(i), decoded.length(i))
            assertEquals(directory.isLeaf(i), decoded.isLeaf(i))
        }
        val zipped = deflate(directory.encode(), Compression.GZIP)
        val unzipped = decodeDirectory(zipped, 0, zipped.size, Compression.GZIP)
        assertContentEquals(directory.encode(), unzipped.encode())
    }

    @Test
    fun offsetsFollowingThePreviousTileAreWrittenAsZero() {
        val directory = DirectoryBuilder()
            .add(5, 1, 0, 10)
            .add(6, 1, 10, 20) // right after the previous tile
            .add(9, 2, 0, 10) // the first tile's bytes again
            .add(20, 0, 300, 40) // a leaf directory
            .build()
        val expected = byteArrayOf(
            4, // entries
            5, 1, 3, 11, // tile ids as deltas
            1, 1, 2, 0, // run lengths
            10, 20, 10, 40, // lengths
            1, 0, 1, 0xad.toByte(), 0x02, // offsets: +1, contiguous, +1, 301 as a varint
        )
        assertContentEquals(expected, directory.encode())
        assertContentEquals(expected, Directory.decode(expected).encode())
        assertEquals(0, directory.floor(5))
        assertEquals(1, directory.floor(6))
        assertEquals(-1, directory.floor(4))
        assertEquals(3, directory.floor(1000))
        assertEquals(2, directory.floor(19))
    }

    @Test
    fun corruptDirectoriesAreRefused() {
        assertFailsWith<PmtilesException> { Directory.decode(byteArrayOf(100, 1, 1, 1, 1)) }
        assertFailsWith<PmtilesException> { Directory.decode(byteArrayOf(1, 5, 1, 10)) }
        assertFailsWith<PmtilesException> { Directory.decode(byteArrayOf(1, 5, 1, 10, 0)) }
        assertFailsWith<PmtilesException> { decodeDirectory(byteArrayOf(1, 2, 3), 0, 3, Compression.GZIP) }
        assertFailsWith<PmtilesException> { decodeDirectory(byteArrayOf(1, 2, 3), 0, 3, Compression.ZSTD) }
    }

    @Test
    fun theBuilderKeepsTileIdsAscending() {
        val builder = DirectoryBuilder().add(10, 1, 0, 5)
        assertFailsWith<IllegalArgumentException> { builder.add(10, 1, 5, 5) }
        assertTrue(builder.extend(11, 0, 5))
        assertTrue(!builder.extend(13, 0, 5))
        assertTrue(!builder.extend(12, 5, 5))
        val directory = builder.build()
        assertEquals(2L, directory.runLength(0))
        assertFailsWith<IllegalStateException> { builder.add(20, 1, 0, 5) }
    }

    @Test
    fun theShippedMapsRootReads() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val reader = PmtilesReader.open(file)
            assertEquals(header, reader.header)
            assertEquals(155, reader.root.size)
            var addressed = 0L
            reader.forEachEntry { _, runLength, _, _ -> addressed += runLength }
            assertEquals(183L, addressed)
            val metadata = reader.metadata()
            assertTrue("\"vector_layers\"" in metadata && "OpenStreetMap" in metadata, metadata.take(200))
        }
    }
}
