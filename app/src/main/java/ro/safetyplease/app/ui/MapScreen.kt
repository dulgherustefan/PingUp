package ro.safetyplease.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.Role as AppRole
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.venue.GeoPoint
import ro.safetyplease.app.venue.Venue
import ro.safetyplease.app.venue.Zone
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class MapPin(val point: GeoPoint, val color: Color, val key: String = "")

private val MapInset = 12.dp

/** Proiectie echirectangulara peste limitele venue-ului; la scara unui festival eroarea e neglijabila. */
private class Projection(venue: Venue, size: Size, pad: Float) {
    private val b = venue.bounds
    private val lonScale = cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    private val worldW = (b.maxLon - b.minLon) * lonScale
    private val worldH = b.maxLat - b.minLat
    // zonele nu ating marginea cardului
    private val scale = min((size.width - 2 * pad) / worldW, (size.height - 2 * pad) / worldH)
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

/** Distanta in metri intre doua puncte apropiate; suficient de exacta in interiorul unui eveniment. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Int {
    val metersPerDegree = 111_320.0
    val dLat = (a.lat - b.lat) * metersPerDegree
    val dLon = (a.lon - b.lon) * metersPerDegree * cos(Math.toRadians((a.lat + b.lat) / 2))
    return hypot(dLat, dLon).roundToInt()
}

/**
 * Harta schematica a venue-ului, desenata direct pe Canvas: fara tile-uri, fara internet.
 * [myZone] primeste contur gros; [highlightZone] e zona atinsa.
 */
@Composable
fun VenueMap(
    venue: Venue,
    modifier: Modifier = Modifier,
    position: GeoPoint? = null,
    pins: List<MapPin> = emptyList(),
    myZone: String = "",
    highlightZone: String = "",
    meetingFocused: Boolean = false,
    onTap: ((GeoPoint, Zone?) -> Unit)? = null,
    onPinTap: ((MapPin) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    val currentPins by rememberUpdatedState(pins)
    val currentTap by rememberUpdatedState(onTap)
    val currentPinTap by rememberUpdatedState(onPinTap)

    Canvas(
        modifier.fillMaxWidth().aspectRatio(venueAspect(venue)).clip(CardShape).background(colors.card).pointerInput(venue) {
            detectTapGestures { offset ->
                val projection = Projection(venue, Size(size.width.toFloat(), size.height.toFloat()), MapInset.toPx())
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
        val projection = Projection(venue, size, MapInset.toPx())
        val marker = position?.let(projection::project)
        venue.zones.forEachIndexed { index, zone ->
            val color = colors.zones[index % colors.zones.size]
            val mine = zone.id == myZone
            val highlighted = zone.id == highlightZone
            val path = Path().apply {
                zone.polygon.forEachIndexed { i, p ->
                    val o = projection.project(p)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
                close()
            }
            drawPath(path, color.copy(alpha = if (highlighted || mine) 0.46f else 0.24f))
            drawPath(path, color.copy(alpha = 0.85f), style = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round))
            if (mine) drawPath(path, colors.accent, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            val xs = zone.polygon.map { projection.project(it).x }
            val width = (xs.max() - xs.min() - 8.dp.toPx()).toInt().coerceAtLeast(1)
            val text = measurer.measure(
                zone.name, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 2,
                constraints = Constraints(maxWidth = width),
            )
            val center = projection.project(zone.center)
            // punctul „esti aici” nu acopera numele zonei: numele urca deasupra lui
            val top = if (marker != null && (marker - center).getDistance() < 30.dp.toPx()) marker.y - 22.dp.toPx() - text.size.height
            else center.y - text.size.height / 2f
            drawText(text, topLeft = Offset(center.x - text.size.width / 2f, top))
        }

        venue.meetingPoint?.let { point ->
            val o = projection.project(point)
            if (meetingFocused) drawCircle(colors.accent.copy(alpha = 0.3f), 20.dp.toPx(), o)
            drawCircle(colors.card, 9.dp.toPx(), o)
            drawCircle(colors.text, 7.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            drawCircle(colors.text, 2.5.dp.toPx(), o)
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
            drawCircle(colors.accent.copy(alpha = 0.26f), 16.dp.toPx(), o)
            drawCircle(Color.White, 7.5.dp.toPx(), o)
            drawCircle(colors.forest, 5.5.dp.toPx(), o)
        }
    }
}

fun incidentPoint(venue: Venue, incident: StaffIncident): GeoPoint? {
    val lat = incident.lat
    val lon = incident.lon
    if (lat != null && lon != null) return GeoPoint(lat, lon)
    return venue.zone(incident.zone)?.center
}

private enum class MapFocus { NONE, MINE, MEETING, ZONE }

@Composable
fun MapScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    var simulate by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(MapFocus.MINE) }
    var tapped by rememberSaveable { mutableStateOf("") }
    val simulated = settings.simLat != null
    val autoZone = position?.let { vm.venue.zoneAt(it.lat, it.lon) }
    val myZoneId = autoZone?.id ?: vm.manualZone
    val requestLocation = rememberLocationRequest(vm)

    val pins = remember(incidents.staff, settings.role, colors) {
        if (settings.role != AppRole.STAFF) emptyList()
        else incidents.staff.filter { it.status < AckStatus.RESOLVED }.mapNotNull { incident ->
            incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomClearance.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(stringResource(R.string.map_my_zone), focus == MapFocus.MINE, {
                focus = MapFocus.MINE
                if (position == null) requestLocation()
            })
            if (vm.venue.meetingPoint != null) {
                FilterPill(stringResource(R.string.map_meeting_point), focus == MapFocus.MEETING, { focus = MapFocus.MEETING })
            }
        }
        VenueMap(
            venue = vm.venue,
            position = position,
            pins = pins,
            myZone = myZoneId,
            highlightZone = if (focus == MapFocus.ZONE) tapped else "",
            meetingFocused = focus == MapFocus.MEETING,
            onTap = { point, zone ->
                if (simulate) vm.setSimulatedLocation(point)
                else if (zone != null) {
                    tapped = zone.id
                    focus = MapFocus.ZONE
                }
            },
            onPinTap = { vm.open(Dest.Incident(it.key)) },
        )

        AppCard(Modifier.fillMaxWidth()) {
            when (focus) {
                MapFocus.MEETING -> {
                    val meeting = vm.venue.meetingPoint
                    MapCardHeader(AppIcons.Flag, stringResource(R.string.map_meeting_point), stringResource(R.string.map_meeting_text))
                    val here = position
                    if (meeting != null && here != null) {
                        Spacer(Modifier.size(8.dp))
                        StateLabel(AppIcons.MyLocation, stringResource(R.string.map_distance, distanceMeters(here, meeting)), colors.textSecondary)
                    }
                }
                MapFocus.ZONE -> {
                    val zone = vm.venue.zone(tapped)
                    MapCardHeader(
                        AppIcons.Place, zone?.name ?: stringResource(R.string.zone_unknown),
                        when {
                            tapped == myZoneId && autoZone != null -> stringResource(R.string.map_you_are_here)
                            tapped == myZoneId -> stringResource(R.string.map_your_zone) + " · " + stringResource(R.string.map_source_manual)
                            else -> null
                        },
                    )
                    if (position == null && tapped != myZoneId) {
                        Spacer(Modifier.size(12.dp))
                        AppButton(stringResource(R.string.map_set_zone), { vm.manualZone = tapped }, compact = true)
                    }
                }
                else -> when {
                    autoZone != null -> MapCardHeader(
                        AppIcons.MyLocation, autoZone.name,
                        stringResource(R.string.map_your_zone) + " · " + stringResource(if (simulated) R.string.map_source_simulated else R.string.map_source_gps),
                    )
                    position != null -> MapCardHeader(AppIcons.MyLocation, stringResource(R.string.map_outside), stringResource(R.string.map_outside_text))
                    vm.manualZone.isNotEmpty() -> MapCardHeader(
                        AppIcons.MyLocation, vm.venue.zoneName(vm.manualZone),
                        stringResource(R.string.map_your_zone) + " · " + stringResource(R.string.map_source_manual),
                    )
                    else -> MapCardHeader(AppIcons.MyLocation, stringResource(R.string.map_pick_zone), stringResource(R.string.map_pick_zone_text))
                }
            }
        }

        if (Demo.AVAILABLE) {
            AppCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.demo_simulate_location), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (simulated) TextAction(stringResource(R.string.clear), { vm.setSimulatedLocation(null) })
                    AppSwitch(simulate) { simulate = it }
                }
            }
        }

        SectionLabel(stringResource(R.string.map_zones), Modifier.padding(start = 4.dp, top = 4.dp))
        Column {
            vm.venue.zones.forEachIndexed { index, z ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) {
                            tapped = z.id
                            focus = MapFocus.ZONE
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(colors.zones[index % colors.zones.size]))
                    Spacer(Modifier.width(12.dp))
                    Text(z.name, style = MaterialTheme.typography.bodyLarge, color = colors.text, modifier = Modifier.weight(1f))
                    if (z.id == myZoneId) StateLabel(AppIcons.MyLocation, stringResource(R.string.map_your_zone), colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun MapCardHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String?) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text)
            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
    }
}

@Composable
fun PinScreen(vm: AppViewModel, pin: Dest.Pin) {
    val position by vm.position.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val lat = pin.lat
    val lon = pin.lon
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else vm.venue.zone(pin.zone)?.center
    val zoneName = vm.venue.zoneName(pin.zone)
    ScreenScaffold(title = pin.label, subtitle = zoneName.ifEmpty { null }, onBack = { vm.back() }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VenueMap(
                venue = vm.venue,
                position = position,
                pins = listOfNotNull(point?.let { MapPin(it, colors.forest) }),
                highlightZone = pin.zone,
            )
            AppCard(Modifier.fillMaxWidth()) {
                MapCardHeader(
                    AppIcons.Place,
                    if (zoneName.isEmpty()) stringResource(R.string.zone_unknown) else stringResource(R.string.map_pin_in, pin.label, zoneName),
                    when {
                        point == null -> stringResource(R.string.map_no_position)
                        !vm.venue.bounds.contains(point.lat, point.lon) -> stringResource(R.string.map_pin_outside)
                        position != null -> stringResource(R.string.map_distance, distanceMeters(position!!, point))
                        else -> null
                    },
                )
            }
        }
    }
}
