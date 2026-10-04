package ro.safetyplease.app.venue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

@Serializable
data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

@Serializable
data class Zone(val id: String, val name: String, val color: String, val polygon: List<GeoPoint>) {
    val center: GeoPoint
        get() = GeoPoint(polygon.map { it.lat }.average(), polygon.map { it.lon }.average())
}

/** Layout-ul evenimentului. Vine din assets/venue.json; schimbarea locului inseamna doar alt fisier. */
@Serializable
data class Venue(
    val name: String,
    val bounds: Bounds,
    val zones: List<Zone>,
    val meetingPoint: GeoPoint? = null,
) {
    fun zoneAt(lat: Double, lon: Double): Zone? = zones.firstOrNull { pointInPolygon(lat, lon, it.polygon) }

    fun zone(id: String): Zone? = zones.firstOrNull { it.id == id }

    fun zoneName(id: String): String = zone(id)?.name ?: id

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Venue = json.decodeFromString(serializer(), text)
    }
}

/** Ray casting: numara de cate ori o raza spre est taie laturile poligonului. */
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
