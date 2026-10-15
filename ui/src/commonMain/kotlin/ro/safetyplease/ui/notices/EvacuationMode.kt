package ro.safetyplease.ui.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.EvacuationTarget
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.ExitChoice
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeReach
import ro.safetyplease.core.announce.Whereabouts
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.route.Route
import ro.safetyplease.core.venue.route.WalkGoal
import ro.safetyplease.core.venue.route.WalkState
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.BackHandler
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.WalkTrack
import ro.safetyplease.ui.common.onSite
import ro.safetyplease.ui.common.rememberWalk
import ro.safetyplease.ui.common.walkStepText
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.largeTitle
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.designsystem.title3
import ro.safetyplease.ui.designsystem.turnedTo
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.MapRoute
import ro.safetyplease.ui.map.OverlayLayer
import ro.safetyplease.ui.map.OverlayPin
import ro.safetyplease.ui.map.PinKind
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.evac_banner
import ro.safetyplease.ui.resources.evac_banner_closed
import ro.safetyplease.ui.resources.evac_banner_plain
import ro.safetyplease.ui.resources.evac_exit_closed
import ro.safetyplease.ui.resources.evac_exit_number
import ro.safetyplease.ui.resources.evac_exits
import ro.safetyplease.ui.resources.evac_exits_all
import ro.safetyplease.ui.resources.evac_fallback
import ro.safetyplease.ui.resources.evac_go
import ro.safetyplease.ui.resources.evac_go_closed
import ro.safetyplease.ui.resources.evac_go_number
import ro.safetyplease.ui.resources.evac_meeting
import ro.safetyplease.ui.resources.evac_minimise
import ro.safetyplease.ui.resources.evac_no_exit
import ro.safetyplease.ui.resources.evac_outside
import ro.safetyplease.ui.resources.evac_switched
import ro.safetyplease.ui.resources.evac_unknown
import ro.safetyplease.ui.resources.evac_your_zone
import ro.safetyplease.ui.resources.exercise_banner
import ro.safetyplease.ui.resources.map_directions
import ro.safetyplease.ui.resources.map_route
import ro.safetyplease.ui.resources.route_arrived
import ro.safetyplease.ui.resources.route_left
import ro.safetyplease.ui.text.rememberNow

/** What the evacuation mode tells a phone besides its exit, when it has none. */
enum class EvacuationCase {
    /** The plan gives the phone's zone an exit. */
    EXIT,

    /** The phone doesn't know which zone it is in. */
    UNKNOWN,

    /** The phone stands outside every zone. */
    OUTSIDE,

    /** The zone is known but has no exit of its own: the evacuation names no plan, or a plan without the zone. */
    NO_EXIT,
}

/**
 * What the evacuation mode shows a phone in [zone] (null: it doesn't know; "": outside every zone): its exit when the
 * plan gives its zone one, else [exits], those of the plan ([fromPlan]) or of the venue, with the meeting point.
 */
data class EvacuationView(val zone: Zone?, val target: EvacuationTarget?, val exits: List<ExitChoice>, val case: EvacuationCase, val fromPlan: Boolean)

fun evacuationView(venue: Venue, state: MapState, zone: String?, from: GeoPoint? = null): EvacuationView {
    val target = Evacuations.target(venue, state, zone)
    val plan = Evacuations.plan(venue, state)
    val exits = if (target == null) Evacuations.exits(venue, plan, state, from) else emptyList()
    val case = when {
        target != null -> EvacuationCase.EXIT
        zone == null -> EvacuationCase.UNKNOWN
        zone.isEmpty() -> EvacuationCase.OUTSIDE
        else -> EvacuationCase.NO_EXIT
    }
    return EvacuationView(zone?.let(venue::zone), target, exits, case, fromPlan = plan != null)
}

/** The evacuation among the [shown] notices that is for a phone [here]; the others stay ordinary bands. */
fun myEvacuation(shown: List<ShownNotice>, venue: Venue, here: Whereabouts): ShownNotice? = shown.firstOrNull {
    it.notice.urgent && it.notice.level == NoticeLevel.EVACUATION && NoticeReach.concernsAt(it.notice, venue, here)
}

/** The pause that takes the screen once: one for this phone, not read yet. An evacuation has its own mode. */
fun screenAlert(shown: List<ShownNotice>, venue: Venue, here: Whereabouts): ShownNotice? = shown.firstOrNull {
    it.notice.urgent && it.notice.level != NoticeLevel.EVACUATION && !it.held.alerted && NoticeReach.concernsAt(it.notice, venue, here)
}

/** Where this phone is, as [NoticeReach.whereabouts] reads it. */
@Composable
fun rememberWhereabouts(vm: AppViewModel, nowMs: Long): Whereabouts {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    return NoticeReach.whereabouts(vm.venue, settings, fix, nowMs)
}

/** Whether the person brought the evacuation back to the whole screen after minimising it. */
@Stable
class EvacuationUi {
    var reopened by mutableStateOf(false)
}

