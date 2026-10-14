package ro.safetyplease.ui.map.style

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The base map's colors in one theme. All but [noData] and [boundary] are those of tools/map_styles/style-light.json
 * and style-dark.json; [noData] is where no pack has tiles: a neutral grey, never the water's color.
 */
internal class BaseColors(
    val noData: String,
    val earth: String,
    val green: String,
    val farmland: String,
    val barren: String,
    val beach: String,
    val pedestrian: String,
    val hospital: String,
    val school: String,
    val urban: String,
    val water: String,
    val building: String,
    val buildingOutline: String,
    val path: String,
    val casing: String,
    val minorRoad: String,
    val majorRoad: String,
    val highway: String,
    val rail: String,
    val boundary: String,
    val roadLabel: String,
    val waterLabel: String,
    val poiLabel: String,
    val placeLabel: String,
    val halo: String,
    val sprite: String,
) {
    companion object {
        val LIGHT = BaseColors(
            noData = "#cccccc", earth = "#e2dfda", green = "#9cd3b4", farmland = "#ece8d8", barren = "#e2e0d7", beach = "#f2e6b8",
            pedestrian = "#e3e0d4", hospital = "#e4dad9", school = "#e4ded7", urban = "#d1dde1", water = "#80deea",
            building = "#cccccc", buildingOutline = "#bdbab4", path = "#f5f3ef", casing = "#d4d2cd", minorRoad = "#ffffff",
            majorRoad = "#ffffff", highway = "#ffffff", rail = "#a7b1b3", boundary = "#adadad", roadLabel = "#616161",
            waterLabel = "#3d7a8a", poiLabel = "#5c5c5c", placeLabel = "#3d3d3d", halo = "#ffffff", sprite = "light",
        )
        val DARK = BaseColors(
            noData = "#3a3a3a", earth = "#1f1f1f", green = "#232a25", farmland = "#242424", barren = "#2b2a27", beach = "#34322a",
            pedestrian = "#262626", hospital = "#2a2526", school = "#28262a", urban = "#25282a", water = "#31353f",
            building = "#2e2e2e", buildingOutline = "#383838", path = "#2d2d2d", casing = "#161616", minorRoad = "#333333",
            majorRoad = "#3d3d3d", highway = "#4a4a4a", rail = "#4f4f4f", boundary = "#666666", roadLabel = "#9e9e9e",
            waterLabel = "#7a8a99", poiLabel = "#a3a3a3", placeLabel = "#c4c4c4", halo = "#1f1f1f", sprite = "dark",
        )
    }
}

/**
 * The layers that draw a Protomaps v4 pack, without source: the template of tools/map_styles, the same for the event's
 * map and each downloaded area, and the world overview's share of them.
 */
internal object BaseLayers {
    /** Overview roads stop after zoom 8 and its labels after 9: past that they are z6 data blown up. */
    const val OVERVIEW_ROADS_MAXZOOM = 9
    const val OVERVIEW_LABELS_MAXZOOM = 10

    private const val OVERVIEW_HIGHWAY_MINZOOM = 4
    private const val OVERVIEW_MAJOR_ROAD_MINZOOM = 6
    private const val OVERVIEW_WATER_LABEL_MINZOOM = 3

    /** The festival's layers, as tools/map_styles has them, with [name] as every label's text. */
    fun pack(c: BaseColors, name: JsonElement): List<JsonObject> = parse(template(c, name.toString()))

    /**
     * The overview's: its fills at every zoom the template gives them, country and region boundaries, the main roads
     * up to [OVERVIEW_ROADS_MAXZOOM] and the sea and place names up to [OVERVIEW_LABELS_MAXZOOM].
     */
    fun overview(c: BaseColors, name: JsonElement): List<JsonObject> {
        val layers = pack(c, name).associateBy { it.id }
        fun take(id: String, minzoom: Int? = null, maxzoom: Int? = null): JsonObject {
            val layer = layers.getValue(id).toMutableMap()
            minzoom?.let { layer["minzoom"] = JsonPrimitive(it) }
            maxzoom?.let { layer["maxzoom"] = JsonPrimitive(it) }
            return JsonObject(layer)
        }
        return listOf("earth", "landcover", "landuse-park", "landuse-beach", "landuse-urban", "water").map { take(it) } +
            boundaries(c) +
            take("roads-major_road", OVERVIEW_MAJOR_ROAD_MINZOOM, OVERVIEW_ROADS_MAXZOOM) +
            take("roads-highway", OVERVIEW_HIGHWAY_MINZOOM, OVERVIEW_ROADS_MAXZOOM) +
            take("water-label", OVERVIEW_WATER_LABEL_MINZOOM, OVERVIEW_LABELS_MAXZOOM) +
            take("places", maxzoom = OVERVIEW_LABELS_MAXZOOM)
    }

