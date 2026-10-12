package ro.safetyplease.app.platform.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** The bundled assets, as a folder on disk. */
private class DirSource(private val root: File) : PackSource {
    override fun list(path: String): List<String> = File(root, path).takeIf { it.isDirectory }?.list()?.sorted().orEmpty()

    override fun open(path: String): InputStream = File(root, path).inputStream()
}

class MapPackTest {
    private val temp = Files.createTempDirectory("maps").toFile()
    private val assets = File(temp, "assets")
    private val files = File(temp, "files/maps")

    @After
    fun cleanUp() {
        temp.walkTopDown().forEach { it.setWritable(true) }
        temp.deleteRecursively()
    }

    private fun bundle(version: String, tiles: ByteArray = "tiles $version".toByteArray(), sha: String? = null) {
        val dir = File(assets, "maps/$version").apply { mkdirs() }
        File(dir, MapPack.TILES).writeBytes(tiles)
        File(dir, MapPack.STYLE_LIGHT).writeText("""{"sources":{"p":{"url":"pmtiles://file://__PACK__/costinesti.pmtiles"}}}""")
        File(dir, MapPack.STYLE_DARK).writeText("""{"glyphs":"file://__ASSETS__/fonts/{fontstack}/{range}.pbf"}""")
        File(dir, "fonts/Noto Sans Regular").mkdirs()
        File(dir, "fonts/Noto Sans Regular/0-255.pbf").writeBytes(byteArrayOf(1, 2, 3))
        val digest = sha ?: sha256Hex(tiles.inputStream())
        File(dir, MapPack.MANIFEST).writeText(
            """{"buildDate":"$version","sha256":"$digest","bbox":[28.6038,43.9295,28.6662,43.9745],"maxzoom":15}""",
        )
    }

    private fun install() = MapInstaller(DirSource(assets), files).install()

    // --- style placeholders ---

    @Test
    fun placeholdersBecomeTheAbsolutePackFolder() {
        val style = """{"url":"pmtiles://file://__PACK__/costinesti.pmtiles","glyphs":"file://__ASSETS__/fonts/{fontstack}/{range}.pbf"}"""
        assertEquals(
            """{"url":"pmtiles://file:///data/user/0/org.pingup.app/files/maps/20261012/costinesti.pmtiles",""" +
                """"glyphs":"file:///data/user/0/org.pingup.app/files/maps/20261012/fonts/{fontstack}/{range}.pbf"}""",
            MapStyle.resolve(style, "/data/user/0/org.pingup.app/files/maps/20261012/"),
        )
    }

    @Test
    fun windowsSeparatorsAreTurnedIntoSlashes() {
        assertEquals("file://C:/maps/1/x", MapStyle.resolve("file://__PACK__/x", "C:\\maps\\1"))
    }

    @Test
    fun shippedStylesResolveToTheirPackAndUseOnlyBundledFonts() {
        listOf("style-light.json", "style-dark.json").forEach { name ->
            val template = File("../tools/map_styles/$name").readText()
            val style = Json.parseToJsonElement(MapStyle.resolve(template, "/pack")).jsonObject
            assertFalse(style.toString().contains("__"))
            val source = style["sources"]!!.jsonObject["protomaps"]!!.jsonObject
            assertEquals("pmtiles://file:///pack/costinesti.pmtiles", source["url"]!!.jsonPrimitive.content)
            assertEquals("15", source["maxzoom"]!!.jsonPrimitive.content)
            assertEquals("© OpenStreetMap", source["attribution"]!!.jsonPrimitive.content)
            assertEquals("file:///pack/fonts/{fontstack}/{range}.pbf", style["glyphs"]!!.jsonPrimitive.content)
            val fonts = style["layers"]!!.jsonArray.mapNotNull { (it.jsonObject["layout"] as? JsonObject)?.get("text-font") as? JsonArray }
                .flatten().map { it.jsonPrimitive.content }.toSet()
            assertEquals(setOf("Noto Sans Regular", "Noto Sans Medium"), fonts)
        }
    }

    // --- versions ---

    @Test
    fun newestBuildDateWinsAndOtherNamesAreIgnored() {
        assertEquals("20261012", MapVersions.newest(listOf("20260901", "20261012", "20261013.tmp", "notes", "2026101")))
        assertNull(MapVersions.newest(listOf("20261013.tmp")))
    }

    // --- install ---

    @Test
    fun withoutBundledDataThereIsNoPack() {
        assertNull(install())
        assertEquals(MapKind.FALLBACK, MapChoice.of(MapState.Missing, textOnly = false))
    }

    @Test
    fun bundledPackIsCopiedVerifiedAndMadeReadOnly() {
        bundle("20261012")
        val pack = install()
        assertNotNull(pack)
        pack!!
        assertEquals(File(files, "20261012"), pack.dir)
        assertEquals("20261012", pack.manifest.buildDate)
        assertEquals(listOf(28.6038, 43.9295, 28.6662, 43.9745), pack.manifest.bbox)
        assertTrue(File(pack.dir, "fonts/Noto Sans Regular/0-255.pbf").isFile)
        pack.dir.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.path, it.canWrite()) }
        val path = pack.dir.absolutePath.replace('\\', '/')
        assertEquals("""{"sources":{"p":{"url":"pmtiles://file://$path/costinesti.pmtiles"}}}""", pack.style(dark = false))
        assertEquals(MapKind.REAL, MapChoice.of(MapState.Ready(pack), textOnly = false))
    }

    @Test
    fun aWrongChecksumInstallsNothing() {
        bundle("20261012", sha = "00".repeat(32))
        assertNull(install())
        assertTrue(files.list().isNullOrEmpty())
    }

    @Test
    fun anInstalledVersionIsNeverRewritten() {
        bundle("20261012")
        val tiles = File(install()!!.dir, MapPack.TILES)
        val stamp = tiles.lastModified() - 10_000
        tiles.setLastModified(stamp)
        assertEquals(File(files, "20261012"), install()!!.dir)
        assertEquals(stamp, tiles.lastModified())
    }

    @Test
    fun aNewerBuildGetsItsOwnFolderAndTheOldOneGoes() {
        bundle("20261012")
        install()
        File(assets, "maps/20261012").deleteRecursively()
        bundle("20261101")
        val pack = install()!!
        assertEquals(File(files, "20261101"), pack.dir)
        assertEquals(listOf("20261101"), files.list()!!.toList())
    }

    @Test
    fun anIncompleteCopyLeftByACrashIsCleanedUp() {
        File(files, "20261012.tmp").apply { mkdirs() }.resolve("costinesti.pmtiles").writeText("half")
        bundle("20261012")
        install()
        assertEquals(listOf("20261012"), files.list()!!.toList())
    }

    @Test
    fun manifestIgnoresUnknownFields() {
        val manifest = MapManifest.parse("""{"buildDate":"20261012","sha256":"ab","bbox":[1,2,3,4],"maxzoom":15,"tool":"pmtiles"}""")
        assertEquals(MapPack.TILES, manifest.file)
        assertEquals(15, manifest.maxzoom)
    }

    // --- which map the screen shows ---

    @Test
    fun screenWaitsWhileLoadingAndFallsBackInTextOnlyMode() {
        assertEquals(MapKind.PENDING, MapChoice.of(MapState.Loading, textOnly = false))
        assertEquals(MapKind.FALLBACK, MapChoice.of(MapState.Loading, textOnly = true))
        bundle("20261012")
        assertEquals(MapKind.FALLBACK, MapChoice.of(MapState.Ready(install()!!), textOnly = true))
    }
}
