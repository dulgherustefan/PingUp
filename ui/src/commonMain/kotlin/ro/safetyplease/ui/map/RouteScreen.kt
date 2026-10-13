package ro.safetyplease.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.route.AnchorKind
import ro.safetyplease.core.venue.route.Route
import ro.safetyplease.core.venue.route.RouteStep
import ro.safetyplease.core.venue.route.Turn
import ro.safetyplease.core.venue.route.WalkNavigation
import ro.safetyplease.core.venue.route.WalkState
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.FriendSpot
import ro.safetyplease.ui.common.MapCamera
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.spotColor
import ro.safetyplease.ui.common.venueAspect
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.LocalTextOnly
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.rememberLocationRequest
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.map_meeting_point
import ro.safetyplease.ui.resources.map_meters
import ro.safetyplease.ui.resources.route_ahead
import ro.safetyplease.ui.resources.route_arrive_at
import ro.safetyplease.ui.resources.route_arrived
import ro.safetyplease.ui.resources.route_gate
import ro.safetyplease.ui.resources.route_incident
import ro.safetyplease.ui.resources.route_landmark
import ro.safetyplease.ui.resources.route_last
import ro.safetyplease.ui.resources.route_left
import ro.safetyplease.ui.resources.route_no_goal
import ro.safetyplease.ui.resources.route_no_position
import ro.safetyplease.ui.resources.route_off
import ro.safetyplease.ui.resources.route_outside
import ro.safetyplease.ui.resources.route_outside_text
import ro.safetyplease.ui.resources.route_steps
import ro.safetyplease.ui.resources.route_title
import ro.safetyplease.ui.resources.route_toward
import ro.safetyplease.ui.resources.route_turn_back
import ro.safetyplease.ui.resources.route_turn_left
import ro.safetyplease.ui.resources.route_turn_on
import ro.safetyplease.ui.resources.route_turn_right
import ro.safetyplease.ui.resources.route_turn_straight
import ro.safetyplease.ui.resources.route_unreachable
import ro.safetyplease.ui.resources.route_unreachable_text
import ro.safetyplease.ui.text.rememberNow
import kotlin.math.max

/** How often the walk is followed: a fix comes about every second while it lasts. */
private const val TICK_MS = 1_000L

/** How often the arrow follows the compass between fixes. */
private const val ARROW_TICK_MS = 250L

private const val END_PIN = "route:end"

/**
 * The walk to [target]: the route on the map, the next step told by a landmark in large text, an arrow when the phone
 * knows which way it faces or the person walks, and every step left. The screen stays on while it leads; a turn coming,
 * leaving the route and arriving vibrate. It plans again only as [WalkNavigation] allows.
 */
@Composable
fun RouteScreen(vm: AppViewModel, target: NavTarget) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val staff = settings.role.hasStaffKeys
    val nav = remember(target, staff) { WalkNavigation(vm.c.walkRouter, staff) }
    var walk by remember(nav) { mutableStateOf<WalkState?>(null) }
    var accuracy by remember { mutableFloatStateOf(0f) }
    val beacon = remember { vm.phone.beacon(sound = false) }
    val requestLocation = rememberLocationRequest(vm)
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }

    DisposableEffect(Unit) {
        vm.phone.urgentLocation(true)
        onDispose {
            vm.phone.urgentLocation(false)
            beacon.release()
            vm.mapOverlays.clear(OverlayLayer.ROUTE)
        }
    }
    LaunchedEffect(nav) {
        while (true) {
            val now = vm.c.clock.wallMs()
            val at = vm.pointNow()
            // a simulated position has no accuracy of its own
            val fix = vm.c.locationFix.value?.takeIf { at != null && it.lat == at.lat && it.lon == at.lon }
            val spots = FriendSpot.of(vm.friendPositions.value, vm.friends.value, now, vm.venue)
            val goal = target.goal(vm.venue, spots, vm.incidents.value.staff, closedPoints(vm.c.points.value, now))
            val state = vm.c.mapState.value
            val next = withContext(Dispatchers.Default) { nav.update(at, fix?.accuracyM ?: 0f, now, state, goal) }
            accuracy = fix?.accuracyM ?: 0f
            walk = next
            (next as? WalkState.Guiding)?.progress?.event?.let { beacon.vibrate(buzz(it)) }
            delay(TICK_MS)
        }
    }

    val guiding = walk as? WalkState.Guiding
    val route = guiding?.route
    val label = when (target) {
        is NavTarget.Friend -> friends.firstOrNull { it.nodeId == target.nodeId }?.nickname
        NavTarget.MeetingPoint -> stringResource(Res.string.map_meeting_point)
        is NavTarget.Landmark -> vm.venue.landmark(target.number)?.name?.ifEmpty { null } ?: stringResource(Res.string.route_landmark, target.number)
        // a place without a name is told by its landmark
        is NavTarget.Nearest -> guiding?.destination?.let { it.name.ifEmpty { null } ?: if (it.number > 0) stringResource(Res.string.route_landmark, it.number) else null }
        is NavTarget.Place -> target.label
        is NavTarget.Incident -> stringResource(Res.string.route_incident)
    } ?: stringResource(Res.string.route_title)
    LaunchedEffect(route, label) {
        if (route == null) vm.mapOverlays.clear(OverlayLayer.ROUTE)
        else vm.mapOverlays.set(OverlayLayer.ROUTE, MapOverlay(routes = listOf(MapRoute(route.points)), pins = listOf(OverlayPin(END_PIN, route.end, target.pin, label))))
    }

    NavScreen(
        title = label,
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(bottom = 24.dp)) {
            RouteMap(vm, position, accuracy, route, Modifier.padding(start = Gutter, end = Gutter, top = 8.dp))
            Spacer(Modifier.height(16.dp))
            when (val state = walk) {
                null -> Unit
                WalkState.NoPosition -> RouteNote(stringResource(Res.string.route_no_position), null) {
                    AppButton(stringResource(Res.string.route_turn_on), requestLocation, compact = true)
                }
                WalkState.Outside -> RouteNote(stringResource(Res.string.route_outside), stringResource(Res.string.route_outside_text))
                WalkState.NoGoal -> RouteNote(stringResource(Res.string.route_no_goal), null)
                WalkState.Unreachable -> RouteNote(stringResource(Res.string.route_unreachable), stringResource(Res.string.route_unreachable_text))
                is WalkState.Guiding -> Guidance(vm, state, label, position)
            }
        }
    }
}

