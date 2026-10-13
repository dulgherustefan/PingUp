package ro.safetyplease.ui.map

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.event.Program
import ro.safetyplease.core.venue.Found
import ro.safetyplease.core.venue.FoundKind
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.NamedPoint
import ro.safetyplease.core.venue.PlaceSearch
import ro.safetyplease.core.venue.ServiceLayer
import ro.safetyplease.core.venue.ServicePoint
import ro.safetyplease.core.venue.ServiceState
import ro.safetyplease.core.venue.Spot
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.common.FriendSpot
import ro.safetyplease.ui.common.spotColor
import ro.safetyplease.ui.common.typeGlyph
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.IconBtn
import ro.safetyplease.ui.designsystem.IconCircle
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.pressScale
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.layers_friend_no_position
import ro.safetyplease.ui.resources.layers_go
import ro.safetyplease.ui.resources.layers_landmark
import ro.safetyplease.ui.resources.layers_landmark_named
import ro.safetyplease.ui.resources.layers_next
import ro.safetyplease.ui.resources.layers_no_results
import ro.safetyplease.ui.resources.layers_no_walk
import ro.safetyplease.ui.resources.layers_now
import ro.safetyplease.ui.resources.layers_plan_faint
import ro.safetyplease.ui.resources.layers_plan_full
import ro.safetyplease.ui.resources.layers_plan_hidden
import ro.safetyplease.ui.resources.layers_program
import ro.safetyplease.ui.resources.layers_program_none
import ro.safetyplease.ui.resources.layers_remove_place
import ro.safetyplease.ui.resources.layers_saved_place
import ro.safetyplease.ui.resources.layers_search
import ro.safetyplease.ui.resources.layers_state_unknown
import ro.safetyplease.ui.resources.layers_type_charge
import ro.safetyplease.ui.resources.layers_type_dea
import ro.safetyplease.ui.resources.layers_type_earplugs
import ro.safetyplease.ui.resources.layers_type_exit
import ro.safetyplease.ui.resources.layers_type_info
import ro.safetyplease.ui.resources.layers_type_lockers
import ro.safetyplease.ui.resources.layers_type_medical
import ro.safetyplease.ui.resources.layers_type_phone
import ro.safetyplease.ui.resources.layers_type_quiet
import ro.safetyplease.ui.resources.layers_type_safe
import ro.safetyplease.ui.resources.layers_type_shade
import ro.safetyplease.ui.resources.layers_type_water
import ro.safetyplease.ui.resources.layers_type_wc
import ro.safetyplease.ui.resources.layers_walk
import ro.safetyplease.ui.resources.point_level_busy
import ro.safetyplease.ui.resources.point_level_closed
import ro.safetyplease.ui.resources.point_level_free
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.fill
import ro.safetyplease.ui.text.rememberNow

private const val FOUND_KEY = "layers:found"
private const val SAVED_KEY = "layers:saved"
private const val STAGE_PREFIX = "layers:stage:"

/** The search shows this many results under the field; a longer list would push the map's controls off screen. */
private const val MAX_ROWS = 8

/**
 * The layers' pins on the map, in the "layers" overlay: the services switched on with their status, "acum / urmează"
 * under each stage that has a schedule, the search result, the saved place, and the organiser's site plan. A tap on a
 * stage's label opens the schedule; on any other pin, its card under the map. With [pins] off, as while a zone is being
 * chosen to send and the chips are hidden, only the site plan is drawn: layers nobody can switch off stay off the map.
 */
