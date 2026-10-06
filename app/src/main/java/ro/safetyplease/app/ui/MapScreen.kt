package ro.safetyplease.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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

private val MapInset = 14.dp
private val CompactInset = 8.dp

/** Cel mai mic text pentru numele unei zone: sub atat nu se mai citeste pe telefon. */
private val MinLabelSize = 9.sp

/** Umbra moale de sub markere: inelul alb ramane vizibil si pe zonele deschise. */
private val MarkerShadow = Color(0x29000000)

/** Proiectie echirectangulara peste limitele venue-ului; la scara unui festival eroarea e neglijabila. */
private class Projection(venue: Venue, size: Size, pad: Float) {
    private val b = venue.bounds
    private val lonScale = cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    private val worldW = (b.maxLon - b.minLon) * lonScale
    private val worldH = b.maxLat - b.minLat
    // zonele nu ating marginea hartii
    private val scale = min((size.width - 2 * pad) / worldW, (size.height - 2 * pad) / worldH)
    private val offX = (size.width - worldW * scale) / 2
    private val offY = (size.height - worldH * scale) / 2

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
 * Harta schematica a venue-ului, desenata direct pe Canvas, ca o harta Apple Maps intr-un card: fara tile-uri, fara internet.
 * Zonele au culorile pastel ale avatarurilor, despartite de linii in culoarea celulelor, cu numele in nuanta lor inchisa.
 * [myZone] are contur albastru, [highlightZone] contur in nuanta ei. Tu esti punctul albastru cu halou.
 * [compact] e varianta din baloanele de mesaj: fara nume, zonele gri, doar [highlightZone] colorata.
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
    compact: Boolean = false,
    onTap: ((GeoPoint, Zone?) -> Unit)? = null,
    onPinTap: ((MapPin) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val flag = rememberVectorPainter(Sym.Flag)
    val currentPins by rememberUpdatedState(pins)
    val currentTap by rememberUpdatedState(onTap)
    val currentPinTap by rememberUpdatedState(onPinTap)
    val inset = if (compact) CompactInset else MapInset

    Box(
        modifier.fillMaxWidth().aspectRatio(venueAspect(venue))
            // fondul e translucid: in balon se vede culoarea balonului prin el, ca harta sa nu se piarda in fundalul ecranului
            .then(if (compact) Modifier else Modifier.clip(RoundedCornerShape(26.dp))).background(colors.fill),
    ) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (onTap == null && onPinTap == null) Modifier else Modifier.pointerInput(venue, inset) {
                    detectTapGestures { offset ->
                        val projection = Projection(venue, Size(size.width.toFloat(), size.height.toFloat()), inset.toPx())
                        val hit = currentPins.minByOrNull { (projection.project(it.point) - offset).getDistance() }
                        if (hit != null && currentPinTap != null && (projection.project(hit.point) - offset).getDistance() < 28.dp.toPx()) {
                            currentPinTap?.invoke(hit)
                        } else {
                            val point = projection.unproject(offset)
                            if (venue.bounds.contains(point.lat, point.lon)) currentTap?.invoke(point, venue.zoneAt(point.lat, point.lon))
                        }
                    }
                },
            ),
        ) {
            val projection = Projection(venue, size, inset.toPx())
            val marker = position?.let(projection::project)
            val meeting = if (compact) null else venue.meetingPoint?.let(projection::project)
            val spots = pins.map { projection.project(it.point) }
            // ce sta pe harta deasupra zonelor; numele unei zone se fereste de ele
            val marks = listOfNotNull(marker, meeting) + spots
            val paths = venue.zones.map { zone ->
                Path().apply {
                    zone.polygon.forEachIndexed { i, p ->
                        val o = projection.project(p)
                        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                    close()
                }
            }

            venue.zones.forEachIndexed { index, zone ->
                val fill = if (compact && zone.id != highlightZone) colors.cell else colors.zoneFill(index, zone.id)
                drawPath(paths[index], fill)
                drawPath(paths[index], colors.cell, style = Stroke((if (compact) 1.dp else 2.dp).toPx(), join = StrokeJoin.Round))
            }
            // contururile vin dupa toate zonele, ca sa nu le acopere linia vecinei
            if (!compact) {
                val highlighted = venue.zones.indexOfFirst { it.id == highlightZone && it.id != myZone }
                if (highlighted >= 0) {
                    drawPath(paths[highlighted], colors.zoneInk(highlighted, highlightZone), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                }
            }
            val mine = venue.zones.indexOfFirst { it.id == myZone }
            if (mine >= 0) drawPath(paths[mine], colors.accent, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))

            if (!compact) {
                venue.zones.forEachIndexed { index, zone ->
                    val xs = zone.polygon.map { projection.project(it).x }
                    val width = (xs.max() - xs.min() - 8.dp.toPx()).toInt().coerceAtLeast(1)
                    // numele se micsoreaza pana incape intreg in zona, in loc sa fie taiat („Punct medi…”)
                    fun label(size: TextUnit) = measurer.measure(
                        zone.name, labelStyle.copy(color = colors.zoneInk(index, zone.id), fontSize = size, lineHeight = size * 1.15f),
                        overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = width),
                    )
                    var size = labelStyle.fontSize
                    var text = label(size)
                    while (text.hasVisualOverflow && size > MinLabelSize) {
                        size = (size.value - 1).sp
                        text = label(size)
                    }
                    val center = projection.project(zone.center)
                    val mark = marks.firstOrNull { (it - center).getDistance() < 30.dp.toPx() }
                    val ys = zone.polygon.map { projection.project(it).y }
                    val gap = 14.dp.toPx()
                    val edge = 3.dp.toPx()
                    // numele ramane in zona: deasupra markerului, dedesubt daca nu incape, altfel lipit de marginea de sus
                    val top = when {
                        mark == null -> center.y - text.size.height / 2f
                        mark.y - gap - text.size.height >= ys.min() + edge -> mark.y - gap - text.size.height
                        mark.y + gap + text.size.height <= ys.max() - edge -> mark.y + gap
                        else -> ys.min() + edge
                    }
                    drawText(text, topLeft = Offset(center.x - text.size.width / 2f, top))
                }
            }

            meeting?.let { o ->
                val r = (if (meetingFocused) 12.dp else 9.dp).toPx()
                if (meetingFocused) drawCircle(colors.orange.copy(alpha = 0.18f), r + 12.dp.toPx(), o)
                markerDot(o, r, colors.orange)
                val glyph = r * 1.2f
                translate(o.x - glyph / 2, o.y - glyph / 2) {
                    with(flag) { draw(Size(glyph, glyph), colorFilter = ColorFilter.tint(Color.White)) }
                }
            }

            spots.forEachIndexed { i, o -> markerDot(o, 7.dp.toPx(), pins[i].color) }

            marker?.let { o ->
                drawCircle(colors.accent.copy(alpha = 0.16f), 22.dp.toPx(), o)
                markerDot(o, 7.dp.toPx(), colors.accent)
            }
        }
    }
}