@Composable
private fun Guidance(vm: AppViewModel, walk: WalkState.Guiding, label: String, me: GeoPoint?) {
    val colors = AppTheme.colors
    val progress = walk.progress
    val route = walk.route
    if (!progress.arrived) vm.phone.KeepScreenOn()
    InsetGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteArrow(vm, me, progress.toward, progress.arrived)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (progress.arrived) stringResource(Res.string.route_arrived) else stepText(route.steps[progress.step], label),
                    style = MaterialTheme.typography.title2, fontWeight = FontWeight.SemiBold, color = colors.label,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val minutes = Route.minutes(progress.remainingSec)
                Text(
                    stringResource(Res.string.route_left, progress.remainingM, minutes.first, minutes.last),
                    style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                )
            }
        }
        if (progress.offRoute && !progress.arrived) {
            GroupDivider(start = Gutter)
            Text(
                stringResource(Res.string.route_off), style = MaterialTheme.typography.subheadline, color = colors.orange,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
            )
        }
    }
    if (progress.arrived) return
    val walked = route.meters - progress.remainingM
    val ahead = route.steps.drop(progress.step)
    SectionTitle(stringResource(Res.string.route_steps))
    InsetGroup {
        ahead.forEachIndexed { index, step ->
            GroupRow(
                if (step.turn == Turn.ARRIVE) stringResource(Res.string.route_arrive_at, label) else stepText(step, label),
                value = stringResource(Res.string.map_meters, max(0, step.alongM - walked)),
                leading = {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        val turned = turnDeg(step.turn)
                        if (turned == null) Icon(Sym.Flag, null, Modifier.size(22.dp), tint = colors.accent)
                        else Icon(Sym.Send, null, Modifier.size(22.dp).rotate(turned), tint = colors.accent)
                    }
                },
            )
            if (index < ahead.lastIndex) GroupDivider()
        }
    }
}

/** The way a turn's arrow points on screen; null for the arrival, which has a flag. */
private fun turnDeg(turn: Turn): Float? = when (turn) {
    Turn.LEFT -> -90f
    Turn.RIGHT -> 90f
    Turn.STRAIGHT -> 0f
    Turn.BACK -> 180f
    Turn.ARRIVE -> null
}

