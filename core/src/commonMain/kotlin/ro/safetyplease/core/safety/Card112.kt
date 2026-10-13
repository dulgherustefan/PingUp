package ro.safetyplease.core.safety

import ro.safetyplease.core.util.formatDecimal
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance

/** A place the person can walk to, [meters] away; [name] is empty when the venue file gives none. */
data class NearbyPlace(val type: String, val name: String, val meters: Int)

/**
 * What a person reads aloud to the 112 operator: where the festival and the spot are, the coordinates the operator can
 * type in, the Plus Code, the square of the site plan and the nearest places to walk to for help. The automatic
 * location of the call only reaches the operator when the call connects; this is for when it doesn't, or is wrong.
 * [ageMin] is how old the position is: 0 for a fresh fix.
 */
data class Card112(
    val venueName: String,
    val zoneName: String?,
    val landmark: Int?,
    val point: GeoPoint,
    val accuracyM: Int?,
    val ageMin: Int,
    val plusCode: String,
    val gridCell: String?,
    val landline: NearbyPlace?,
    val medical: NearbyPlace?,
) {
    /** Latitude and longitude with five decimals (about a metre), whatever the phone's language. */
    val coordinates: String get() = formatDecimal(point.lat, DECIMALS) + ", " + formatDecimal(point.lon, DECIMALS)

    /**
     * "Sunt la [atVenue], [zone], lângă Reperul N": [atVenue] and [nearLandmark] are the sentence's own words in the
     * language of the app; the zone is left out when the spot is in none, the landmark when none is near.
     */
    fun spoken(atVenue: String, nearLandmark: String?): String = listOfNotNull(atVenue, zoneName, nearLandmark.takeIf { landmark != null }).joinToString(", ")

    companion object {
        /** The venue's type for a landline phone the public can use. */
        const val LANDLINE = "phone"

        const val DECIMALS = 5

        /**
         * The card for a person at [point], whose position is [ageMs] old and good to [accuracyM] metres (0: unknown).
         * Places are measured by [walk]; one nobody can walk to is not offered.
         */
        fun of(venue: Venue, grid: SiteGrid, point: GeoPoint, accuracyM: Int, ageMs: Long, walk: WalkDistance): Card112 = Card112(
            venueName = venue.name,
            zoneName = venue.zoneAt(point.lat, point.lon)?.name,
            landmark = venue.nearestLandmark(point)?.number,
            point = point,
            accuracyM = accuracyM.takeIf { it > 0 },
            ageMin = (ageMs.coerceAtLeast(0) / 60_000L).toInt(),
            plusCode = OpenLocationCode.encode(point.lat, point.lon),
            gridCell = grid.cellAt(point.lat, point.lon),
            landline = nearest(venue, LANDLINE, point, walk),
            medical = nearest(venue, "medical", point, walk),
        )

        private fun nearest(venue: Venue, type: String, from: GeoPoint, walk: WalkDistance): NearbyPlace? =
            venue.spots(type).mapNotNull { spot -> walk.meters(from, spot.point)?.let { NearbyPlace(type, spot.name, it) } }.minByOrNull { it.meters }
    }
}