/** Un marker ca pe Apple Maps: punctul colorat intr-un inel alb de 3, cu o umbra moale putin mai jos. */
private fun DrawScope.markerDot(center: Offset, radius: Float, color: Color) {
    val outer = radius + 3.dp.toPx()
    val spread = 3.dp.toPx()
    val shade = center + Offset(0f, 1.dp.toPx())
    drawCircle(
        Brush.radialGradient(0f to MarkerShadow, outer / (outer + spread) to MarkerShadow, 1f to Color.Transparent, center = shade, radius = outer + spread),
        outer + spread, shade,
    )
    drawCircle(Color.White, outer, center)
    drawCircle(color, radius, center)
}

fun incidentPoint(venue: Venue, incident: StaffIncident): GeoPoint? {
    val lat = incident.lat
    val lon = incident.lon
    if (lat != null && lon != null) return GeoPoint(lat, lon)
    return venue.zone(incident.zone)?.center
}

private enum class MapFocus { MINE, MEETING, ZONE }

/**
 * Harta evenimentului, ca tab sau deschisa din chat, pe fundalul gri al setarilor iOS: harta intr-un
 * card, alegerea intre zona ta si punctul de intalnire, locul ales intr-un grup si, dedesubt, toate zonele.
 * O atingere pe harta sau pe o zona din lista o alege. Venita din chat, zona aleasa pleaca direct in conversatie.
 * Ca tab ([asTab]), in stanga sus sta bula ta, ca pe celelalte taburi, nu sageata inapoi.
 */
