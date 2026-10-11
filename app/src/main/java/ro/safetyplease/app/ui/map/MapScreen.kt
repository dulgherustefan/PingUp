package ro.safetyplease.app.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ro.safetyplease.app.R
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.MapCamera
import ro.safetyplease.app.ui.common.MapGlyph
import ro.safetyplease.app.ui.common.MapPin
import ro.safetyplease.app.ui.common.MeButton
import ro.safetyplease.app.ui.common.VenueMap
import ro.safetyplease.app.ui.common.incidentPoint
import ro.safetyplease.app.ui.common.spotColor
import ro.safetyplease.app.ui.common.typeGlyph
import ro.safetyplease.app.ui.common.venueAspect
import ro.safetyplease.app.ui.common.zoneTone
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.GroupDivider
import ro.safetyplease.app.ui.designsystem.GroupRow
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.IconCircle
import ro.safetyplease.app.ui.designsystem.InsetGroup
import ro.safetyplease.app.ui.designsystem.LocalReduceMotion
import ro.safetyplease.app.ui.designsystem.NavScreen
import ro.safetyplease.app.ui.designsystem.SectionTitle
import ro.safetyplease.app.ui.designsystem.SwitchRow
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TextLink
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.initials
import ro.safetyplease.app.ui.designsystem.pressScale
import ro.safetyplease.app.ui.designsystem.rememberHaptics
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.rememberLocationRequest
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.data.Role as AppRole

/** Places the quick chips lead to, in chip order. */
private val SpotTypes = listOf("medical", "exit", Venue.MEETING, "water", "wc", "info", "charge")

private enum class MapFocus { MINE, ZONE, SPOT }

/**
 * Event map, as a tab or opened from a chat. On top the big map, which opens on where you are; below it, shortcuts
 * to the nearest medical point, exit, meeting point, water, toilets, info and charging, each with its distance,
 * then the chosen place and every zone. Without GPS, the zone you picked stands in for your position.
 * Opened from a chat, the chosen zone is sent right away. As a tab ([asTab]) the top left shows your avatar instead of a back arrow.
 */
