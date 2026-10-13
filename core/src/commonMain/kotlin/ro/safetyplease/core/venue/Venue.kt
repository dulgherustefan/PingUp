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

/**
 * A useful point on the map: exit, wc, water, info, charge, dea (a defibrillator). With a [number], 1 to 255, it is a
 * landmark of the site plan: a report names the one nearest to the reporter by it, so the number must not change
 * during an event. [team] is the staff team that reports its status to the console, if any.
 */
@Serializable
data class Poi(val type: String, val lat: Double, val lon: Double, val name: String = "", val number: Int = 0, val team: String = "") {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/**
 * An opening in a barrier, the only place a walk may cross one: within [REACH_M] of its point. [number] names the
 * useful point it stands at (an exit, usually), 0 for none; a [staffOnly] gate is closed to the public.
 */
@Serializable
data class Gate(val lat: Double, val lon: Double, val name: String = "", val number: Int = 0, val staffOnly: Boolean = false) {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    companion object {
        const val REACH_M = 5
    }
}

/** An area nobody walks through ([NONE]) or only staff do ([STAFF]): backstage, technical areas, VIP. */
@Serializable
data class BlockedArea(val polygon: List<GeoPoint>, val access: String = STAFF, val name: String = "") {
    companion object {
        const val STAFF = "staff"
        const val NONE = "none"
    }
}

/** Where one zone leaves in an evacuation: the numbers of two exit points; [alternative] is 0 when there is none. */
@Serializable
data class EvacuationExit(val main: Int, val alternative: Int = 0)

/**
 * One of the organiser's approved evacuation plans: [id] 1..255 is what the console's evacuation names on the wire,
 * [exits] gives each zone, by id, its exits. [EvacuationPlans] checks it against the venue.
 */
@Serializable
data class EvacuationPlan(val id: Int, val name: String, val exits: Map<String, EvacuationExit> = emptyMap())

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
    /** The organiser has a safeguarding team that answers discreet reports; without it the app does not offer them. */
    val safeguarding: Boolean = false,
    /** Lakes, the sea, rivers: a fix inside one is a GNSS error, since nobody stands there. */
    val water: List<List<GeoPoint>> = emptyList(),
    /** Fences as polylines: nobody crosses one but at a [Gate]. */
    val barriers: List<List<GeoPoint>> = emptyList(),
    val gates: List<Gate> = emptyList(),
    val blocked: List<BlockedArea> = emptyList(),
    /** Numbers of the landmarks fit for meeting someone; empty means every numbered landmark ([meetingPlaces]). */
    val meetingLandmarks: List<Int> = emptyList(),
    val evacuation: List<EvacuationPlan> = emptyList(),
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

    fun landmark(number: Int): Poi? = if (number in LANDMARK_NUMBERS) pois.firstOrNull { it.number == number } else null

    /** The landmarks a group may be sent to meet at: those the organiser marked, else every numbered one. */
    fun meetingPlaces(): List<Poi> {
        val numbered = pois.filter { it.number in LANDMARK_NUMBERS }
        return if (meetingLandmarks.isEmpty()) numbered else numbered.filter { it.number in meetingLandmarks }
    }

    fun evacuationPlan(id: Int): EvacuationPlan? = evacuation.firstOrNull { it.id == id }

    /** The landmark a team would walk to: the nearest numbered point, if one is within [LANDMARK_METERS]. */
    fun nearestLandmark(from: GeoPoint): Poi? = pois.filter { it.number in LANDMARK_NUMBERS }
        .map { it to distanceMeters(from, it.point) }
        .filter { it.second <= LANDMARK_METERS }
        .minByOrNull { it.second }?.first

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val MEETING = "meeting"
        const val DEA = "dea"
        const val EXIT = "exit"

        /** A shaded place, where the heat notice sends people. */
        const val SHADE = "shade"

        /** A landline phone the public can use; the service layer and the 112 card both read this type. */
        const val PHONE = "phone"

        /** Farther than this, "near" would send a team to the wrong place. */
        const val LANDMARK_METERS = 100

        /** One byte in a report; 0 means none. */
        val LANDMARK_NUMBERS = 1..255

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
    val dLon = (a.lon - b.lon) * metersPerDegree * cos(radians((a.lat + b.lat) / 2))
    return hypot(dLat, dLon).roundToInt()
}

/** Metres from [point] to the nearest edge of [polygon], 0 inside it; on a flat projection around the point. */
fun metersToPolygon(point: GeoPoint, polygon: List<GeoPoint>): Double {
    if (pointInPolygon(point.lat, point.lon, polygon)) return 0.0
    val metersPerDegree = 111_320.0
    val scale = cos(radians(point.lat))
    fun x(p: GeoPoint) = (p.lon - point.lon) * metersPerDegree * scale
    fun y(p: GeoPoint) = (p.lat - point.lat) * metersPerDegree
    var best = Double.MAX_VALUE
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        val ax = x(a)
        val ay = y(a)
        val dx = x(b) - ax
        val dy = y(b) - ay
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
        best = minOf(best, hypot(ax + t * dx, ay + t * dy))
    }
    return best
}

/** Direction from [from] to [to] in eight sectors: 0 north, 1 north-east, ... 7 north-west. */
fun compassSector(from: GeoPoint, to: GeoPoint): Int {
    val dLat = to.lat - from.lat
    val dLon = (to.lon - from.lon) * cos(radians((from.lat + to.lat) / 2))
    val degrees = (degrees(atan2(dLon, dLat)) + 360) % 360
    return ((degrees + 22.5) / 45).toInt() % 8
}

/** Math.toRadians and Math.toDegrees, with the JVM's constants, so every platform rounds the same way. */
fun radians(degrees: Double): Double = degrees * 0.017453292519943295

internal fun degrees(radians: Double): Double = radians * 57.29577951308232
