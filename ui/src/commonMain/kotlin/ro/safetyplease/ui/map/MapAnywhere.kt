package ro.safetyplease.ui.map

import ro.safetyplease.core.location.MeState
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters

/** Zooms of the real map past the festival. */
object MapZoom {
    /** Where only the world overview covers, the map opens and follows at this zoom: its roads and names still show. */
    const val OVERVIEW_OPEN = 8.0

    /** Past this zoom, with no detailed pack under the middle of the map, it says it is only the overview. */
    const val OVERVIEW_MAX = 9.0
}

/** Where the real map opens: [points] framed no closer than [maxZoom]; none, the whole festival. */
data class MapOpening(val points: List<GeoPoint>, val maxZoom: Double)

/** What the label on top of the map says of where you are. */
sealed interface WhereText {
    data class InZone(val zone: String) : WhereText

    /** On the festival's ground, in no zone. */
    data object OnSite : WhereText

    /** Away from the festival, [meters] from its middle. */
    data class Away(val meters: Int) : WhereText

    /** The last position, [minutes] old. */
    data class Old(val minutes: Long) : WhereText

    /** The zone picked by hand, with no position from the phone. */
    data class Picked(val zone: String) : WhereText

    data object Searching : WhereText

    data object Off : WhereText
}

object MapAnywhere {
    /**
     * Where the real map opens: on you, wherever you are, with the place it was opened for ([toward]); with no position,
     * on that place, or on the festival. Where only the overview covers, no closer than it can be read at.
     */
    fun opening(me: GeoPoint?, toward: GeoPoint?, close: Double, detailed: (GeoPoint) -> Boolean): MapOpening {
        val points = listOfNotNull(me, toward)
        return MapOpening(points, if (points.all(detailed)) close else MapZoom.OVERVIEW_OPEN)
    }

    /** The overview's note: past [MapZoom.OVERVIEW_MAX] with no detailed pack under the middle of the map. */
    fun overviewOnly(zoom: Double, detailed: Boolean): Boolean = zoom > MapZoom.OVERVIEW_MAX && !detailed

    /**
     * What the label says: the zone you are in, or that you are on the ground or how far away, from a fresh or a
     * [simulated] position; else how old the last one is; else the zone picked by hand, never shown as a GNSS
     * position; else that the phone searches or location is off.
     */
    fun where(state: MeState, simulated: GeoPoint?, venue: Venue, manualZone: String, nowMs: Long): WhereText {
        val fresh = simulated ?: (state as? MeState.Fixed)?.fused?.fix?.let { GeoPoint(it.lat, it.lon) }
        if (fresh != null) {
            venue.zoneAt(fresh.lat, fresh.lon)?.let { return WhereText.InZone(it.name) }
            if (venue.bounds.contains(fresh.lat, fresh.lon)) return WhereText.OnSite
            val middle = GeoPoint((venue.bounds.minLat + venue.bounds.maxLat) / 2, (venue.bounds.minLon + venue.bounds.maxLon) / 2)
            return WhereText.Away(distanceMeters(fresh, middle))
        }
        if (state is MeState.Stale) return WhereText.Old(maxOf(1, (nowMs - state.fused.fix.timeMs) / 60_000))
        venue.zone(manualZone)?.let { return WhereText.Picked(it.name) }
        return if (state is MeState.Off) WhereText.Off else WhereText.Searching
    }
}
