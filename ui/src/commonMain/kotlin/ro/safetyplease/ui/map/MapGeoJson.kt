package ro.safetyplease.ui.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ro.safetyplease.core.util.validLatLon
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.radians
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The overlay as GeoJSON FeatureCollections, the strings a MapLibre GeoJSON source takes on both phones. */
object MapGeoJson {
    private const val METERS_PER_DEGREE = 111_320.0
    private const val RING_STEPS = 48

    /** A closed circle of [radiusM] metres around [center], as polygon vertices; the first point repeats at the end. */
    fun ring(center: GeoPoint, radiusM: Double, steps: Int = RING_STEPS): List<GeoPoint> {
        val dLat = radiusM / METERS_PER_DEGREE
        val dLon = radiusM / (METERS_PER_DEGREE * cos(radians(center.lat)))
        val points = (0 until steps).map { i ->
            val a = 2 * PI * i / steps
            GeoPoint(center.lat + dLat * cos(a), center.lon + dLon * sin(a))
        }
        return points + points.first()
    }

    fun zones(venue: Venue): String = collection(
        venue.zones.filter { it.polygon.size >= 3 }.map { zone ->
            feature(polygon(zone.polygon + zone.polygon.first())) {
                put("id", zone.id)
                put("name", zone.name)
                put("color", zone.color)
            }
        },
    )

    /** The square edges as lines, then a letter over each column and a number beside each row, inside the site. */
    fun grid(grid: SiteGrid): String {
        val b = grid.bounds
        val lines = (0..grid.columns).map { c -> feature(line(listOf(GeoPoint(b.maxLat, grid.columnLon(c)), GeoPoint(b.minLat, grid.columnLon(c))))) {} } +
            (0..grid.rows).map { r -> feature(line(listOf(GeoPoint(grid.rowLat(r), b.minLon), GeoPoint(grid.rowLat(r), b.maxLon)))) {} }
        val halfRow = (b.maxLat - b.minLat) / grid.rows / 2
        val halfColumn = (b.maxLon - b.minLon) / grid.columns / 2
        val labels = (0 until grid.columns).map { c ->
            feature(point(GeoPoint(b.maxLat - halfRow, grid.columnLon(c) + halfColumn))) { put("label", grid.columnLabel(c)) }
        } + (0 until grid.rows).map { r ->
            feature(point(GeoPoint(grid.rowLat(r) - halfRow, b.minLon + halfColumn))) { put("label", grid.rowLabel(r)) }
        }
        return collection(lines + labels)
    }

    /** Useful points with their landmark number as the label, and the meeting point. */
    fun pois(venue: Venue, color: (String) -> String): String = collection(
        venue.pois.map { poi ->
            feature(point(poi.point)) {
                put("type", poi.type)
                put("color", color(poi.type))
                put("label", if (poi.number in Venue.LANDMARK_NUMBERS) poi.number.toString() else "")
                put("name", poi.name)
            }
        } + listOfNotNull(
            venue.meetingPoint?.let { meeting ->
                feature(point(meeting)) {
                    put("type", Venue.MEETING)
                    put("color", color(Venue.MEETING))
                    put("label", "")
                    put("name", "")
                }
            },
        ),
    )

    fun target(point: GeoPoint?): String = collection(listOfNotNull(point?.takeIf(::onEarth)?.let { feature(point(it)) {} }))

    fun markers(markers: List<MapMarker>): String = collection(
        markers.filter { onEarth(it.point) }.map { marker ->
            feature(point(marker.point)) {
                put("key", marker.key)
                put("color", marker.color)
            }
        },
    )

    /**
     * Each friend twice: the ring as a polygon (an outline, capped, once the position is old) and the dot, with how
     * faded, how large and whether grey by age.
     */
    fun friends(friends: List<MapFriend>): String = collection(
        friends.filter { validLatLon(it.lat, it.lon) }.flatMap { f ->
            val center = GeoPoint(f.lat, f.lon)
            val look: JsonObjectBuilder.() -> Unit = {
                put("id", f.id)
                put("fade", f.fade)
                put("grey", f.grey)
                put("outline", f.outline)
            }
            listOf(
                feature(polygon(ring(center, f.ringM.toDouble())), look),
                feature(point(center)) {
                    look()
                    put("size", f.dotRadius)
                    put("label", f.label)
                },
            )
        },
    )

    /** The features' routes as lines, each with its style: `primary` or `alternative`. */
    fun routes(routes: List<MapRoute>): String = collection(
        routes.filter { it.points.size >= 2 && it.points.all(::onEarth) }.map { route ->
            feature(line(route.points)) { put("style", route.style.code) }
        },
    )

    /** The styled zones as polygons, with the style, its color and how much of the zone it covers. */
    fun zoneStyles(venue: Venue, styles: Map<String, ZoneStyle>, color: (ZoneStyle) -> String): String = collection(
        venue.zones.filter { it.polygon.size >= 3 }.mapNotNull { zone ->
            val style = styles[zone.id] ?: return@mapNotNull null
            feature(polygon(zone.polygon + zone.polygon.first())) {
                put("id", zone.id)
                put("style", style.code)
                put("color", color(style))
                put("opacity", OverlayColors.fillAlpha(style))
            }
        },
    )

    /** The features' pins, with the key a tap reports, the kind, its color and the label. */
    fun overlayPins(pins: List<OverlayPin>, color: (PinKind) -> String): String = collection(
        pins.filter { onEarth(it.point) }.map { pin ->
            feature(point(pin.point)) {
                put("key", pin.key)
                put("kind", pin.kind.code)
                put("color", color(pin.kind))
                put("label", pin.label)
            }
        },
    )

    private val RouteStyle.code: String get() = name.lowercase()

    private val ZoneStyle.code: String get() = name.lowercase()

    private val PinKind.code: String get() = name.lowercase()

    /** "Me": the ring, dashed without an accuracy, then the dot; both grey once the position is [MapMe.stale]. */
    fun me(me: MapMe?): String = collection(
        if (me == null || !validLatLon(me.lat, me.lon)) emptyList()
        else {
            val center = GeoPoint(me.lat, me.lon)
            val look: JsonObjectBuilder.() -> Unit = {
                put("grey", me.stale)
                put("dashed", me.dashed)
            }
            listOf(feature(polygon(ring(center, me.ringM.toDouble())), look), feature(point(center), look))
        },
    )

    /** Places from the network may be off the earth; they aren't drawn. */
    private fun onEarth(p: GeoPoint) = validLatLon(p.lat, p.lon)

    private fun coordinate(p: GeoPoint) = buildJsonArray {
        add(p.lon)
        add(p.lat)
    }

    private fun point(p: GeoPoint) = buildJsonObject {
        put("type", "Point")
        put("coordinates", coordinate(p))
    }

    private fun line(points: List<GeoPoint>) = buildJsonObject {
        put("type", "LineString")
        put("coordinates", JsonArray(points.map(::coordinate)))
    }

    private fun polygon(closed: List<GeoPoint>) = buildJsonObject {
        put("type", "Polygon")
        put("coordinates", JsonArray(listOf(JsonArray(closed.map(::coordinate)))))
    }

    private fun feature(geometry: JsonObject, properties: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", buildJsonObject(properties))
    }

    private fun collection(features: List<JsonObject>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()
}
