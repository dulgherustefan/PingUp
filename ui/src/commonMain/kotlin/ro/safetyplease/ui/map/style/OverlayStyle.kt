package ro.safetyplease.ui.map.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ro.safetyplease.ui.map.MapFriend
import ro.safetyplease.ui.map.MapPalette
import ro.safetyplease.ui.map.RouteStyle
import ro.safetyplease.ui.map.ZoneStyle
import kotlin.math.roundToInt

/**
 * What the screens draw over the base map, as style JSON: the organiser's zones, grid and points, the features'
 * routes, zone styles and pins, the markers, friends and "me". The source ids, layer ids, filters and looks are the
 * ones MapLibreEngine built in code; the sources start empty and the engine fills them from MapGeoJson. The site plan
 * is not here: it is an image the engine adds right below [ZONES_FILL_LAYER].
 */
object OverlayStyle {
    const val ZONES = "pingup-zones"
    const val GRID = "pingup-grid"
    const val POIS = "pingup-pois"
    const val TARGET = "pingup-target"
    const val MARKERS = "pingup-markers"
    const val FRIENDS = "pingup-friends"
    const val ME = "pingup-me"
    const val ROUTES = "pingup-routes"
    const val ZONE_STYLES = "pingup-zone-styles"
    const val PINS = "pingup-pins"
    val SOURCES = listOf(ZONES, GRID, POIS, TARGET, MARKERS, FRIENDS, ME, ROUTES, ZONE_STYLES, PINS)

    const val PLAN = "pingup-plan"
    const val PLAN_LAYER = "plan-raster"
    const val ZONES_FILL_LAYER = "zones-fill"

    /** Its filter names the selected zone: [selected]. */
    const val SELECTED_LAYER = "zones-selected"

    /** "Me"'s ring where the phone gave no accuracy: a dashed guess. */
    const val ME_DASHED_LAYER = "me-ring-dashed"

    /** A tap looks for a feature's "key" in these two. */
    const val MARKERS_LAYER = "markers-circle"
    const val PINS_LAYER = "overlay-pins"

    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    private val WHITE = JsonPrimitive("#ffffff")
    private val MEDIUM = json("""["Noto Sans Medium"]""")

    /** The overlay's GeoJSON sources, each with its data from [data] by source id, or empty. */
    fun sources(data: Map<String, String> = emptyMap()): Map<String, JsonObject> = SOURCES.associateWith { id ->
        buildJsonObject {
            put("type", "geojson")
            put("data", json(data[id] ?: EMPTY))
        }
    }

