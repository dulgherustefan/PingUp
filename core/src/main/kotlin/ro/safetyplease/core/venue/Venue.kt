package ro.safetyplease.core.venue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

@Serializable
data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

/** [type] picks the icon and color: stage, food, bar, medical, entrance, camping, chill. */
@Serializable
data class Zone(val id: String, val name: String, val color: String, val polygon: List<GeoPoint>, val type: String = "") {
    val center: GeoPoint
        get() = GeoPoint(polygon.map { it.lat }.average(), polygon.map { it.lon }.average())
}

/** A useful point on the map: exit, wc, water, info, charge, dea (a defibrillator). */
@Serializable
data class Poi(val type: String, val lat: Double, val lon: Double, val name: String = "") {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/** A place the map can lead you to: a useful point, the center of a zone or the meeting point. */
data class Spot(val type: String, val name: String, val point: GeoPoint)

/** Event layout, loaded from assets/venue.json; a different venue is just a different file. */
@Serializable
data class Venue(
    val name: String,
    val bounds: Bounds,
    val zones: List<Zone>,
    val meetingPoint: GeoPoint? = null,
    val pois: List<Poi> = emptyList(),
    /** Walkways as polylines; drawn under the zones. */
    val paths: List<List<GeoPoint>> = emptyList(),
) {
    fun zoneAt(lat: Double, lon: Double): Zone? = zones.firstOrNull { pointInPolygon(lat, lon, it.polygon) }

    fun zone(id: String): Zone? = zones.firstOrNull { it.id == id }

    fun zoneName(id: String): String = zone(id)?.name ?: id

    /** Places of one type; medical zones count as medical places, the meeting point has the type [MEETING]. */
    fun spots(type: String): List<Spot> = when (type) {
        MEETING -> listOfNotNull(meetingPoint?.let { Spot(MEETING, "", it) })
        else -> zones.filter { it.type == type }.map { Spot(type, it.name, it.center) } +
            pois.filter { it.type == type }.map { Spot(type, it.name, it.point) }
    }

    /** The nearest place of a type; without a position, the first one in the file. */
    fun nearest(type: String, from: GeoPoint?): Spot? =
        spots(type).let { all -> if (from == null) all.firstOrNull() else all.minByOrNull { distanceMeters(from, it.point) } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val MEETING = "meeting"
        const val DEA = "dea"

        fun parse(text: String): Venue = json.decodeFromString(serializer(), text)
    }
}

/** Ray casting: counts how many times a ray going east crosses the polygon edges. */
fun pointInPolygon(lat: Double, lon: Double, polygon: List<GeoPoint>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        val crosses = (a.lat > lat) != (b.lat > lat) &&
            lon < (b.lon - a.lon) * (lat - a.lat) / (b.lat - a.lat) + a.lon
        if (crosses) inside = !inside
        j = i
    }
    return inside
}

/** Distance in meters between two nearby points; accurate enough within one event. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Int {
    val metersPerDegree = 111_320.0
    val dLat = (a.lat - b.lat) * metersPerDegree
    val dLon = (a.lon - b.lon) * metersPerDegree * cos(Math.toRadians((a.lat + b.lat) / 2))
    return hypot(dLat, dLon).roundToInt()
}

/** Direction from [from] to [to] in eight sectors: 0 north, 1 north-east, ... 7 north-west. */
fun compassSector(from: GeoPoint, to: GeoPoint): Int {
    val dLat = to.lat - from.lat
    val dLon = (to.lon - from.lon) * cos(Math.toRadians((from.lat + to.lat) / 2))
    val degrees = (Math.toDegrees(atan2(dLon, dLat)) + 360) % 360
    return ((degrees + 22.5) / 45).toInt() % 8
}