/** An exit's name for the screen: "Ieșirea 3" for one the venue gives no name. */
@Composable
private fun exitLabel(exit: Poi): String = exitName(exit.name, exit.number, stringResource(Res.string.evac_exit_number))

/** "Ieși pe Poarta A", or "Ieși pe ieșirea 3" for an exit without a name. */
@Composable
private fun goText(exit: Poi): String =
    if (exit.name.isBlank()) stringResource(Res.string.evac_go_number, exit.number) else stringResource(Res.string.evac_go, exit.name)

/**
 * The minimised evacuation: a red band above every screen while it is in force, which can't be hidden. A tap brings the
 * whole screen back.
 */
@Composable
fun EvacuationBanner(vm: AppViewModel, shown: ShownNotice, zone: String?, statusBar: Boolean, onOpen: () -> Unit) {
    val state by vm.c.mapState.collectAsStateWithLifecycle()
    val target = Evacuations.target(vm.venue, state, zone)
    val text = when {
        target == null -> stringResource(Res.string.evac_banner_plain)
        target.closed -> stringResource(Res.string.evac_banner_closed)
        else -> stringResource(Res.string.evac_banner, goText(target.exit))
    }
    Row(
        Modifier.fillMaxWidth().background(AppTheme.colors.red).then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
            .clickable(onClickLabel = shown.title, role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Report, null, Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(
            text, style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(Sym.Chevron, null, Modifier.size(16.dp), tint = Color.White)
    }
}

/**
 * The evacuation for this phone's zone takes the whole screen and keeps it on, until the person minimises it to its band;
 * it can't be dismissed while it holds. At most five things: what happens, your zone, your exit with the way to it, the
 * alternative and the notice's text. Without an exit of its own (no position, outside the zones, a plan-less evacuation
 * or a zone the plan lacks), it says which of these it is and lists the exits, open and nearest first, and the meeting
 * point.
 * In an exercise it says so at the top; the alarm sound stays off, as everywhere in an exercise.
 */
@Composable
fun EvacuationMode(vm: AppViewModel, shown: ShownNotice, zone: String?, ui: EvacuationUi, walk: WalkTrack) {
    if (shown.held.alerted && !ui.reopened) return
    val state by vm.c.mapState.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val all by vm.mapOverlays.overlay.collectAsStateWithLifecycle()
    // only what helps to get out: the way, the exits and the zones' states; no crowd levels, services or friends
    val overlay = remember(all) { vm.mapOverlays.only(OverlayLayer.EVACUATION, OverlayLayer.CONTROL) }
    val here = onSite(position, vm.venue)
    val view = evacuationView(vm.venue, state, zone, here)
    val type = MaterialTheme.typography
    fun minimise() {
        vm.c.notices.alerted(shown.notice.topic, shown.notice.seq)
        ui.reopened = false
    }
    vm.phone.KeepScreenOn()
    BackHandler(enabled = true) { minimise() }
    // it takes every touch, so nothing underneath is tapped by mistake
    Box(
        Modifier.fillMaxSize().background(AppTheme.colors.red)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            if (vm.c.exercise) Line(stringResource(Res.string.exercise_banner), type.headline, bold = true)
            Icon(Sym.Report, null, Modifier.size(56.dp), tint = Ink)
            Line(shown.title, type.largeTitle, bold = true, modifier = Modifier.semantics { heading() })
            view.zone?.let { Line(stringResource(Res.string.evac_your_zone, it.name), type.headline) }
            val target = view.target
            if (target != null) {
                if (target.closed) {
                    Line(stringResource(Res.string.evac_go_closed, exitLabel(target.exit)), type.title2, bold = true)
                } else {
                    Line(goText(target.exit), type.title1, bold = true)
                }
                if (target.switched) Line(stringResource(Res.string.evac_switched), type.subheadline)
                // the walk out, step by step, once it has a route; else the way as the crow flies
                val guiding = walk.state as? WalkState.Guiding
                if (guiding != null) WalkLine(vm, guiding, exitLabel(target.exit), here)
                else here?.let { WayTo(vm, it, target.exit.point) }
                target.fallback?.let { Line(stringResource(Res.string.evac_fallback, exitLabel(it)), type.title3) }
                VenueMap(
                    vm.venue, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)), position = here,
                    myZone = zone.orEmpty(), target = target.point.takeIf { guiding == null }, mapOverlay = overlay,
                    poiTypes = EVACUATION_POIS,
                )
            } else {
                Line(stringResource(caseText(view.case)), type.title3)
                ExitList(vm, view, here)
            }
            Line(shown.text, type.subheadline)
            Spacer(Modifier.height(8.dp))
            AppButton(stringResource(Res.string.evac_minimise), ::minimise, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}

private val Ink = Color.White

private const val EXIT_PIN = "evacuation:exit"

/** The places the evacuation's map keeps: the exits and where to find first aid. */
private val EVACUATION_POIS = setOf(Venue.EXIT, "medical", Venue.DEA)

/**
 * The walk out of an evacuation for a phone in [zone], to the exit its plan gives it now, around the zones the console
 * closed. It starts by itself and lasts while the evacuation is in force, minimised or not, so the route stays on both
 * maps through the evacuation's own overlay layer. Without a position there is no route, and the mode keeps its list.
 */
@Composable
fun rememberEvacuationWalk(vm: AppViewModel, zone: String?): WalkTrack {
    val state by vm.c.mapState.collectAsStateWithLifecycle()
    val exit = Evacuations.target(vm.venue, state, zone)?.exit
    // a walk per exit: a closure that switches it plans the new way from scratch, and no exit keeps the GPS slow
    val track = if (exit != null) rememberWalk(vm, exit.number, goal = { WalkGoal.To(exit.point) }) else remember { WalkTrack() }
    val route = (track.state as? WalkState.Guiding)?.route?.takeIf { exit != null }
    val label = exit?.let { exitLabel(it) }.orEmpty()
    LaunchedEffect(route, label) {
        if (route == null) vm.mapOverlays.clear(OverlayLayer.EVACUATION)
        else {
            val pin = OverlayPin(EXIT_PIN, route.end, PinKind.SERVICE, label)
            vm.mapOverlays.set(OverlayLayer.EVACUATION, MapOverlay(routes = listOf(MapRoute(route.points)), pins = listOf(pin)))
        }
    }
    DisposableEffect(Unit) { onDispose { vm.mapOverlays.clear(OverlayLayer.EVACUATION) } }
    return track
}

/** The next step out told by a landmark, with the walk left; an arrow turns with the phone where it has a compass. */
@Composable
private fun WalkLine(vm: AppViewModel, walk: WalkState.Guiding, exit: String, from: GeoPoint?) {
    val progress = walk.progress
    if (progress.arrived) {
        Line(stringResource(Res.string.route_arrived), MaterialTheme.typography.title3, bold = true)
        return
    }
    // the compass has no flow of its own: read it again every second
    rememberNow(1_000)
    val heading = vm.phone.heading()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (from != null && heading != null) {
            Icon(Sym.Send, null, Modifier.size(32.dp).turnedTo(Guide.arrowDeg(Guide.bearing(from, progress.toward), heading)), tint = Ink)
            Spacer(Modifier.width(12.dp))
        }
        Line(
            walkStepText(walk.route.steps[progress.step], exit), MaterialTheme.typography.title3, bold = true,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val minutes = Route.minutes(progress.remainingSec)
    Line(stringResource(Res.string.route_left, progress.remainingM, minutes.first, minutes.last), MaterialTheme.typography.subheadline)
}

@Composable
private fun Line(text: String, style: TextStyle, bold: Boolean = false, modifier: Modifier = Modifier) = Text(
    text, modifier, style = style, fontWeight = if (bold) FontWeight.Bold else null, color = Ink, textAlign = TextAlign.Center,
)

/** "120 m spre nord-est", led by an arrow that turns with the phone where it has a compass. */
@Composable
private fun WayTo(vm: AppViewModel, from: GeoPoint, to: GeoPoint) {
    // the compass has no flow of its own: read it again every second
    rememberNow(1_000)
    val directions = stringArrayResource(Res.array.map_directions)
    val meters = vm.c.walkDistance.meters(from, to) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.Send, null, Modifier.size(32.dp).turnedTo(Guide.arrowDeg(Guide.bearing(from, to), vm.phone.heading())), tint = Ink)
        Spacer(Modifier.width(12.dp))
        val way = stringResource(Res.string.map_route, Guide.round(meters), directions[compassSector(from, to)])
        Line(way, MaterialTheme.typography.title3, bold = true)
    }
}

fun caseText(case: EvacuationCase): StringResource = when (case) {
    EvacuationCase.UNKNOWN -> Res.string.evac_unknown
    EvacuationCase.OUTSIDE -> Res.string.evac_outside
    EvacuationCase.EXIT, EvacuationCase.NO_EXIT -> Res.string.evac_no_exit
}

/** The heading over the exits: the plan's, or the festival's when the evacuation names no plan. */
fun exitsHeading(view: EvacuationView): StringResource = if (view.fromPlan) Res.string.evac_exits else Res.string.evac_exits_all

/** The exits and the meeting point, each with the way to it when the phone is on the site; a closed one says so. */
@Composable
private fun ExitList(vm: AppViewModel, view: EvacuationView, here: GeoPoint?) {
    val type = MaterialTheme.typography
    Line(stringResource(exitsHeading(view)), type.headline)
    for ((exit, closed) in view.exits) {
        val label = exitLabel(exit)
        Line(if (closed) stringResource(Res.string.evac_exit_closed, label) else label, type.title3, bold = !closed)
        if (!closed) here?.let { WayTo(vm, it, exit.point) }
    }
    vm.venue.meetingPoint?.let { meeting ->
        Line(stringResource(Res.string.evac_meeting), type.title3, bold = true)
        here?.let { WayTo(vm, it, meeting) }
    }
}
