package ro.safetyplease.ui.map.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import ro.safetyplease.core.map.InstalledPack
import ro.safetyplease.core.map.PackKind
import ro.safetyplease.core.map.PackManifest
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.ui.map.MapPalette

/**
 * How the phone's MapLibre names a local file. Android reads `file://<absolute path>`, and a PMTiles archive as
 * `pmtiles://file://<absolute path>`; the iPhone passes its own.
 */
class MapUrls(
    val file: (Path) -> String = { "file://" + it.toString().replace('\\', '/') },
    val tiles: (Path) -> String = { "pmtiles://" + file(it) },
)

/** A pack the style draws: [id] names its source and prefixes its layers' ids as `<id>:<layer>`. */
data class StyleSource(val id: String, val pack: InstalledPack)

/** The packs one style draws, bottom to top, and the areas [left] out past [MapStyleBuilder.MAX_AREAS]. */
data class StylePacks(
    val overview: StyleSource?,
    val areas: List<StyleSource>,
    val events: List<StyleSource>,
    val left: List<InstalledPack>,
) {
    /** The packs with detail, which the coverage mask covers. */
    val detail: List<StyleSource> get() = areas + events
}

/**
 * One MapLibre style for every installed pack, the same JSON on both phones. Bottom to top: a neutral "no data"
 * background; the world overview; an opaque mask over the places other packs have detail, from each one's minzoom, so
 * the overview's roads and names never show through; the downloaded areas; the event's map; the overlay the screens
 * draw ([OverlayStyle]). Every pack is a copy of the same layer template on its own source. Glyphs and sprites come
 * from [assets], which holds `fonts/<stack>/<range>.pbf` and `sprites/v4/{light,dark}`.
 */
class MapStyleBuilder(private val assets: Path, private val urls: MapUrls = MapUrls()) {
    /**
     * The style JSON. Labels read `name:<language>`, then `name:en`, then the local `name`. [selectedZone] and
     * [overlayData] (GeoJSON by [OverlayStyle] source id) start the overlay as the engine has it; [outlines] gives a
     * pack's outline by manifest id, for the mask, where its bbox would cover too much.
     */
    fun build(
        packs: List<InstalledPack>,
        dark: Boolean,
        language: String,
        palette: MapPalette,
        selectedZone: String = "",
        overlayData: Map<String, String> = emptyMap(),
        outlines: Map<String, List<GeoPoint>> = emptyMap(),
    ): String = style(packs, dark, language, palette, selectedZone, overlayData, outlines).toString()

    fun style(
        packs: List<InstalledPack>,
        dark: Boolean,
        language: String,
        palette: MapPalette,
        selectedZone: String = "",
        overlayData: Map<String, String> = emptyMap(),
        outlines: Map<String, List<GeoPoint>> = emptyMap(),
    ): JsonObject {
        val chosen = choose(packs)
        val colors = if (dark) BaseColors.DARK else BaseColors.LIGHT
        val name = BaseLayers.name(language)
        val template = BaseLayers.pack(colors, name)
        val sources = LinkedHashMap<String, JsonElement>()
        val layers = mutableListOf<JsonObject>()
        layers += background(colors.noData)
        chosen.overview?.let { overview ->
            sources[overview.id] = source(overview.pack)
            layers += prefixed(overview.id, BaseLayers.overview(colors, name))
            if (chosen.detail.isNotEmpty()) {
                sources[MASK] = mask(chosen.detail, outlines)
                layers += maskLayers(chosen.detail, colors.noData)
            }
        }
        for (pack in chosen.areas + chosen.events) {
            sources[pack.id] = source(pack.pack)
            layers += prefixed(pack.id, template)
        }
        sources += OverlayStyle.sources(overlayData)
        layers += OverlayStyle.layers(palette, selectedZone)
        val base = urls.file(assets).trimEnd('/')
        return buildJsonObject {
            put("version", 8)
            put("name", if (dark) "Ping Up dark" else "Ping Up light")
            put("sources", JsonObject(sources))
            put("glyphs", "$base/fonts/{fontstack}/{range}.pbf")
            put("sprite", "$base/sprites/v4/${colors.sprite}")
            put("layers", JsonArray(layers))
        }
    }

    private fun source(pack: InstalledPack): JsonObject = buildJsonObject {
        put("type", "vector")
        put("url", urls.tiles(pack.file))
        put("minzoom", pack.manifest.minzoom)
        put("maxzoom", pack.manifest.maxzoom)
        put("attribution", attribution(pack.manifest))
    }