@Composable
fun MapScreen(vm: AppViewModel, dest: Dest.Map, asTab: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    var simulate by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(if (dest.meeting) MapFocus.MEETING else MapFocus.MINE) }
    var tapped by rememberSaveable { mutableStateOf("") }
    val here = position
    val simulated = settings.simLat != null
    val autoZone = here?.let { vm.venue.zoneAt(it.lat, it.lon) }
    val myZoneId = autoZone?.id ?: vm.manualZone
    val meeting = vm.venue.meetingPoint
    val requestLocation = rememberLocationRequest(vm)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    val pins = remember(incidents.staff, settings.role, colors) {
        if (settings.role != AppRole.STAFF) emptyList()
        else incidents.staff.filter { it.status < AckStatus.RESOLVED }.mapNotNull { incident ->
            incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId) }
        }
    }

    fun showMine() {
        focus = MapFocus.MINE
        if (position == null) requestLocation()
    }

    fun showZone(id: String) {
        tapped = id
        focus = MapFocus.ZONE
    }

    val haptics = rememberHaptics()
    fun setMine(id: String) {
        vm.manualZone = id
        val to = dest.sendTo
        if (to != null && vm.sendMyZone(to)) {
            haptics.confirm()
            vm.back()
        }
    }

    NavScreen(
        title = stringResource(R.string.map_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop ->
            if (asTab) MeButton(settings.nickname) { vm.open(Dest.Me) }
            else GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop)
        },
        trailing = { backdrop ->
            GlassIconButton(Sym.MyLocation, stringResource(R.string.map_my_zone), {
                showMine()
                scope.launch { listState.animateScrollToItem(0) }
            }, backdrop)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item(key = "map") {
                VenueMap(
                    venue = vm.venue,
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp),
                    position = here,
                    pins = pins,
                    myZone = myZoneId,
                    highlightZone = if (focus == MapFocus.ZONE) tapped else "",
                    meetingFocused = focus == MapFocus.MEETING,
                    onTap = { point, zone ->
                        if (simulate) vm.setSimulatedLocation(point)
                        else if (zone != null) showZone(zone.id)
                    },
                    onPinTap = { vm.open(Dest.Incident(it.key)) },
                )
            }
            item(key = "place") {
                // zona ta (sau zona atinsa) si punctul de intalnire stau unul sub altul, fara comutator intre ele
                InsetGroup(Modifier.padding(top = 16.dp)) {
                    when (focus) {
                        MapFocus.ZONE -> {
                            val index = vm.venue.zones.indexOfFirst { it.id == tapped }
                            val zone = vm.venue.zones.getOrNull(index)
                            GroupRow(
                                zone?.name ?: stringResource(R.string.zone_unknown),
                                subtitle = when {
                                    tapped == myZoneId && autoZone != null -> stringResource(R.string.map_you_are_here)
                                    tapped == myZoneId -> stringResource(R.string.map_your_zone) + " · " + stringResource(R.string.map_source_manual)
                                    else -> stringResource(R.string.map_zone_tag)
                                },
                                leading = { if (zone != null) ZoneBadge(index, zone.id, zone.name) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                                trailing = if (here == null && tapped != myZoneId) {
                                    { AppButton(stringResource(R.string.map_set_zone), { setMine(tapped) }, compact = true) }
                                } else null,
                            )
                        }
                        MapFocus.MINE, MapFocus.MEETING -> {
                            val yourZone = stringResource(R.string.map_your_zone)
                            val source = stringResource(if (simulated) R.string.map_source_simulated else R.string.map_source_gps)
                            val manual = stringResource(R.string.map_source_manual)
                            val (title, subtitle) = when {
                                autoZone != null -> autoZone.name to "$yourZone · $source"
                                here != null -> stringResource(R.string.map_outside) to stringResource(R.string.map_outside_text)
                                vm.manualZone.isNotEmpty() -> vm.venue.zoneName(vm.manualZone) to "$yourZone · $manual"
                                else -> stringResource(R.string.map_pick_zone) to stringResource(R.string.map_pick_zone_text)
                            }
                            val known = here != null || vm.manualZone.isNotEmpty()
                            GroupRow(
                                title, subtitle = subtitle,
                                leading = {
                                    IconCircle(Sym.MyLocation, if (known) colors.accent else colors.fill, if (known) colors.onAccent else colors.secondaryLabel, 36.dp)
                                },
                            )
                        }
                    }
                    if (meeting != null) {
                        GroupDivider(start = 64.dp)
                        GroupRow(
                            stringResource(R.string.map_meeting_point),
                            subtitle = listOfNotNull(
                                stringResource(R.string.map_meeting_text),
                                if (here != null) stringResource(R.string.map_distance, distanceMeters(here, meeting)) else null,
                            ).joinToString(" · "),
                            leading = { IconCircle(Sym.Flag, colors.orange, Color.White, 36.dp) },
                            // atins, steagul de pe harta se mareste
                            onClick = {
                                focus = MapFocus.MEETING
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                        )
                    }
                }
            }
            // unealta de test; ramane vizibila cat e pusa o locatie simulata, ca sa poata fi stearsa
            if (Demo.AVAILABLE && (vm.demoUnlocked || simulated)) {
                item(key = "simulate") {
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        SwitchRow(stringResource(R.string.demo_simulate_location), simulate, { simulate = it }, icon = Sym.Science)
                        if (simulated) {
                            GroupDivider()
                            // textul legaturii se aliniaza cu textul randului de deasupra
                            TextLink(stringResource(R.string.clear), { vm.setSimulatedLocation(null) }, Modifier.padding(start = 48.dp, top = 4.dp, bottom = 4.dp))
                        }
                    }
                }
            }
            item(key = "zones-title") { SectionTitle(stringResource(R.string.map_zones)) }
            item(key = "zones") {
                val hereLabel = stringResource(R.string.map_you_are_here)
                InsetGroup {
                    vm.venue.zones.forEachIndexed { index, zone ->
                        val mine = zone.id == myZoneId
                        GroupRow(
                            zone.name,
                            value = if (mine) hereLabel else null,
                            onClick = {
                                // venit din chat: o atingere alege zona si o trimite
                                if (dest.sendTo != null) setMine(zone.id)
                                else {
                                    showZone(zone.id)
                                    scope.launch { listState.animateScrollToItem(0) }
                                }
                            },
                            leading = { ZoneBadge(index, zone.id, zone.name) },
                            trailing = if (mine) {
                                { Icon(Sym.Check, null, Modifier.size(20.dp), tint = colors.accent) }
                            } else null,
                        )
                        if (index < vm.venue.zones.lastIndex) GroupDivider(start = 64.dp)
                    }
                }
            }
        }
    }
}

