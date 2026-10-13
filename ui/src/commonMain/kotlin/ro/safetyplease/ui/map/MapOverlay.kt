package ro.safetyplease.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.event.SitePlan
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.common.spotColor
import ro.safetyplease.ui.designsystem.AppColors

enum class RouteStyle { PRIMARY, ALTERNATIVE }

/** A walking route along [points]; an alternative is drawn thinner and dashed. */
data class MapRoute(val points: List<GeoPoint>, val style: RouteStyle = RouteStyle.PRIMARY)

/** How a zone is drawn over its own colors: crowded, closed (hatched), exit only, or being evacuated. */
enum class ZoneStyle { CROWDED, CLOSED, EXIT_ONLY, EVACUATE }

enum class PinKind { MEETING, LAST_SEEN, SERVICE, SEARCH_RESULT, SHARED_PLACE, INCIDENT }

/** A pin a feature puts on the map; a tap on it comes back to that feature with [key]. [label] is written under it. */
data class OverlayPin(val key: String, val point: GeoPoint, val kind: PinKind, val label: String = "")

/** The organiser's site plan, drawn over the base map at [opacity]. */
data class PlanImage(val plan: SitePlan, val opacity: Float = DEFAULT_OPACITY) {
    companion object {
        const val DEFAULT_OPACITY = 0.85f
    }
}

/**
 * What the features draw over the venue on both maps, MapLibre and the drawn one: routes, zone styles by zone id, pins
 * and the site plan. Each feature fills its own layer in [MapOverlays]; the map draws them merged.
 */
data class MapOverlay(
    val routes: List<MapRoute> = emptyList(),
    val zones: Map<String, ZoneStyle> = emptyMap(),
    val pins: List<OverlayPin> = emptyList(),
    val plan: PlanImage? = null,
) {
    /** [other] goes on top: its zone styles and plan win. */
    operator fun plus(other: MapOverlay): MapOverlay =
        MapOverlay(routes + other.routes, zones + other.zones, pins + other.pins, other.plan ?: plan)

    companion object {
        val EMPTY = MapOverlay()

        /** The console's map state as zone styles; an evacuation covers every other state of its zones. */
        fun zoneStyles(state: MapState, venue: Venue): Map<String, ZoneStyle> {
            val styles = state.zones.mapNotNull { (id, zoneState) ->
                when (zoneState) {
                    ZoneState.OPEN -> null
                    ZoneState.CROWDED -> id to ZoneStyle.CROWDED
                    ZoneState.CLOSED -> id to ZoneStyle.CLOSED
                    ZoneState.EXIT_ONLY -> id to ZoneStyle.EXIT_ONLY
                }
            }.toMap()
            val evacuated = state.evacuation?.let { e -> venue.zones.map { it.id }.filter { e.covers(it) } }.orEmpty()
            return styles + evacuated.associateWith { ZoneStyle.EVACUATE }
        }
    }
}

/** The layers of [MapOverlays], one per feature, so no two features write over each other. */
object OverlayLayer {
    const val ROUTE = "route"
    const val CONTROL = "control"
    const val REGASIRE = "regasire"
    const val LAYERS = "layers"
    const val SAFETY = "safety"
    const val STAFF = "staff"
}

/**
 * The overlay every map of the screens draws: the features' layers merged in the order they were first set. A feature
 * sets its layer, with what a tap on one of its pins does, and clears it when it has nothing to show. Used from the UI
 * thread.
 */
class MapOverlays {
    private class Layer(val overlay: MapOverlay, val onPinTap: ((OverlayPin) -> Unit)?)

    private var layers = mapOf<String, Layer>()
    private val merged = MutableStateFlow(MapOverlay.EMPTY)
    val overlay: StateFlow<MapOverlay> = merged

    fun set(layer: String, overlay: MapOverlay, onPinTap: ((OverlayPin) -> Unit)? = null) {
        layers = layers + (layer to Layer(overlay, onPinTap))
        merged.value = layers.values.fold(MapOverlay.EMPTY) { all, l -> all + l.overlay }
    }

    fun clear(layer: String) {
        layers = layers - layer
        merged.value = layers.values.fold(MapOverlay.EMPTY) { all, l -> all + l.overlay }
    }

    /** Hands a tap on the pin with [key] to its layer; false when no layer has such a pin. */
    fun tap(key: String): Boolean {
        for (layer in layers.values.reversed()) {
            val pin = layer.overlay.pins.firstOrNull { it.key == key } ?: continue
            layer.onPinTap?.invoke(pin)
            return true
        }
        return false
    }
}

/** The overlay's colors, the same on both maps. */
data class OverlayColors(
    val route: Color,
    val alternative: Color,
    val crowded: Color,
    val closed: Color,
    val exitOnly: Color,
    val evacuate: Color,
    val meeting: Color,
    val lastSeen: Color,
    val service: Color,
    val searchResult: Color,
    val sharedPlace: Color,
    val incident: Color,
) {
    fun route(style: RouteStyle): Color = if (style == RouteStyle.PRIMARY) route else alternative

    fun zone(style: ZoneStyle): Color = when (style) {
        ZoneStyle.CROWDED -> crowded
        ZoneStyle.CLOSED -> closed
        ZoneStyle.EXIT_ONLY -> exitOnly
        ZoneStyle.EVACUATE -> evacuate
    }

    fun pin(kind: PinKind): Color = when (kind) {
        PinKind.MEETING -> meeting
        PinKind.LAST_SEEN -> lastSeen
        PinKind.SERVICE -> service
        PinKind.SEARCH_RESULT -> searchResult
        PinKind.SHARED_PLACE -> sharedPlace
        PinKind.INCIDENT -> incident
    }

    companion object {
        fun of(colors: AppColors): OverlayColors = OverlayColors(
            route = colors.accent,
            alternative = colors.secondaryLabel,
            crowded = colors.orange,
            closed = colors.label,
            exitOnly = spotColor(colors, Venue.EXIT),
            evacuate = colors.red,
            meeting = colors.orange,
            lastSeen = colors.secondaryLabel,
            service = spotColor(colors, "info"),
            searchResult = colors.accent,
            sharedPlace = colors.green,
            incident = colors.red,
        )

        /** How much of a zone's own colors a style covers. */
        fun fillAlpha(style: ZoneStyle): Float = when (style) {
            ZoneStyle.CROWDED -> 0.30f
            ZoneStyle.CLOSED -> 0.40f
            ZoneStyle.EXIT_ONLY -> 0.25f
            ZoneStyle.EVACUATE -> 0.35f
        }
    }
}

/**
 * The affine map that lays an image of [width] by [height] pixels on screen with its top-left corner at [topLeft], its
 * top-right at [topRight] and its bottom-left at [bottomLeft]; the bottom-right corner follows. A site plan is a
 * rectangle on the ground, so three corners place it on a map drawn without perspective.
 */
fun imageMatrix(topLeft: Offset, topRight: Offset, bottomLeft: Offset, width: Int, height: Int): Matrix {
    val across = (topRight - topLeft) / width.toFloat()
    val down = (bottomLeft - topLeft) / height.toFloat()
    return Matrix().apply {
        values[Matrix.ScaleX] = across.x
        values[Matrix.SkewY] = across.y
        values[Matrix.SkewX] = down.x
        values[Matrix.ScaleY] = down.y
        values[Matrix.TranslateX] = topLeft.x
        values[Matrix.TranslateY] = topLeft.y
    }
}
