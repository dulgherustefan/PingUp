package ro.safetyplease.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.common.FriendSpot
import ro.safetyplease.ui.common.LowBatteryBanner
import ro.safetyplease.ui.common.MapCamera
import ro.safetyplease.ui.common.MapCorner
import ro.safetyplease.ui.common.MapGlyph
import ro.safetyplease.ui.common.MapPin
import ro.safetyplease.ui.common.MapReserve
import ro.safetyplease.ui.common.MeButton
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.friendSpotText
import ro.safetyplease.ui.common.incidentPoint
import ro.safetyplease.ui.common.onSite
import ro.safetyplease.ui.common.routeText
import ro.safetyplease.ui.common.spotColor
import ro.safetyplease.ui.common.typeGlyph
import ro.safetyplease.ui.common.venueAspect
import ro.safetyplease.ui.common.zoneTone
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.IconCircle
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.LocalTextOnly
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SwitchRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.initials
import ro.safetyplease.ui.designsystem.largeFont
import ro.safetyplease.ui.designsystem.pressScale
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.notices.shadeType
import ro.safetyplease.ui.rememberLocationRequest
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.clear
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.demo_simulate_location
import ro.safetyplease.ui.resources.map_about
import ro.safetyplease.ui.resources.map_about_subtitle
import ro.safetyplease.ui.resources.map_about_text
import ro.safetyplease.ui.resources.map_center_me
import ro.safetyplease.ui.resources.map_distance
import ro.safetyplease.ui.resources.map_friends
import ro.safetyplease.ui.resources.map_here_at
import ro.safetyplease.ui.resources.map_meeting_point
import ro.safetyplease.ui.resources.map_meters
import ro.safetyplease.ui.resources.map_no_position
import ro.safetyplease.ui.resources.map_outside
import ro.safetyplease.ui.resources.map_outside_text
import ro.safetyplease.ui.resources.map_pick_zone
import ro.safetyplease.ui.resources.map_pick_zone_text
import ro.safetyplease.ui.resources.map_pin_outside
import ro.safetyplease.ui.resources.map_quick_charge
import ro.safetyplease.ui.resources.map_quick_dea
import ro.safetyplease.ui.resources.map_quick_exit
import ro.safetyplease.ui.resources.map_quick_info
import ro.safetyplease.ui.resources.map_quick_me
import ro.safetyplease.ui.resources.map_quick_medical
import ro.safetyplease.ui.resources.map_quick_meeting
import ro.safetyplease.ui.resources.map_quick_shade
import ro.safetyplease.ui.resources.map_quick_water
import ro.safetyplease.ui.resources.map_quick_wc
import ro.safetyplease.ui.resources.map_set_zone
import ro.safetyplease.ui.resources.map_source_gps
import ro.safetyplease.ui.resources.map_source_manual
import ro.safetyplease.ui.resources.map_source_simulated
import ro.safetyplease.ui.resources.map_title
import ro.safetyplease.ui.resources.map_where_tap
import ro.safetyplease.ui.resources.map_you_are_here
import ro.safetyplease.ui.resources.map_your_zone
import ro.safetyplease.ui.resources.map_zone_tag
import ro.safetyplease.ui.resources.map_zones
import ro.safetyplease.ui.resources.map_zoom_in
import ro.safetyplease.ui.resources.map_zoom_out
import ro.safetyplease.ui.resources.pages_map_text
import ro.safetyplease.ui.resources.pages_title
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.ui.text.NoticeText
import ro.safetyplease.ui.text.noticeTitle
import ro.safetyplease.ui.text.rememberNow
import kotlin.math.ln

/** Places the quick chips lead to, in chip order. */
private val SpotTypes = listOf("medical", "exit", Venue.MEETING, "water", "wc", "info", "charge", Venue.DEA)

private enum class MapFocus { MINE, ZONE, SPOT }

