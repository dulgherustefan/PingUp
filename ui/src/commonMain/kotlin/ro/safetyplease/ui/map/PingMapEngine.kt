package ro.safetyplease.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import ro.safetyplease.core.position.FriendAge
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/** [accuracyM] is the radius the phone reports for the fix; the ring drawn around the dot is the 95% one. */
data class MapMe(val lat: Double, val lon: Double, val accuracyM: Float, val headingDeg: Float? = null)

/**
 * A friend's last shared position: [ringM] is the ring to draw, already grown with the age ([FriendRing]); [age] makes
 * the dot full, normal, faded or grey. [label] is what the map writes under the dot.
 */
data class MapFriend(
    val id: String,
    val lat: Double,
    val lon: Double,
    val ringM: Float,
    val age: FriendAge,
    val label: String = "",
) {
    /** Opacity of the dot: full for the first ten minutes, faded after. */
    val fade: Float get() = if (age == FriendAge.NOW || age == FriendAge.RECENT) 1f else STALE_FADE

    /** A fresh position is drawn a little larger: the "full pin". */
    val dotRadius: Float get() = if (age == FriendAge.NOW) 8.5f else 7f

    val grey: Boolean get() = age == FriendAge.STALE

    private companion object {
        const val STALE_FADE = 0.5f
    }
}

/** A tappable marker, such as an open incident; [color] is #RRGGBB. */
data class MapMarker(val key: String, val point: GeoPoint, val color: String)

enum class FollowMode { NONE, NORTH_UP, HEADING_UP }

/**
 * The map as the screens see it. One implementation, over MapLibre Native; moving to another renderer
 * (maplibre-compose, once its API is stable) means writing this interface again, not touching the screens.
 */
interface PingMapEngine {
    /** A full MapLibre style, placeholders already resolved; the overlay is put back on top after every load. */
    fun setStyle(styleJson: String)

    /** The organiser's layer: zones, grid, numbered points and the meeting point; [poiColor] gives #RRGGBB by type. */
    fun setVenue(venue: Venue, poiColor: (String) -> String)

    /** The zone drawn as selected and the place being led to; empty or null for none. */
    fun setSelection(zoneId: String, target: GeoPoint?)

    fun setMarkers(markers: List<MapMarker>)

    fun setFriends(friends: List<MapFriend>)

    /** The features' routes, zone styles, pins and site plan ([MapOverlays]), over the organiser's layer. */
    fun setOverlay(overlay: MapOverlay)

    fun setMe(me: MapMe?)

    /** Following keeps "me" in the middle, north or heading up; a drag on the map turns it off. */
    fun setFollow(mode: FollowMode)

    val follow: FollowMode

    fun fit(bounds: Bounds, animated: Boolean)

    /** Fits every point, never closer than [maxZoom]; one point is centered at [maxZoom]. */
    fun show(points: List<GeoPoint>, maxZoom: Double, animated: Boolean)

    fun zoomBy(delta: Double, animated: Boolean)
}

/**
 * #RRGGBB colors from the app theme: text and its halo, "me", friends, and friends seen over 30 minutes ago; [overlay]
 * colors the features' layer.
 */
data class MapPalette(val ink: String, val halo: String, val me: String, val friend: String, val stale: String, val overlay: OverlayColors)

/** The map a screen shows: the offline base map, nothing yet while its data is being installed, or the drawn venue map. */
enum class MapKind { PENDING, REAL, FALLBACK }

/**
 * The offline base map the phone draws itself, under the organiser's layer: MapLibre on Android, with the area's data in
 * the app. Where there is none, as on the iPhone, the drawn venue map stands in.
 */
interface OfflineMap {
    /** Whether its data is ready, still being installed, or missing. */
    val kind: StateFlow<MapKind>

    /** The map, kept in step with the screen. [onReady] gets the engine once the map exists; [onTap] a point and a marker's key. */
    @Composable
    fun Show(
        dark: Boolean,
        palette: MapPalette,
        modifier: Modifier,
        onTap: (GeoPoint, String?) -> Unit,
        onReady: (PingMapEngine) -> Unit,
    )
}
