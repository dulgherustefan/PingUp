package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.map_directions
import ro.safetyplease.ui.resources.map_route
import ro.safetyplease.ui.resources.map_route_unknown

/** "120 m spre nord" from [origin] to [to]; without an origin, a hint to share where you are. */
@Composable
fun routeText(origin: GeoPoint?, to: GeoPoint): String {
    val directions = stringArrayResource(Res.array.map_directions)
    return if (origin != null) {
        stringResource(Res.string.map_route, distanceMeters(origin, to), directions[compassSector(origin, to)])
    } else {
        stringResource(Res.string.map_route_unknown)
    }
}

/** Where you are, for distances and routes: only inside the venue's bounds, since from home they'd read hundreds of km. */
fun onSite(position: GeoPoint?, venue: Venue): GeoPoint? = position?.takeIf { venue.bounds.contains(it.lat, it.lon) }

/**
 * Where the way to help is measured from: your position now if you're at the event, else where [report] was sent from,
 * else the zone you picked by hand. Null if we know none of them.
 */
fun helpOrigin(position: GeoPoint?, report: MyReport?, manualZone: String, venue: Venue): GeoPoint? {
    val lat = report?.lat
    val lon = report?.lon
    val reported = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    return onSite(position, venue) ?: reported ?: report?.let { venue.zone(it.zone) }?.center ?: venue.zone(manualZone)?.center
}
