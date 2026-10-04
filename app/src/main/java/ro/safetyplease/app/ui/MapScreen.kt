package ro.safetyplease.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.Role
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.venue.GeoPoint
import ro.safetyplease.app.venue.Venue
import ro.safetyplease.app.venue.Zone
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

class MapPin(val point: GeoPoint, val color: Color, val key: String = "")

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** Proiectie echirectangulara peste limitele venue-ului; la scara unui festival eroarea e neglijabila. */
private class Projection(venue: Venue, size: Size) {
    private val b = venue.bounds
    private val lonScale = cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    private val worldW = (b.maxLon - b.minLon) * lonScale
    private val worldH = b.maxLat - b.minLat
    private val scale = min(size.width / worldW, size.height / worldH)
    private val offX = (size.width - worldW * scale) / 2
    private val offY = (size.height - worldH * scale) / 2
    val topLeft = Offset(offX.toFloat(), offY.toFloat())
    val extent = Size((worldW * scale).toFloat(), (worldH * scale).toFloat())

    fun project(p: GeoPoint) = Offset(
        (offX + (p.lon - b.minLon) * lonScale * scale).toFloat(),
        (offY + (b.maxLat - p.lat) * scale).toFloat(),
    )

    fun unproject(o: Offset) = GeoPoint(b.maxLat - (o.y - offY) / scale, b.minLon + (o.x - offX) / (lonScale * scale))
}

fun venueAspect(venue: Venue): Float {
    val b = venue.bounds
    val w = (b.maxLon - b.minLon) * cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    return (w / (b.maxLat - b.minLat)).toFloat()
}

/** Harta schematica a venue-ului, desenata direct pe Canvas: fara tile-uri, fara internet. */
@Composable
fun VenueMap(
    venue: Venue,
    modifier: Modifier = Modifier,
    position: GeoPoint? = null,
    pins: List<MapPin> = emptyList(),
    selectedZone: String = "",
    onTap: ((GeoPoint, Zone?) -> Unit)? = null,
    onPinTap: ((MapPin) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val outline = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val meetingLabel = stringResource(R.string.map_meeting_point)
    val currentPins by rememberUpdatedState(pins)
    val currentTap by rememberUpdatedState(onTap)
    val currentPinTap by rememberUpdatedState(onPinTap)

    Canvas(
        modifier.fillMaxWidth().aspectRatio(venueAspect(venue)).pointerInput(venue) {
            detectTapGestures { offset ->
                val projection = Projection(venue, Size(size.width.toFloat(), size.height.toFloat()))
                val hit = currentPins.minByOrNull { (projection.project(it.point) - offset).getDistance() }
                if (hit != null && currentPinTap != null && (projection.project(hit.point) - offset).getDistance() < 28.dp.toPx()) {
                    currentPinTap?.invoke(hit)
                } else {
                    val point = projection.unproject(offset)
                    if (venue.bounds.contains(point.lat, point.lon)) currentTap?.invoke(point, venue.zoneAt(point.lat, point.lon))
                }
            }
        }
    ) {
        val projection = Projection(venue, size)
        drawRoundRect(surface, projection.topLeft, projection.extent, CornerRadius(12.dp.toPx()))
        drawRoundRect(outline, projection.topLeft, projection.extent, CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))

        for (zone in venue.zones) {
            val color = parseColor(zone.color)
            val selected = zone.id == selectedZone
            val path = Path().apply {
                zone.polygon.forEachIndexed { i, p ->
                    val o = projection.project(p)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
                close()
            }
            drawPath(path, color.copy(alpha = if (selected) 0.55f else 0.26f))
            drawPath(path, color.copy(alpha = if (selected) 1f else 0.8f), style = Stroke((if (selected) 3f else 1.2f).dp.toPx()))
            val xs = zone.polygon.map { projection.project(it).x }
            val width = (xs.max() - xs.min() - 6.dp.toPx()).toInt().coerceAtLeast(1)
            val text = measurer.measure(
                zone.name, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 2,
                constraints = Constraints(maxWidth = width),
            )
            val center = projection.project(zone.center)
            drawText(text, topLeft = Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))
        }

        venue.meetingPoint?.let { point ->
            val o = projection.project(point)
            drawCircle(Color.White, 7.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, 2.5.dp.toPx(), o)
            val text = measurer.measure(meetingLabel, labelStyle.copy(fontSize = 9.sp))
            drawText(text, topLeft = Offset(o.x - text.size.width / 2f, o.y + 9.dp.toPx()))
        }

        for (pin in pins) {
            val o = projection.project(pin.point)
            val tip = Path().apply {
                moveTo(o.x, o.y)
                lineTo(o.x - 7.dp.toPx(), o.y - 14.dp.toPx())
                lineTo(o.x + 7.dp.toPx(), o.y - 14.dp.toPx())
                close()
            }
            drawPath(tip, pin.color)
            drawCircle(pin.color, 9.dp.toPx(), Offset(o.x, o.y - 18.dp.toPx()))
            drawCircle(Color.White, 3.5.dp.toPx(), Offset(o.x, o.y - 18.dp.toPx()))
        }

        position?.let { point ->
            val o = projection.project(point)
            drawCircle(Palette.Mesh.copy(alpha = 0.25f), 16.dp.toPx(), o)
            drawCircle(Color.White, 7.dp.toPx(), o)
            drawCircle(Palette.Mesh, 5.dp.toPx(), o)
        }
    }
}