@Composable
fun MapScreen(vm: AppViewModel, dest: Dest.Map, asTab: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    var simulate by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(if (dest.spot.isNotEmpty()) MapFocus.SPOT else MapFocus.MINE) }
    var spotType by rememberSaveable { mutableStateOf(dest.spot) }
    var tapped by rememberSaveable { mutableStateOf("") }
    val here = position
    val simulated = settings.simLat != null
    val autoZone = here?.let { vm.venue.zoneAt(it.lat, it.lon) }
    val manualZone = settings.manualZone
    val myZoneId = autoZone?.id ?: manualZone
    val origin = here ?: vm.venue.zone(manualZone)?.center
    val spot = if (focus == MapFocus.SPOT) vm.venue.nearest(spotType, origin) else null
    val requestLocation = rememberLocationRequest(vm)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val camera = remember { MapCamera() }
    val animateMoves = !LocalReduceMotion.current
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    val pins = remember(incidents.staff, settings.role, colors) {
        if (settings.role != AppRole.STAFF) emptyList()
        else incidents.staff.filter { it.status < AckStatus.RESOLVED }.mapNotNull { incident ->
            incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId) }
        }
    }

    fun move(points: List<GeoPoint>, most: Float) {
        scope.launch { camera.show(points, most, animateMoves) }
    }

    fun showMine() {
        focus = MapFocus.MINE
        if (position == null) requestLocation()
        move(listOfNotNull(origin), 2.2f)
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun showSpot(type: String) {
        focus = MapFocus.SPOT
        spotType = type
        move(listOfNotNull(origin, vm.venue.nearest(type, origin)?.point), 2.6f)
    }

    fun showZone(id: String) {
        tapped = id
        focus = MapFocus.ZONE
    }

    // on opening, the map shows where you are, or the route to the place it was opened for
    LaunchedEffect(camera) {
        snapshotFlow { camera.ready }.first { it }
        val points = if (focus == MapFocus.SPOT) listOfNotNull(origin, vm.venue.nearest(spotType, origin)?.point) else listOfNotNull(origin)
        if (points.isNotEmpty()) camera.show(points, if (focus == MapFocus.SPOT) 2.6f else 1.5f, animated = false)
    }

    val haptics = rememberHaptics()
    fun setMine(id: String) {
        vm.setManualZone(id)
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
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item(key = "map") {
                VenueMap(
                    venue = vm.venue,
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp),
                    position = origin,
                    pins = pins,
                    myZone = myZoneId,
                    highlightZone = if (focus == MapFocus.ZONE) tapped else "",
                    meetingFocused = focus == MapFocus.SPOT && spotType == Venue.MEETING,
                    onTap = { point, zone ->
                        if (simulate) vm.setSimulatedLocation(point)
                        else if (zone != null) showZone(zone.id)
                    },
                    onPinTap = { vm.open(Dest.Incident(it.key)) },
                    camera = camera,
                    target = spot?.point,
                    targetColor = spotColor(colors, spotType),
                    // taller than the site: room for the top label and the buttons without covering the zones
                    aspect = venueAspect(vm.venue) * 0.8f,
                ) {
                    val whereText = when {
                        autoZone != null -> stringResource(R.string.map_here_at, autoZone.name)
                        here != null -> stringResource(R.string.map_outside)
                        manualZone.isNotEmpty() -> stringResource(R.string.map_here_at, vm.venue.zoneName(manualZone))
                        else -> stringResource(R.string.map_where_tap)
                    }
                    WherePill(whereText, known = origin != null, Modifier.align(Alignment.TopStart).padding(10.dp)) { showMine() }
                    MapControls(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        onZoomIn = { scope.launch { camera.zoomBy(2f, animateMoves) } },
                        onZoomOut = { scope.launch { camera.zoomBy(0.5f, animateMoves) } },
                        onLocate = { showMine() },
                    )
                }
            }
            item(key = "quick") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickChip(Sym.MyLocation, stringResource(R.string.map_quick_me), null, colors.accent, focus == MapFocus.MINE) { showMine() }
                    SpotTypes.forEach { type ->
                        val near = vm.venue.nearest(type, origin) ?: return@forEach
                        QuickChip(
                            typeGlyph(type) ?: Sym.Flag, spotLabel(type), origin?.let { distanceMeters(it, near.point) },
                            spotColor(colors, type), focus == MapFocus.SPOT && spotType == type,
                        ) { showSpot(type) }
                    }
                }
            }
            item(key = "place") {
                InsetGroup {
                    when (focus) {
                        MapFocus.SPOT -> {
                            val name = spot?.name?.takeIf { it.isNotEmpty() }
                                ?: if (spotType == Venue.MEETING) stringResource(R.string.map_meeting_point) else spotLabel(spotType)
                            val directions = stringArrayResource(R.array.map_directions)
                            GroupRow(
                                name,
                                subtitle = when {
                                    spot == null -> null
                                    origin != null -> stringResource(R.string.map_route, distanceMeters(origin, spot.point), directions[compassSector(origin, spot.point)])
                                    else -> stringResource(R.string.map_route_unknown)
                                },
                                leading = { IconCircle(typeGlyph(spotType) ?: Sym.Flag, spotColor(colors, spotType), Color.White, 36.dp) },
                            )
                        }
                        MapFocus.ZONE -> {
                            val index = vm.venue.zones.indexOfFirst { it.id == tapped }
                            val zone = vm.venue.zones.getOrNull(index)
                            val directions = stringArrayResource(R.array.map_directions)
                            GroupRow(
                                zone?.name ?: stringResource(R.string.zone_unknown),
                                subtitle = when {
                                    tapped == myZoneId && autoZone != null -> stringResource(R.string.map_you_are_here)
                                    tapped == myZoneId -> stringResource(R.string.map_your_zone) + " · " + stringResource(R.string.map_source_manual)
                                    origin != null && zone != null -> stringResource(
                                        R.string.map_route, distanceMeters(origin, zone.center), directions[compassSector(origin, zone.center)],
                                    )
                                    else -> stringResource(R.string.map_zone_tag)
                                },
                                leading = { if (zone != null) ZoneBadge(vm.venue, index) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                                trailing = if (here == null && tapped != myZoneId) {
                                    { AppButton(stringResource(R.string.map_set_zone), { setMine(tapped) }, compact = true) }
                                } else null,
                            )
                        }
                        MapFocus.MINE -> {
                            val yourZone = stringResource(R.string.map_your_zone)
                            val source = stringResource(if (simulated) R.string.map_source_simulated else R.string.map_source_gps)
                            val manual = stringResource(R.string.map_source_manual)
                            val (title, subtitle) = when {
                                autoZone != null -> autoZone.name to "$yourZone · $source"
                                here != null -> stringResource(R.string.map_outside) to stringResource(R.string.map_outside_text)
                                manualZone.isNotEmpty() -> vm.venue.zoneName(manualZone) to "$yourZone · $manual"
                                else -> stringResource(R.string.map_pick_zone) to stringResource(R.string.map_pick_zone_text)
                            }
                            GroupRow(
                                title, subtitle = subtitle,
                                leading = {
                                    IconCircle(Sym.MyLocation, if (origin != null) colors.accent else colors.fill, if (origin != null) colors.onAccent else colors.secondaryLabel, 36.dp)
                                },
                            )
                        }
                    }
                }
            }
            // test tool; stays visible while a simulated location is set so it can be cleared
            if (Demo.AVAILABLE && (vm.demoUnlocked || simulated)) {
                item(key = "simulate") {
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        SwitchRow(stringResource(R.string.demo_simulate_location), simulate, { simulate = it }, icon = Sym.Science)
                        if (simulated) {
                            GroupDivider()
                            // align the link text with the row text above
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
                                // opened from a chat: one tap picks the zone and sends it
                                if (dest.sendTo != null) setMine(zone.id)
                                else {
                                    showZone(zone.id)
                                    move(listOf(zone.center), 2.4f)
                                    scope.launch { listState.animateScrollToItem(0) }
                                }
                            },
                            leading = { ZoneBadge(vm.venue, index) },
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

@Composable
private fun spotLabel(type: String): String = stringResource(
    when (type) {
        "medical" -> R.string.map_quick_medical
        "exit" -> R.string.map_quick_exit
        Venue.MEETING -> R.string.map_quick_meeting
        "water" -> R.string.map_quick_water
        "wc" -> R.string.map_quick_wc
        "info" -> R.string.map_quick_info
        else -> R.string.map_quick_charge
    },
)

/** The label on top of the map: where you are at a glance; tapped, it shows you on the map. */
@Composable
private fun WherePill(text: String, known: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(press, 0.96f).shadow(6.dp, CircleShape).clip(CircleShape).background(colors.cell)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (known) colors.accent else colors.orange))
        Spacer(Modifier.width(7.dp))
        Text(
            text, style = MaterialTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.label,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MapControls(modifier: Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onLocate: () -> Unit) {
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.shadow(6.dp, RoundedCornerShape(19.dp)).clip(RoundedCornerShape(19.dp)).background(colors.cell),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapButton(MapGlyph.Plus, stringResource(R.string.map_zoom_in), onZoomIn)
            Box(Modifier.width(22.dp).height(1.dp).background(colors.separator))
            MapButton(MapGlyph.Minus, stringResource(R.string.map_zoom_out), onZoomOut)
        }
        Box(Modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(colors.cell)) {
            MapButton(Sym.MyLocation, stringResource(R.string.map_center_me), onLocate, tint = colors.accent)
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = AppTheme.colors.label) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(38.dp).clickable(press, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(20.dp).pressScale(press, 0.85f), tint = tint)
    }
}

/** Shortcut to the nearest place of a type, with its distance; when selected it takes the place's color. */
@Composable
private fun QuickChip(icon: ImageVector, label: String, meters: Int?, tone: Color, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val press = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) tone else colors.cell, label = "chip")
    Row(
        Modifier.pressScale(press, 0.95f).height(40.dp).clip(CircleShape).background(fill)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.22f) else tone),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Color.White else colors.label, maxLines = 1,
        )
        if (meters != null) {
            Text(
                " · " + stringResource(R.string.map_meters, meters), style = MaterialTheme.typography.subheadline,
                color = if (selected) Color.White.copy(alpha = 0.85f) else colors.secondaryLabel, maxLines = 1,
            )
        }
    }
}

