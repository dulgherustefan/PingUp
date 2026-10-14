package ro.safetyplease.app.platform.map

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.map.InstalledPack
import ro.safetyplease.core.map.PackKind
import ro.safetyplease.core.map.PackManifest
import ro.safetyplease.ui.map.MapKind
import ro.safetyplease.ui.map.MapPalette
import ro.safetyplease.ui.map.OverlayColors
import ro.safetyplease.ui.map.style.MapStyleBuilder
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** The bundled assets, as a folder on disk. */
private class DirSource(private val root: File) : PackSource {
    override fun list(path: String): List<String> = File(root, path).takeIf { it.isDirectory }?.list()?.sorted().orEmpty()

    override fun open(path: String): InputStream = File(root, path).inputStream()
}

class MapPackTest {
    private val temp = Files.createTempDirectory("maps").toFile()
    private val assets = File(temp, "assets")
    private val shipped = File("src/main/assets")

    @After
    fun cleanUp() {
        temp.walkTopDown().forEach { it.setWritable(true) }
        temp.deleteRecursively()
    }

    private val files = File(temp, "files/maps")

    private fun bundle(folder: String, id: String, kind: PackKind, content: String = "tiles $id", withFile: Boolean = true, sha: String? = null) {
        val dir = File(assets, "maps/$folder").apply { mkdirs() }
        val file = File(dir, "$id.pmtiles")
        if (withFile) file.writeText(content)
        val digest = sha ?: sha256(content.toByteArray())
        File(dir, BundledPacks.MANIFEST).writeText(
            """{"id":"$id","kind":"$kind","buildDate":"20261013","file":"$id.pmtiles","sha256":"$digest","bytes":${content.length},""" +
                """"bbox":[28.59,43.92,28.675,44.01],"maxzoom":15,"tool":"map_extract"}""",
        )
        File(dir, "fonts/Noto Sans Regular").mkdirs()
        File(dir, "sprites/v4").mkdirs()
    }

    private fun bundled() = BundledPacks(DirSource(assets), files)

    private fun pack(id: String, kind: PackKind, bbox: List<Double>, minzoom: Int = 0) =
        InstalledPack(PackManifest(id, kind, "20261013", "$id.pmtiles", "ab", 5, bbox, minzoom = minzoom, maxzoom = 15), "/packs/$id".toPath())

    private fun names(dir: File) = dir.list()?.sorted().orEmpty()

    // --- the packs in the APK ---

    @Test
    fun theOverviewAndTheNewestDatedPackAreCopiedCheckedAndMadeReadOnly() {
        bundle("overview", "world", PackKind.OVERVIEW)
        bundle("20261012", "old", PackKind.EVENT)
        bundle("20261013", "costinesti", PackKind.EVENT)
        File(assets, "maps/notes").mkdirs()
        assertEquals("nothing copied yet", emptyList<InstalledPack>(), bundled().installed())
        val packs = bundled().install()
        assertEquals(listOf("world", "costinesti"), packs.map { it.manifest.id })
        val world = sha256("tiles world".toByteArray()).take(12)
        assertEquals(listOf("20261013-" + sha256("tiles costinesti".toByteArray()).take(12), "overview-$world"), names(File(files, "bundled")))
        packs.forEach { pack ->
            val file = pack.file.toFile()
            assertEquals("tiles " + pack.manifest.id, file.readText())
            assertTrue(!file.canWrite())
        }
        assertEquals("in place: the next start draws them at once", packs, bundled().installed())
        assertEquals("/android_asset/maps/20261013", bundled().styleAssets().toString().replace('\\', '/'))
    }

    @Test
    fun aWrongChecksumAMissingFileOrAnOldManifestInstallNothing() {
        bundle("overview", "world", PackKind.OVERVIEW, sha = "00")
        bundle("20261013", "costinesti", PackKind.EVENT, withFile = false)
        assertEquals(emptyList<InstalledPack>(), bundled().install())
        File(assets, "maps/20261014").mkdirs()
        File(assets, "maps/20261014/manifest.json").writeText("""{"buildDate":"20261014","sha256":"ab","bbox":[1,2,3,4],"maxzoom":15}""")
        assertEquals("a manifest without id and kind", emptyList<InstalledPack>(), bundled().install())
        assertNull(bundled().styleAssets())
        assertEquals("no half copy left", emptyList<String>(), names(File(files, "bundled")))
    }

    @Test
    fun aPackWithOtherContentGetsItsOwnFolderAndTheOldOneGoes() {
        bundle("overview", "world", PackKind.OVERVIEW, content = "tiles one")
        val first = bundled().install().single()
        bundle("overview", "world", PackKind.OVERVIEW, content = "tiles two")
        val second = bundled().install().single()
        assertTrue(first.dir != second.dir)
        assertEquals("tiles two", second.file.toFile().readText())
        assertEquals(listOf(second.dir.name), names(File(files, "bundled")))
    }

    @Test
    fun theCopiesOlderVersionsMadeGoAndDownloadedAreasStay() {
        bundle("overview", "world", PackKind.OVERVIEW)
        listOf("20261012", "20261013.tmp", "areas/ro").forEach { File(files, it).mkdirs() }
        File(files, "20261012/costinesti.pmtiles").apply { writeText("x") }.setReadOnly()
        bundled().install()
        assertEquals(listOf("areas", "bundled"), names(files))
    }

