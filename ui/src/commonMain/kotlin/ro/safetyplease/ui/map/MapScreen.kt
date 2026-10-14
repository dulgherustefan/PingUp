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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.location.Accuracy
import ro.safetyplease.core.location.MeState
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
import ro.safetyplease.ui.common.RegasireMapRows
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.distanceText
import ro.safetyplease.ui.common.friendSpotText
import ro.safetyplease.ui.common.incidentPoint
import ro.safetyplease.ui.common.onSite
import ro.safetyplease.ui.common.rememberRegasireMap
import ro.safetyplease.ui.common.routeText
import ro.safetyplease.ui.common.spotColor
import ro.safetyplease.ui.common.typeGlyph
import ro.safetyplease.ui.common.venueAspect
import ro.safetyplease.ui.common.zoneTone
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.caption2
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
import ro.safetyplease.ui.map.style.MapStyleBuilder
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
import ro.safetyplease.ui.resources.map_friends
import ro.safetyplease.ui.resources.map_here_at
import ro.safetyplease.ui.resources.map_meeting_point
import ro.safetyplease.ui.resources.map_no_position
import ro.safetyplease.ui.resources.map_outside
import ro.safetyplease.ui.resources.map_outside_text
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
import ro.safetyplease.ui.resources.map_you_are_here
import ro.safetyplease.ui.resources.map_your_zone
import ro.safetyplease.ui.resources.map_zone_tag
import ro.safetyplease.ui.resources.map_zones
import ro.safetyplease.ui.resources.map_zoom_in
import ro.safetyplease.ui.resources.map_zoom_out
import ro.safetyplease.ui.resources.pages_map_text
import ro.safetyplease.ui.resources.pages_title
import ro.safetyplease.ui.resources.maps_credits
import ro.safetyplease.ui.resources.maps_distance
import ro.safetyplease.ui.resources.maps_far
import ro.safetyplease.ui.resources.maps_far_text
import ro.safetyplease.ui.resources.maps_location_off
import ro.safetyplease.ui.resources.maps_no_ring
import ro.safetyplease.ui.resources.maps_off_text
import ro.safetyplease.ui.resources.maps_osm
import ro.safetyplease.ui.resources.maps_overview
import ro.safetyplease.ui.resources.maps_overview_text
import ro.safetyplease.ui.resources.maps_picked_zone
import ro.safetyplease.ui.resources.maps_searching
import ro.safetyplease.ui.resources.maps_stale_hours
import ro.safetyplease.ui.resources.maps_stale_min
import ro.safetyplease.ui.resources.maps_stale_text
import ro.safetyplease.ui.resources.route_go
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.ui.text.NoticeText
import ro.safetyplease.ui.text.noticeTitle
import ro.safetyplease.ui.text.rememberNow
import kotlin.math.ln

/** Places the quick chips lead to, in chip order. */
private val SpotTypes = listOf("medical", "exit", Venue.MEETING, "water", "wc", "info", "charge", Venue.DEA)