fun incidentPoint(venue: Venue, incident: StaffIncident): GeoPoint? {
    val lat = incident.lat
    val lon = incident.lon
    if (lat != null && lon != null) return GeoPoint(lat, lon)
    return venue.zone(incident.zone)?.center
}

@Composable
fun MapScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    var simulate by rememberSaveable { mutableStateOf(false) }
    val simulated = settings.simLat != null
    val zone = position?.let { vm.venue.zoneAt(it.lat, it.lon) }

    val pins = remember(incidents.staff, settings.role) {
        if (settings.role != Role.STAFF) emptyList()
        else incidents.staff.filter { it.status < AckStatus.RESOLVED }.mapNotNull { incident ->
            incidentPoint(vm.venue, incident)?.let { MapPin(it, Palette.severity(incident.severity), incident.incidentId) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VenueMap(
            venue = vm.venue,
            position = position,
            pins = pins,
            selectedZone = zone?.id ?: vm.manualZone,
            onTap = { point, tapped ->
                if (simulate) vm.setSimulatedLocation(point) else if (position == null && tapped != null) vm.manualZone = tapped.id
            },
            onPinTap = { vm.open(Dest.Incident(it.key)) },
        )

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.MyLocation, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.map_your_zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            zone != null -> zone.name
                            position != null -> stringResource(R.string.map_outside)
                            vm.manualZone.isNotEmpty() -> vm.venue.zoneName(vm.manualZone)
                            else -> stringResource(R.string.map_pick_zone)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            simulated -> stringResource(R.string.map_source_simulated)
                            position != null -> stringResource(R.string.map_source_gps)
                            else -> stringResource(R.string.map_source_manual)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (Demo.AVAILABLE) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.demo_simulate_location), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (simulated) TextButton(onClick = { vm.setSimulatedLocation(null) }) { Text(stringResource(R.string.clear)) }
                    Switch(checked = simulate, onCheckedChange = { simulate = it })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (z in vm.venue.zones) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = position == null) { vm.manualZone = z.id }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(z.color)))
                    Spacer(Modifier.width(12.dp))
                    Text(z.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (z.id == (zone?.id ?: vm.manualZone)) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun PinScreen(vm: AppViewModel, pin: Dest.Pin) {
    val position by vm.position.collectAsStateWithLifecycle()
    val lat = pin.lat
    val lon = pin.lon
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else vm.venue.zone(pin.zone)?.center
    ScreenScaffold(title = pin.label, subtitle = vm.venue.zoneName(pin.zone).ifEmpty { null }, onBack = { vm.back() }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VenueMap(
                venue = vm.venue,
                position = position,
                pins = listOfNotNull(point?.let { MapPin(it, MaterialTheme.colorScheme.primary) }),
                selectedZone = pin.zone,
            )
            if (point == null) Text(stringResource(R.string.map_no_position), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (!vm.venue.bounds.contains(point.lat, point.lon)) {
                Text(stringResource(R.string.map_pin_outside), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