    /** The overlay's layers, bottom to top, in [palette]'s colors; [selectedZone] is the zone drawn as selected. */
    fun layers(palette: MapPalette, selectedZone: String = ""): List<JsonObject> {
        val ink = JsonPrimitive(palette.ink)
        val me = JsonPrimitive(palette.me)
        val dataColor = json("""["to-color",["get","color"]]""")
        val polygons = json("""["==",["geometry-type"],"Polygon"]""")
        val points = json("""["==",["geometry-type"],"Point"]""")
        val fade = json("""["to-number",["get","fade"]]""")
        val outline = json("""["to-boolean",["get","outline"]]""")
        val friend = JsonArray(
            listOf(JsonPrimitive("case"), json("""["get","grey"]"""), JsonPrimitive(palette.stale), JsonPrimitive(palette.friend)),
        )
        // "me" grey once the position is old, its ring dashed when the phone gave no accuracy
        val meColor = JsonArray(listOf(JsonPrimitive("case"), json("""["to-boolean",["get","grey"]]"""), JsonPrimitive(palette.stale), me))
        val dashed = json("""["to-boolean",["get","dashed"]]""")
        val primary = json("""["==",["get","style"],"${RouteStyle.PRIMARY.name.lowercase()}"]""")
        val closed = json("""["==",["get","style"],"${ZoneStyle.CLOSED.name.lowercase()}"]""")
        val dashes = json("[2,1.5]")
        val round = mapOf("line-cap" to JsonPrimitive("round"), "line-join" to JsonPrimitive("round"))
        val textPaint = mapOf("text-color" to ink, "text-halo-color" to JsonPrimitive(palette.halo), "text-halo-width" to n(1.5))
        val under = mapOf("text-anchor" to JsonPrimitive("top"), "text-offset" to json("[0,0.9]"))
        fun text(size: Int) = mapOf("text-font" to MEDIUM, "text-size" to n(size), "text-field" to json("""["get","label"]"""))
        fun line(color: JsonElement, width: Number) = mapOf("line-color" to color, "line-width" to n(width))
        fun dot(color: JsonElement, radius: Number, stroke: Number) =
            mapOf("circle-color" to color, "circle-radius" to n(radius), "circle-stroke-color" to WHITE, "circle-stroke-width" to n(stroke))

        return listOf(
            layer(ZONES_FILL_LAYER, "fill", ZONES, paint = mapOf("fill-color" to dataColor, "fill-opacity" to n(0.22))),
            layer("zones-line", "line", ZONES, paint = line(dataColor, 1.5)),
            layer(SELECTED_LAYER, "line", ZONES, selected(selectedZone), paint = line(dataColor, 4)),
            layer(
                "overlay-zones-fill", "fill", ZONE_STYLES,
                paint = mapOf("fill-color" to dataColor, "fill-opacity" to json("""["to-number",["get","opacity"]]""")),
            ),
            layer("overlay-zones-line", "line", ZONE_STYLES, not(closed), paint = line(dataColor, 2.5)),
            layer("overlay-zones-closed", "line", ZONE_STYLES, closed, paint = line(dataColor, 2.5) + ("line-dasharray" to dashes)),
            layer(
                "grid-line", "line", GRID, json("""["==",["geometry-type"],"LineString"]"""),
                paint = line(ink, 1) + ("line-opacity" to n(0.18)),
            ),
            layer("grid-label", "symbol", GRID, points, layout = text(11), paint = textPaint),
            layer(
                "overlay-route-alternative", "line", ROUTES, not(primary), layout = round,
                paint = line(color(palette.overlay.alternative), 4) + ("line-dasharray" to dashes),
            ),
            layer("overlay-route-casing", "line", ROUTES, primary, layout = round, paint = line(WHITE, 8)),
            layer("overlay-route", "line", ROUTES, primary, layout = round, paint = line(color(palette.overlay.route), 5)),
            layer("pois-circle", "circle", POIS, paint = dot(dataColor, 7, 2)),
            layer(
                "pois-label", "symbol", POIS,
                layout = text(10) + ("text-allow-overlap" to JsonPrimitive(true)),
                paint = textPaint + mapOf("text-color" to WHITE, "text-halo-width" to n(0)),
            ),
            layer(
                "target-circle", "circle", TARGET,
                paint = mapOf("circle-radius" to n(11), "circle-opacity" to n(0), "circle-stroke-color" to ink, "circle-stroke-width" to n(3)),
            ),
            layer(MARKERS_LAYER, "circle", MARKERS, paint = dot(dataColor, 9, 2.5)),
            layer(PINS_LAYER, "circle", PINS, paint = dot(dataColor, 8, 2.5)),
            layer("overlay-pins-label", "symbol", PINS, layout = text(12) + under, paint = textPaint),
            layer(
                "friends-ring", "fill", FRIENDS, all(polygons, not(outline)),
                paint = mapOf("fill-color" to friend, "fill-opacity" to JsonArray(listOf(JsonPrimitive("*"), n(0.16), fade))),
            ),
            layer(
                "friends-ring-outline", "line", FRIENDS, all(polygons, outline),
                paint = line(friend, 1) + ("line-opacity" to n(MapFriend.OUTLINE_OPACITY)),
            ),
            layer(
                "friends-dot", "circle", FRIENDS, points,
                paint = mapOf(
                    "circle-color" to friend, "circle-radius" to json("""["to-number",["get","size"]]"""), "circle-stroke-color" to WHITE,
                    "circle-stroke-width" to n(2), "circle-opacity" to fade, "circle-stroke-opacity" to fade,
                ),
            ),
            layer("friends-label", "symbol", FRIENDS, points, layout = text(12) + under, paint = textPaint),
            layer("me-ring", "fill", ME, all(polygons, not(dashed)), paint = mapOf("fill-color" to meColor, "fill-opacity" to n(0.18))),
            layer(ME_DASHED_LAYER, "line", ME, all(polygons, dashed), paint = line(meColor, 1.5) + ("line-dasharray" to dashes)),
            layer("me-dot", "circle", ME, points, paint = dot(meColor, 8, 3)),
        )
    }

    /** The filter of [SELECTED_LAYER] that draws [zoneId] as selected; an engine sets it again when the selection changes. */
    fun selected(zoneId: String): JsonElement = JsonArray(listOf(JsonPrimitive("=="), json("""["get","id"]"""), JsonPrimitive(zoneId)))

    /** A theme color as MapLibre reads it: #rrggbb, or rgba() when it lets the map through, as the Android SDK wrote it. */
    fun color(color: Color): JsonPrimitive {
        val argb = color.toArgb()
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0xFF) return JsonPrimitive("#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0'))
        val rgb = listOf(16, 8, 0).joinToString(", ") { ((argb shr it) and 0xFF).toString() }
        return JsonPrimitive("rgba($rgb, ${(alpha / 255.0 * 1000).roundToInt() / 1000.0})")
    }

    private fun n(value: Number) = JsonPrimitive(value)

    private fun layer(
        id: String,
        type: String,
        source: String,
        filter: JsonElement? = null,
        layout: Map<String, JsonElement> = emptyMap(),
        paint: Map<String, JsonElement> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("source", source)
        filter?.let { put("filter", it) }
        if (layout.isNotEmpty()) put("layout", JsonObject(layout))
        if (paint.isNotEmpty()) put("paint", JsonObject(paint))
    }

    private fun not(expression: JsonElement) = JsonArray(listOf(JsonPrimitive("!"), expression))

    private fun all(vararg expressions: JsonElement) = JsonArray(listOf(JsonPrimitive("all")) + expressions)
}
