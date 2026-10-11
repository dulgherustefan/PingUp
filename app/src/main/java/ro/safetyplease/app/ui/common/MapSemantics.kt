package ro.safetyplease.app.ui.common

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.pointInPolygon
import kotlin.math.roundToInt

/** The words TalkBack reads on the map, resolved once from resources so [mapNodes] stays testable. */
internal class MapWords(
    /** Place type to its name, e.g. "wc" to "toalete". */
    val types: Map<String, String>,
    /** A zone without a known type. */
    val zone: String,
    val here: String,
    val meeting: String,
    val incident: String,
    /** A pin's severity, e.g. "Urgent". */
    val urgency: (Int) -> String,
    /** The eight compass sectors, north first. */
    val directions: List<String>,
    /** Distance in meters and direction: "120 m spre nord". */
    val route: (Int, String) -> String,
)

/** One thing TalkBack can focus on the map. [zone] is set on zone nodes, [pin] on incident pins. */
internal class MapNode(val description: String, val point: GeoPoint, val zone: Zone? = null, val pin: MapPin? = null)

/**
 * What the map says to TalkBack: every zone first, then the useful points, the meeting point and the [pins].
 * Each says its name, its type unless the name already says it, then "aici ești" for your zone or the distance and
 * direction from [position] when it's known.
 */
internal fun mapNodes(venue: Venue, words: MapWords, position: GeoPoint?, myZone: String, pins: List<MapPin>): List<MapNode> {
    // rounded so TalkBack doesn't re-announce a place for every meter you walk
    fun route(to: GeoPoint) = position?.let { words.route(roundToFive(distanceMeters(it, to)), words.directions[compassSector(it, to)]) }

    val zones = venue.zones.map { zone ->
        val mine = zone.id == myZone || (position != null && pointInPolygon(position.lat, position.lon, zone.polygon))
        MapNode(describe(zone.name, words.types[zone.type] ?: words.zone, if (mine) words.here else route(zone.center)), zone.center, zone = zone)
    }
    val pois = venue.pois.map { MapNode(describe(it.name, words.types[it.type], route(it.point)), it.point) }
    val meeting = listOfNotNull(venue.meetingPoint?.let { MapNode(describe(words.meeting, null, route(it)), it) })
    val incidents = pins.map { pin ->
        val zone = venue.zoneAt(pin.point.lat, pin.point.lon)?.name
        MapNode(listOfNotNull(words.incident, pin.severity?.let(words.urgency), zone, route(pin.point)).joinToString(", "), pin.point, pin = pin)
    }
    return zones + pois + meeting + incidents
}

private fun roundToFive(meters: Int): Int = (meters + 2) / 5 * 5

private fun describe(name: String, type: String?, where: String?): String =
    listOfNotNull(name.takeIf { it.isNotEmpty() }, type?.takeUnless { name.contains(it, ignoreCase = true) }, where).joinToString(", ")

@Composable
internal fun rememberMapWords(): MapWords {
    val resources = LocalResources.current
    return remember(resources) { mapWords(resources) }
}

private fun mapWords(resources: Resources) = MapWords(
    types = mapOf(
        "stage" to R.string.map_type_stage, "food" to R.string.map_type_food, "bar" to R.string.map_type_bar,
        "medical" to R.string.map_type_medical, "entrance" to R.string.map_type_entrance, "exit" to R.string.map_type_exit,
        "camping" to R.string.map_type_camping, "chill" to R.string.map_type_chill, "water" to R.string.map_type_water,
        "wc" to R.string.map_type_wc, "info" to R.string.map_type_info, "charge" to R.string.map_type_charge,
    ).mapValues { resources.getString(it.value) },
    zone = resources.getString(R.string.map_type_zone),
    here = resources.getString(R.string.map_a11y_here),
    meeting = resources.getString(R.string.map_meeting_point),
    incident = resources.getString(R.string.map_a11y_incident),
    urgency = { resources.getString(Labels.urgency(it)) },
    directions = resources.getStringArray(R.array.map_directions).toList(),
    route = { meters, direction -> resources.getString(R.string.map_route, meters, direction) },
)

/** Touch area of a point for exploring by touch. */
private val PointTarget = 44.dp

/**
 * Invisible boxes over the drawn zones and points, so TalkBack can find them by touch and read them in order:
 * zones first, then points. Only what is on screen gets a box. They take no pointer input, so taps and pinches
 * still reach the map; a double tap from TalkBack does what a touch on that place does.
 */
@Composable
internal fun MapSemanticsLayer(
    venue: Venue,
    nodes: List<MapNode>,
    inset: Dp,
    camera: MapCamera?,
    highlightZone: String,
    onTap: ((GeoPoint, Zone?) -> Unit)?,
    onPinTap: ((MapPin) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            nodes.forEach { node ->
                Box(
                    Modifier.semantics {
                        contentDescription = node.description
                        traversalIndex = if (node.zone != null) 0f else 1f
                        if (node.zone != null && node.zone.id == highlightZone) selected = true
                        val pin = node.pin
                        if (pin != null) {
                            if (onPinTap != null) onClick { onPinTap(pin); true }
                        } else if (onTap != null) {
                            onClick { onTap(node.point, node.zone ?: venue.zoneAt(node.point.lat, node.point.lon)); true }
                        }
                    },
                )
            }
        },
        modifier = modifier.semantics { isTraversalGroup = true },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val projection = Projection(venue, Size(width.toFloat(), height.toFloat()), inset.toPx())
        val view = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val half = PointTarget.toPx() / 2
        fun at(p: GeoPoint) = projection.project(p).let { camera?.toScreen(it) ?: it }
        val placed = mutableListOf<Pair<Placeable, Offset>>()
        measurables.forEachIndexed { i, measurable ->
            val node = nodes[i]
            val area = node.zone?.polygon?.map(::at)?.let { corners ->
                Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
            } ?: at(node.point).takeIf(view::contains)?.let { Rect(it.x - half, it.y - half, it.x + half, it.y + half) }
            val box = area?.intersect(view)?.takeIf { it.width >= 1f && it.height >= 1f } ?: return@forEachIndexed
            val placeable = measurable.measure(Constraints.fixed(box.width.roundToInt(), box.height.roundToInt()))
            placed += placeable to box.topLeft
        }
        layout(width, height) {
            placed.forEach { (placeable, at) -> placeable.place(at.x.roundToInt(), at.y.roundToInt()) }
        }
    }
}