/** The chips that walk you there from a position on the site: their distance is on foot to the nearest open one. */
private val WalkTypes = setOf("medical", Venue.EXIT)

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
    val fused by vm.c.positioning.fused.collectAsStateWithLifecycle()
    val locating = remember(now, fused) { vm.phone.locationAllowed() && vm.phone.locationOn() }
    val meState = remember(fused, now, locating) { MeState.of(fused, vm.c.clock.wallMs(), locating, vm.venue.water) }
    val simPoint = settings.simLat?.let { lat -> settings.simLon?.let { lon -> GeoPoint(lat, lon) } }
    // the dot is only ever the phone's position, fresh or old, or the simulated one: a zone picked by hand is highlighted
    val mapMe = simPoint?.let { MapMe(it.lat, it.lon, Accuracy.UNKNOWN) } ?: MapMe.of(meState)
    val mePoint = mapMe?.let { GeoPoint(it.lat, it.lon) }
    val autoZone = here?.let { vm.venue.zoneAt(it.lat, it.lon) }
    val manualZone = settings.manualZone
    val myZoneId = autoZone?.id ?: manualZone
    val where = MapAnywhere.where(meState, simPoint, vm.venue, manualZone, vm.c.clock.wallMs())
    val onSiteFix = onSite(here, vm.venue)
    // distances run from where you are, anywhere; with no position, from the zone you picked
    val origin = here ?: vm.venue.zone(manualZone)?.center
    // the drawn map has only the festival: it opens on you there, or on the zone you picked
    val siteOrigin = onSiteFix ?: vm.venue.zone(manualZone)?.center
    // only from a real position on the site does a chip walk you there
    val walkMeters = rememberWalkMeters(vm, onSiteFix, WalkTypes)
    // the walking grid is built once, away from the main thread, before a chip or a pin asks for a route
    LaunchedEffect(settings.role.hasStaffKeys) { withContext(Dispatchers.Default) { vm.c.walkRouter.grid(settings.role.hasStaffKeys) } }
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
    val packs by remember(offline) { offline?.packs?.packs ?: MutableStateFlow(emptyList()) }.collectAsStateWithLifecycle()
    // where the camera last stopped, for the overview's note
    var cameraAt by remember { mutableStateOf<Pair<GeoPoint, Double>?>(null) }
    val overviewOnly = cameraAt?.let { (center, zoom) ->
        MapAnywhere.overviewOnly(zoom, offline?.packs?.detailAt(center.lat, center.lon, zoom) != null)
    } == true
    val mapOverlay by vm.mapOverlays.overlay.collectAsStateWithLifecycle()
    var aboutMap by rememberSaveable { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<GeoPoint?>(null) }
    LayersOverlay(vm, dest.sendTo == null)
    val regasire = rememberRegasireMap(vm)

    val friends by vm.friends.collectAsStateWithLifecycle()
    val records by vm.friendPositions.collectAsStateWithLifecycle()
    val friendSpots = FriendSpot.of(records, friends, now, vm.venue)
    val mapFriends = friendSpots.map { MapFriend(it.friend.nodeId.toString(), it.point.lat, it.point.lon, it.ringM.toFloat(), it.age, it.friend.nickname) }
    val friendPoint = dest.friend?.let { id -> friendSpots.firstOrNull { it.friend.nodeId == id }?.point }

    /** What the drawn map opens on: the way to a place, a friend and you, or just you. */
    fun openingPoints(): List<GeoPoint> = when {
        focus == MapFocus.SPOT -> listOfNotNull(siteOrigin, vm.venue.nearest(spotType, origin)?.point)
        friendPoint != null -> listOfNotNull(siteOrigin, friendPoint)
        else -> listOfNotNull(siteOrigin)
    }

    /** A pack has streets at [point], at the zoom the map comes to on the festival. */
    fun detailed(point: GeoPoint): Boolean = offline?.packs?.detailAt(point.lat, point.lon, realZoom(1f)) != null

    val pins = remember(incidents.staff, settings.role, colors) {
        if (!settings.role.hasStaffKeys) emptyList()
        else incidents.staff.filter { !AckStatus.isClosed(it.status) && !Priority.isInformational(it.category) }.mapNotNull { incident ->
            incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId, incident.severity) }
        }
    }

    fun move(points: List<GeoPoint>, most: Float) {
        val real = engine
        if (mapKind == MapKind.REAL && real != null) {
            real.setFollow(FollowMode.NONE)
            // where only the overview covers, no closer than it can be read at
            real.show(points, if (points.all(::detailed)) realZoom(most) else minOf(realZoom(most), MapZoom.OVERVIEW_OPEN), animateMoves)
        } else scope.launch { camera.show(points, most, animateMoves) }
    }

    fun showMine() {
        focus = MapFocus.MINE
        if (mePoint == null) requestLocation()
        val real = engine
        if (mapKind == MapKind.REAL && real != null && mePoint != null) {
            real.setFollow(FollowMode.NORTH_UP)
        } else move(listOfNotNull(if (mapKind == MapKind.REAL) origin else siteOrigin), 2.2f)
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

    // The real map: the organiser's layer, you as the phone places you, the selection and the incident pins. It opens
    // on you wherever you are, with the place it was opened for; with no position, on the festival. Opened on you, it
    // keeps you in the middle until the map is moved, a first position too.
    LaunchedEffect(engine, mapKind) {
        val real = engine ?: return@LaunchedEffect
        real.setVenue(vm.venue) { hex(spotColor(colors, it)) }
        real.setOnCameraIdle { center, zoom -> cameraAt = center to zoom }
        val toward = if (focus == MapFocus.SPOT) vm.venue.nearest(spotType, origin)?.point else friendPoint
        val opening = MapAnywhere.opening(mePoint ?: siteOrigin, toward, realZoom(if (focus == MapFocus.SPOT) 2.6f else 1.5f), ::detailed)
        if (opening.points.isEmpty()) real.fit(vm.venue.bounds, animated = false)
        else real.show(opening.points, opening.maxZoom, animated = false)
        if (toward == null) real.setFollow(FollowMode.NORTH_UP)
    }
    LaunchedEffect(engine, mapMe) {
        engine?.setMe(mapMe)
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
    // A finger held on the map, not while a zone is being chosen to send: anywhere on the real map, inside the site on
    // the drawn one. Near a numbered landmark it asks whether the person stands by it; elsewhere it offers the point to
    // a friend or keeps it as the saved place.
    val choosingZone = dest.sendTo != null
    fun onMapLongPress(point: GeoPoint) {
        if (choosingZone || (mapKind != MapKind.REAL && !vm.venue.bounds.contains(point.lat, point.lon))) return
        if (!regasire.longPress(point)) pressed = point
    }
    LaunchedEffect(engine, choosingZone) {
        engine?.setOnLongPress { onMapLongPress(it) }
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
    regasire.AskDialog(vm)
    if (aboutMap) {
        AppDialog(
            onDismiss = { aboutMap = false }, title = stringResource(Res.string.map_about),
            confirmLabel = stringResource(Res.string.close), onConfirm = { aboutMap = false }, dismissLabel = null,
        ) {
            Text(stringResource(Res.string.map_about_text), style = MaterialTheme.typography.subheadline, color = colors.label)
            val credits = remember(packs) { MapStyleBuilder.credits(packs) }
            if (credits.isNotEmpty()) {
                Text(
                    stringResource(Res.string.maps_credits), style = MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.label, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                credits.forEach { Text("• $it", style = MaterialTheme.typography.footnote, color = colors.secondaryLabel) }
            }
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
                // the label on top is as wide as its words: the drawn map keeps names and its scale out from under it
                var pill by remember { mutableStateOf(IntSize.Zero) }
                val overlay: @Composable BoxScope.() -> Unit = {
                    // clear of the compass in the top right corner
                    Column(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp, end = 48.dp)) {
                        WherePill(whereText(where), whereTone(where), Modifier.onSizeChanged { pill = it }) { showMine() }
                        if (mapKind == MapKind.REAL && overviewOnly) OverviewNote(Modifier.padding(top = 6.dp))
                    }
                    // the credit the map's data asks for, never under the buttons
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp, end = ControlsReserveWidth),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        heat?.let { HeatPill(it.level, Modifier) }
                        if (mapKind == MapKind.REAL) MapCredit { aboutMap = true }
                    }
                    MapControls(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        onZoomIn = { zoom(true) },
                        onZoomOut = { zoom(false) },
                        onLocate = { showMine() },
                    )
                }
                val mapModifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp)
                // the drawn map is taller than the site: room for the top label and the buttons without covering the zones
                val mapAspect = if (mapKind == MapKind.REAL) RealMapAspect else venueAspect(vm.venue) * 0.8f
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
                        me = mapMe,
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
                        onLongPress = { onMapLongPress(it) },
                        camera = camera,
                        target = spot?.point,
                        targetColor = spotColor(colors, spotType),
                        aspect = mapAspect,
                        reserved = listOfNotNull(
                            MapReserve(MapCorner.BottomEnd, ControlsReserveWidth, ControlsReserveHeight),
                            heat?.let { MapReserve(MapCorner.BottomStart, HeatReserveWidth, HeatReserveHeight) },
                            pill.takeIf { it != IntSize.Zero }?.let { size ->
                                with(LocalDensity.current) { MapReserve(MapCorner.TopStart, size.width.toDp() + PillMargin, size.height.toDp() + PillMargin) }
                            },
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
                        val walks = type in WalkTypes && onSiteFix != null
                        QuickChip(
                            typeGlyph(type) ?: Sym.Flag, spotLabel(type),
                            if (walks) walkMeters[type] else origin?.let { distanceMeters(it, near.point) },
                            spotColor(colors, type), focus == MapFocus.SPOT && spotType == type,
                        ) { if (walks) vm.navigate(NavTarget.Nearest(type)) else showSpot(type) }
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
                            val (title, subtitle) = when (where) {
                                is WhereText.InZone -> where.zone to "$yourZone · $source"
                                WhereText.OnSite -> stringResource(Res.string.map_outside) to stringResource(Res.string.map_outside_text)
                                is WhereText.Away -> whereText(where) to stringResource(Res.string.maps_far_text)
                                is WhereText.Old -> whereText(where) to stringResource(Res.string.maps_stale_text)
                                is WhereText.Picked -> where.zone to "$yourZone · $manual"
                                WhereText.Searching -> whereText(where) to stringResource(Res.string.map_pick_zone_text)
                                WhereText.Off -> whereText(where) to stringResource(Res.string.maps_off_text)
                            }
                            // a ring the phone gave no size for is a guess, and says so
                            val guess = if (mapMe?.dashed == true && !simulated) " " + stringResource(Res.string.maps_no_ring) else ""
                            GroupRow(
                                title, subtitle = subtitle + guess,
                                leading = {
                                    IconCircle(Sym.MyLocation, if (origin != null) colors.accent else colors.fill, if (origin != null) colors.onAccent else colors.secondaryLabel, 36.dp)
                                },
                            )
                        }
                    }
                }
            }
            item(key = "regasire") { RegasireMapRows(vm, origin) }
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
internal fun realZoom(most: Float): Double = 16.0 + ln(most.toDouble()) / ln(2.0)

