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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.EvacuationTarget
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeReach
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.BackHandler
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.onSite
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.largeTitle
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.designsystem.title3
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.evac_banner
import ro.safetyplease.ui.resources.evac_banner_plain
import ro.safetyplease.ui.resources.evac_exit_number
import ro.safetyplease.ui.resources.evac_exits
import ro.safetyplease.ui.resources.evac_fallback
import ro.safetyplease.ui.resources.evac_follow
import ro.safetyplease.ui.resources.evac_go
import ro.safetyplease.ui.resources.evac_meeting
import ro.safetyplease.ui.resources.evac_minimise
import ro.safetyplease.ui.resources.evac_outside
import ro.safetyplease.ui.resources.evac_switched
import ro.safetyplease.ui.resources.evac_unknown
import ro.safetyplease.ui.resources.evac_your_zone
import ro.safetyplease.ui.resources.exercise_banner
import ro.safetyplease.ui.resources.map_directions
import ro.safetyplease.ui.resources.map_route
import ro.safetyplease.ui.text.rememberNow

/**
 * What the evacuation mode tells a phone in [zone] (null: it doesn't know; "": outside every zone): its exit when the
 * plan gives its zone one, else every exit of the plan, with the meeting point.
 */
data class EvacuationView(val zone: Zone?, val target: EvacuationTarget?, val exits: List<Poi>, val outside: Boolean)

fun evacuationView(venue: Venue, state: MapState, zone: String?): EvacuationView {
    val target = Evacuations.target(venue, state, zone)
    val exits = if (target == null) Evacuations.exits(venue, Evacuations.plan(venue, state)) else emptyList()
    return EvacuationView(zone?.let(venue::zone), target, exits, outside = zone == "")
}

/** The evacuation among the [shown] notices that is for a phone in [zone]; the others stay ordinary bands. */
fun myEvacuation(shown: List<ShownNotice>, venue: Venue, zone: String?): ShownNotice? = shown.firstOrNull {
    it.notice.urgent && it.notice.level == NoticeLevel.EVACUATION && NoticeReach.concerns(it.notice, venue, zone)
}

/** The pause that takes the screen once: one for this phone, not read yet. An evacuation has its own mode. */
fun screenAlert(shown: List<ShownNotice>, venue: Venue, zone: String?): ShownNotice? = shown.firstOrNull {
    it.notice.urgent && it.notice.level != NoticeLevel.EVACUATION && !it.held.alerted && NoticeReach.concerns(it.notice, venue, zone)
}

/** The zone this phone is in, as [NoticeReach.zone] reads it. */
@Composable
fun rememberHereZone(vm: AppViewModel, nowMs: Long): String? {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    return NoticeReach.zone(vm.venue, settings, fix, nowMs)
}

/** Whether the person brought the evacuation back to the whole screen after minimising it. */
@Stable
class EvacuationUi {
    var reopened by mutableStateOf(false)
}

/** An exit's name for the screen. */
@Composable
private fun exitLabel(exit: Poi): String = exitName(exit.name, exit.number, stringResource(Res.string.evac_exit_number))

/**
 * The minimised evacuation: a red band above every screen while it is in force, which can't be hidden. A tap brings the
 * whole screen back.
 */
@Composable
fun EvacuationBanner(vm: AppViewModel, shown: ShownNotice, zone: String?, statusBar: Boolean, onOpen: () -> Unit) {
    val state by vm.c.mapState.collectAsStateWithLifecycle()
    val target = Evacuations.target(vm.venue, state, zone)
    val text = target?.let { stringResource(Res.string.evac_banner, exitLabel(it.exit)) } ?: stringResource(Res.string.evac_banner_plain)
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
 * alternative and "follow the staff". Outside the zones or without a position, the plan's exits and the meeting point.
 * In an exercise it says so at the top; the alarm sound stays off, as everywhere in an exercise.
 */
@Composable
fun EvacuationMode(vm: AppViewModel, shown: ShownNotice, zone: String?, ui: EvacuationUi) {
    if (shown.held.alerted && !ui.reopened) return
    val state by vm.c.mapState.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val overlay by vm.mapOverlays.overlay.collectAsStateWithLifecycle()
    val view = evacuationView(vm.venue, state, zone)
    val here = onSite(position, vm.venue)
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
                Line(stringResource(Res.string.evac_go, exitLabel(target.exit)), type.title1, bold = true)
                if (target.switched) Line(stringResource(Res.string.evac_switched), type.subheadline)
                here?.let { WayTo(vm, it, target.exit.point) }
                target.fallback?.let { Line(stringResource(Res.string.evac_fallback, exitLabel(it)), type.title3) }
                VenueMap(
                    vm.venue, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)), position = here,
                    myZone = zone.orEmpty(), target = target.point, mapOverlay = overlay,
                )
            } else {
                Line(stringResource(if (view.outside) Res.string.evac_outside else Res.string.evac_unknown), type.title3)
                ExitList(vm, view.exits, here)
            }
            Line(shown.text, type.subheadline)
            Line(stringResource(Res.string.evac_follow), type.title3, bold = true)
            Spacer(Modifier.height(8.dp))
            AppButton(stringResource(Res.string.evac_minimise), ::minimise, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}

private val Ink = Color.White

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
        Icon(Sym.Send, null, Modifier.size(32.dp).rotate(Guide.arrowDeg(Guide.bearing(from, to), vm.phone.heading())), tint = Ink)
        Spacer(Modifier.width(12.dp))
        val way = stringResource(Res.string.map_route, Guide.round(meters), directions[compassSector(from, to)])
        Line(way, MaterialTheme.typography.title3, bold = true)
    }
}

/** The plan's exits and the meeting point, each with the way to it when the phone is on the site. */
@Composable
private fun ExitList(vm: AppViewModel, exits: List<Poi>, here: GeoPoint?) {
    val type = MaterialTheme.typography
    Line(stringResource(Res.string.evac_exits), type.headline)
    for (exit in exits) {
        Line(exitLabel(exit), type.title3, bold = true)
        here?.let { WayTo(vm, it, exit.point) }
    }
    vm.venue.meetingPoint?.let { meeting ->
        Line(stringResource(Res.string.evac_meeting), type.title3, bold = true)
        here?.let { WayTo(vm, it, meeting) }
    }
}
