package ro.safetyplease.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.pointInPolygon
import ro.safetyplease.ui.common.onSite
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.map_a11y_here
import ro.safetyplease.ui.resources.map_a11y_incident
import ro.safetyplease.ui.resources.map_directions
import ro.safetyplease.ui.resources.map_meeting_point
import ro.safetyplease.ui.resources.map_route
import ro.safetyplease.ui.resources.map_type_bar
import ro.safetyplease.ui.resources.map_type_camping
import ro.safetyplease.ui.resources.map_type_charge
import ro.safetyplease.ui.resources.map_type_chill
import ro.safetyplease.ui.resources.map_type_dea
import ro.safetyplease.ui.resources.map_type_entrance
import ro.safetyplease.ui.resources.map_type_exit
import ro.safetyplease.ui.resources.map_type_food
import ro.safetyplease.ui.resources.map_type_info
import ro.safetyplease.ui.resources.map_type_medical
import ro.safetyplease.ui.resources.map_type_stage
import ro.safetyplease.ui.resources.map_type_water
import ro.safetyplease.ui.resources.map_type_wc
import ro.safetyplease.ui.resources.map_type_zone
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.fill
import kotlin.math.roundToInt

/** The words TalkBack reads on the map, resolved once from resources so [mapNodes] stays testable. */
class MapWords(
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
class MapNode(val description: String, val point: GeoPoint, val zone: Zone? = null, val pin: MapPin? = null)

/**
 * What the map says to TalkBack: every zone first, then the useful points, the meeting point and the [pins].
 * Each says its name, its type unless the name already says it, then "aici ești" for your zone or the distance and
 * direction from [position] when it's known and inside the venue.
 */
fun mapNodes(venue: Venue, words: MapWords, position: GeoPoint?, myZone: String, pins: List<MapPin>): List<MapNode> {
    // rounded so TalkBack doesn't re-announce a place for every meter you walk
    fun route(to: GeoPoint) = onSite(position, venue)?.let { words.route(roundToFive(distanceMeters(it, to)), words.directions[compassSector(it, to)]) }

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
fun rememberMapWords(): MapWords {
    val types = mapOf(
        "stage" to Res.string.map_type_stage, "food" to Res.string.map_type_food, "bar" to Res.string.map_type_bar,
        "medical" to Res.string.map_type_medical, "entrance" to Res.string.map_type_entrance, "exit" to Res.string.map_type_exit,
        "camping" to Res.string.map_type_camping, "chill" to Res.string.map_type_chill, "water" to Res.string.map_type_water,
        "wc" to Res.string.map_type_wc, "info" to Res.string.map_type_info, "charge" to Res.string.map_type_charge,
        Venue.DEA to Res.string.map_type_dea,
    ).mapValues { stringResource(it.value) }
    val zone = stringResource(Res.string.map_type_zone)
    val here = stringResource(Res.string.map_a11y_here)
    val meeting = stringResource(Res.string.map_meeting_point)
    val incident = stringResource(Res.string.map_a11y_incident)
    val urgent = stringResource(Labels.urgency(Severity.URGENT))
    val notUrgent = stringResource(Labels.urgency(Severity.MEDIUM))
    val directions = stringArrayResource(Res.array.map_directions)
    val route = stringResource(Res.string.map_route)
    return remember(types, zone, here, meeting, incident, urgent, notUrgent, directions, route) {
        MapWords(
            types = types, zone = zone, here = here, meeting = meeting, incident = incident,
            urgency = { if (it == Severity.URGENT) urgent else notUrgent },
            directions = directions,
            route = { meters, direction -> route.fill(meters, direction) },
        )
    }
}

/** Touch area of a point for exploring by touch. */
private val PointTarget = 44.dp

/**
 * Invisible boxes over the drawn zones and points, so TalkBack can find them by touch and read them in order:
 * zones first, then points. Only what is on screen gets a box. They take no pointer input, so taps and pinches
 * still reach the map; a double tap from TalkBack does what a touch on that place does.
 */
@Composable
fun MapSemanticsLayer(
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