@Composable
fun LayersOverlay(vm: AppViewModel, pins: Boolean = true) {
    val layers = vm.layers
    val now = rememberNow()
    val board by vm.c.points.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val schedule = vm.c.program.schedule
    val stateWords = ServiceState.entries.associateWith { stringResource(stateLabel(it)) }
    val nowText = stringResource(Res.string.layers_now)
    val nextText = stringResource(Res.string.layers_next)
    val savedText = stringResource(Res.string.layers_saved_place)
    val services = if (!pins) emptyList() else ServiceLayer.points(vm.venue, layers.types, board, now).map { p ->
        OverlayPin(p.key, p.point, PinKind.SERVICE, if (p.state == ServiceState.UNKNOWN) "" else stateWords.getValue(p.state))
    }
    val stages = if (!pins) emptyList() else Program.stages(schedule).mapNotNull { id ->
        val zone = vm.venue.zone(id) ?: return@mapNotNull null
        val (on, next) = Program.nowNext(schedule, id, now / 1000)
        val label = when {
            on != null -> nowText.fill(on.title)
            next != null -> nextText.fill(Labels.clock(next.start * 1000), next.title)
            else -> return@mapNotNull null
        }
        // under the zone's own name, which the map writes at its middle
        val span = zone.polygon.maxOf { it.lat } - zone.polygon.minOf { it.lat }
        OverlayPin(STAGE_PREFIX + id, GeoPoint(zone.center.lat - span * 0.3, zone.center.lon), PinKind.SERVICE, label)
    }
    val found = (layers.picked as? LayerPick.Result)?.found?.takeIf { pins }
        ?.let { f -> f.point?.let { OverlayPin(FOUND_KEY, it, PinKind.SEARCH_RESULT, f.name) } }
    val saved = settings.savedPlace?.takeIf { pins }?.let { OverlayPin(SAVED_KEY, it, PinKind.SHARED_PLACE, savedText) }
    val plan = vm.c.event?.plan?.let { p -> layers.plan.opacity?.let { PlanImage(p, it) } }
    val overlay = MapOverlay(pins = services + stages + listOfNotNull(found, saved), plan = plan)
    LaunchedEffect(overlay) {
        vm.mapOverlays.set(OverlayLayer.LAYERS, overlay) { pin ->
            when {
                pin.key.startsWith(STAGE_PREFIX) -> vm.open(Dest.Program)
                pin.key == SAVED_KEY -> layers.picked = LayerPick.Saved
                pin.key != FOUND_KEY -> layers.picked = LayerPick.Service(pin.key)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.mapOverlays.clear(OverlayLayer.LAYERS) } }
}

/**
 * Under the map: the offline search with its results, the layer chips (the site plan first, when the pack has one),
 * the card of what was picked, and the stages' "acum" with the way to the schedule. [onShow] centres the map on a point.
 */
@Composable
fun LayersPanel(vm: AppViewModel, origin: GeoPoint?, onShow: (GeoPoint) -> Unit) {
    val layers = vm.layers
    val colors = AppTheme.colors
    val now = rememberNow()
    val board by vm.c.points.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val records by vm.friendPositions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    val schedule = vm.c.program.schedule
    val present = remember(vm.venue) { ServiceLayer.present(vm.venue) }

    Column(Modifier.fillMaxWidth()) {
        InputField(
            query, { query = it }, stringResource(Res.string.layers_search),
            Modifier.padding(start = Gutter, end = Gutter, top = 4.dp), background = colors.cell,
        )
        if (query.isNotBlank()) {
            val spots = FriendSpot.of(records, friends, now, vm.venue)
            val named = friends.map { f -> NamedPoint(f.nodeId, f.nickname, spots.firstOrNull { it.friend.nodeId == f.nodeId }?.point) }
            val results = remember(query, named, now / 60_000) {
                PlaceSearch.search(query, vm.venue, schedule, named, now / 1000).take(MAX_ROWS)
            }
            InsetGroup(Modifier.padding(top = 8.dp)) {
                if (results.isEmpty()) {
                    Text(
                        stringResource(Res.string.layers_no_results, query.trim()), style = MaterialTheme.typography.footnote,
                        color = colors.secondaryLabel, modifier = Modifier.padding(16.dp),
                    )
                }
                results.forEachIndexed { index, found ->
                    GroupRow(
                        foundTitle(found),
                        subtitle = listOfNotNull(foundDetail(vm, found), walkLine(vm, origin, found.point))
                            .joinToString(" · ").ifEmpty { null },
                        leading = { IconCircle(foundGlyph(found), foundColor(found), Color.White, 36.dp) },
                        onClick = {
                            focus.clearFocus()
                            query = ""
                            val point = found.point
                            if (found.kind == FoundKind.FRIEND && point == null) {
                                vm.open(Dest.Profile(Conversations.friend(found.friend)))
                            } else {
                                layers.picked = LayerPick.Result(found)
                                point?.let(onShow)
                            }
                        },
                    )
                    if (index < results.lastIndex) GroupDivider(start = 64.dp)
                }
            }
        }
        val plan = vm.c.event?.plan
        if (plan != null || present.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (plan != null) {
                    val label = when (layers.plan) {
                        PlanShade.FULL -> Res.string.layers_plan_full
                        PlanShade.FAINT -> Res.string.layers_plan_faint
                        PlanShade.HIDDEN -> Res.string.layers_plan_hidden
                    }
                    LayerChip(Sym.MapFill, stringResource(label), colors.accent, layers.plan != PlanShade.HIDDEN) {
                        layers.plan = layers.plan.next()
                    }
                }
                present.forEach { type ->
                    LayerChip(serviceGlyph(type), stringResource(typeLabel(type)), spotColor(colors, type), type in layers.types) {
                        layers.toggle(type)
                        // switched on, it shows the nearest one not said to be closed, by the walk there
                        if (type in layers.types && origin != null) {
                            ServiceLayer.nearestOpen(vm.venue, type, board, now, origin, vm.c.walkDistance)?.let { near ->
                                layers.picked = LayerPick.Service(near.key)
                                onShow(near.point)
                            }
                        }
                    }
                }
            }
        }
        PickedCard(vm, origin, ServiceLayer.points(vm.venue, layers.types, board, now), settings.savedPlace, onShow)
        if (schedule.isNotEmpty()) {
            val playing = Program.stages(schedule).mapNotNull { id ->
                Program.nowNext(schedule, id, now / 1000).now?.let { "${vm.venue.zoneName(id)}: ${it.title}" }
            }
            InsetGroup(Modifier.padding(top = 8.dp)) {
                GroupRow(
                    stringResource(Res.string.layers_program),
                    subtitle = playing.joinToString("\n").ifEmpty { stringResource(Res.string.layers_program_none) },
                    icon = Sym.Clock, chevron = true, onClick = { vm.open(Dest.Program) },
                )
            }
        }
    }
}

