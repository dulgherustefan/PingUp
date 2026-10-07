package ro.safetyplease.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ro.safetyplease.app.R
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.MapPin
import ro.safetyplease.app.ui.common.MeButton
import ro.safetyplease.app.ui.common.VenueMap
import ro.safetyplease.app.ui.common.distanceMeters
import ro.safetyplease.app.ui.common.incidentPoint
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.GroupDivider
import ro.safetyplease.app.ui.designsystem.GroupRow
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.IconCircle
import ro.safetyplease.app.ui.designsystem.InsetGroup
import ro.safetyplease.app.ui.designsystem.NavScreen
import ro.safetyplease.app.ui.designsystem.SectionTitle
import ro.safetyplease.app.ui.designsystem.SwitchRow
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TextLink
import ro.safetyplease.app.ui.designsystem.initials
import ro.safetyplease.app.ui.designsystem.rememberHaptics
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.rememberLocationRequest
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.data.Role as AppRole

private enum class MapFocus { MINE, MEETING, ZONE }

/**
 * Event map, as a tab or opened from a chat: the map in a card, the chosen place in a group, then every zone.
 * Tapping the map or a zone in the list selects it. Opened from a chat, the chosen zone is sent right away.
 * As a tab ([asTab]) the top left shows your avatar instead of a back arrow.
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
                // your zone (or the tapped one) and the meeting point stacked, no toggle between them
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
                            // tapping enlarges the flag on the map
                            onClick = {
                                focus = MapFocus.MEETING
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                        )
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

/** Zone circle in its map color, initials in the dark tint, like an avatar. */
@Composable
private fun ZoneBadge(index: Int, id: String, name: String) {
    val colors = AppTheme.colors
    val size = 36.dp
    // letters scale with the circle, as in Avatar
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(Modifier.size(size).clip(CircleShape).background(colors.zoneFill(index, id)), contentAlignment = Alignment.Center) {
        Text(
            initials(name), color = colors.zoneInk(index, id), maxLines = 1,
            style = MaterialTheme.typography.subheadline.copy(fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Medium),
        )
    }
}

/** A place sent in a message: map with the sender's dot, then their zone and how far it is from you. */
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