internal fun hex(color: Color): String = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

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

/** The real map's frame, a little taller than wide: it shows anywhere, not the festival's shape. */
internal const val RealMapAspect = 0.8f

/** The label on top of the map's words for where you are. */
@Composable
private fun whereText(where: WhereText): String = when (where) {
    is WhereText.InZone -> stringResource(Res.string.map_here_at, where.zone)
    WhereText.OnSite -> stringResource(Res.string.map_outside)
    is WhereText.Away -> stringResource(Res.string.maps_far, distanceText(where.meters))
    is WhereText.Old ->
        if (where.minutes < 60) stringResource(Res.string.maps_stale_min, where.minutes.toInt())
        else stringResource(Res.string.maps_stale_hours, (where.minutes / 60).toInt())
    is WhereText.Picked -> stringResource(Res.string.maps_picked_zone, where.zone)
    WhereText.Searching -> stringResource(Res.string.maps_searching)
    WhereText.Off -> stringResource(Res.string.maps_location_off)
}

/** The label's dot: green for a position, grey for an old one, orange for none. */
@Composable
private fun whereTone(where: WhereText): Color = when (where) {
    is WhereText.Old -> AppTheme.colors.secondaryLabel
    WhereText.Searching, WhereText.Off -> AppTheme.colors.orange
    else -> AppTheme.colors.accent
}

