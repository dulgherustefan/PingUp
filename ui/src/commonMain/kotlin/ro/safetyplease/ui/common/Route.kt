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
import ro.safetyplease.ui.resources.map_meters
import ro.safetyplease.ui.resources.map_route
import ro.safetyplease.ui.resources.map_route_unknown
import ro.safetyplease.ui.resources.maps_km
import ro.safetyplease.ui.resources.maps_km_tenths
import ro.safetyplease.ui.resources.maps_route

/** "120 m spre nord", or "12 km spre nord" past a kilometre, from [origin] to [to]; without an origin, a hint to share where you are. */
@Composable
fun routeText(origin: GeoPoint?, to: GeoPoint): String {
    val directions = stringArrayResource(Res.array.map_directions)
    if (origin == null) return stringResource(Res.string.map_route_unknown)
    val meters = distanceMeters(origin, to)
    val direction = directions[compassSector(origin, to)]
    return if (meters < 1000) stringResource(Res.string.map_route, meters, direction)
    else stringResource(Res.string.maps_route, distanceText(meters), direction)
}

/** How a distance reads: metres under a kilometre, kilometres with tenths under ten, whole kilometres past that. */
sealed interface Distance {
    data class Meters(val meters: Int) : Distance

    data class Tenths(val km: Int, val tenth: Int) : Distance

    data class Km(val km: Int) : Distance

    companion object {
        fun of(meters: Int): Distance {
            if (meters < 1000) return Meters(meters)
            val tenths = (meters + 50) / 100
            return if (tenths < 100) Tenths(tenths / 10, tenths % 10) else Km((meters + 500) / 1000)
        }
    }
}

@Composable
fun distanceText(meters: Int): String = when (val d = Distance.of(meters)) {
    is Distance.Meters -> stringResource(Res.string.map_meters, d.meters)
    is Distance.Tenths -> stringResource(Res.string.maps_km_tenths, d.km, d.tenth)
    is Distance.Km -> stringResource(Res.string.maps_km, d.km)
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
