package ro.safetyplease.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import ro.safetyplease.app.R
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.distanceMeters

/** "120 m spre nord" from [origin] to [to]; without an origin, a hint to share where you are. */
@Composable
fun routeText(origin: GeoPoint?, to: GeoPoint): String {
    val directions = stringArrayResource(R.array.map_directions)
    return if (origin != null) {
        stringResource(R.string.map_route, distanceMeters(origin, to), directions[compassSector(origin, to)])
    } else {
        stringResource(R.string.map_route_unknown)
    }
}