/** Points towards the route from where the phone faces or the person walks; without either, a walker, never a guessed north. */
@Composable
private fun RouteArrow(vm: AppViewModel, me: GeoPoint?, toward: GeoPoint, arrived: Boolean) {
    val colors = AppTheme.colors
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    // the compass turns between fixes
    rememberNow(ARROW_TICK_MS)
    val angle = if (arrived || me == null) null else routeArrowDeg(me, toward, vm.phone.heading(), fix?.courseDeg)
    Box(Modifier.size(56.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
        when {
            arrived -> Icon(Sym.Flag, null, Modifier.size(28.dp), tint = colors.onAccent)
            angle != null -> Icon(Sym.Send, null, Modifier.size(30.dp).rotate(angle), tint = colors.onAccent)
            else -> Icon(Sym.Walk, null, Modifier.size(28.dp), tint = colors.onAccent)
        }
    }
}

/** "Spre Reperul 14, apoi la dreapta"; with nothing to see nearby, "Înainte, apoi la dreapta". */
@Composable
private fun stepText(step: RouteStep, label: String): String {
    if (step.turn == Turn.ARRIVE) return stringResource(Res.string.route_last, label)
    val turn = stringResource(
        when (step.turn) {
            Turn.LEFT -> Res.string.route_turn_left
            Turn.RIGHT -> Res.string.route_turn_right
            Turn.BACK -> Res.string.route_turn_back
            else -> Res.string.route_turn_straight
        },
    )
    val anchor = step.anchor
    val place = when (anchor.kind) {
        AnchorKind.LANDMARK -> stringResource(Res.string.route_landmark, anchor.number)
        AnchorKind.GATE -> anchor.name.ifEmpty { null }
            ?: if (anchor.number > 0) stringResource(Res.string.route_landmark, anchor.number) else stringResource(Res.string.route_gate)
        AnchorKind.ZONE -> anchor.name
        AnchorKind.NONE -> null
    }
    return if (place != null) stringResource(Res.string.route_toward, place, turn) else stringResource(Res.string.route_ahead, turn)
}

@Composable
private fun RouteNote(title: String, text: String?, action: (@Composable () -> Unit)? = null) {
    val colors = AppTheme.colors
    InsetGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headline, color = colors.label)
            if (text != null) Text(text, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                action()
            }
        }
    }
}

/** The map of the walk, framed on you and the route each time it is planned: MapLibre where the phone has it, else the drawn map. */
@Composable
private fun RouteMap(vm: AppViewModel, me: GeoPoint?, accuracyM: Float, route: Route?, modifier: Modifier) {
    val colors = AppTheme.colors
    val overlay by vm.mapOverlays.overlay.collectAsStateWithLifecycle()
    val offline = vm.phone.offlineMap
    val offlineKind by remember(offline) { offline?.kind ?: MutableStateFlow(MapKind.FALLBACK) }.collectAsStateWithLifecycle()
    val kind = if (LocalTextOnly.current) MapKind.FALLBACK else offlineKind
    val animate = !LocalReduceMotion.current
    val aspect = venueAspect(vm.venue)
    val frame = listOfNotNull(me) + route?.points.orEmpty()
    when (kind) {
        MapKind.REAL -> {
            var engine by remember { mutableStateOf<PingMapEngine?>(null) }
            Box(modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(26.dp))) {
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
            LaunchedEffect(engine) { engine?.setVenue(vm.venue) { hex(spotColor(colors, it)) } }
            LaunchedEffect(engine, me, accuracyM) { engine?.setMe(me?.let { MapMe(it.lat, it.lon, accuracyM) }) }
            LaunchedEffect(engine, overlay) { engine?.setOverlay(overlay) }
            LaunchedEffect(engine, route) { if (frame.isNotEmpty()) engine?.show(frame, realZoom(ROUTE_ZOOM), animate) }
        }
        MapKind.PENDING -> Box(modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(26.dp)).background(colors.cell))
        MapKind.FALLBACK -> {
            val camera = remember { MapCamera() }
            LaunchedEffect(camera, route) {
                snapshotFlow { camera.ready }.first { it }
                if (frame.isNotEmpty()) camera.show(frame, ROUTE_ZOOM, animate)
            }
            VenueMap(venue = vm.venue, modifier = modifier, position = me, camera = camera, mapOverlay = overlay, aspect = aspect)
        }
    }
}

/**
 * Metres of walking from [origin] to the nearest open place of each of [types], for the map's shortcuts; a type no walk
 * reaches is left out, and all of them without a position.
 */
@Composable
fun rememberWalkMeters(vm: AppViewModel, origin: GeoPoint?, types: Collection<String>): Map<String, Int> {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mapState by vm.c.mapState.collectAsStateWithLifecycle()
    val points by vm.c.points.collectAsStateWithLifecycle()
    val staff = settings.role.hasStaffKeys
    val meters by produceState(emptyMap(), origin, mapState, points, staff, types) {
        value = if (origin == null) {
            emptyMap()
        } else {
            val closed = closedPoints(points, vm.c.clock.wallMs())
            withContext(Dispatchers.Default) {
                types.mapNotNull { type -> vm.c.walkRouter.nearestOpen(origin, type, mapState, staff, closed)?.let { type to it.route.meters } }.toMap()
            }
        }
    }
    return meters
}

/** How close the map comes on a short walk. */
private const val ROUTE_ZOOM = 2.6f