/** Under the top label, where only the world overview covers and the map is closer than it can be read at. */
@Composable
private fun OverviewNote(modifier: Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier.shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(colors.cell)
            .padding(horizontal = 12.dp, vertical = 7.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(
            stringResource(Res.string.maps_overview), style = MaterialTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.label,
        )
        Text(stringResource(Res.string.maps_overview_text), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
    }
}

/** "© OpenStreetMap contributors" over the real map, as its data's licence asks; a tap shows every credit. */
@Composable
private fun MapCredit(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Text(
        stringResource(Res.string.maps_osm), style = MaterialTheme.typography.caption2, color = colors.label, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.cell.copy(alpha = 0.8f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The label on top of the map: where you are at a glance; tapped, it shows you on the map. */
@Composable
private fun WherePill(text: String, tone: Color, modifier: Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(press, 0.96f).shadow(6.dp, CircleShape).clip(CircleShape).background(colors.cell)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
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

/** The top label's 10 dp margin and a little air around it. */
private val PillMargin = 14.dp

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
                " · " + distanceText(meters), style = MaterialTheme.typography.subheadline,
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

/**
 * A place sent in a message: the map with the place and you, wherever the two are (the drawn map only has the
 * festival), then its zone and how far it is from you, and the walk there when it is on the festival's paths.
 */
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
    val offline = vm.phone.offlineMap
    val offlineKind by remember(offline) { offline?.kind ?: MutableStateFlow(MapKind.FALLBACK) }.collectAsStateWithLifecycle()
    val kind = if (LocalTextOnly.current) MapKind.FALLBACK else offlineKind
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    // a simulated position has no accuracy of its own
    val me = here?.let { h -> MapMe(h.lat, h.lon, fix?.takeIf { it.lat == h.lat && it.lon == h.lon }?.accuracyM ?: 0f) }
    val detail = when {
        point == null -> stringResource(Res.string.map_no_position)
        kind != MapKind.REAL && !vm.venue.bounds.contains(point.lat, point.lon) -> stringResource(Res.string.map_pin_outside)
        here != null -> stringResource(Res.string.maps_distance, distanceText(distanceMeters(here, point))).replaceFirstChar { it.titlecase() }
        else -> null
    }

    NavScreen(
        title = pin.label,
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            val mapModifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp)
            when (kind) {
                MapKind.REAL -> {
                    var engine by remember { mutableStateOf<PingMapEngine?>(null) }
                    Box(mapModifier.fillMaxWidth().aspectRatio(RealMapAspect).clip(RoundedCornerShape(26.dp))) {
                        // the map view goes with this box; its engine must not outlive it
                        DisposableEffect(Unit) { onDispose { engine = null } }
                        offline?.Show(
                            dark = colors.dark,
                            palette = MapPalette(
                                hex(colors.label), hex(colors.cell), hex(colors.accent), hex(colors.green), hex(colors.secondaryLabel),
                                OverlayColors.of(colors),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            onTap = { _, _ -> },
                            onReady = { engine = it },
                        )
                    }
                    LaunchedEffect(engine) {
                        val real = engine ?: return@LaunchedEffect
                        real.setVenue(vm.venue) { hex(spotColor(colors, it)) }
                        val shown = listOfNotNull(here, point)
                        val close = shown.all { offline?.packs?.detailAt(it.lat, it.lon, realZoom(1f)) != null }
                        if (shown.isNotEmpty()) real.show(shown, if (close) realZoom(2.4f) else MapZoom.OVERVIEW_OPEN, animated = false)
                    }
                    LaunchedEffect(engine, point) {
                        engine?.setSelection(pin.zone, point)
                        engine?.setMarkers(listOfNotNull(point?.let { MapMarker(PIN_KEY, it, hex(colors.accent)) }))
                    }
                    LaunchedEffect(engine, me) { engine?.setMe(me) }
                }
                MapKind.PENDING -> Box(mapModifier.fillMaxWidth().aspectRatio(RealMapAspect).clip(RoundedCornerShape(26.dp)).background(colors.cell))
                MapKind.FALLBACK -> {
                    LaunchedEffect(camera) {
                        snapshotFlow { camera.ready }.first { it }
                        camera.show(listOfNotNull(here, point), 2.4f, animated = false)
                    }
                    VenueMap(
                        venue = vm.venue,
                        modifier = mapModifier,
                        me = me,
                        pins = listOfNotNull(point?.let { MapPin(it, colors.accent) }),
                        highlightZone = pin.zone,
                        camera = camera,
                        target = point,
                    )
                }
            }
            InsetGroup(Modifier.padding(top = 16.dp)) {
                GroupRow(
                    if (zoneName.isEmpty()) stringResource(Res.string.zone_unknown) else zoneName,
                    subtitle = detail,
                    leading = { if (index >= 0) ZoneBadge(vm.venue, index) else IconCircle(Sym.Place, colors.fill, colors.label, 36.dp) },
                )
                if (point != null && vm.c.walkRouter.covers(point)) {
                    GroupDivider()
                    GroupRow(
                        stringResource(Res.string.route_go), icon = Sym.Walk, tint = colors.accent, chevron = true,
                        onClick = { vm.navigate(NavTarget.Place(point, pin.label)) },
                    )
                }
            }
        }
    }
}

/** The key of the sent place's marker: a tap on it opens nothing more. */
private const val PIN_KEY = "pin"
