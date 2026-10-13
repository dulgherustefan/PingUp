package ro.safetyplease.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.location.LandmarkFix
import ro.safetyplease.core.position.FriendRing
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.position.SearchDraft
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SwitchRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.map.MapRoute
import ro.safetyplease.ui.map.OverlayPin
import ro.safetyplease.ui.map.PinKind
import ro.safetyplease.ui.map.RouteStyle
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.cancel
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.landmark_active
import ro.safetyplease.ui.resources.landmark_active_text
import ro.safetyplease.ui.resources.landmark_ask
import ro.safetyplease.ui.resources.landmark_ask_text
import ro.safetyplease.ui.resources.landmark_clear
import ro.safetyplease.ui.resources.landmark_none
import ro.safetyplease.ui.resources.landmark_pick_title
import ro.safetyplease.ui.resources.landmark_text
import ro.safetyplease.ui.resources.landmark_title
import ro.safetyplease.ui.resources.landmark_yes
import ro.safetyplease.ui.resources.lost_button
import ro.safetyplease.ui.resources.lost_button_text
import ro.safetyplease.ui.resources.lost_confirm_text
import ro.safetyplease.ui.resources.lost_confirm_title
import ro.safetyplease.ui.resources.lost_found
import ro.safetyplease.ui.resources.lost_no_group
import ro.safetyplease.ui.resources.lost_pick_group
import ro.safetyplease.ui.resources.lost_send
import ro.safetyplease.ui.resources.lost_sent
import ro.safetyplease.ui.resources.lost_target
import ro.safetyplease.ui.resources.lost_wait
import ro.safetyplease.ui.resources.meet_place_landmark
import ro.safetyplease.ui.resources.meet_place_venue
import ro.safetyplease.ui.resources.lastseen_age
import ro.safetyplease.ui.resources.lastseen_attach
import ro.safetyplease.ui.resources.lastseen_consent
import ro.safetyplease.ui.resources.lastseen_no_position
import ro.safetyplease.ui.resources.lastseen_nobody
import ro.safetyplease.ui.resources.lastseen_pin
import ro.safetyplease.ui.resources.lastseen_section
import ro.safetyplease.ui.resources.lastseen_staff
import ro.safetyplease.ui.resources.lastseen_staff_title
import ro.safetyplease.ui.resources.lastseen_told
import ro.safetyplease.ui.resources.steps_text
import ro.safetyplease.ui.resources.steps_title
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** "Reperul 14", or the venue's meeting point for landmark 0. */
@Composable
fun meetingPlaceName(landmark: Int): String =
    if (landmark != 0) stringResource(Res.string.meet_place_landmark, landmark) else stringResource(Res.string.meet_place_venue)

/**
 * "M-am pierdut de grup" for [conversation], a group: what it does and where the group would meet, then the lost
 * session. Once every 10 minutes; before that it says how long is left.
 */
@Composable
fun LostConfirmDialog(vm: AppViewModel, conversation: String, onDismiss: () -> Unit) {
    val reg = vm.c.regasire
    val waitMs = remember(conversation) { reg.activationWaitMs() }
    if (waitMs > 0) {
        AppDialog(onDismiss, stringResource(Res.string.lost_confirm_title), stringResource(Res.string.close), onConfirm = onDismiss, dismissLabel = null) {
            Text(
                stringResource(Res.string.lost_wait, ((waitMs + 59_999) / 60_000).toString()),
                style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            )
        }
        return
    }
    val place = remember(conversation) { reg.meetingFor(conversation) }
    val group = vm.groups.value.firstOrNull { Conversations.group(it.id) == conversation }?.name.orEmpty()
    AppDialog(
        onDismiss, stringResource(Res.string.lost_confirm_title), stringResource(Res.string.lost_send),
        onConfirm = {
            vm.askFriend(Res.string.lost_sent) { reg.imLost(conversation) }
            onDismiss()
        },
    ) {
        Text(
            stringResource(Res.string.lost_confirm_text, group, place?.let { meetingPlaceName(it.landmark) } ?: "–"),
            style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
        )
    }
}

