package ro.safetyplease.app.platform.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import ro.safetyplease.core.event.SitePlan
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.map.FollowMode
import ro.safetyplease.ui.map.MapFriend
import ro.safetyplease.ui.map.MapMarker
import ro.safetyplease.ui.map.MapMe
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.MapPalette
import ro.safetyplease.ui.map.PingMapEngine
import ro.safetyplease.ui.map.RouteStyle
import ro.safetyplease.ui.map.ZoneStyle

/**
 * [PingMapEngine] over a MapLibre map. The overlay sources are kept here as GeoJSON strings and pushed again after
 * every style load, so a theme change keeps everything on screen. [onTap] gets the point and, over a marker or an
 * overlay pin, its key. The site plan is an image source under the zones.
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
    private var venue: Venue? = null
    private var overlay = MapOverlay.EMPTY
    private var planBitmap: Pair<SitePlan, Bitmap>? = null
    private var onLongPress: ((GeoPoint) -> Unit)? = null

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
        map.addOnMapLongClickListener { latLng ->
            val handler = onLongPress ?: return@addOnMapLongClickListener false
            handler(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        map.addOnMapClickListener { latLng ->
            val key = map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), MARKERS_LAYER, PINS_LAYER)
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
        this.venue = venue
        put(ZONES, MapGeoJson.zones(venue))
        put(GRID, MapGeoJson.grid(SiteGrid.of(venue.bounds)))
        put(POIS, MapGeoJson.pois(venue, poiColor))
        putOverlay()
    }

    override fun setOverlay(overlay: MapOverlay) {
        this.overlay = overlay
        putOverlay()
        style?.let(::showPlan)
    }

    /** The overlay's sources, colored from the palette, so a new style gets them in its own colors. */
    private fun putOverlay() {
        val colors = palette().overlay
        put(ROUTES, MapGeoJson.routes(overlay.routes))
        put(ZONE_STYLES, venue?.let { v -> MapGeoJson.zoneStyles(v, overlay.zones) { colors.zone(it).hex() } } ?: EMPTY)
        put(PINS, MapGeoJson.overlayPins(overlay.pins) { colors.pin(it).hex() })
    }

    private fun showPlan(style: Style) {
        val image = overlay.plan
        if (image != null && style.getSource(PLAN) != null && planBitmap?.first === image.plan) {
            style.getLayerAs<RasterLayer>(PLAN_LAYER)?.setProperties(rasterOpacity(image.opacity))
            return
        }
        style.removeLayer(PLAN_LAYER)
        style.removeSource(PLAN)
        if (image == null) return
        val plan = image.plan
        val bitmap = planBitmap?.takeIf { it.first === plan }?.second
            ?: BitmapFactory.decodeByteArray(plan.png, 0, plan.png.size)?.also { planBitmap = plan to it } ?: return
        val corners = LatLngQuad(plan.topLeft.latLng(), plan.topRight.latLng(), plan.bottomRight.latLng(), plan.bottomLeft.latLng())
        style.addSource(ImageSource(PLAN, corners, bitmap))
        style.addLayerBelow(RasterLayer(PLAN_LAYER, PLAN).withProperties(rasterOpacity(image.opacity)), ZONES_FILL_LAYER)
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

    override fun setOnLongPress(onLongPress: ((GeoPoint) -> Unit)?) {
        this.onLongPress = onLongPress
    }

    private fun move(update: CameraUpdate, animated: Boolean) {
        if (animated) map.animateCamera(update, ANIMATION_MS) else map.moveCamera(update)
    }

    private fun put(id: String, geoJson: String) {
        data[id] = geoJson
        style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(geoJson)
    }

    private fun addOverlay(style: Style) {
        putOverlay()
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
        val fade = Expression.toNumber(Expression.get("fade"))
        val outline = Expression.toBool(Expression.get("outline"))
        val friendColor = Expression.switchCase(Expression.get("grey"), Expression.color(colors.stale.toColorInt()), Expression.color(colors.friend.toColorInt()))
        val dataColor = Expression.toColor(Expression.get("color"))
        val primary = Expression.eq(Expression.get("style"), RouteStyle.PRIMARY.name.lowercase())
        val closed = Expression.eq(Expression.get("style"), ZoneStyle.CLOSED.name.lowercase())
        val dashes = lineDasharray(arrayOf(2f, 1.5f))
        listOf<Layer>(
            FillLayer(ZONES_FILL_LAYER, ZONES).withProperties(fillColor(Expression.toColor(Expression.get("color"))), fillOpacity(0.22f)),
            LineLayer("zones-line", ZONES).withProperties(lineColor(Expression.toColor(Expression.get("color"))), lineWidth(1.5f)),
            LineLayer(SELECTED_LAYER, ZONES).withProperties(lineColor(Expression.toColor(Expression.get("color"))), lineWidth(4f))
                .withFilter(Expression.eq(Expression.get("id"), selectedZone)),
            FillLayer("overlay-zones-fill", ZONE_STYLES).withProperties(fillColor(dataColor), fillOpacity(Expression.toNumber(Expression.get("opacity")))),
            LineLayer("overlay-zones-line", ZONE_STYLES).withProperties(lineColor(dataColor), lineWidth(2.5f)).withFilter(Expression.not(closed)),
            LineLayer("overlay-zones-closed", ZONE_STYLES).withProperties(lineColor(dataColor), lineWidth(2.5f), dashes).withFilter(closed),
            LineLayer("grid-line", GRID).withProperties(lineColor(ink), lineOpacity(0.18f), lineWidth(1f))
                .withFilter(Expression.eq(Expression.geometryType(), "LineString")),
            label(SymbolLayer("grid-label", GRID), 11f).withProperties(textField(Expression.get("label")), textFont(MEDIUM))
                .withFilter(points),
            route(LineLayer("overlay-route-alternative", ROUTES)).withProperties(lineColor(colors.overlay.alternative.toArgb()), lineWidth(4f), dashes)
                .withFilter(Expression.not(primary)),
            route(LineLayer("overlay-route-casing", ROUTES)).withProperties(lineColor(Color.WHITE), lineWidth(8f)).withFilter(primary),
            route(LineLayer("overlay-route", ROUTES)).withProperties(lineColor(colors.overlay.route.toArgb()), lineWidth(5f)).withFilter(primary),
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
            CircleLayer(PINS_LAYER, PINS).withProperties(circleColor(dataColor), circleRadius(8f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f)),
            label(SymbolLayer("overlay-pins-label", PINS), 12f).withProperties(
                textField(Expression.get("label")), textFont(MEDIUM), textAnchor("top"), textOffset(arrayOf(0f, 0.9f)),
            ),
            FillLayer("friends-ring", FRIENDS).withProperties(fillColor(friendColor), fillOpacity(Expression.product(Expression.literal(0.16f), fade)))
                .withFilter(Expression.all(polygons, Expression.not(outline))),
            LineLayer("friends-ring-outline", FRIENDS).withProperties(lineColor(friendColor), lineWidth(1f), lineOpacity(MapFriend.OUTLINE_OPACITY))
                .withFilter(Expression.all(polygons, outline)),
            CircleLayer("friends-dot", FRIENDS).withProperties(
                circleColor(friendColor), circleRadius(Expression.toNumber(Expression.get("size"))), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
                circleOpacity(fade), circleStrokeOpacity(fade),
            ).withFilter(points),
            label(SymbolLayer("friends-label", FRIENDS), 12f).withProperties(
                textField(Expression.get("label")), textFont(MEDIUM), textAnchor("top"), textOffset(arrayOf(0f, 0.9f)),
            ).withFilter(points),
            FillLayer("me-ring", ME).withProperties(fillColor(colors.me), fillOpacity(0.18f)).withFilter(polygons),
            CircleLayer("me-dot", ME).withProperties(circleColor(colors.me), circleRadius(8f), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f))
                .withFilter(points),
        ).forEach(style::addLayer)
        showPlan(style)
    }

    private fun route(layer: LineLayer): LineLayer = layer.withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))

    private fun GeoPoint.latLng() = LatLng(lat, lon)

    private fun androidx.compose.ui.graphics.Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

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
        private const val ROUTES = "pingup-routes"
        private const val ZONE_STYLES = "pingup-zone-styles"
        private const val PINS = "pingup-pins"
        private const val PLAN = "pingup-plan"
        private val SOURCES = listOf(ZONES, GRID, POIS, TARGET, MARKERS, FRIENDS, ME, ROUTES, ZONE_STYLES, PINS)
        private const val ZONES_FILL_LAYER = "zones-fill"
        private const val PLAN_LAYER = "plan-raster"
        private const val PINS_LAYER = "overlay-pins"
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
