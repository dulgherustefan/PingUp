package ro.safetyplease.core.map.pmtiles

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.use
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.exists
import ro.safetyplease.core.map.MapArea
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.GeoPoint
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Packs cut from the shipped map and from synthetic archives: the same tiles, valid archives, the hash of the file. */
class WriterTest {
    private val tmp = TempDir()

    @AfterTest
    fun cleanUp() = tmp.delete()

    @Test
    fun aSubAreaIsCutByteForByte() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val source = PmtilesReader.open(file)
            val sub = MapArea(listOf(28.60, 43.95, 28.64, 43.99), 0, 15, "sub")
            val plan = source.plan(sub)
            val target = tmp.root / "packs" / "sub" / "1" / "sub.pmtiles"
            val written = PmtilesWriter(fs).write(target, plan, file, source.metadata())
            assertFalse((target.parent!! / "sub.pmtiles.tmp").exists())
            val bytes = fs.read(target) { readByteArray() }
            assertEquals(written.bytes, bytes.size.toLong())
            assertEquals(sha256(bytes).toHex(), written.sha256)
            FileRangeSource(fs, target).use { out ->
                val pack = PmtilesReader.open(out)
                assertEquals(written.header, pack.header)
                val ids = AreaTiles(sub).ids()
                assertEquals(ids.toSet(), pack.allTiles().keys)
                for (id in ids) assertContentEquals(assertNotNull(source.tile(id)), pack.tile(id), "tile $id")
                with(pack.header) {
                    assertEquals(52L, addressedTiles)
                    assertEquals(plan.entries.size.toLong(), tileEntries)
                    assertEquals(plan.contents.toLong(), tileContents)
                    assertEquals(plan.bytes, tileDataLength)
                    assertTrue(clustered)
                    assertEquals(Compression.GZIP, internalCompression)
                    assertEquals(source.header.tileCompression, tileCompression)
                    assertEquals(TileType.MVT, tileType)
                    assertEquals(0 to 15, minZoom to maxZoom)
                    assertEquals(listOf(286_000_000, 439_500_000, 286_400_000, 439_900_000), listOf(minLonE7, minLatE7, maxLonE7, maxLatE7))
                    assertEquals(286_200_000 to 439_700_000, centerLonE7 to centerLatE7)
                }
                val metadata = Json.parseToJsonElement(pack.metadata()).jsonObject
                val original = Json.parseToJsonElement(source.metadata()).jsonObject
                assertFalse("bounds" in metadata)
                assertEquals(original, metadata)
            }
        }
    }

    @Test
    fun theWholeAreaGivesBackTheShippedTileData() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val source = PmtilesReader.open(file)
            val target = tmp.root / "whole.pmtiles"
            val written = PmtilesWriter(fs).write(target, source.plan(COSTINESTI), file, source.metadata())
            val shipped = fs.read(SHIPPED) { readByteArray() }
            val copy = fs.read(target) { readByteArray() }
            val original = source.header
            assertContentEquals(
                shipped.copyOfRange(original.tileDataOffset.toInt(), shipped.size),
                copy.copyOfRange(written.header.tileDataOffset.toInt(), copy.size),
            )
            FileRangeSource(fs, target).use { out ->
                val pack = PmtilesReader.open(out)
                // the same entries, offsets counted from each archive's tile data
                fun relative(tiles: Map<Long, Pair<Long, Int>>, start: Long) = tiles.mapValues { (_, at) -> at.first - start to at.second }
                assertEquals(relative(source.allTiles(), original.tileDataOffset), relative(pack.allTiles(), pack.header.tileDataOffset))
                assertEquals(
                    listOf(original.addressedTiles, original.tileEntries, original.tileContents),
                    listOf(pack.header.addressedTiles, pack.header.tileEntries, pack.header.tileContents),
                )
            }
        }
    }

    @Test
    fun aPackWrittenFromTheFetchedSpansIsTheSameFile() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val source = PmtilesReader.open(file)
            val plan = source.plan(MapArea(listOf(28.60, 43.95, 28.64, 43.99), 7, 15, "sub"), maxGap = 2048, maxSpan = 64 * 1024)
            assertTrue(plan.spans.size > 2)
            // what a download keeps: the spans one after another
            val fetched = tmp.root / "data.bin"
            fs.write(fetched) {
                for (i in 0 until plan.spans.size) write(file.read(plan.spans.offset(i), plan.spans.length(i)))
            }
            val metadata = source.metadata()
            val target = tmp.root / "sub.pmtiles"
            val direct = PmtilesWriter(fs).write(target, plan, file, metadata)
            // written again over the first one
            val packed = FileRangeSource(fs, fetched).use { data ->
                PmtilesWriter(fs).write(target, plan, PackedSource(plan.spans, data), metadata)
            }
            assertEquals(direct.sha256, packed.sha256)
            assertEquals(direct.bytes, packed.bytes)
            assertEquals(direct.sha256, sha256(fs.read(target) { readByteArray() }).toHex())
            assertEquals(plan.fetchBytes, fs.metadata(fetched).size)
        }
    }

    @Test
    fun aRootTooBigForSixteenKilobytesSpillsIntoLeaves() = runTest {
        val random = Random(11)
        val entries = DirectoryBuilder()
        var offset = 0L
        val first = TileId.first(14)
        for (i in 0 until 40_000) {
            val length = 1 + random.nextInt(200)
            entries.add(first + 3L * i, 1, offset, length)
            offset += length
        }
        val directory = entries.build()
        val area = MapArea(listOf(-180.0, -85.0, 180.0, 85.0), 14, 14, "world")
        val target = tmp.root / "leaves.pmtiles"
        val data = PatternSource(offset)
        val written = PmtilesWriter(fs).write(target, areaHeader(area, 14, 14, TileType.MVT, Compression.NONE), directory, data, "{}")
        with(written.header) {
            assertTrue(leafDirectoryLength > 0)
            assertTrue(rootLength <= PmtilesReader.FIRST_BYTES - PmtilesHeader.LENGTH, "root of $rootLength bytes")
            assertTrue(rootOffset + rootLength <= PmtilesReader.FIRST_BYTES)
        }
        assertTrue(deflate(directory.encode(), Compression.GZIP).size > PmtilesReader.FIRST_BYTES, "the entries alone would fit")
        FileRangeSource(fs, target).use { file ->
            val reader = PmtilesReader.open(file, cachedLeaves = 2)
            assertTrue(reader.root.size > 1 && (0 until reader.root.size).all { reader.root.isLeaf(it) })
            for (i in 0 until directory.size step 97) {
                val at = assertNotNull(reader.find(directory.tileId(i)))
                assertEquals(directory.length(i), at.length)
                assertContentEquals(data.read(directory.offset(i), directory.length(i)), reader.tile(directory.tileId(i)))
                assertEquals(null, reader.find(directory.tileId(i) + 1))
            }
            var count = 0
            reader.forEachEntry { _, _, _, _ -> count++ }
            assertEquals(40_000, count)
        }
        assertEquals(sha256(fs.read(target) { readByteArray() }).toHex(), written.sha256)
    }

    @Test
    fun leavesDoubleInSizeUntilTheRootFits() = runTest {
        val builder = DirectoryBuilder()
        for (i in 0 until 1000) builder.add(10L + 2 * i, 1, 1000L * i, 100 + i)
        val tiles = builder.build()
        val (root, leaves) = buildDirectories(tiles, Compression.NONE, rootBytes = 64, firstLeafSize = 2)
        assertTrue(root.size <= 64)
        val top = Directory.decode(root)
        val leafSize = Directory.decode(leaves, 0, top.length(0)).size
        assertTrue(leafSize > 2 && leafSize and (leafSize - 1) == 0, "leaves of $leafSize entries")
        // half as many entries per leaf makes a root too big, so it doubled from there to the same leaves
        assertContentEquals(root, buildDirectories(tiles, Compression.NONE, rootBytes = 64, firstLeafSize = leafSize / 2).first)
        assertEquals(0, buildDirectories(tiles, Compression.NONE, rootBytes = Int.MAX_VALUE).second.size)
        val again = DirectoryBuilder()
        for (k in 0 until top.size) {
            assertTrue(top.isLeaf(k))
            val leaf = Directory.decode(leaves, top.offset(k).toInt(), top.offset(k).toInt() + top.length(k))
            assertEquals(top.tileId(k), leaf.tileId(0))
            for (i in 0 until leaf.size) again.add(leaf.tileId(i), leaf.runLength(i), leaf.offset(i), leaf.length(i))
        }
        assertContentEquals(tiles.encode(), again.build().encode())
    }

    @Test
    fun aPacksMetadataLeavesOutTheSourcesBoundsAndZooms() {
        val source = """{"name":"Protomaps","bounds":"-180,-85,180,85","center":"0,0,0","minzoom":"0","maxzoom":"15",""" +
            """"vector_layers":[{"id":"roads","minzoom":4}],"attribution":"<a>© OpenStreetMap</a>"}"""
        val pack = Json.parseToJsonElement(packMetadata(source)).jsonObject
        assertEquals(listOf("name", "vector_layers", "attribution"), pack.keys.toList())
        assertEquals(Json.parseToJsonElement(source).jsonObject["vector_layers"], pack["vector_layers"])
        assertFailsWith<PmtilesException> { packMetadata("[1, 2]") }
        assertFailsWith<PmtilesException> { packMetadata("not json") }
    }

    @Test
    fun aPackThatReadsBackDifferentIsDeleted() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val source = PmtilesReader.open(file)
            val plan = source.plan(COSTINESTI)
            // bytes that change once read: what the file got is not what the check reads again
            val changing = object : RangeSource {
                private val seen = mutableSetOf<Long>()
                override val size = file.size

                override suspend fun read(offset: Long, length: Int): ByteArray {
                    val bytes = file.read(offset, length)
                    if (!seen.add(offset)) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    return bytes
                }
            }
            val target = tmp.root / "bad.pmtiles"
            assertFailsWith<PmtilesException> { PmtilesWriter(fs).write(target, plan, changing, source.metadata()) }
            assertFalse(target.exists())
            assertFalse((tmp.root / "bad.pmtiles.tmp").exists())
        }
    }

    @Test
    fun nothingToWriteIsRefused() = runTest {
        FileRangeSource(fs, SHIPPED).use { file ->
            val source = PmtilesReader.open(file)
            val plan = source.plan(MapArea(listOf(2.2, 48.8, 2.4, 48.9), 10, 15, "Paris"))
            assertFailsWith<IllegalArgumentException> { PmtilesWriter(fs).write(tmp.root / "empty.pmtiles", plan, file, "{}") }
        }
    }

    @Test
    fun anOutlineCutFromAnArchiveWithLeavesKeepsEveryTile() = runTest {
        val source = tmp.root / "synthetic.pmtiles"
        syntheticArchive(source, SYNTHETIC)
        val outline = listOf(
            GeoPoint(44.05, 28.12), GeoPoint(44.43, 28.71), GeoPoint(44.98, 29.31), GeoPoint(45.21, 28.83),
            GeoPoint(44.73, 28.41), GeoPoint(44.62, 27.93), GeoPoint(44.35, 28.29),
        )
        val area = SYNTHETIC.copy(minzoom = 7, polygon = outline)
        FileRangeSource(fs, source).use { file ->
            val reader = PmtilesReader.open(file)
            val plan = reader.plan(area)
            val ids = AreaTiles(area).ids()
            assertEquals(ids.size.toLong(), plan.tiles)
            assertTrue(plan.tiles < AreaTiles(SYNTHETIC.copy(minzoom = 7)).count() / 2)
            val target = tmp.root / "outline.pmtiles"
            val written = PmtilesWriter(fs).write(target, plan, file, reader.metadata())
            assertEquals(sha256(fs.read(target) { readByteArray() }).toHex(), written.sha256)
            FileRangeSource(fs, target).use { out ->
                val pack = PmtilesReader.open(out)
                assertEquals(7 to 15, pack.header.minZoom to pack.header.maxZoom)
                assertEquals(ids.toSet(), pack.allTiles().keys)
                for (id in ids) assertContentEquals(reader.tile(id), pack.tile(id), "tile $id")
            }
        }
    }
}
