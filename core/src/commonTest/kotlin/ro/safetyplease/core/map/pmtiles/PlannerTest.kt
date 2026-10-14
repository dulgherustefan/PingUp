package ro.safetyplease.core.map.pmtiles

import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.use
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.map.MapArea
import ro.safetyplease.core.map.MapEstimate
import ro.safetyplease.core.map.NetState
import ro.safetyplease.core.map.RangeHttp
import ro.safetyplease.core.map.Remote
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Plans against the shipped map and a synthetic archive with leaf directories: tiles, exact bytes, spans, reads. */
class PlannerTest {
    private val tmp = TempDir()

    @AfterTest
    fun cleanUp() = tmp.delete()

    @Test
    fun theShippedAreaPlansToTheWholeShippedMap() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val reader = PmtilesReader.open(file)
            val plan = reader.plan(COSTINESTI)
            assertEquals(183L, plan.tiles)
            assertEquals(155, plan.entries.size)
            assertEquals(138, plan.contents)
            assertEquals(reader.header.tileDataLength, plan.bytes)
            assertEquals(MapEstimate(183, 1_112_632, 0), plan.estimate())
            assertEquals(0 to 15, plan.minZoom to plan.maxZoom)
            // the whole tile data section, contiguous, in one request
            assertEquals(1, plan.spans.size)
            assertEquals(reader.header.tileDataOffset, plan.spans.offset(0))
            assertEquals(plan.bytes, plan.fetchBytes)
            // the entries are the archive's own
            val own = reader.allTiles()
            val planned = plan.entries.allTiles()
            assertEquals(own, planned)
        }
    }

    @Test
    fun aSmallerAreaFetchesItsTilesAndBridgesSmallGaps() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val reader = PmtilesReader.open(file)
            val sub = MapArea(listOf(28.60, 43.95, 28.64, 43.99), 0, 15, "sub")
            val plan = reader.plan(sub, maxGap = 4096, maxSpan = 64 * 1024)
            assertEquals(52L, plan.tiles)
            val tiles = plan.entries.allTiles()
            assertEquals(AreaTiles(sub).ids().toSet(), tiles.keys)
            val own = reader.allTiles()
            for ((id, at) in tiles) assertEquals(own.getValue(id), at)
            assertEquals(tiles.values.toSet().sumOf { it.second.toLong() }, plan.bytes)
            checkSpans(plan, maxGap = 4096, maxSpan = 64 * 1024)
            assertTrue(plan.fetchBytes > plan.bytes && plan.spans.size > 1, "${plan.spans.size} spans, ${plan.fetchBytes} bytes")
        }
    }

    @Test
    fun tilesInstalledPacksHoldAreLeftOut() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val reader = PmtilesReader.open(file)
            val sub = MapArea(listOf(28.60, 43.95, 28.64, 43.99), 0, 15, "sub")
            val installed = AreaTiles(sub)
            val plan = reader.plan(COSTINESTI, held = TileFilter { installed.contains(it) })
            val alone = reader.plan(sub)
            assertEquals(183L - 52, plan.tiles)
            assertEquals(52L, plan.heldTiles)
            assertEquals(alone.bytes, plan.heldBytes)
            val planned = plan.entries.allTiles().keys
            assertTrue(planned.none { installed.contains(it) })
            assertEquals(AreaTiles(COSTINESTI).ids().toSet(), planned + installed.ids().toSet())
            assertEquals(MapEstimate(131, plan.bytes, alone.bytes), plan.estimate())
        }
    }

    @Test
    fun zoomsPastTheArchivesAreDropped() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val reader = PmtilesReader.open(file)
            val deep = reader.plan(COSTINESTI.copy(maxzoom = 20))
            assertEquals(15, deep.maxZoom)
            assertEquals(183L, deep.tiles)
            val above = reader.plan(COSTINESTI.copy(minzoom = 16, maxzoom = 18))
            assertEquals(0L, above.tiles)
            assertEquals(0, above.spans.size)
            val elsewhere = reader.plan(MapArea(listOf(2.2, 48.8, 2.4, 48.9), 10, 15, "Paris"))
            assertEquals(0L, elsewhere.tiles)
            assertEquals(0L, elsewhere.bytes)
        }
    }

    @Test
    fun rangesCoalesceAcrossSmallGapsUpToEightMegabytes() {
        val mib = 1024 * 1024
        fun spans(vararg ranges: Pair<Long, Int>) = coalesce(
            ranges.map { it.first }.toLongArray(), ranges.map { it.second }.toIntArray(), ranges.size,
            Spans.MAX_GAP, Spans.MAX_SPAN,
        ).let { s -> List(s.size) { s.offset(it) to s.length(it) } }
        assertEquals(listOf(0L to 20 + 65_536 + 5), spans(0L to 10, 10L to 10, 20L + 65_536 to 5))
        assertEquals(listOf(0L to 20, 20L + 65_537 to 5), spans(0L to 10, 10L to 10, 20L + 65_537 to 5))
        assertEquals(listOf(0L to 6 * mib, 6L * mib to 3 * mib), spans(0L to 3 * mib, 3L * mib to 3 * mib, 6L * mib to 3 * mib))
        assertEquals(listOf(100L to 10 * mib, 100L + 10 * mib to 1), spans(100L to 10 * mib, 100L + 10 * mib to 1))
        assertEquals(listOf(0L to 50), spans(0L to 50, 10L to 20))
        assertEquals(emptyList(), spans())
    }

    @Test
    fun aMapHostIsReadThroughRangeHttp() = runTest {
        val bytes = fs.read(SHIPPED) { readByteArray() }
        val http = FakeHttp(bytes, "\"v1\"")
        val remote = http.probe(URL)
        val reader = PmtilesReader.open(HttpRangeSource(http, URL, remote))
        val plan = reader.plan(COSTINESTI)
        assertEquals(183L, plan.tiles)
        assertEquals(1_112_632L, plan.bytes)
        // the header and the root directory come in one request
        assertEquals(listOf(0L to PmtilesReader.FIRST_BYTES), http.reads)
        val id = AreaTiles(COSTINESTI).ids().last()
        val local = FileRangeSource(fs, SHIPPED).use { file -> PmtilesReader.open(file).tile(id) }
        assertTrue(local != null && local.contentEquals(reader.tile(id)))
        // the host's file changed: nothing is read any more
        assertFailsWith<IOException> { PmtilesReader.open(HttpRangeSource(http, URL, remote.size, "\"v0\"")) }
    }

    @Test
    fun onlyTheLeafDirectoriesTheAreaFallsInAreRead() = runTest {
        val source = tmp.root / "synthetic.pmtiles"
        syntheticArchive(source, SYNTHETIC)
        FileRangeSource(fs, source).use { file ->
            val counting = CountingSource(file)
            val reader = PmtilesReader.open(counting)
            val header = reader.header
            assertTrue(header.leafDirectoryLength > 0 && reader.root.size > 4, "${reader.root.size} leaves")
            val area = MapArea(listOf(28.0, 44.0, 28.2, 44.2), 8, 15, "small")
            val ids = AreaTiles(area).ids()
            counting.reads.clear()
            val plan = reader.plan(area)
            assertEquals(ids.size.toLong(), plan.tiles)
            val root = reader.root
            val needed = (0 until root.size).filter { k ->
                val end = if (k + 1 < root.size) root.tileId(k + 1) else Long.MAX_VALUE
                ids.any { it >= root.tileId(k) && it < end }
            }
            val leafReads = counting.reads.filter { it.first >= header.leafDirectoryOffset && it.first < header.tileDataOffset }
            assertEquals(counting.reads, leafReads)
            // exactly the leaves the area falls in, consecutive ones in one read
            val read = leafReads.flatMap { (offset, length) ->
                (0 until root.size).filter { k ->
                    val at = header.leafDirectoryOffset + root.offset(k)
                    at >= offset && at + root.length(k) <= offset + length
                }
            }
            assertEquals(needed, read)
            assertEquals(needed.sumOf { root.length(it).toLong() }, leafReads.sumOf { it.second.toLong() })
            assertTrue(leafReads.size <= needed.size, "${leafReads.size} reads for ${needed.size} leaves")
            assertTrue(needed.size < root.size / 2, "${needed.size} of ${root.size} leaves")
        }
    }

    @Test
    fun aHundredThousandTilesPlanExactly() = runTest {
        val source = tmp.root / "synthetic.pmtiles"
        val written = syntheticArchive(source, SYNTHETIC)
        val ids = AreaTiles(SYNTHETIC).ids()
        assertEquals(100_313, ids.size)
        val distinct = ids.filter { (it / 4) % 6 != 0L }
        FileRangeSource(fs, source).use { file ->
            val counting = CountingSource(file)
            val plan = PmtilesReader.open(counting).plan(SYNTHETIC)
            // every leaf is needed, and they follow each other: one read for all of them
            val header = plan.source
            assertTrue(header.leafDirectoryLength > 0)
            assertEquals(
                listOf(0L to PmtilesReader.FIRST_BYTES, header.leafDirectoryOffset to header.leafDirectoryLength.toInt()),
                counting.reads,
            )
            assertEquals(100_313L, plan.tiles)
            assertTrue(plan.entries.size < ids.size, "runs merged: ${plan.entries.size} entries")
            assertEquals(distinct.size + 1, plan.contents)
            assertEquals(SEA + distinct.sumOf { 40L + it % 97 }, plan.bytes)
            assertEquals(written.header.tileDataLength, plan.bytes)
            checkSpans(plan, Spans.MAX_GAP, Spans.MAX_SPAN)
        }
    }

    @Test
    fun leafDirectoriesUnderLeafDirectoriesAreFollowed() = runTest {
        // the shipped area's ids in leaves of 20 entries, under middle leaves of three each, under the root
        val ids = AreaTiles(COSTINESTI).ids()
        val tiles = DirectoryBuilder()
        var dataLength = 0L
        for (id in ids) {
            val length = 10 + (id % 13).toInt()
            tiles.add(id, 1, dataLength, length)
            dataLength += length
        }
        val all = tiles.build()
        val leaves = ByteOutput()
        val bottom = DirectoryBuilder()
        for (from in 0 until all.size step 20) {
            val leaf = deflate(all.encode(from, minOf(from + 20, all.size)), Compression.GZIP)
            bottom.add(all.tileId(from), 0, leaves.size.toLong(), leaf.size)
            leaves.write(leaf)
        }
        val pointers = bottom.build()
        val root = DirectoryBuilder()
        for (from in 0 until pointers.size step 3) {
            val middle = deflate(pointers.encode(from, minOf(from + 3, pointers.size)), Compression.GZIP)
            root.add(pointers.tileId(from), 0, leaves.size.toLong(), middle.size)
            leaves.write(middle)
        }
        val rootBytes = deflate(root.build().encode(), Compression.GZIP)
        val metadata = deflate("{}".encodeToByteArray(), Compression.GZIP)
        val leafBytes = leaves.toByteArray()
        val leafStart = PmtilesHeader.LENGTH.toLong() + rootBytes.size + metadata.size
        val header = areaHeader(COSTINESTI, 0, 15, TileType.MVT, Compression.NONE).copy(
            rootOffset = PmtilesHeader.LENGTH.toLong(), rootLength = rootBytes.size.toLong(),
            metadataOffset = PmtilesHeader.LENGTH.toLong() + rootBytes.size, metadataLength = metadata.size.toLong(),
            leafDirectoryOffset = leafStart, leafDirectoryLength = leafBytes.size.toLong(),
            tileDataOffset = leafStart + leafBytes.size, tileDataLength = dataLength,
            addressedTiles = 183, tileEntries = 183, tileContents = 183,
        )
        val nested = tmp.root / "nested.pmtiles"
        val data = PatternSource(dataLength).read(0, dataLength.toInt())
        fs.write(nested) {
            write(header.encode())
            write(rootBytes)
            write(metadata)
            write(leafBytes)
            write(data)
        }
        FileRangeSource(fs, nested).use { file ->
            val reader = PmtilesReader.open(file)
            for (i in 0 until all.size) {
                assertEquals(TileLocation(header.tileDataOffset + all.offset(i), all.length(i)), reader.find(all.tileId(i)))
            }
            assertEquals(null, reader.find(ids.last() + 1))
            val whole = reader.plan(COSTINESTI)
            assertEquals(183L, whole.tiles)
            assertEquals(dataLength, whole.bytes)
            val sub = MapArea(listOf(28.60, 43.95, 28.64, 43.99), 0, 15, "sub")
            assertEquals(AreaTiles(sub).ids().toSet(), reader.plan(sub).entries.allTiles().keys)
            // written again, with uncompressed directories: one level, the same tiles
            val flat = PmtilesWriter(fs, Compression.NONE).write(tmp.root / "flat.pmtiles", whole, file, reader.metadata())
            assertEquals(Compression.NONE, flat.header.internalCompression)
            FileRangeSource(fs, flat.path).use { out ->
                val copy = PmtilesReader.open(out)
                for (id in ids) assertTrue(reader.tile(id)!!.contentEquals(copy.tile(id)), "tile $id")
            }
        }
    }

    @Test
    fun spansKeptElsewhereComeBack() {
        val spans = Spans.of(longArrayOf(10, 100, 5000), intArrayOf(20, 50, 7))
        assertEquals(77L, spans.bytes)
        assertEquals(listOf(0L, 20L, 70L), List(3) { spans.packedOffset(it) })
        assertEquals(listOf(-1, 0, 0, 1, 2), listOf(9L, 10L, 99L, 100L, 6000L).map { spans.floor(it) })
        assertFailsWith<IllegalArgumentException> { Spans.of(longArrayOf(10, 20), intArrayOf(20, 5)) }
        assertFailsWith<IllegalArgumentException> { Spans.of(longArrayOf(10), intArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { Spans.of(longArrayOf(10), intArrayOf()) }
    }

    private fun checkSpans(plan: ExtractPlan, maxGap: Long, maxSpan: Long) {
        val spans = plan.spans
        for (i in 0 until spans.size) {
            if (i > 0) {
                val end = spans.offset(i - 1) + spans.length(i - 1)
                assertTrue(spans.offset(i) >= end, "span $i overlaps")
                assertTrue(
                    spans.offset(i) - end > maxGap || spans.offset(i) + spans.length(i) - spans.offset(i - 1) > maxSpan,
                    "span $i could have joined the previous one",
                )
            }
        }
        val tiles = plan.entries.allTiles().values.toSet()
        for ((offset, length) in tiles) {
            val k = spans.floor(offset)
            assertTrue(k >= 0 && offset + length <= spans.offset(k) + spans.length(k), "tile at $offset+$length not fetched")
        }
        for (i in 0 until spans.size) {
            assertTrue(spans.length(i) <= maxSpan || tiles.any { it.first == spans.offset(i) && it.second == spans.length(i) })
        }
    }

    private class FakeHttp(private val bytes: ByteArray, private val etag: String) : RangeHttp {
        val reads = mutableListOf<Pair<Long, Int>>()

        override fun network() = NetState.UNMETERED

        override suspend fun probe(url: String) = Remote(bytes.size.toLong(), etag, ranges = true)

        override suspend fun read(url: String, offset: Long, length: Int, etag: String?): ByteArray? {
            if (etag != this.etag || offset < 0 || offset + length > bytes.size) return null
            reads += offset to length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    private companion object {
        const val URL = "https://maps.example.org/planet.pmtiles"
    }
}