    companion object {
        /** About 25 layers each: past this many areas MapLibre has too many layers to draw smoothly. */
        const val MAX_AREAS = 8

        const val BACKGROUND = "background"

        /** The source of the coverage mask; its layers are `coverage-mask:z<minzoom>`. */
        const val MASK = "coverage-mask"

        const val OSM_ATTRIBUTION = """<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>"""

        /**
         * Which installed packs a style draws: the first overview, at most [MAX_AREAS] areas in the order given (most
         * wanted first) and every event map. Source ids are `<kind>-<pack id>`, unique.
         */
        fun choose(packs: List<InstalledPack>): StylePacks {
            val taken = (listOf(BACKGROUND, MASK) + OverlayStyle.SOURCES).toMutableSet()
            fun named(pack: InstalledPack): StyleSource {
                val safe = pack.manifest.id.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("")
                val base = pack.manifest.kind.name.lowercase() + "-" + safe
                val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
                taken += id
                return StyleSource(id, pack)
            }
            val overview = packs.firstOrNull { it.manifest.kind == PackKind.OVERVIEW }?.let(::named)
            val areas = packs.filter { it.manifest.kind == PackKind.AREA }
            return StylePacks(
                overview = overview,
                areas = areas.take(MAX_AREAS).map(::named),
                events = packs.filter { it.manifest.kind == PackKind.EVENT }.map(::named),
                left = areas.drop(MAX_AREAS),
            )
        }

        /** What a pack's source credits: its manifest's attribution, OpenStreetMap's when the manifest has none. */
        fun attribution(manifest: PackManifest): String = manifest.attribution.ifBlank { OSM_ATTRIBUTION }

        /** Each pack's credit once, as plain text, for "About the map". */
        fun credits(packs: List<InstalledPack>): List<String> = packs.map { pack ->
            attribution(pack.manifest).replace(Regex("<[^>]*>"), "").replace("&copy;", "©").replace("&amp;", "&").trim()
        }.filter { it.isNotEmpty() }.distinct()

        private fun background(color: String): JsonObject = buildJsonObject {
            put("id", BACKGROUND)
            put("type", "background")
            put("paint", buildJsonObject { put("background-color", color) })
        }

        /** The template's layers on [source], with ids `<source>:<id>`. */
        private fun prefixed(source: String, layers: List<JsonObject>): List<JsonObject> = layers.map { layer ->
            buildJsonObject {
                put("id", "$source:" + (layer.getValue("id") as JsonPrimitive).content)
                put("type", layer.getValue("type"))
                put("source", source)
                layer.forEach { (key, value) -> if (key != "id" && key != "type") put(key, value) }
            }
        }

        /** Each detail pack's outline, or its bbox (split at the antimeridian), with the zoom its detail starts at. */
        private fun mask(detail: List<StyleSource>, outlines: Map<String, List<GeoPoint>>): JsonObject {
            val features = detail.flatMap { source ->
                val manifest = source.pack.manifest
                val outline = outlines[manifest.id]?.takeIf { it.size >= 3 }
                val rings = if (outline != null) listOf(outline.map { it.lon to it.lat }) else boxes(manifest.bbox)
                rings.map { ring -> polygon(ring, source.id, manifest.minzoom) }
            }
            return buildJsonObject {
                put("type", "geojson")
                put("data", buildJsonObject {
                    put("type", "FeatureCollection")
                    put("features", JsonArray(features))
                })
            }
        }

        private fun maskLayers(detail: List<StyleSource>, color: String): List<JsonObject> =
            detail.map { it.pack.manifest.minzoom }.distinct().sorted().map { zoom ->
                buildJsonObject {
                    put("id", "$MASK:z$zoom")
                    put("type", "fill")
                    put("source", MASK)
                    put("minzoom", zoom)
                    put("filter", JsonArray(listOf(JsonPrimitive("=="), json("""["get","minzoom"]"""), JsonPrimitive(zoom))))
                    put("paint", buildJsonObject { put("fill-color", color) })
                }
            }

        /** A bbox (west, south, east, north) as rings of (lon, lat); west past east crosses the antimeridian. */
        private fun boxes(bbox: List<Double>): List<List<Pair<Double, Double>>> {
            if (bbox.size != 4) return emptyList()
            val (west, south, east, north) = bbox
            fun box(w: Double, e: Double) = listOf(w to south, e to south, e to north, w to north)
            return if (west <= east) listOf(box(west, east)) else listOf(box(west, 180.0), box(-180.0, east))
        }

        private fun polygon(ring: List<Pair<Double, Double>>, pack: String, minzoom: Int): JsonObject {
            val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
            val coordinates = closed.map { (lon, lat) -> JsonArray(listOf(JsonPrimitive(lon), JsonPrimitive(lat))) }
            return buildJsonObject {
                put("type", "Feature")
                put("geometry", buildJsonObject {
                    put("type", "Polygon")
                    put("coordinates", JsonArray(listOf(JsonArray(coordinates))))
                })
                put("properties", buildJsonObject {
                    put("pack", pack)
                    put("minzoom", minzoom)
                })
            }
        }
    }
}

internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)