/** The card of what a tap or a search picked: its name, status and walk, and the way there. */
@Composable
private fun PickedCard(vm: AppViewModel, origin: GeoPoint?, services: List<ServicePoint>, saved: GeoPoint?, onShow: (GeoPoint) -> Unit) {
    val layers = vm.layers
    val colors = AppTheme.colors
    val stateWords = ServiceState.entries.associateWith { stringResource(stateLabel(it)) }
    val savedName = stringResource(Res.string.layers_saved_place)
    val picked = layers.picked ?: return
    val card: Triple<String, String?, Pair<Spot, ImageVector>> = when (picked) {
        is LayerPick.Service -> {
            val p = services.firstOrNull { it.key == picked.key } ?: return
            val name = p.name.ifEmpty { stringResource(typeLabel(p.type)) }
            val title = if (p.number > 0) stringResource(Res.string.layers_landmark_named, p.number, name) else name
            Triple(title, stateWords.getValue(p.state), p.spot to serviceGlyph(p.type))
        }
        is LayerPick.Result -> {
            val point = picked.found.point ?: return
            val spot = Spot(picked.found.type, picked.found.name, point)
            Triple(foundTitle(picked.found), foundDetail(vm, picked.found), spot to foundGlyph(picked.found))
        }
        LayerPick.Saved -> {
            val point = saved ?: return
            Triple(savedName, null, Spot("", savedName, point) to Sym.Place)
        }
    }
    val (title, detail, target) = card
    val (spot, glyph) = target
    InsetGroup(Modifier.padding(top = 4.dp)) {
        GroupRow(
            title,
            subtitle = listOfNotNull(detail, walkLine(vm, origin, spot.point)).joinToString(" · "),
            leading = {
                IconCircle(glyph, if (picked == LayerPick.Saved) colors.green else spotColor(colors, spot.type), Color.White, 36.dp)
            },
            onClick = { onShow(spot.point) },
            trailing = { IconBtn(Sym.Close, stringResource(Res.string.close), { layers.picked = null }) },
        )
        Row(Modifier.padding(start = 64.dp, end = Gutter, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton(stringResource(Res.string.layers_go), { layers.routeTo(spot) }, compact = true)
            if (picked == LayerPick.Saved) {
                TextLink(stringResource(Res.string.layers_remove_place), {
                    vm.c.settings.update { it.copy(savedPlace = null) }
                    layers.picked = null
                })
            }
        }
    }
}

/** The walk from [origin], worked out once per pair of points and not on every tick of the clock. */
@Composable
private fun walkLine(vm: AppViewModel, origin: GeoPoint?, point: GeoPoint?): String? {
    val meters = remember(origin, point) { if (origin == null || point == null) null else vm.c.walkDistance.meters(origin, point) }
    val unreachable = stringResource(Res.string.layers_no_walk)
    if (origin == null || point == null) return null
    return if (meters == null) unreachable else stringResource(Res.string.layers_walk, meters)
}

@Composable
private fun foundTitle(found: Found): String = when {
    found.kind == FoundKind.LANDMARK && found.name.isEmpty() -> stringResource(Res.string.layers_landmark, found.number)
    else -> found.name
}

@Composable
private fun foundDetail(vm: AppViewModel, found: Found): String? = when (found.kind) {
    FoundKind.LANDMARK -> if (found.name.isEmpty()) null else stringResource(Res.string.layers_landmark, found.number)
    FoundKind.SET -> found.set?.let {
        "${vm.venue.zoneName(it.stage)} · ${Labels.clock(it.start * 1000)}–${Labels.clock(it.end * 1000)}"
    }
    FoundKind.FRIEND -> if (found.point == null) stringResource(Res.string.layers_friend_no_position) else null
    FoundKind.ZONE, FoundKind.PLACE -> null
}

private fun foundGlyph(found: Found): ImageVector = when (found.kind) {
    FoundKind.FRIEND -> Sym.Person
    FoundKind.SET -> Sym.Clock
    else -> serviceGlyph(found.type)
}

@Composable
private fun foundColor(found: Found): Color =
    if (found.kind == FoundKind.FRIEND) AppTheme.colors.green else spotColor(AppTheme.colors, found.type)

fun serviceGlyph(type: String): ImageVector = typeGlyph(type) ?: when (type) {
    Venue.PHONE -> Sym.Phone
    ServiceLayer.SAFE -> Sym.Groups
    else -> Sym.Place
}

fun typeLabel(type: String): StringResource = when (type) {
    "water" -> Res.string.layers_type_water
    "wc" -> Res.string.layers_type_wc
    "medical" -> Res.string.layers_type_medical
    Venue.DEA -> Res.string.layers_type_dea
    "info" -> Res.string.layers_type_info
    "charge" -> Res.string.layers_type_charge
    Venue.SHADE -> Res.string.layers_type_shade
    ServiceLayer.SAFE -> Res.string.layers_type_safe
    Venue.PHONE -> Res.string.layers_type_phone
    Venue.EXIT -> Res.string.layers_type_exit
    ServiceLayer.QUIET -> Res.string.layers_type_quiet
    ServiceLayer.EARPLUGS -> Res.string.layers_type_earplugs
    else -> Res.string.layers_type_lockers
}

private fun stateLabel(state: ServiceState): StringResource = when (state) {
    ServiceState.FREE -> Res.string.point_level_free
    ServiceState.BUSY -> Res.string.point_level_busy
    ServiceState.CLOSED -> Res.string.point_level_closed
    ServiceState.UNKNOWN -> Res.string.layers_state_unknown
}

/** A layer switched on or off; on, it takes the layer's color. */
@Composable
private fun LayerChip(icon: ImageVector, label: String, tone: Color, on: Boolean, onToggle: () -> Unit) {
    val colors = AppTheme.colors
    val press = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (on) tone else colors.cell, label = "layer")
    Row(
        Modifier.pressScale(press, 0.95f).heightIn(min = 40.dp).clip(CircleShape).background(fill)
            .toggleable(on, interactionSource = press, indication = null, role = Role.Checkbox) { onToggle() }
            .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.22f) else tone),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = if (on) Color.White else colors.label, maxLines = 1,
        )
    }
}