/**
 * Event map, as a tab or opened from a chat. On top the big map, which opens on where you are; below it, shortcuts
 * to the nearest medical point, exit, meeting point, water, toilets, info, charging and defibrillator (DEA), each
 * with its distance, then the chosen place and every zone. Without GPS, the zone you picked stands in for your position.
 * Opened from a chat, the chosen zone is sent right away. As a tab ([asTab]) the top left shows your avatar instead of a back arrow.
 */
@Composable
fun MapScreen(vm: AppViewModel, dest: Dest.Map, asTab: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val board by vm.c.notices.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    val battery by vm.c.battery.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val heat = if (vm.c.showsNotices(settings)) board.active(now / 1000).firstOrNull { it.second.topic == NoticeTopic.HEAT }?.second else null
    // in the heat, water and shade come first
    val quickTypes = if (heat == null) SpotTypes else listOfNotNull("water", shadeType(vm.venue)) + (SpotTypes - "water")
    var simulate by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(if (dest.spot.isNotEmpty()) MapFocus.SPOT else MapFocus.MINE) }
    var spotType by rememberSaveable { mutableStateOf(dest.spot) }
    var tapped by rememberSaveable { mutableStateOf("") }
    val here = position
    val simulated = settings.simLat != null
    val autoZone = here?.let { vm.venue.zoneAt(it.lat, it.lon) }
    val manualZone = settings.manualZone
    val myZoneId = autoZone?.id ?: manualZone
    // away from the venue, distances would run to hundreds of km
    val origin = onSite(here, vm.venue) ?: vm.venue.zone(manualZone)?.center
    val spot = if (focus == MapFocus.SPOT) vm.venue.nearest(spotType, origin) else null
    val requestLocation = rememberLocationRequest(vm)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val camera = remember { MapCamera() }
    val animateMoves = !LocalReduceMotion.current
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val offline = vm.phone.offlineMap
    val offlineKind by remember(offline) { offline?.kind ?: MutableStateFlow(MapKind.FALLBACK) }.collectAsStateWithLifecycle()
    // in text-only mode the drawn map stands in, with its battery note
    val mapKind = if (LocalTextOnly.current) MapKind.FALLBACK else offlineKind
    var engine by remember { mutableStateOf<PingMapEngine?>(null) }
    val mapOverlay by vm.mapOverlays.overlay.collectAsStateWithLifecycle()
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    var aboutMap by rememberSaveable { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<GeoPoint?>(null) }
    LayersOverlay(vm)

    val friends by vm.friends.collectAsStateWithLifecycle()
    val records by vm.friendPositions.collectAsStateWithLifecycle()
    val friendSpots = FriendSpot.of(records, friends, now, vm.venue)
    val mapFriends = friendSpots.map { MapFriend(it.friend.nodeId.toString(), it.point.lat, it.point.lon, it.ringM.toFloat(), it.age, it.friend.nickname) }
    val friendPoint = dest.friend?.let { id -> friendSpots.firstOrNull { it.friend.nodeId == id }?.point }

    /** What the map opens on: the way to a place, a friend and you, or just you. */
    fun openingPoints(): List<GeoPoint> = when {
        focus == MapFocus.SPOT -> listOfNotNull(origin, vm.venue.nearest(spotType, origin)?.point)
        friendPoint != null -> listOfNotNull(origin, friendPoint)
        else -> listOfNotNull(origin)
    }

    val pins = remember(incidents.staff, settings.role, colors) {
        if (!settings.role.hasStaffKeys) emptyList()
        else incidents.staff.filter { !AckStatus.isClosed(it.status) && !Priority.isInformational(it.category) }.mapNotNull { incident ->
            incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId, incident.severity) }
        }
    }

    fun move(points: List<GeoPoint>, most: Float) {
        val real = engine
        if (mapKind == MapKind.REAL && real != null) real.show(points, realZoom(most), animateMoves)
        else scope.launch { camera.show(points, most, animateMoves) }
    }

    fun showMine() {
        focus = MapFocus.MINE
        if (position == null) requestLocation()
        val real = engine
        if (mapKind == MapKind.REAL && real != null && here != null) {
            real.setFollow(FollowMode.NORTH_UP)
        } else move(listOfNotNull(origin), 2.2f)
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun zoom(closer: Boolean) {
        val real = engine
        if (mapKind == MapKind.REAL && real != null) real.zoomBy(if (closer) 1.0 else -1.0, animateMoves)
        else scope.launch { camera.zoomBy(if (closer) 2f else 0.5f, animateMoves) }
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
        val points = openingPoints()
        if (points.isNotEmpty()) camera.show(points, if (focus == MapFocus.SPOT) 2.6f else 1.5f, animated = false)
    }

    // the real map: the organiser's layer, you with your accuracy, the selection and the incident pins
    LaunchedEffect(engine, mapKind) {
        val real = engine ?: return@LaunchedEffect
        real.setVenue(vm.venue) { hex(spotColor(colors, it)) }
        val points = openingPoints()
        if (points.isEmpty()) real.fit(vm.venue.bounds, animated = false)
        else real.show(points, realZoom(if (focus == MapFocus.SPOT) 2.6f else 1.5f), animated = false)
    }
    LaunchedEffect(engine, here, fix) {
        // a simulated or expired position has no accuracy of its own
        val accuracy = fix?.takeIf { here != null && it.lat == here.lat && it.lon == here.lon }?.accuracyM ?: 0f
        engine?.setMe(here?.let { MapMe(it.lat, it.lon, accuracy) })
    }
    LaunchedEffect(engine, focus, tapped, myZoneId, spot) {
        engine?.setSelection(if (focus == MapFocus.ZONE) tapped else myZoneId, spot?.point)
    }
    LaunchedEffect(engine, pins) {
        engine?.setMarkers(pins.map { MapMarker(it.key, it.point, hex(it.color)) })
    }
    LaunchedEffect(engine, mapFriends) {
        engine?.setFriends(mapFriends)
    }
    LaunchedEffect(engine, mapOverlay) {
        engine?.setOverlay(mapOverlay)
    }
    LaunchedEffect(engine) {
        engine?.setLongPress { pressed = it }
    }

    // a pin of a feature's layer goes to that feature, any other is an incident
    fun openPin(key: String) {
        if (!vm.mapOverlays.tap(key)) vm.open(Dest.Incident(key))
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

    pressed?.let { PlaceDialog(vm, it) { pressed = null } }
    if (aboutMap) {
        AppDialog(
            onDismiss = { aboutMap = false }, title = stringResource(Res.string.map_about),
            confirmLabel = stringResource(Res.string.close), onConfirm = { aboutMap = false }, dismissLabel = null,
        ) {
            Text(stringResource(Res.string.map_about_text), style = MaterialTheme.typography.subheadline, color = colors.label)
        }
    }

    NavScreen(
        title = stringResource(Res.string.map_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop ->
            if (asTab) MeButton(settings.nickname) { vm.open(Dest.Me) }
            else GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            if (PowerPolicy.lowBattery(battery.percent, battery.charging)) {
                item(key = "battery") { LowBatteryBanner(battery, vm.venue, origin, Modifier.padding(start = Gutter, end = Gutter, top = 8.dp)) }
            }
            item(key = "map") {
                val overlay: @Composable BoxScope.() -> Unit = {
                    val whereText = when {
                        autoZone != null -> stringResource(Res.string.map_here_at, autoZone.name)
                        here != null -> stringResource(Res.string.map_outside)
                        manualZone.isNotEmpty() -> stringResource(Res.string.map_here_at, vm.venue.zoneName(manualZone))
                        else -> stringResource(Res.string.map_where_tap)
                    }
                    // clear of the compass in the top right corner
                    WherePill(whereText, known = origin != null, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp, end = 48.dp)) { showMine() }
                    heat?.let { HeatPill(it.level, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
                    MapControls(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        onZoomIn = { zoom(true) },
                        onZoomOut = { zoom(false) },
                        onLocate = { showMine() },
                    )
                }
                val mapModifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp)
                // taller than the site: room for the top label and the buttons without covering the zones
                val mapAspect = venueAspect(vm.venue) * 0.8f
                val frame = mapModifier.fillMaxWidth().aspectRatio(mapAspect).clip(RoundedCornerShape(26.dp))
                when (mapKind) {
                    MapKind.REAL -> Box(frame) {
                        // the map view goes with this item; its engine must not outlive it
                        DisposableEffect(Unit) { onDispose { engine = null } }
                        offline?.Show(
                            dark = colors.dark,
                            palette = MapPalette(
                                hex(colors.label), hex(colors.cell), hex(colors.accent), hex(colors.green), hex(colors.secondaryLabel),
                                OverlayColors.of(colors),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onTap = { point, key ->
                                when {
                                    key != null -> openPin(key)
                                    simulate -> vm.setSimulatedLocation(point)
                                    else -> vm.venue.zoneAt(point.lat, point.lon)?.let { showZone(it.id) }
                                }
                            },
                            onReady = { engine = it },
                        )
                        overlay()
                    }
                    MapKind.PENDING -> Box(frame.background(colors.cell))
                    MapKind.FALLBACK -> VenueMap(
                        venue = vm.venue,
                        modifier = mapModifier,
                        position = origin,
                        pins = pins,
                        friends = mapFriends,
                        myZone = myZoneId,
                        highlightZone = if (focus == MapFocus.ZONE) tapped else "",
                        meetingFocused = focus == MapFocus.SPOT && spotType == Venue.MEETING,
                        onTap = { point, zone ->
                            if (simulate) vm.setSimulatedLocation(point)
                            else if (zone != null) showZone(zone.id)
                        },
                        onPinTap = { openPin(it.key) },
                        onLongPress = { pressed = it },
                        camera = camera,
                        target = spot?.point,
                        targetColor = spotColor(colors, spotType),
                        aspect = mapAspect,
                        reserved = listOfNotNull(
                            MapReserve(MapCorner.BottomEnd, ControlsReserveWidth, ControlsReserveHeight),
                            heat?.let { MapReserve(MapCorner.BottomStart, HeatReserveWidth, HeatReserveHeight) },
                        ),
                        mapOverlay = mapOverlay,
                        overlay = overlay,
                    )
                }
            }
            item(key = "quick") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 12.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickChip(Sym.MyLocation, stringResource(Res.string.map_quick_me), null, colors.accent, focus == MapFocus.MINE) { showMine() }
                    quickTypes.forEach { type ->
                        val near = vm.venue.nearest(type, origin) ?: return@forEach
                        QuickChip(
                            typeGlyph(type) ?: Sym.Flag, spotLabel(type), origin?.let { distanceMeters(it, near.point) },
                            spotColor(colors, type), focus == MapFocus.SPOT && spotType == type,
                        ) { showSpot(type) }
                    }
                }
            }
            if (dest.sendTo == null) {
                item(key = "layers") {
                    LayersPanel(vm, origin) { point ->
                        move(listOf(point), 2.6f)
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                }
            }
            item(key = "place") {
                InsetGroup {
                    when (focus) {
                        MapFocus.SPOT -> {
                            val name = spot?.name?.takeIf { it.isNotEmpty() }
                                ?: if (spotType == Venue.MEETING) stringResource(Res.string.map_meeting_point) else spotLabel(spotType)
                            GroupRow(
                                name,
                                subtitle = spot?.let { routeText(origin, it.point) },
                                leading = { IconCircle(typeGlyph(spotType) ?: Sym.Flag, spotColor(colors, spotType), Color.White, 36.dp) },
                            )
                        }
                        MapFocus.ZONE -> {
                            val index = vm.venue.zones.indexOfFirst { it.id == tapped }
                            val zone = vm.venue.zones.getOrNull(index)
                            val canSet = here == null && tapped != myZoneId
                            // with large fonts the button goes under the name, so the name keeps the row's width
                            val stacked = largeFont()
                            GroupRow(
                                zone?.name ?: stringResource(Res.string.zone_unknown),
                                subtitle = when {
                                    tapped == myZoneId && autoZone != null -> stringResource(Res.string.map_you_are_here)
                                    tapped == myZoneId -> stringResource(Res.string.map_your_zone) + " · " + stringResource(Res.string.map_source_manual)
                                    origin != null && zone != null -> routeText(origin, zone.center)
                                    else -> stringResource(Res.string.map_zone_tag)
                                },
                                leading = { if (zone != null) ZoneBadge(vm.venue, index) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                                trailing = if (canSet && !stacked) {
                                    { AppButton(stringResource(Res.string.map_set_zone), { setMine(tapped) }, compact = true) }
                                } else null,
                            )
                            if (canSet && stacked) {
                                AppButton(
                                    stringResource(Res.string.map_set_zone), { setMine(tapped) },
                                    Modifier.padding(start = 64.dp, end = Gutter, bottom = 12.dp), compact = true,
                                )
                            }
                        }
                        MapFocus.MINE -> {
                            val yourZone = stringResource(Res.string.map_your_zone)
                            val source = stringResource(if (simulated) Res.string.map_source_simulated else Res.string.map_source_gps)
                            val manual = stringResource(Res.string.map_source_manual)
                            val (title, subtitle) = when {
                                autoZone != null -> autoZone.name to "$yourZone · $source"
                                here != null -> stringResource(Res.string.map_outside) to stringResource(Res.string.map_outside_text)
                                manualZone.isNotEmpty() -> vm.venue.zoneName(manualZone) to "$yourZone · $manual"
                                else -> stringResource(Res.string.map_pick_zone) to stringResource(Res.string.map_pick_zone_text)
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
            if (friendSpots.isNotEmpty()) {
                item(key = "friends-title") { SectionTitle(stringResource(Res.string.map_friends)) }
                item(key = "friends") {
                    InsetGroup {
                        friendSpots.forEachIndexed { index, spot ->
                            GroupRow(
                                spot.friend.nickname,
                                subtitle = friendSpotText(spot),
                                leading = { Avatar(spot.friend.nickname, 36.dp) },
                                chevron = true,
                                onClick = { vm.open(Dest.Profile(Conversations.friend(spot.friend.nodeId))) },
                            )
                            if (index < friendSpots.lastIndex) GroupDivider(start = 64.dp)
                        }
                    }
                }
            }
            if (dest.sendTo == null) {
                item(key = "pages") {
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        GroupRow(
                            stringResource(Res.string.pages_title), subtitle = stringResource(Res.string.pages_map_text), icon = Sym.Info,
                            chevron = true, onClick = { vm.open(Dest.Pages) },
                        )
                        GroupDivider()
                        GroupRow(
                            stringResource(Res.string.map_about), subtitle = stringResource(Res.string.map_about_subtitle), icon = Sym.Place,
                            chevron = true, onClick = { aboutMap = true },
                        )
                    }
                }
            }
            // test tool; stays visible while a simulated location is set so it can be cleared
            if (vm.phone.demoAvailable && (vm.demoUnlocked || simulated)) {
                item(key = "simulate") {
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        SwitchRow(stringResource(Res.string.demo_simulate_location), simulate, { simulate = it }, icon = Sym.Science)
                        if (simulated) {
                            GroupDivider()
                            // align the link text with the row text above
                            TextLink(stringResource(Res.string.clear), { vm.setSimulatedLocation(null) }, Modifier.padding(start = 48.dp, top = 4.dp, bottom = 4.dp))
                        }
                    }
                }
            }
            item(key = "zones-title") { SectionTitle(stringResource(Res.string.map_zones)) }
            item(key = "zones") {
                val hereLabel = stringResource(Res.string.map_you_are_here)
                // with large fonts "Ești aici" goes under the name instead of squeezing it
                val stacked = largeFont()
                InsetGroup {
                    vm.venue.zones.forEachIndexed { index, zone ->
                        val mine = zone.id == myZoneId
                        GroupRow(
                            zone.name,
                            subtitle = if (mine && stacked) hereLabel else null,
                            value = if (mine && !stacked) hereLabel else null,
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

/** VenueMap's zoom over the whole site, as a MapLibre zoom level: 1 is about level 16 at a festival's scale. */
private fun realZoom(most: Float): Double = 16.0 + ln(most.toDouble()) / ln(2.0)

private fun hex(color: Color): String = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

@Composable
private fun spotLabel(type: String): String = stringResource(
    when (type) {
        "medical" -> Res.string.map_quick_medical
        "exit" -> Res.string.map_quick_exit
        Venue.MEETING -> Res.string.map_quick_meeting
        "water" -> Res.string.map_quick_water
        "wc" -> Res.string.map_quick_wc
        "info" -> Res.string.map_quick_info
        Venue.DEA -> Res.string.map_quick_dea
        Venue.SHADE, "chill" -> Res.string.map_quick_shade
        else -> Res.string.map_quick_charge
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
            maxLines = if (largeFont()) 2 else 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The heat level in force, over the bottom left of the map, so the shortcuts below read as the way out of it. */
@Composable
private fun HeatPill(level: Int, modifier: Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(colors.cell)
            .padding(horizontal = 12.dp, vertical = 7.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Fire, null, Modifier.size(16.dp), tint = colors.orange)
        Spacer(Modifier.width(6.dp))
        Text(
            noticeTitle(NoticeTopic.HEAT, level),
            style = MaterialTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.label, maxLines = 1,
        )
    }
}

/** The heat label with its 10 dp margin; the map draws no name under it. */
private val HeatReserveWidth = 170.dp
private val HeatReserveHeight = 32.dp + 20.dp

/** The zoom and locate buttons with their 10 dp margin and a little air: the map draws no name under them. */
private val ControlsReserveWidth = 38.dp + 20.dp
private val ControlsReserveHeight = 38.dp * 3 + 1.dp + 8.dp + 20.dp

@Composable
private fun MapControls(modifier: Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onLocate: () -> Unit) {
    val colors = AppTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.shadow(6.dp, RoundedCornerShape(19.dp)).clip(RoundedCornerShape(19.dp)).background(colors.cell),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapButton(MapGlyph.Plus, stringResource(Res.string.map_zoom_in), onZoomIn)
            Box(Modifier.width(22.dp).height(1.dp).background(colors.separator))
            MapButton(MapGlyph.Minus, stringResource(Res.string.map_zoom_out), onZoomOut)
        }
        Box(Modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(colors.cell)) {
            MapButton(Sym.MyLocation, stringResource(Res.string.map_center_me), onLocate, tint = colors.accent)
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
        Modifier.pressScale(press, 0.95f).heightIn(min = 40.dp).clip(CircleShape).background(fill)
            .selectable(selected, interactionSource = press, indication = null, role = Role.Tab, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
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
                " · " + stringResource(Res.string.map_meters, meters), style = MaterialTheme.typography.subheadline,
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
    val near = onSite(here, vm.venue)
    val detail = when {
        point == null -> stringResource(Res.string.map_no_position)
        !vm.venue.bounds.contains(point.lat, point.lon) -> stringResource(Res.string.map_pin_outside)
        near != null -> stringResource(Res.string.map_distance, distanceMeters(near, point)).replaceFirstChar { it.titlecase() }
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
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
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
                    if (zoneName.isEmpty()) stringResource(Res.string.zone_unknown) else zoneName,
                    subtitle = detail,
                    leading = { if (index >= 0) ZoneBadge(vm.venue, index) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                )
            }
        }
    }
}
