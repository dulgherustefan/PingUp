package ro.safetyplease.app.platform.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import ro.safetyplease.core.event.SitePlan
import ro.safetyplease.core.util.validLatLon
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.map.FollowMode
import ro.safetyplease.ui.map.MapFriend
import ro.safetyplease.ui.map.MapGeoJson
import ro.safetyplease.ui.map.MapMarker
import ro.safetyplease.ui.map.MapMe
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.MapPalette
import ro.safetyplease.ui.map.MapZoom
import ro.safetyplease.ui.map.PingMapEngine
import ro.safetyplease.ui.map.style.MapStyleBuilder
import ro.safetyplease.ui.map.style.OverlayStyle

/**
 * [PingMapEngine] over a MapLibre map whose style comes whole from [MapStyleBuilder]: the packs, then the overlay's
 * sources and layers ([OverlayStyle]). The overlay's data is kept here as GeoJSON strings and pushed into those sources
 * after every style load, so a theme change keeps everything on screen; the site plan is an image source added right
 * under the zones. [onTap] gets the point and, over a marker or an overlay pin, its key. [detailed] says whether a pack
 * has streets at a point: following you where only the world overview does stays at a zoom it can be read at.
 */
class MapLibreEngine(
    private val map: MapLibreMap,
    private val palette: () -> MapPalette,
    private val detailed: (GeoPoint) -> Boolean,
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
    private var onIdle: ((GeoPoint, Double) -> Unit)? = null

    override var follow = FollowMode.NONE
        private set

    init {
        map.uiSettings.isLogoEnabled = false
        // the screen writes the credit itself, where its own buttons never cover it
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(MAX_ZOOM)
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = FollowMode.NONE
        }
        map.addOnCameraIdleListener {
            val camera = map.cameraPosition
            val target = camera.target ?: return@addOnCameraIdleListener
            onIdle?.invoke(GeoPoint(target.latitude, target.longitude), camera.zoom)
        }
        map.addOnMapLongClickListener { latLng ->
            val handler = onLongPress ?: return@addOnMapLongClickListener false
            handler(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        map.addOnMapClickListener { latLng ->
            val key = map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), OverlayStyle.MARKERS_LAYER, OverlayStyle.PINS_LAYER)
                .firstNotNullOfOrNull { it.getStringProperty("key") }
            onTap(GeoPoint(latLng.latitude, latLng.longitude), key)
            true
        }
    }

    /** A style from [MapStyleBuilder]: its overlay sources start empty and get what this engine holds once it loads. */
    override fun setStyle(styleJson: String) {
        style = null
        map.setStyle(Style.Builder().fromJson(styleJson)) { loaded ->
            style = loaded
            putOverlay()
            data.forEach { (id, json) -> loaded.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json) }
            select(loaded)
            showPlan(loaded)
        }
    }

    override fun setVenue(venue: Venue, poiColor: (String) -> String) {
        this.venue = venue
        put(OverlayStyle.ZONES, MapGeoJson.zones(venue))
        put(OverlayStyle.GRID, MapGeoJson.grid(SiteGrid.of(venue.bounds)))
        put(OverlayStyle.POIS, MapGeoJson.pois(venue, poiColor))
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
        put(OverlayStyle.ROUTES, MapGeoJson.routes(overlay.routes))
        put(OverlayStyle.ZONE_STYLES, venue?.let { v -> MapGeoJson.zoneStyles(v, overlay.zones) { colors.zone(it).hex() } } ?: OverlayStyle.EMPTY)
        put(OverlayStyle.PINS, MapGeoJson.overlayPins(overlay.pins) { colors.pin(it).hex() })
    }

    private fun showPlan(style: Style) {
        val image = overlay.plan
        if (image != null && style.getSource(OverlayStyle.PLAN) != null && planBitmap?.first === image.plan) {
            style.getLayerAs<RasterLayer>(OverlayStyle.PLAN_LAYER)?.setProperties(rasterOpacity(image.opacity))
            return
        }
        style.removeLayer(OverlayStyle.PLAN_LAYER)
        style.removeSource(OverlayStyle.PLAN)
        if (image == null) return
        val plan = image.plan
        val bitmap = planBitmap?.takeIf { it.first === plan }?.second
            ?: BitmapFactory.decodeByteArray(plan.png, 0, plan.png.size)?.also { planBitmap = plan to it } ?: return
        val corners = LatLngQuad(plan.topLeft.latLng(), plan.topRight.latLng(), plan.bottomRight.latLng(), plan.bottomLeft.latLng())
        style.addSource(ImageSource(OverlayStyle.PLAN, corners, bitmap))
        style.addLayerBelow(RasterLayer(OverlayStyle.PLAN_LAYER, OverlayStyle.PLAN).withProperties(rasterOpacity(image.opacity)), OverlayStyle.ZONES_FILL_LAYER)
    }

    override fun setSelection(zoneId: String, target: GeoPoint?) {
        selectedZone = zoneId
        style?.let(::select)
        put(OverlayStyle.TARGET, MapGeoJson.target(target))
    }

    private fun select(style: Style) {
        style.getLayerAs<LineLayer>(OverlayStyle.SELECTED_LAYER)?.setFilter(Expression.eq(Expression.get("id"), selectedZone))
    }

    override fun setMarkers(markers: List<MapMarker>) = put(OverlayStyle.MARKERS, MapGeoJson.markers(markers))

    override fun setFriends(friends: List<MapFriend>) = put(OverlayStyle.FRIENDS, MapGeoJson.friends(friends))

    override fun setMe(me: MapMe?) {
        val shown = me?.takeIf { validLatLon(it.lat, it.lon) }
        this.me = shown
        put(OverlayStyle.ME, MapGeoJson.me(shown))
        if (shown != null && follow != FollowMode.NONE) {
            val bearing = if (follow == FollowMode.HEADING_UP) shown.headingDeg?.toDouble() ?: map.cameraPosition.bearing else 0.0
            val target = LatLng(shown.lat, shown.lon)
            val floor = if (detailed(GeoPoint(shown.lat, shown.lon))) FOLLOW_ZOOM else MapZoom.OVERVIEW_OPEN
            // a jump to a place off screen, like the first fix far from the festival, frames it afresh; on screen the
            // zoom the person chose stays
            val onScreen = map.projection.visibleRegion.latLngBounds.contains(target)
            val zoom = if (onScreen) map.cameraPosition.zoom.coerceAtLeast(floor) else floor
            move(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).build()), true)
        }
    }

    override fun setFollow(mode: FollowMode) {
        follow = mode
        if (mode == FollowMode.NONE) return
        setMe(me)
    }

    // LatLng and LatLngBounds throw for a latitude past 90°, and a place from the network (a friend, a report) may have
    // one: the camera only ever goes to places on the earth
    override fun fit(bounds: Bounds, animated: Boolean) {
        val valid = validLatLon(bounds.minLat, bounds.minLon) && validLatLon(bounds.maxLat, bounds.maxLon) &&
            bounds.minLat <= bounds.maxLat && bounds.minLon <= bounds.maxLon
        if (!valid) return
        move(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon), PADDING), animated)
    }

    override fun show(points: List<GeoPoint>, maxZoom: Double, animated: Boolean) {
        val shown = points.filter { validLatLon(it.lat, it.lon) }
        if (shown.isEmpty()) return
        val north = shown.maxOf { it.lat }
        val south = shown.minOf { it.lat }
        val east = shown.maxOf { it.lon }
        val west = shown.minOf { it.lon }
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

    override fun visibleBounds(): Bounds? {
        val box = map.projection.visibleRegion.latLngBounds
        return Bounds(box.latitudeSouth, box.longitudeWest, box.latitudeNorth, box.longitudeEast)
    }

    override fun setOnCameraIdle(onIdle: ((GeoPoint, Double) -> Unit)?) {
        this.onIdle = onIdle
        val target = map.cameraPosition.target ?: return
        onIdle?.invoke(GeoPoint(target.latitude, target.longitude), map.cameraPosition.zoom)
    }

    private fun move(update: CameraUpdate, animated: Boolean) {
        if (animated) map.animateCamera(update, ANIMATION_MS) else map.moveCamera(update)
    }

    private fun put(id: String, geoJson: String) {
        data[id] = geoJson
        style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(geoJson)
    }

    private fun GeoPoint.latLng() = LatLng(lat, lon)

    private fun androidx.compose.ui.graphics.Color.hex(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

    companion object {
        /** The whole world fits on a phone at zoom 2: the overview is there to be seen. */
        const val MIN_ZOOM = 2.0

        /** Tiles stop at 15; beyond it MapLibre overzooms them, which is fine for the organiser's layer on top. */
        const val MAX_ZOOM = 19.0
        const val FOLLOW_ZOOM = 17.0

        private const val PADDING = 64
        private const val TINY = 1e-5
        private const val ANIMATION_MS = 450
    }
}