/** A zone's circle in its map tone with the icon of its type; without a type, the initials, like an avatar. */
@Composable
private fun ZoneBadge(venue: Venue, index: Int) {
    val colors = AppTheme.colors
    val zone = venue.zones[index]
    val tone = zoneTone(venue, index)
    val glyph = typeGlyph(zone.type)
    if (glyph != null) {
        IconCircle(glyph, colors.zoneSolid(tone, zone.id), Color.White, 36.dp)
        return
    }
    val size = 36.dp
    // letters scale with the circle, as in Avatar
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(Modifier.size(size).clip(CircleShape).background(colors.zoneFill(tone, zone.id)), contentAlignment = Alignment.Center) {
        Text(
            initials(zone.name), color = colors.zoneInk(tone, zone.id), maxLines = 1,
            style = MaterialTheme.typography.subheadline.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Medium),
        )
    }
}

/** A place sent in a message: the map with the sender's dot and the route to it, then their zone and how far it is from you. */
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
    val camera = remember { MapCamera() }
    val detail = when {
        point == null -> stringResource(R.string.map_no_position)
        !vm.venue.bounds.contains(point.lat, point.lon) -> stringResource(R.string.map_pin_outside)
        here != null -> stringResource(R.string.map_distance, distanceMeters(here, point)).replaceFirstChar { it.titlecase() }
        else -> null
    }

    LaunchedEffect(camera) {
        snapshotFlow { camera.ready }.first { it }
        camera.show(listOfNotNull(here, point), 2.4f, animated = false)
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
                camera = camera,
                target = point,
            )
            InsetGroup(Modifier.padding(top = 16.dp)) {
                GroupRow(
                    if (zoneName.isEmpty()) stringResource(R.string.zone_unknown) else zoneName,
                    subtitle = detail,
                    leading = { if (index >= 0) ZoneBadge(vm.venue, index) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                )
            }
        }
    }
}