    private fun boundaries(c: BaseColors): List<JsonObject> = parse(
        """[
        {"id":"boundaries-country","type":"line","source-layer":"boundaries","filter":["==",["get","kind"],"country"],
         "layout":{"line-join":"round"},
         "paint":{"line-color":"${c.boundary}","line-width":["interpolate",["linear"],["zoom"],2,0.7,10,1.6],"line-dasharray":[3,2]}},
        {"id":"boundaries-region","type":"line","source-layer":"boundaries","minzoom":5,"filter":["!=",["get","kind"],"country"],
         "layout":{"line-join":"round"},
         "paint":{"line-color":"${c.boundary}","line-width":["interpolate",["linear"],["zoom"],5,0.4,10,0.8],"line-opacity":0.6}}
        ]""",
    )

    private val JsonObject.id: String get() = getValue("id").jsonPrimitive.content

    private fun parse(text: String): List<JsonObject> = json(text).jsonArray.map { it.jsonObject }

    private fun template(c: BaseColors, name: String): String = """[
    {"id":"earth","type":"fill","source-layer":"earth","paint":{"fill-color":"${c.earth}"}},
    {"id":"landcover","type":"fill","source-layer":"landcover","maxzoom":8,
     "paint":{"fill-color":["match",["get","kind"],"forest","${c.green}","grassland","${c.green}","farmland","${c.farmland}",
       "barren","${c.barren}","${c.earth}"],"fill-opacity":0.6}},
    {"id":"landuse-park","type":"fill","source-layer":"landuse",
     "filter":["in",["get","kind"],["literal",["park","national_park","nature_reserve","protected_area","garden","grass","grassland",
       "forest","wood","scrub","cemetery","golf_course","playground"]]],
     "paint":{"fill-color":"${c.green}"}},
    {"id":"landuse-beach","type":"fill","source-layer":"landuse","filter":["in",["get","kind"],["literal",["beach","sand"]]],
     "paint":{"fill-color":"${c.beach}"}},
    {"id":"landuse-urban","type":"fill","source-layer":"landuse",
     "filter":["in",["get","kind"],["literal",["pedestrian","hospital","school","university","college","industrial","railway","parking",
       "military"]]],
     "paint":{"fill-color":["match",["get","kind"],"pedestrian","${c.pedestrian}","hospital","${c.hospital}",
       ["school","university","college"],"${c.school}","${c.urban}"]}},
    {"id":"water","type":"fill","source-layer":"water","filter":["==",["geometry-type"],"Polygon"],"paint":{"fill-color":"${c.water}"}},
    {"id":"water-lines","type":"line","source-layer":"water","minzoom":11,"filter":["==",["geometry-type"],"LineString"],
     "paint":{"line-color":"${c.water}","line-width":["interpolate",["exponential",1.6],["zoom"],11,0.5,18,6]}},
    {"id":"buildings","type":"fill","source-layer":"buildings","minzoom":13,
     "paint":{"fill-color":"${c.building}","fill-outline-color":"${c.buildingOutline}",
       "fill-opacity":["interpolate",["linear"],["zoom"],13,0,15,0.9]}},
    {"id":"roads-other","type":"line","source-layer":"roads","minzoom":14,"filter":["==",["get","kind"],"other"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.path}","line-width":["interpolate",["exponential",1.6],["zoom"],14,0.3,18,2.5],"line-dasharray":[2,1]}},
    {"id":"roads-path","type":"line","source-layer":"roads","minzoom":14,"filter":["==",["get","kind"],"path"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.path}","line-width":["interpolate",["exponential",1.6],["zoom"],14,0.3,18,2.5],"line-dasharray":[2,1]}},
    {"id":"roads-minor_road-casing","type":"line","source-layer":"roads","minzoom":13,"filter":["==",["get","kind"],"minor_road"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.casing}","line-gap-width":["interpolate",["exponential",1.6],["zoom"],12,0.3,18,8],"line-width":1}},
    {"id":"roads-minor_road","type":"line","source-layer":"roads","minzoom":12,"filter":["==",["get","kind"],"minor_road"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.minorRoad}","line-width":["interpolate",["exponential",1.6],["zoom"],12,0.3,18,8]}},
    {"id":"roads-major_road-casing","type":"line","source-layer":"roads","minzoom":13,"filter":["==",["get","kind"],"major_road"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.casing}","line-gap-width":["interpolate",["exponential",1.6],["zoom"],10,0.5,18,12],"line-width":1}},
    {"id":"roads-major_road","type":"line","source-layer":"roads","minzoom":10,"filter":["==",["get","kind"],"major_road"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.majorRoad}","line-width":["interpolate",["exponential",1.6],["zoom"],10,0.5,18,12]}},
    {"id":"roads-highway-casing","type":"line","source-layer":"roads","minzoom":13,"filter":["==",["get","kind"],"highway"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.casing}","line-gap-width":["interpolate",["exponential",1.6],["zoom"],8,0.5,18,16],"line-width":1}},
    {"id":"roads-highway","type":"line","source-layer":"roads","minzoom":8,"filter":["==",["get","kind"],"highway"],
     "layout":{"line-cap":"round","line-join":"round"},
     "paint":{"line-color":"${c.highway}","line-width":["interpolate",["exponential",1.6],["zoom"],8,0.5,18,16]}},
    {"id":"roads-rail","type":"line","source-layer":"roads","minzoom":11,"filter":["==",["get","kind"],"rail"],
     "paint":{"line-color":"${c.rail}","line-width":["interpolate",["linear"],["zoom"],11,0.6,18,2.5],"line-dasharray":[3,2]}},
    {"id":"roads-label","type":"symbol","source-layer":"roads","minzoom":14,
     "filter":["in",["get","kind"],["literal",["highway","major_road","minor_road","path"]]],
     "layout":{"symbol-placement":"line","text-field":$name,"text-font":["Noto Sans Regular"],"text-size":12},
     "paint":{"text-color":"${c.roadLabel}","text-halo-color":"${c.halo}","text-halo-width":1.5}},
    {"id":"water-label","type":"symbol","source-layer":"water","minzoom":10,"filter":["==",["geometry-type"],"Point"],
     "layout":{"text-field":$name,"text-font":["Noto Sans Regular"],"text-size":13,"text-max-width":8},
     "paint":{"text-color":"${c.waterLabel}","text-halo-color":"${c.water}","text-halo-width":1}},
    {"id":"pois","type":"symbol","source-layer":"pois","minzoom":15,
     "layout":{"icon-image":["get","kind"],"icon-size":0.9,"text-field":$name,"text-font":["Noto Sans Regular"],"text-size":11,
       "text-anchor":"top","text-offset":[0,0.9],"text-max-width":8,"text-optional":true},
     "paint":{"text-color":"${c.poiLabel}","text-halo-color":"${c.halo}","text-halo-width":1.5}},
    {"id":"places","type":"symbol","source-layer":"places",
     "filter":["in",["get","kind"],["literal",["locality","neighbourhood","macrohood","region","country"]]],
     "layout":{"text-field":$name,"text-font":["Noto Sans Medium"],
       "text-size":["match",["get","kind"],"locality",15,"region",13,"country",13,12],
       "text-transform":["match",["get","kind"],["neighbourhood","macrohood"],"uppercase","none"],
       "text-letter-spacing":["match",["get","kind"],["neighbourhood","macrohood"],0.1,0],"text-max-width":8},
     "paint":{"text-color":"${c.placeLabel}","text-halo-color":"${c.halo}","text-halo-width":1.5}}
    ]"""

    /** `name:<language>`, then `name:en`, then the local `name`, as one expression; a blank language starts at English. */
    fun name(language: String): JsonArray {
        val keys = (listOf("name:${language.trim()}").filter { language.isNotBlank() } + "name:en" + "name").distinct()
        return JsonArray(listOf(JsonPrimitive("coalesce")) + keys.map { JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(it))) })
    }
}
