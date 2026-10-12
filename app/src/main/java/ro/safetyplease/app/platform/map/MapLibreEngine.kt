package ro.safetyplease.app.platform.map

import android.graphics.Color
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue

/** #RRGGBB colors from the app theme: text and its halo, "me", friends. */
data class MapPalette(val ink: String, val halo: String, val me: String, val friend: String)

/**
 * [PingMapEngine] over a MapLibre map. The overlay sources are kept here as GeoJSON strings and pushed again after
 * every style load, so a theme change keeps everything on screen. [onTap] gets the point and, over a marker, its key.
 */
class MapLibreEngine(
    private val map: MapLibreMap,
    private val palette: () -> MapPalette,
    private val onTap: (GeoPoint, String?) -> Unit,
) : PingMapEngine {
    private val data = mutableMapOf<String, String>()
    private var selectedZone = ""
    private var me: MapMe? = null
    private var style: Style? = null

    override var follow = FollowMode.NONE
        private set

    init {
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isAttributionEnabled = true
        map.uiSettings.isTiltGesturesEnabled = false
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = FollowMode.NONE
        }
        map.addOnMapClickListener { latLng ->
            val key = map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), MARKERS_LAYER)
                .firstNotNullOfOrNull { it.getStringProperty("key") }
            onTap(GeoPoint(latLng.latitude, latLng.longitude), key)
            true
        }
    }

    override fun setStyle(styleJson: String) {
        style = null
        map.setStyle(Style.Builder().fromJson(styleJson)) { loaded ->
            addOverlay(loaded)
            style = loaded
        }
    }

    override fun setVenue(venue: Venue, poiColor: (String) -> String) {
        put(ZONES, MapGeoJson.zones(venue))
        put(GRID, MapGeoJson.grid(SiteGrid.of(venue.bounds)))
        put(POIS, MapGeoJson.pois(venue, poiColor))
    }

    override fun setSelection(zoneId: String, target: GeoPoint?) {
        selectedZone = zoneId
        style?.getLayerAs<LineLayer>(SELECTED_LAYER)?.setFilter(Expression.eq(Expression.get("id"), zoneId))
        put(TARGET, MapGeoJson.target(target))
    }

    override fun setMarkers(markers: List<MapMarker>) = put(MARKERS, MapGeoJson.markers(markers))

    override fun setFriends(friends: List<MapFriend>) = put(FRIENDS, MapGeoJson.friends(friends))

    override fun setMe(me: MapMe?) {
        this.me = me
        put(ME, MapGeoJson.me(me))
        if (me != null && follow != FollowMode.NONE) {
            val bearing = if (follow == FollowMode.HEADING_UP) me.headingDeg?.toDouble() ?: map.cameraPosition.bearing else 0.0
            val zoom = map.cameraPosition.zoom.coerceAtLeast(FOLLOW_ZOOM)
            move(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(me.lat, me.lon)).zoom(zoom).bearing(bearing).build()), true)
        }
    }

    override fun setFollow(mode: FollowMode) {
        follow = mode
        if (mode == FollowMode.NONE) return
        setMe(me)
    }

    override fun fit(bounds: Bounds, animated: Boolean) {
        move(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon), PADDING), animated)
    }

    override fun show(points: List<GeoPoint>, maxZoom: Double, animated: Boolean) {
        if (points.isEmpty()) return
        val north = points.maxOf { it.lat }
        val south = points.minOf { it.lat }
        val east = points.maxOf { it.lon }
        val west = points.minOf { it.lon }
        val fitted = if (north - south < TINY && east - west < TINY) null
        else map.getCameraForLatLngBounds(LatLngBounds.from(north, east, south, west), intArrayOf(PADDING, PADDING, PADDING, PADDING))
        val center = fitted?.target ?: LatLng((north + south) / 2, (east + west) / 2)
        val zoom = fitted?.zoom?.coerceAtMost(maxZoom) ?: maxZoom
        move(CameraUpdateFactory.newLatLngZoom(center, zoom), animated)
    }

    override fun zoomBy(delta: Double, animated: Boolean) = move(CameraUpdateFactory.zoomBy(delta), animated)

    private fun move(update: CameraUpdate, animated: Boolean) {
        if (animated) map.animateCamera(update, ANIMATION_MS) else map.moveCamera(update)
    }

    private fun put(id: String, geoJson: String) {
        data[id] = geoJson
        style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(geoJson)
    }

    private fun addOverlay(style: Style) {
        SOURCES.forEach { id -> style.addSource(GeoJsonSource(id, data[id] ?: EMPTY)) }
        val colors = palette()
        val ink = colors.ink
        val halo = colors.halo
        val label = { layer: SymbolLayer, size: Float ->
            layer.withProperties(
                textFont(REGULAR), textSize(size), textColor(ink), textHaloColor(halo), textHaloWidth(1.5f),
            )
        }
        val polygons = Expression.eq(Expression.geometryType(), "Polygon")
        val points = Expression.eq(Expression.geometryType(), "Point")
        val fadedIfStale = Expression.switchCase(Expression.get("stale"), Expression.literal(0.45f), Expression.literal(1f))
        listOf<Layer>(
            FillLayer("zones-fill", ZONES).withProperties(fillColor(Expression.toColor(Expression.get("color"))), fillOpacity(0.22f)),
            LineLayer("zones-line", ZONES).withProperties(lineColor(Expression.toColor(Expression.get("color"))), lineWidth(1.5f)),
            LineLayer(SELECTED_LAYER, ZONES).withProperties(lineColor(Expression.toColor(Expression.get("color"))), lineWidth(4f))
                .withFilter(Expression.eq(Expression.get("id"), selectedZone)),
            LineLayer("grid-line", GRID).withProperties(lineColor(ink), lineOpacity(0.18f), lineWidth(1f))
                .withFilter(Expression.eq(Expression.geometryType(), "LineString")),
            label(SymbolLayer("grid-label", GRID), 11f).withProperties(textField(Expression.get("label")), textFont(MEDIUM))
                .withFilter(points),
            CircleLayer("pois-circle", POIS).withProperties(
                circleColor(Expression.toColor(Expression.get("color"))), circleRadius(7f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
            ),
            label(SymbolLayer("pois-label", POIS), 10f).withProperties(
                textField(Expression.get("label")), textFont(MEDIUM), textColor(Color.WHITE), textHaloWidth(0f), textAllowOverlap(true),
            ),
            CircleLayer("target-circle", TARGET).withProperties(circleRadius(11f), circleOpacity(0f), circleStrokeColor(ink), circleStrokeWidth(3f)),
            CircleLayer(MARKERS_LAYER, MARKERS).withProperties(
                circleColor(Expression.toColor(Expression.get("color"))), circleRadius(9f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f),
            ),
            FillLayer("friends-ring", FRIENDS).withProperties(fillColor(colors.friend), fillOpacity(Expression.product(Expression.literal(0.16f), fadedIfStale)))
                .withFilter(polygons),
            CircleLayer("friends-dot", FRIENDS).withProperties(
                circleColor(colors.friend), circleRadius(7f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f), circleOpacity(fadedIfStale),
            ).withFilter(points),
            label(SymbolLayer("friends-label", FRIENDS), 12f).withProperties(
                textField(Expression.get("label")), textFont(MEDIUM), textAnchor("top"), textOffset(arrayOf(0f, 0.9f)),
            ).withFilter(points),
            FillLayer("me-ring", ME).withProperties(fillColor(colors.me), fillOpacity(0.18f)).withFilter(polygons),
            CircleLayer("me-dot", ME).withProperties(circleColor(colors.me), circleRadius(8f), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f))
                .withFilter(points),
        ).forEach(style::addLayer)
    }

    companion object {
        const val MIN_ZOOM = 12.0
        /** Tiles stop at 15; beyond it MapLibre overzooms them, which is fine for the organiser's layer on top. */
        const val MAX_ZOOM = 19.0
        const val FOLLOW_ZOOM = 17.0

        private const val ZONES = "pingup-zones"
        private const val GRID = "pingup-grid"
        private const val POIS = "pingup-pois"
        private const val TARGET = "pingup-target"
        private const val MARKERS = "pingup-markers"
        private const val FRIENDS = "pingup-friends"
        private const val ME = "pingup-me"
        private val SOURCES = listOf(ZONES, GRID, POIS, TARGET, MARKERS, FRIENDS, ME)
        private const val SELECTED_LAYER = "zones-selected"
        private const val MARKERS_LAYER = "markers-circle"
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        private val REGULAR = arrayOf("Noto Sans Regular")
        private val MEDIUM = arrayOf("Noto Sans Medium")
        private const val PADDING = 64
        private const val TINY = 1e-5
        private const val ANIMATION_MS = 450
    }
}