/** What the map screen adds: the lost button, where it sends you, the landmark correction and its long press. */
class RegasireMap(private val venue: Venue) {
    var asked by mutableStateOf<Poi?>(null)

    /** A finger held near a numbered landmark asks whether the person stands by it. */
    fun longPress(point: GeoPoint) {
        asked = venue.pois.filter { it.number in Venue.LANDMARK_NUMBERS }
            .minByOrNull { distanceMeters(point, it.point) }
            ?.takeIf { distanceMeters(point, it.point) <= LONG_PRESS_M }
    }

    /** "Ești lângă Reperul N?" after a long press; hosted by the map screen. */
    @Composable
    fun AskDialog(vm: AppViewModel) {
        val poi = asked ?: return
        AppDialog(
            { asked = null }, stringResource(Res.string.landmark_ask, poi.number.toString()), stringResource(Res.string.landmark_yes),
            onConfirm = {
                vm.c.positioning.nearLandmark(poi.number, poi.point)
                asked = null
            },
        ) {
            Text(stringResource(Res.string.landmark_ask_text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
        }
    }

    private companion object {
        /** About a fingertip on the map at the zoom a festival is looked at. */
        const val LONG_PRESS_M = 40
    }
}

@Composable
fun rememberRegasireMap(vm: AppViewModel): RegasireMap = remember(vm) { RegasireMap(vm.venue) }

/** The map screen's rows: where a lost session sends you, "M-am pierdut de grup" and "Sunt lângă un reper". */
@Composable
fun RegasireMapRows(vm: AppViewModel, origin: GeoPoint?) {
    val reg = vm.c.regasire
    val groups by vm.groups.collectAsStateWithLifecycle()
    val target by reg.target.collectAsStateWithLifecycle()
    val now = rememberNow()
    var picking by remember { mutableStateOf(false) }
    var lostFor by remember { mutableStateOf<String?>(null) }
    val noGroup = stringResource(Res.string.lost_no_group)
    InsetGroup(Modifier.padding(top = 20.dp)) {
        target?.takeIf { it.untilMs > now }?.let { t ->
            val name = meetingPlaceName(t.landmark)
            GroupRow(
                stringResource(Res.string.lost_target, name), subtitle = origin?.let { routeText(it, t.point) }, icon = Sym.Flag,
                onClick = { vm.open(Dest.Pin(t.point.lat, t.point.lon, "", name.replaceFirstChar { it.uppercase() })) },
                trailing = if (t.mine) {
                    { TextLink(stringResource(Res.string.lost_found), { reg.foundGroup(t.conversation) }) }
                } else null,
            )
            GroupDivider()
        }
        GroupRow(
            stringResource(Res.string.lost_button), subtitle = stringResource(Res.string.lost_button_text), icon = Sym.PersonSearch,
            chevron = true,
            onClick = {
                when (groups.size) {
                    0 -> vm.phone.toast(noGroup)
                    1 -> lostFor = Conversations.group(groups.single().id)
                    else -> picking = true
                }
            },
        )
        GroupDivider()
        LandmarkRow(vm)
    }
    if (picking) {
        AppDialog({ picking = false }, stringResource(Res.string.lost_pick_group), stringResource(Res.string.cancel), onConfirm = { picking = false }, dismissLabel = null) {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                for (group in groups) {
                    Text(
                        group.name, style = MaterialTheme.typography.subheadline, color = AppTheme.colors.label,
                        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(role = Role.Button) {
                            picking = false
                            lostFor = Conversations.group(group.id)
                        }.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    lostFor?.let { LostConfirmDialog(vm, it) { lostFor = null } }
}

/** "Sunt lângă un reper": picks the landmark the person stands by, or shows the one in force with a way out. */
@Composable
fun LandmarkRow(vm: AppViewModel) {
    val positioning = vm.c.positioning
    val mark by positioning.landmark.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val now = rememberNow()
    var picking by remember { mutableStateOf(false) }
    val held = mark?.takeIf { !it.expired(now) }
    if (held != null) {
        val left = ((held.setAtMs + LandmarkFix.KEPT_MS - now + 59_999) / 60_000).coerceAtLeast(1)
        GroupRow(
            stringResource(Res.string.landmark_active, held.number.toString()), subtitle = stringResource(Res.string.landmark_active_text, left.toString()),
            icon = Sym.Place, tint = AppTheme.colors.accent,
            trailing = { TextLink(stringResource(Res.string.landmark_clear), { positioning.clearLandmark() }) },
        )
    } else {
        GroupRow(
            stringResource(Res.string.landmark_title), subtitle = stringResource(Res.string.landmark_text), icon = Sym.Place, chevron = true,
            onClick = { picking = true },
        )
    }
    if (!picking) return
    val landmarks = vm.venue.pois.filter { it.number in Venue.LANDMARK_NUMBERS }
        .sortedWith(compareBy({ p -> position?.let { distanceMeters(it, p.point) } ?: 0 }, { it.number }))
    AppDialog({ picking = false }, stringResource(Res.string.landmark_pick_title), stringResource(Res.string.cancel), onConfirm = { picking = false }, dismissLabel = null) {
        if (landmarks.isEmpty()) {
            Text(stringResource(Res.string.landmark_none), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
            return@AppDialog
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            for (poi in landmarks) {
                val far = position?.let { "${Guide.round(distanceMeters(it, poi.point))} m" }
                val label = listOfNotNull(stringResource(Res.string.meet_place_landmark, poi.number), poi.name.ifEmpty { null }, far).joinToString(" · ")
                Text(
                    label, style = MaterialTheme.typography.subheadline, color = AppTheme.colors.label,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(role = Role.Button) {
                        positioning.nearLandmark(poi.number, poi.point)
                        picking = false
                    }.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * The report screen's part: the landmark correction, the steps while it is open and, for a lost child or friend, who is
 * missing and, with the reporter's yes, the last position that person shared with them.
 */
@Composable
fun RegasireReportRows(vm: AppViewModel, category: Int) {
    val reg = vm.c.regasire
    LifecycleResumeEffect(Unit) {
        vm.c.positioning.reportOpen(true)
        onPauseOrDispose { vm.c.positioning.reportOpen(false) }
    }
    val lost = category == IncidentCategory.LOST_FRIEND || category == IncidentCategory.LOST_CHILD
    LaunchedEffect(lost) { if (!lost) reg.setDraft(null) }
    // the person picked belongs to this report only: leaving the screen unsent forgets them
    DisposableEffect(Unit) { onDispose { reg.setDraft(null) } }
    InsetGroup(Modifier.padding(top = 12.dp)) { LandmarkRow(vm) }
    if (!lost) return
    val friends by vm.friends.collectAsStateWithLifecycle()
    val draft by reg.draft.collectAsStateWithLifecycle()
    val records by vm.friendPositions.collectAsStateWithLifecycle()
    val gasps by reg.gasps.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    SectionTitle(stringResource(Res.string.lastseen_section), top = 20.dp)
    InsetGroup(Modifier.selectableGroup()) {
        GroupRow(
            stringResource(Res.string.lastseen_nobody), Modifier.semantics { selected = draft == null }, onClick = { reg.setDraft(null) },
            trailing = if (draft == null) {
                { Icon(Sym.Check, null, Modifier.size(20.dp), tint = colors.accent) }
            } else null,
        )
        for (friend in friends) {
            GroupDivider(start = 64.dp)
            GroupRow(
                friend.nickname, Modifier.semantics { selected = draft?.friend == friend.nodeId }, leading = { Avatar(friend.nickname, 36.dp) },
                onClick = { reg.setDraft(SearchDraft(friend.nodeId, attach = false)) },
                trailing = if (draft?.friend == friend.nodeId) {
                    { Icon(Sym.Check, null, Modifier.size(20.dp), tint = colors.accent) }
                } else null,
            )
        }
    }
    val chosen = draft ?: return
    val name = friends.firstOrNull { it.nodeId == chosen.friend }?.nickname.orEmpty()
    val seen = remember(records, gasps, chosen.friend, now) { reg.lastSeenOf(chosen.friend) }
    val notes = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp)
    if (seen != null) {
        InsetGroup(Modifier.padding(top = 12.dp)) {
            SwitchRow(
                stringResource(Res.string.lastseen_attach), chosen.attach, { reg.setDraft(chosen.copy(attach = it)) },
                subtitle = stringResource(Res.string.lastseen_age, agoText(now - seen.ageMin * 60_000L, now)),
            )
        }
        Text(stringResource(Res.string.lastseen_consent), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = notes)
    } else {
        Text(stringResource(Res.string.lastseen_no_position), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = notes)
    }
    Text(stringResource(Res.string.lastseen_told, name), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = notes)
}

/** Where a lost report's person was last seen, for staff: a pin with its age and a ring that grows with it. */
@Composable
fun lastSeenOverlay(vm: AppViewModel, incident: StaffIncident): MapOverlay {
    val seen = incident.lastSeen ?: return MapOverlay.EMPTY
    val now = rememberNow()
    val point = GeoPoint(seen.lat, seen.lon)
    val radius = FriendRing.radiusM(FriendRing.accuracy95(seen.accuracy), now - seen.atMs, point, vm.venue.bounds)
    return MapOverlay(
        routes = listOf(MapRoute(circle(point, radius), RouteStyle.ALTERNATIVE)),
        pins = listOf(OverlayPin("regasire-seen-${incident.incidentId}", point, PinKind.LAST_SEEN, stringResource(Res.string.lastseen_pin, agoText(seen.atMs, now)))),
    )
}

/** The same in words, under the incident's map. */
@Composable
fun LastSeenBlock(vm: AppViewModel, incident: StaffIncident) {
    val seen = incident.lastSeen ?: return
    val now = rememberNow()
    val point = GeoPoint(seen.lat, seen.lon)
    val radius = FriendRing.radiusM(FriendRing.accuracy95(seen.accuracy), now - seen.atMs, point, vm.venue.bounds).toInt()
    val near = vm.venue.nearestLandmark(point)?.let { " · " + stringResource(Res.string.meet_place_landmark, it.number) }.orEmpty()
    InsetGroup(Modifier.padding(top = 20.dp)) {
        GroupRow(
            stringResource(Res.string.lastseen_staff_title), subtitle = stringResource(Res.string.lastseen_staff, agoText(seen.atMs, now), radius.toString()) + near,
            icon = Sym.PersonSearch,
        )
    }
}

/** The steps setting, in the hidden GNSS panel: off until the field campaign measures the drift. */
@Composable
fun StepsSwitch(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SwitchRow(
        stringResource(Res.string.steps_title), settings.deadReckoning, { on -> vm.c.settings.update { it.copy(deadReckoning = on) } },
        subtitle = stringResource(Res.string.steps_text),
    )
}

private fun circle(center: GeoPoint, radiusM: Double): List<GeoPoint> = (0..CIRCLE_POINTS).map { i ->
    val a = 2 * PI * i / CIRCLE_POINTS
    GeoPoint(
        center.lat + radiusM * cos(a) / METERS_PER_DEGREE,
        center.lon + radiusM * sin(a) / (METERS_PER_DEGREE * cos(radians(center.lat))),
    )
}

private const val CIRCLE_POINTS = 32
private const val METERS_PER_DEGREE = 111_320.0