/** Cercul unei zone, in culoarea ei de pe harta, cu initialele in nuanta inchisa, ca bula unui om. */
@Composable
private fun ZoneBadge(index: Int, id: String, name: String) {
    val colors = AppTheme.colors
    val size = 36.dp
    // literele tin de marimea cercului, ca la Avatar
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(Modifier.size(size).clip(CircleShape).background(colors.zoneFill(index, id)), contentAlignment = Alignment.Center) {
        Text(
            initials(name), color = colors.zoneInk(index, id), maxLines = 1,
            style = MaterialTheme.typography.subheadline.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Medium),
        )
    }
}

/** Locul trimis intr-un mesaj: harta cu punctul omului, apoi zona lui si cat de departe e de tine. */
@Composable
fun PinScreen(vm: AppViewModel, pin: Dest.Pin) {
    val position by vm.position.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val here = position
    val lat = pin.lat
    val lon = pin.lon
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else vm.venue.zone(pin.zone)?.center
    val zoneName = vm.venue.zoneName(pin.zone)
    val index = vm.venue.zones.indexOfFirst { it.id == pin.zone }
    val detail = when {
        point == null -> stringResource(R.string.map_no_position)
        !vm.venue.bounds.contains(point.lat, point.lon) -> stringResource(R.string.map_pin_outside)
        here != null -> stringResource(R.string.map_distance, distanceMeters(here, point)).replaceFirstChar { it.titlecase() }
        else -> null
    }

    NavScreen(
        title = pin.label,
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            VenueMap(
                venue = vm.venue,
                modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp),
                position = here,
                pins = listOfNotNull(point?.let { MapPin(it, colors.accent) }),
                highlightZone = pin.zone,
            )
            InsetGroup(Modifier.padding(top = 16.dp)) {
                GroupRow(
                    if (zoneName.isEmpty()) stringResource(R.string.zone_unknown) else zoneName,
                    subtitle = detail,
                    leading = { if (index >= 0) ZoneBadge(index, pin.zone, zoneName) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                )
            }
        }
    }
}