    @Test
    fun fontsAndSpritesAreReadFromTheApkAndTilesFromTheCopies() {
        val urls = BundledPacks.urls
        assertEquals("asset://maps/20261013", urls.file(BundledPacks.ASSETS / "maps" / "20261013"))
        assertEquals(
            "pmtiles://file:///data/user/0/org.pingup.app/files/maps/bundled/overview-58b352170d32/world.pmtiles",
            urls.tiles("/data/user/0/org.pingup.app/files/maps/bundled/overview-58b352170d32/world.pmtiles".toPath()).replace('\\', '/'),
        )
    }

    // --- what the APK ships ---

    @Test
    fun theShippedPacksAreTheWorldAndTheFestivalWithTheirChecksums() {
        val packs = BundledPacks(DirSource(shipped), files).install()
        assertEquals(listOf("world" to PackKind.OVERVIEW, "costinesti" to PackKind.EVENT), packs.map { it.manifest.id to it.manifest.kind })
        packs.forEach { assertTrue(it.manifest.attribution, "© OpenStreetMap contributors" in it.manifest.attribution) }
        assertEquals(listOf(-180.0, -85.0511287, 180.0, 85.0511287), packs.first().manifest.bbox)
    }

    @Test
    fun theComposedStyleUsesOnlyTheBundledFontsAndSprites() {
        val bundled = BundledPacks(DirSource(shipped), files)
        val styleAssets = bundled.styleAssets()!!
        val folder = File(shipped, "maps/" + styleAssets.name)
        val packs = bundled.install()
        for (dark in listOf(false, true)) {
            val style = Json.parseToJsonElement(MapStyleBuilder(styleAssets, BundledPacks.urls).build(packs, dark, "ro", palette)).jsonObject
            assertEquals("asset://maps/${styleAssets.name}/fonts/{fontstack}/{range}.pbf", style["glyphs"]!!.jsonPrimitive.content)
            val sprite = style["sprite"]!!.jsonPrimitive.content.removePrefix("asset://maps/${styleAssets.name}/")
            listOf(".json", ".png", "@2x.json", "@2x.png").forEach { assertTrue(sprite + it, File(folder, sprite + it).isFile) }
            val fonts = style["layers"]!!.jsonArray.mapNotNull { (it.jsonObject["layout"] as? JsonObject)?.get("text-font") as? JsonArray }
                .flatten().map { it.jsonPrimitive.content }.toSet()
            assertTrue(fonts.isNotEmpty())
            fonts.forEach { assertTrue(it, File(folder, "fonts/$it/0-255.pbf").isFile) }
            val tiles = style["sources"]!!.jsonObject.values.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
            assertEquals(2, tiles.size)
            assertTrue(tiles.joinToString(), tiles.all { it.startsWith("pmtiles://file://") })
            assertEquals(listOf("world.pmtiles", "costinesti.pmtiles"), tiles.map { it.substringAfterLast('/') })
        }
    }

    // --- the registry ---

    @Test
    fun theRegistryStacksTheOverviewTheAreasThenTheEvent() {
        val registry = AndroidPackRegistry()
        val world = pack("world", PackKind.OVERVIEW, listOf(-180.0, -85.0, 180.0, 85.0))
        val festival = pack("costinesti", PackKind.EVENT, listOf(28.59, 43.92, 28.675, 44.01))
        val area = pack("ro-bucuresti", PackKind.AREA, listOf(25.9, 44.3, 26.3, 44.6), minzoom = 7)
        registry.setBundled(listOf(festival, world))
        registry.setAreas(listOf(area, world))
        assertEquals(listOf("world", "ro-bucuresti", "costinesti"), registry.packs.value.map { it.manifest.id })
        assertEquals("costinesti", registry.detailAt(43.95, 28.63, 16.0)?.manifest?.id)
        assertEquals("ro-bucuresti", registry.detailAt(44.43, 26.10, 12.0)?.manifest?.id)
        assertNull("below the area's first zoom only the overview covers", registry.detailAt(44.43, 26.10, 6.0))
        assertNull("Paris: only the overview", registry.detailAt(48.85, 2.35, 12.0))
        registry.setAreas(emptyList())
        assertEquals(listOf("world", "costinesti"), registry.packs.value.map { it.manifest.id })
    }

    @Test
    fun aBoxPastWestOfEastCrossesTheAntimeridian() {
        val fiji = listOf(177.0, -19.0, -179.0, -16.0)
        assertTrue(PackCoverage.covers(fiji, -17.5, 178.5))
        assertTrue(PackCoverage.covers(fiji, -17.5, -179.5))
        assertTrue(!PackCoverage.covers(fiji, -17.5, 170.0))
    }

    @Test
    fun theRealMapNeedsAPackAndItsFonts() {
        val world = pack("world", PackKind.OVERVIEW, listOf(-180.0, -85.0, 180.0, 85.0))
        assertEquals(MapKind.REAL, MapChoice.of(listOf(world), "/fonts".toPath()))
        assertEquals(MapKind.FALLBACK, MapChoice.of(listOf(world), null))
        assertEquals(MapKind.FALLBACK, MapChoice.of(emptyList(), "/fonts".toPath()))
    }

    private val palette = MapPalette(
        "#0E110F", "#FFFFFF", "#1A7F37", "#30D158", "#8E8E93",
        OverlayColors(
            route = Color(0xFF1A7F37), alternative = Color(0xB83B4338), crowded = Color(0xFFFF9500), closed = Color(0xFF0E110F),
            exitOnly = Color(0xFF2B5E45), evacuate = Color(0xFFFF3B30), meeting = Color(0xFFFF9500), lastSeen = Color(0xB83B4338),
            service = Color(0xFF285476), searchResult = Color(0xFF1A7F37), sharedPlace = Color(0xFF30D158), incident = Color(0xFFFF3B30),
        ),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
