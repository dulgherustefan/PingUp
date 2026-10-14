package ro.safetyplease.ui.map.style

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ro.safetyplease.core.map.InstalledPack
import ro.safetyplease.core.map.PackKind
import ro.safetyplease.core.map.PackManifest
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.ui.map.MapGeoJson
import ro.safetyplease.ui.map.MapMe
import ro.safetyplease.ui.map.MapPalette
import ro.safetyplease.ui.map.OverlayColors
import ro.safetyplease.ui.readText
import ro.safetyplease.ui.repoFile
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapStyleBuilderTest {
    private val files = "/data/user/0/org.pingup.app/files/maps"
    private val bundled = repoFile("app/src/main/assets/maps/20261013")
    private val builder = MapStyleBuilder("$files/20261013".toPath())

    private val palette = MapPalette(
        ink = "#0E110F", halo = "#FFFFFF", me = "#1A7F37", friend = "#30D158", stale = "#8E8E93",
        overlay = OverlayColors(
            route = Color(0xFF1A7F37), alternative = Color(0xB83B4338), crowded = Color(0xFFFF9500), closed = Color(0xFF0E110F),
            exitOnly = Color(0xFF2B5E45), evacuate = Color(0xFFFF3B30), meeting = Color(0xFFFF9500), lastSeen = Color(0xB83B4338),
            service = Color(0xFF285476), searchResult = Color(0xFF1A7F37), sharedPlace = Color(0xFF30D158), incident = Color(0xFFFF3B30),
        ),
    )

    /** The festival's own pack, from the manifest the app ships. */
    private val festival = InstalledPack(
        Json.decodeFromString(PackManifest.serializer(), (bundled / "manifest.json").readText()),
        "$files/20261013".toPath(),
    )

    private fun pack(id: String, kind: PackKind, bbox: List<Double>, minzoom: Int = 7, maxzoom: Int = 15, attribution: String = "") =
        InstalledPack(
            PackManifest(id, kind, "20261014", "$id.pmtiles", "00".repeat(32), 1_000L, bbox, minzoom, maxzoom, attribution, label = id),
            "$files/packs/$id/20261014".toPath(),
        )

    private val world = pack("world", PackKind.OVERVIEW, listOf(-180.0, -85.0511, 180.0, 85.0511), minzoom = 0, maxzoom = 6)
    private val constanta = pack("ro-constanta", PackKind.AREA, listOf(27.9, 43.7, 29.7, 44.8))
    private val bucuresti = pack("ro-bucuresti", PackKind.AREA, listOf(25.9, 44.3, 26.3, 44.6))

    private val overlayIds = listOf(
        "zones-fill", "zones-line", "zones-selected", "overlay-zones-fill", "overlay-zones-line", "overlay-zones-closed", "grid-line",
        "grid-label", "overlay-route-alternative", "overlay-route-casing", "overlay-route", "pois-circle", "pois-label", "target-circle",
        "markers-circle", "overlay-pins", "overlay-pins-label", "friends-ring", "friends-ring-outline", "friends-dot", "friends-label",
        "me-ring", "me-ring-dashed", "me-dot",
    )

    private fun style(packs: List<InstalledPack>, dark: Boolean = false, language: String = "ro") =
        builder.style(packs, dark, language, palette)

    private fun layers(style: JsonObject) = style.getValue("layers").jsonArray.map { it.jsonObject }

    private fun sources(style: JsonObject) = style.getValue("sources").jsonObject

    private val JsonObject.id: String get() = getValue("id").jsonPrimitive.content

    private val JsonObject.source: String? get() = this["source"]?.jsonPrimitive?.content

    private fun JsonObject.paint(key: String) = getValue("paint").jsonObject[key]

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Numbers to six decimals, so 12 and 12.0 compare equal, and a Float written on another platform too. */
    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        is JsonPrimitive -> if (element.isString) element else element.doubleOrNull?.let { JsonPrimitive((it * 1e6).roundToLong()) } ?: element
    }

    @Test
    fun theComposedStyleParsesAndEveryLayerFindsItsSource() {
        val text = builder.build(listOf(festival, constanta, world, bucuresti), dark = false, language = "ro", palette = palette)
        val style = Json.parseToJsonElement(text).jsonObject
        assertEquals(8, style.getValue("version").jsonPrimitive.int)
        val sources = sources(style)
        layers(style).forEach { layer -> layer.source?.let { assertTrue(it in sources, "${layer.id} draws from $it") } }
        val overview = sources.getValue("overview-world").jsonObject
        assertEquals("pmtiles://file://$files/packs/world/20261014/world.pmtiles", overview.text("url"))
        val event = sources.getValue("event-costinesti").jsonObject
        assertEquals("pmtiles://file://$files/20261013/costinesti.pmtiles", event.text("url"))
        assertEquals("vector", event.text("type"))
        assertEquals(listOf("0", "15"), listOf("minzoom", "maxzoom").map { event.text(it) })
        assertEquals(listOf("7", "15"), listOf("minzoom", "maxzoom").map { sources.getValue("area-ro-constanta").jsonObject.text(it) })
        assertEquals("file://$files/20261013/fonts/{fontstack}/{range}.pbf", style.text("glyphs"))
        assertEquals("file://$files/20261013/sprites/v4/light", style.text("sprite"))
        assertEquals("file://$files/20261013/sprites/v4/dark", style(listOf(festival), dark = true).text("sprite"))
    }

    @Test
    fun everyPackHasItsOwnSourceAndPrefix() {
        val style = style(listOf(world, constanta, bucuresti, festival))
        val packs = listOf("overview-world", "area-ro-constanta", "area-ro-bucuresti", "event-costinesti")
        val layers = layers(style)
        assertEquals(layers.size, layers.map { it.id }.toSet().size, "layer ids are unique")
        for (layer in layers) {
            val source = layer.source ?: continue
            if (source in packs || source == MapStyleBuilder.MASK) assertTrue(layer.id.startsWith("$source:"), layer.id)
        }
        val prefixes = layers.filter { ':' in it.id }.map { it.id.substringBefore(':') }.distinct()
        assertEquals(listOf("overview-world", MapStyleBuilder.MASK, "area-ro-constanta", "area-ro-bucuresti", "event-costinesti"), prefixes)
        assertEquals(packs.toSet(), sources(style).filterValues { it.jsonObject.text("type") == "vector" }.keys)
        // one template for every pack
        val template = layers.filter { it.source == "event-costinesti" }.map { it.id.substringAfter(':') }
        assertEquals(template, layers.filter { it.source == "area-ro-bucuresti" }.map { it.id.substringAfter(':') })
    }

    @Test
    fun twoPacksWithTheSameIdStillGetTwoSources() {
        val chosen = MapStyleBuilder.choose(listOf(constanta, constanta, pack("ro constanta", PackKind.AREA, constanta.manifest.bbox)))
        assertEquals(listOf("area-ro-constanta", "area-ro-constanta-2", "area-ro_constanta"), chosen.areas.map { it.id })
    }

    @Test
    fun layersGoOverviewThenMaskThenAreasThenTheEventThenTheOverlay() {
        val layers = layers(style(listOf(festival, bucuresti, world, constanta)))
        fun rank(layer: JsonObject): Int {
            val source = layer.source
            return when {
                layer.id == MapStyleBuilder.BACKGROUND -> 0
                source == "overview-world" -> 1
                source == MapStyleBuilder.MASK -> 2
                source == "area-ro-bucuresti" -> 3
                source == "area-ro-constanta" -> 4
                source == "event-costinesti" -> 5
                source in OverlayStyle.SOURCES -> 6
                else -> error("unexpected layer ${layer.id}")
            }
        }
        val ranks = layers.map(::rank)
        assertEquals(ranks.sorted(), ranks)
        assertEquals((0..6).toList(), ranks.distinct())
    }

    @Test
    fun labelsReadTheAppLanguageThenEnglishThenTheLocalName() {
        fun names(language: String) = layers(style(listOf(world, constanta, festival), language = language))
            .filter { it.source !in OverlayStyle.SOURCES }
            .mapNotNull { it["layout"]?.jsonObject?.get("text-field") }
        val ro = names("ro")
        // the overview's sea and place names, and each pack's road, water, point and place names
        assertEquals(2 + 4 * 2, ro.size)
        ro.forEach { assertEquals(json("""["coalesce",["get","name:ro"],["get","name:en"],["get","name"]]"""), it) }
        names("en").forEach { assertEquals(json("""["coalesce",["get","name:en"],["get","name"]]"""), it) }
        names("").forEach { assertEquals(json("""["coalesce",["get","name:en"],["get","name"]]"""), it) }
        names("de").forEach { assertEquals(json("""["coalesce",["get","name:de"],["get","name:en"],["get","name"]]"""), it) }
    }

    @Test
    fun whereNoPackHasTilesTheMapIsANeutralGreyNotTheSea() {
        val backgrounds = listOf(false, true).map { dark ->
            val layers = layers(style(listOf(world, constanta, festival), dark = dark))
            val background = layers.first()
            assertEquals(MapStyleBuilder.BACKGROUND, background.id)
            val grey = background.paint("background-color")!!.jsonPrimitive.content
            assertTrue(grey.length == 7 && grey.drop(1).chunked(2).distinct().size == 1, "$grey is a neutral grey")
            for (fill in listOf("water", "earth")) {
                assertNotEquals(layers.first { it.id == "event-costinesti:$fill" }.paint("fill-color")!!.jsonPrimitive.content, grey, fill)
            }
            val masks = layers.filter { it.source == MapStyleBuilder.MASK }
            assertEquals(2, masks.size)
            masks.forEach { assertEquals(grey, it.paint("fill-color")!!.jsonPrimitive.content) }
            grey
        }
        assertNotEquals(backgrounds[0], backgrounds[1])
    }

    @Test
    fun theOverlayIsTodaysLayersWithTodaysFilters() {
        val style = builder.style(listOf(festival), dark = false, language = "ro", palette = palette, selectedZone = "stage")
        val overlay = layers(style).filter { it.source in OverlayStyle.SOURCES }
        assertEquals(overlayIds, overlay.map { it.id })
        assertEquals(overlayIds, layers(style).takeLast(overlayIds.size).map { it.id }, "the overlay is on top")
        assertEquals(
            listOf(
                "zones", "zones", "zones", "zone-styles", "zone-styles", "zone-styles", "grid", "grid", "routes", "routes", "routes",
                "pois", "pois", "target", "markers", "pins", "pins", "friends", "friends", "friends", "friends", "me", "me", "me",
            ).map { "pingup-$it" },
            overlay.map { it.source },
        )
        val polygons = """["==",["geometry-type"],"Polygon"]"""
        val points = """["==",["geometry-type"],"Point"]"""
        val expected = mapOf(
            "zones-selected" to """["==",["get","id"],"stage"]""",
            "overlay-zones-line" to """["!",["==",["get","style"],"closed"]]""",
            "overlay-zones-closed" to """["==",["get","style"],"closed"]""",
            "grid-line" to """["==",["geometry-type"],"LineString"]""",
            "grid-label" to points,
            "overlay-route-alternative" to """["!",["==",["get","style"],"primary"]]""",
            "overlay-route-casing" to """["==",["get","style"],"primary"]""",
            "overlay-route" to """["==",["get","style"],"primary"]""",
            "friends-ring" to """["all",$polygons,["!",["to-boolean",["get","outline"]]]]""",
            "friends-ring-outline" to """["all",$polygons,["to-boolean",["get","outline"]]]""",
            "friends-dot" to points,
            "friends-label" to points,
            "me-ring" to """["all",$polygons,["!",["to-boolean",["get","dashed"]]]]""",
            "me-ring-dashed" to """["all",$polygons,["to-boolean",["get","dashed"]]]""",
            "me-dot" to points,
        )
        overlay.forEach { layer -> assertEquals(expected[layer.id]?.let(::json), layer["filter"], layer.id) }
        val byId = overlay.associateBy { it.id }
        fun paint(id: String, key: String) = byId.getValue(id).paint(key)
        assertEquals(JsonPrimitive(0.22), paint("zones-fill", "fill-opacity"))
        assertEquals(json("""["to-color",["get","color"]]"""), paint("zones-fill", "fill-color"))
        assertEquals(
            listOf(4, 8, 5).map { JsonPrimitive(it) },
            listOf("overlay-route-alternative", "overlay-route-casing", "overlay-route").map { paint(it, "line-width") },
        )
        assertEquals(JsonPrimitive("rgba(59, 67, 56, 0.722)"), paint("overlay-route-alternative", "line-color"))
        assertEquals(JsonPrimitive("#1a7f37"), paint("overlay-route", "line-color"))
        assertEquals(json("""["case",["get","grey"],"#8E8E93","#30D158"]"""), paint("friends-dot", "circle-color"))
        assertEquals(json("""["*",0.16,["to-number",["get","fade"]]]"""), paint("friends-ring", "fill-opacity"))
        val meColor = json("""["case",["to-boolean",["get","grey"]],"#8E8E93","#1A7F37"]""")
        assertEquals(meColor, paint("me-ring", "fill-color"))
        assertEquals(meColor, paint("me-ring-dashed", "line-color"))
        assertEquals(meColor, paint("me-dot", "circle-color"))
        assertEquals(JsonPrimitive(0), paint("pois-label", "text-halo-width"))
        val pinsLabel = byId.getValue("overlay-pins-label").getValue("layout").jsonObject
        assertEquals(json("""["Noto Sans Medium"]"""), pinsLabel["text-font"])
        assertEquals(json("[0,0.9]"), pinsLabel["text-offset"])
        val empty = json(OverlayStyle.EMPTY)
        OverlayStyle.SOURCES.forEach { assertEquals(empty, sources(style).getValue(it).jsonObject["data"], it) }
    }

    @Test
    fun theOverlayCanStartWithTheEnginesData() {
        val me = MapGeoJson.me(MapMe(43.95, 28.635, accuracyM = 10f))
        val style = builder.style(listOf(festival), false, "ro", palette, overlayData = mapOf(OverlayStyle.ME to me))
        assertEquals(json(me), sources(style).getValue(OverlayStyle.ME).jsonObject["data"])
    }

    @Test
    fun withoutAnOverviewTheFestivalIsDrawnAsToday() {
        for ((dark, file) in listOf(false to "style-light.json", true to "style-dark.json")) {
            val resolved = (bundled / file).readText().replace("__PACK__", "$files/20261013").replace("__ASSETS__", "$files/20261013")
            val today = json(resolved).jsonObject
            val style = style(listOf(festival), dark = dark)
            val composed = layers(style)
            val todays = layers(today)
            assertEquals(listOf("event-costinesti") + OverlayStyle.SOURCES, sources(style).keys.toList(), "no overview, no mask")
            assertEquals(1 + (todays.size - 1) + overlayIds.size, composed.size)
            // the only changes: the background is no longer the sea, and names fall back to English before the local name
            val todayBackground = todays.first().paint("background-color")
            assertEquals(todays.first { it.id == "water" }.paint("fill-color"), todayBackground)
            assertNotEquals(todayBackground, composed.first().paint("background-color"))
            val oldName = json("""["coalesce",["get","name:ro"],["get","name"]]""")
            val newName = json("""["coalesce",["get","name:ro"],["get","name:en"],["get","name"]]""")
            todays.drop(1).zip(composed.drop(1)).forEach { (old, new) ->
                val layout = old["layout"]?.jsonObject
                val renamed = if (layout?.get("text-field") == oldName) JsonObject(layout + ("text-field" to newName)) else layout
                val onPack = "source" to JsonPrimitive("event-costinesti")
                val expected = JsonObject(old + onPack + listOfNotNull(renamed?.let { "layout" to it }))
                val actual = JsonObject(new + ("id" to JsonPrimitive(new.id.removePrefix("event-costinesti:"))))
                assertEquals(canonical(expected), canonical(actual), old.id)
            }
            val oldSource = sources(today).getValue("protomaps").jsonObject
            val newSource = sources(style).getValue("event-costinesti").jsonObject
            for (key in listOf("type", "url", "minzoom", "maxzoom")) assertEquals(oldSource[key], newSource[key], key)
            // the credit comes from the manifest: today's, then ESA WorldCover's for the landcover of zooms 0-7
            assertTrue(newSource.text("attribution").startsWith(oldSource.text("attribution") + " "), newSource.text("attribution"))
            for (key in listOf("version", "glyphs", "sprite")) assertEquals(today[key], style[key], key)
        }
    }

    @Test
    fun sourcesCreditOpenStreetMapContributorsWithTheLink() {
        val credited = pack("credited", PackKind.AREA, listOf(20.0, 44.0, 21.0, 45.0), attribution = "© Someone &amp; co")
        val sources = sources(style(listOf(world, credited, festival)))
        fun attribution(source: String) = sources.getValue(source).jsonObject.text("attribution")
        assertEquals(MapStyleBuilder.OSM_ATTRIBUTION, attribution("overview-world"), "a manifest without one gets OpenStreetMap's")
        assertEquals(festival.manifest.attribution, attribution("event-costinesti"), "the bundled manifest's")
        assertTrue("href=\"https://www.openstreetmap.org/copyright\"" in MapStyleBuilder.OSM_ATTRIBUTION)
        assertTrue(">© OpenStreetMap contributors<" in MapStyleBuilder.OSM_ATTRIBUTION)
        assertEquals("© Someone &amp; co", attribution("area-credited"))
        assertEquals(
            listOf("© OpenStreetMap contributors", ESA, "© Someone & co"),
            MapStyleBuilder.credits(listOf(world, festival, credited)),
            "each link once, as text",
        )
    }

    @Test
    fun atMostEightAreasAreDrawnAndOnlyTheyAreMasked() {
        val areas = (1..10).map { pack("a$it", PackKind.AREA, listOf(20.0 + it, 44.0, 20.5 + it, 44.5)) }
        val chosen = MapStyleBuilder.choose(listOf(world) + areas + festival)
        assertEquals(areas.take(MapStyleBuilder.MAX_AREAS), chosen.areas.map { it.pack })
        assertEquals(areas.drop(MapStyleBuilder.MAX_AREAS), chosen.left)
        val style = style(listOf(world) + areas + festival)
        assertEquals(1 + MapStyleBuilder.MAX_AREAS + 1, sources(style).values.count { it.jsonObject.text("type") == "vector" })
        val masked = sources(style).getValue(MapStyleBuilder.MASK).jsonObject.getValue("data").jsonObject.getValue("features").jsonArray
            .map { it.jsonObject.getValue("properties").jsonObject.text("pack") }
        assertEquals((1..8).map { "area-a$it" } + "event-costinesti", masked)
    }

    @Test
    fun theMaskStartsAtEachPacksMinzoomAndFollowsItsOutline() {
        val fiji = pack("fiji", PackKind.AREA, listOf(177.0, -19.0, -179.0, -16.0), minzoom = 8)
        val outline = listOf(GeoPoint(44.0, 28.0), GeoPoint(44.5, 28.5), GeoPoint(44.0, 29.0))
        val packs = listOf(world, constanta, fiji, festival)
        val style = builder.style(packs, false, "ro", palette, outlines = mapOf("ro-constanta" to outline))
        val masks = layers(style).filter { it.source == MapStyleBuilder.MASK }
        assertEquals(listOf("coverage-mask:z0", "coverage-mask:z7", "coverage-mask:z8"), masks.map { it.id })
        masks.forEach { mask ->
            val zoom = mask.id.substringAfter(":z")
            assertEquals(zoom, mask.text("minzoom"))
            assertEquals(json("""["==",["get","minzoom"],$zoom]"""), mask["filter"])
        }
        val features = sources(style).getValue(MapStyleBuilder.MASK).jsonObject.getValue("data").jsonObject.getValue("features").jsonArray
            .map { it.jsonObject }
        fun rings(pack: String) = features.filter { it.getValue("properties").jsonObject.text("pack") == pack }
            .map { it.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.single() }
        assertEquals(listOf(canonical(json("[[28,44],[28.5,44.5],[29,44],[28,44]]"))), rings("area-ro-constanta").map(::canonical))
        assertEquals(
            listOf("[[177,-19],[180,-19],[180,-16],[177,-16],[177,-19]]", "[[-180,-19],[-179,-19],[-179,-16],[-180,-16],[-180,-19]]")
                .map { canonical(json(it)) },
            rings("area-fiji").map(::canonical),
            "split at the antimeridian",
        )
        assertEquals(
            canonical(json("[[28.59,43.92],[28.675,43.92],[28.675,44.01],[28.59,44.01],[28.59,43.92]]")),
            canonical(rings("event-costinesti").single()),
        )
        // nothing to hide without an overview, nothing to cover without detail
        assertNull(sources(style(listOf(constanta, festival)))[MapStyleBuilder.MASK])
        assertTrue(layers(style(listOf(world))).none { it.source == MapStyleBuilder.MASK })
    }

    @Test
    fun theOverviewDrawsRoadsUpToZoom8AndNamesUpTo9() {
        val overview = layers(style(listOf(world, festival))).filter { it.source == "overview-world" }
        assertEquals(
            listOf(
                "earth", "landcover", "landuse-park", "landuse-beach", "landuse-urban", "water", "boundaries-country", "boundaries-region",
                "roads-major_road", "roads-highway", "water-label", "places",
            ).map { "overview-world:$it" },
            overview.map { it.id },
        )
        fun maxzoom(layer: JsonObject) = layer["maxzoom"]?.jsonPrimitive?.int
        overview.filter { it.text("source-layer") == "roads" }.forEach { assertEquals(9, maxzoom(it), it.id) }
        overview.filter { it.text("type") == "symbol" }.forEach { assertEquals(10, maxzoom(it), it.id) }
        overview.filter { it.text("type") == "fill" || it.text("source-layer") == "boundaries" }
            .forEach { assertEquals(if (it.id.endsWith(":landcover")) 8 else null, maxzoom(it), it.id) }
        // the same look as the template's
        val event = layers(style(listOf(world, festival))).associateBy { it.id }
        assertEquals(event.getValue("event-costinesti:places")["paint"], overview.last()["paint"])
    }

    @Test
    fun aPhoneNamesItsFilesItsOwnWay() {
        val urls = MapUrls(file = { "file://" + it.toString().replace(" ", "%20") })
        val ios = MapStyleBuilder("/var/app/PingUp.app/maps/20261013".toPath(), urls)
        val pack = InstalledPack(festival.manifest, "/var/mobile/Library/Application Support/pingup/maps/packs/costinesti".toPath())
        val style = ios.style(listOf(pack), dark = false, language = "en", palette = palette)
        assertEquals(
            "pmtiles://file:///var/mobile/Library/Application%20Support/pingup/maps/packs/costinesti/costinesti.pmtiles",
            sources(style).getValue("event-costinesti").jsonObject.text("url"),
        )
        assertEquals("file:///var/app/PingUp.app/maps/20261013/fonts/{fontstack}/{range}.pbf", style.text("glyphs"))
        val custom = MapStyleBuilder("/a".toPath(), MapUrls(tiles = { "pmtiles://asset://$it" }))
        assertEquals(
            "pmtiles://asset:///var/x/costinesti.pmtiles",
            sources(custom.style(listOf(InstalledPack(festival.manifest, "/var/x".toPath())), false, "en", palette))
                .getValue("event-costinesti").jsonObject.text("url"),
        )
    }

    /**
     * The whole style of a phone with the overview, one area and the festival, against
     * ui/src/commonTest/resources/golden/map-style-{light,dark}.json. After a deliberate change the new style is in
     * ui/build/golden/: read the diff, then copy it over.
     */
    @Test
    fun theWholeStyleMatchesItsGoldenFiles() {
        val changed = listOf(false, true).mapNotNull { dark ->
            val name = "map-style-${if (dark) "dark" else "light"}.json"
            val actual = pretty.encodeToString(JsonObject.serializer(), style(listOf(world, constanta, festival), dark = dark)) + "\n"
            val golden = repoFile("ui/src/commonTest/resources/golden/$name")
            if (FileSystem.SYSTEM.exists(golden) && canonical(json(golden.readText())) == canonical(json(actual))) return@mapNotNull null
            val out = repoFile("ui/build/golden")
            FileSystem.SYSTEM.createDirectories(out)
            FileSystem.SYSTEM.write(out / name) { writeUtf8(actual) }
            name
        }
        assertEquals(emptyList(), changed, "the composed style changed; the new files are in ui/build/golden")
    }

    @Test
    fun theShippedManifestIsAPackManifestOfItsFile() {
        val manifest = festival.manifest
        assertEquals("costinesti", manifest.id)
        assertEquals(PackKind.EVENT, manifest.kind)
        assertEquals("20261013", manifest.buildDate)
        assertEquals(listOf(28.59, 43.92, 28.675, 44.01), manifest.bbox)
        assertEquals(0 to 15, manifest.minzoom to manifest.maxzoom)
        assertEquals("Costinești", manifest.label)
        val tiles = bundled / manifest.file
        assertEquals(FileSystem.SYSTEM.metadata(tiles).size, manifest.bytes)
        assertEquals(FileSystem.SYSTEM.read(tiles) { readByteString() }.sha256().hex(), manifest.sha256)
        // zooms 0-7 hold the landcover, from ESA WorldCover
        assertEquals(listOf("© OpenStreetMap contributors", ESA), MapStyleBuilder.credits(listOf(festival)))
    }

    private companion object {
        const val ESA = "© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium"
    }
}
