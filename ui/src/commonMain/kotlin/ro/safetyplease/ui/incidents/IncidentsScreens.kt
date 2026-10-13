package ro.safetyplease.ui.incidents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.isAssignedTo
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Clustering
import ro.safetyplease.core.incidents.DuplicateHint
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.incidents.StaffProfile
import ro.safetyplease.core.incidents.StatusFilter
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.common.IncidentHeader
import ro.safetyplease.ui.common.MapPin
import ro.safetyplease.ui.common.MeButton
import ro.safetyplease.ui.common.NotificationsOffBanner
import ro.safetyplease.ui.common.UpdateTimeline
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.categoryIcon
import ro.safetyplease.ui.common.incidentPoint
import ro.safetyplease.ui.common.placeText
import ro.safetyplease.ui.common.reportFacts
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppMenu
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.IconCircle
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.MenuRow
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.SwipeRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.largeFont
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.control_state_reopened
import ro.safetyplease.ui.resources.control_word_unfounded
import ro.safetyplease.ui.resources.delete
import ro.safetyplease.ui.resources.filter_all
import ro.safetyplease.ui.resources.filter_for_me
import ro.safetyplease.ui.resources.filter_resolved
import ro.safetyplease.ui.resources.incident_accept
import ro.safetyplease.ui.resources.incident_anonymous
import ro.safetyplease.ui.resources.incident_cancel_requested
import ro.safetyplease.ui.resources.incident_cancel_requested_text
import ro.safetyplease.ui.resources.incident_dismiss
import ro.safetyplease.ui.resources.incident_dismiss_text
import ro.safetyplease.ui.resources.incident_dismiss_title
import ro.safetyplease.ui.resources.incident_each_separately
import ro.safetyplease.ui.resources.incident_handed_over
import ro.safetyplease.ui.resources.incident_mark_resolved
import ro.safetyplease.ui.resources.incident_maybe_same
import ro.safetyplease.ui.resources.incident_no_description
import ro.safetyplease.ui.resources.incident_on_scene
import ro.safetyplease.ui.resources.incident_recognition
import ro.safetyplease.ui.resources.incident_reported_at
import ro.safetyplease.ui.resources.incident_reporter
import ro.safetyplease.ui.resources.incident_same_zone
import ro.safetyplease.ui.resources.incident_same_zone_title
import ro.safetyplease.ui.resources.incident_state_closed
import ro.safetyplease.ui.resources.incident_state_handed_over
import ro.safetyplease.ui.resources.incident_state_on_scene
import ro.safetyplease.ui.resources.incident_state_taken
import ro.safetyplease.ui.resources.incident_take
import ro.safetyplease.ui.resources.incident_updates_title
import ro.safetyplease.ui.resources.incidents_dismiss_closed
import ro.safetyplease.ui.resources.incidents_dismiss_closed_text
import ro.safetyplease.ui.resources.incidents_dismiss_closed_title
import ro.safetyplease.ui.resources.incidents_empty_text
import ro.safetyplease.ui.resources.incidents_empty_title
import ro.safetyplease.ui.resources.incidents_filter_empty
import ro.safetyplease.ui.resources.incidents_for_me_empty
import ro.safetyplease.ui.resources.incidents_for_me_empty_text
import ro.safetyplease.ui.resources.more_options
import ro.safetyplease.ui.resources.notif_off_staff_text
import ro.safetyplease.ui.resources.reports
import ro.safetyplease.ui.resources.sev_urgent
import ro.safetyplease.ui.resources.staff_codes_open
import ro.safetyplease.ui.resources.staff_status_mine
import ro.safetyplease.ui.resources.staff_status_new
import ro.safetyplease.ui.resources.status_cancelled
import ro.safetyplease.ui.resources.status_resolved
import ro.safetyplease.ui.resources.tab_incidents
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.ui.safety.ReportOptionNotes
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.hopsText
import ro.safetyplease.ui.text.rememberNow
import ro.safetyplease.ui.text.staffStatusText
import ro.safetyplease.core.data.Role as AppRole

/**
 * Status icon and color for text: unassigned is orange, taken is gray, resolved is green.
 * Text colors, not surface ones, so they read in direct sunlight.
 */
@Composable
private fun statusLook(status: Int): Pair<ImageVector, Color> {
    val colors = AppTheme.colors
    return when (status) {
        AckStatus.CANCELLED, AckStatus.UNFOUNDED -> Sym.Close to colors.secondaryLabel
        AckStatus.RESOLVED, AckStatus.CLOSED_BY_CONTROL -> Sym.CheckCircle to colors.accent
        AckStatus.ACKNOWLEDGED, AckStatus.ON_SCENE, AckStatus.HANDED_OVER -> Sym.Check to colors.secondaryLabel
        else -> Sym.Bell to colors.orangeInk
    }
}

/** Status in one word, for list rows. */
private fun statusWord(status: Int): StringResource = when (status) {
    AckStatus.CANCELLED -> Res.string.status_cancelled
    AckStatus.RESOLVED -> Res.string.status_resolved
    AckStatus.ACKNOWLEDGED -> Res.string.incident_state_taken
    AckStatus.ON_SCENE -> Res.string.incident_state_on_scene
    AckStatus.HANDED_OVER -> Res.string.incident_state_handed_over
    AckStatus.CLOSED_BY_CONTROL -> Res.string.incident_state_closed
    AckStatus.UNFOUNDED -> Res.string.control_word_unfounded
    AckStatus.REOPENED -> Res.string.control_state_reopened
    else -> Res.string.staff_status_new
}

/** The field steps after a take, one at a time; null once nothing is left but resolving it. */
fun nextFieldStep(status: Int): Int? = when (status) {
    AckStatus.ACKNOWLEDGED -> AckStatus.ON_SCENE
    AckStatus.ON_SCENE -> AckStatus.HANDED_OVER
    else -> null
}

/** Nobody has it and it isn't closed: a team can take it. */
fun StaffIncident.isOpen(): Boolean = !AckStatus.isTaken(status) && !AckStatus.isClosed(status)
private fun fieldStepLabel(step: Int): StringResource = if (step == AckStatus.ON_SCENE) Res.string.incident_on_scene else Res.string.incident_handed_over

private fun AppViewModel.fieldStep(incidentId: String, step: Int) = if (step == AckStatus.ON_SCENE) onScene(incidentId) else handOver(incidentId)

/** Status with the team name: "Preluat de Echipa 2", or "Alocat echipei tale" when the console gave it to this team. */
@Composable
private fun StaffStatus(status: Int, team: String, modifier: Modifier = Modifier, mine: Boolean = false) {
    if (mine) {
        StatusLabel(stringResource(Res.string.staff_status_mine), modifier, icon = Sym.Person, color = AppTheme.colors.label)
        return
    }
    val (icon, color) = statusLook(status)
    StatusLabel(staffStatusText(status, team), modifier, icon = icon, color = color)
}

/** Taken in the name of [team] or its role, by the console or by the team itself, and not closed: its work comes first. */
fun StaffIncident.isMine(team: String): Boolean = AckStatus.isTaken(status) && isAssignedTo(teamName, team)

/** The console gave it to [team]: "Alocat echipei tale". A team's own take is not an assignment. */
fun StaffIncident.isAssignedToMe(team: String): Boolean = assigned && isMine(team)

/**
 * One row per report under [filter], without what is for the control room only: those [team] has, assigned or taken,
 * first, then those routed to this phone, then unassigned urgent ones, closed ones last, then severity and recency.
 */
fun incidentRows(
    staff: List<StaffIncident>,
    filter: StatusFilter,
    team: String = "",
    forMe: (StaffIncident) -> Boolean = { false },
): List<StaffIncident> =
    staff.filter { filter.matches(it) && !Priority.isInformational(it.category) }.sortedWith(
        compareByDescending<StaffIncident> { it.isMine(team) }
            .thenByDescending(forMe)
            .thenByDescending { it.severity == Severity.URGENT && it.isOpen() }
            .thenBy { AckStatus.isClosed(it.status) }
            .thenByDescending { it.severity }
            .thenByDescending { it.reportedAt },
    )

/** A field phone's lists: what is routed to it, by default; every open incident, without alerts; the closed ones. */
enum class IncidentList { FOR_ME, ALL, CLOSED }

fun listRows(staff: List<StaffIncident>, list: IncidentList, me: StaffProfile, nowMs: Long): List<StaffIncident> {
    val forMe = { incident: StaffIncident -> Routing.isForMe(me, incident, nowMs) }
    return when (list) {
        IncidentList.FOR_ME -> incidentRows(staff, StatusFilter.ACTIVE, me.team, forMe).filter(forMe)
        IncidentList.ALL -> incidentRows(staff, StatusFilter.ACTIVE, me.team, forMe)
        IncidentList.CLOSED -> incidentRows(staff, StatusFilter.RESOLVED, me.team)
    }
}

/**
 * A crowd or a fire has many witnesses: "Posibil același incident · 3 rapoarte".
 * A patient never gets that: the line only says how many others came from the zone.
 */
@Composable
private fun hintText(hint: DuplicateHint): String =
    if (hint.sameEvent) stringResource(Res.string.incident_maybe_same) + " · " + pluralStringResource(Res.plurals.reports, hint.count, hint.count)
    else pluralStringResource(Res.plurals.incident_same_zone, hint.count - 1, hint.count - 1)

/**
 * Incidents tab: your avatar top left, a segmented filter under the bar, then one row per incident. "Pentru mine"
 * comes first: what the console assigned to the team and what alerts this phone; "Toate" has the rest, silent.
 * At the end of the "Inchise" filter, a link removes them all from the list.
 */
@Composable
fun IncidentsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var list by rememberSaveable { mutableStateOf(IncidentList.FOR_ME) }
    var confirmDismiss by remember { mutableStateOf<StaffIncident?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // a lost friend is for the control room only
    val staff = remember(incidents.staff) { incidents.staff.filterNot { Priority.isInformational(it.category) } }
    val hints = remember(staff) { Clustering.suggestions(staff) }
    val me = remember(settings) { Routing.profile(settings) }
    val shown = remember(staff, list, me, now) { listRows(staff, list, me, now) }
    val forMeCount = remember(staff, me, now) { listRows(staff, IncidentList.FOR_ME, me, now).size }
    val forMeLabel = stringResource(Res.string.filter_for_me)
    val closedCount = staff.count(StatusFilter.RESOLVED::matches)
    val options = listOf(
        IncidentList.FOR_ME to if (forMeCount > 0) "$forMeLabel · $forMeCount" else forMeLabel,
        IncidentList.ALL to stringResource(Res.string.filter_all),
        IncidentList.CLOSED to stringResource(Res.string.filter_resolved),
    )
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    NavScreen(
        title = stringResource(Res.string.tab_incidents),
        scrolled = scrolled,
        leading = { MeButton(settings.nickname) { vm.open(Dest.Me) } },
        trailing = { backdrop ->
            // the console has its own screen; a team in the field gets its one-tap codes here
            if (settings.role == AppRole.STAFF) GlassIconButton(Sym.Flag, stringResource(Res.string.staff_codes_open), { vm.open(Dest.StaffCodes) }, backdrop)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            item(key = "notifications") {
                NotificationsOffBanner(vm.phone, stringResource(Res.string.notif_off_staff_text), Modifier.padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp))
            }
            if (staff.isNotEmpty()) {
                item(key = "filters") {
                    SegmentedControl(options, list, { list = it }, Modifier.padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp))
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    // an empty filter doesn't mean nothing arrived
                    when {
                        staff.isEmpty() -> EmptyState(stringResource(Res.string.incidents_empty_title), stringResource(Res.string.incidents_empty_text), icon = Sym.Bell)
                        list == IncidentList.FOR_ME -> EmptyState(
                            stringResource(Res.string.incidents_for_me_empty), stringResource(Res.string.incidents_for_me_empty_text), icon = Sym.Check,
                        )
                        else -> EmptyState(stringResource(Res.string.incidents_filter_empty), null, icon = Sym.Check)
                    }
                }
            } else {
                items(shown, key = { it.incidentId }) { incident ->
                    IncidentRow(
                        vm, incident, hints[incident.incidentId], now, incident.isAssignedToMe(settings.teamName),
                        awaitsMe = Routing.awaitsAcceptance(incident, settings.teamName),
                        onDismiss = { vm.dismissIncident(incident.incidentId) },
                        onAskDismiss = { confirmDismiss = incident },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (list == IncidentList.CLOSED) {
                    item(key = "clear") {
                        TextLink(
                            stringResource(Res.string.incidents_dismiss_closed), { confirmClear = true },
                            Modifier.animateItem().fillMaxWidth().wrapContentWidth().padding(top = 8.dp), color = colors.redInk,
                        )
                    }
                }
            }
        }
    }

    confirmDismiss?.let { incident ->
        ConfirmDialog(
            stringResource(Res.string.incident_dismiss_title), stringResource(Res.string.incident_dismiss_text),
            onDismiss = { confirmDismiss = null }, confirmLabel = stringResource(Res.string.delete), destructive = true,
        ) {
            vm.dismissIncident(incident.incidentId)
            haptics.confirm()
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            pluralStringResource(Res.plurals.incidents_dismiss_closed_title, closedCount, closedCount),
            stringResource(Res.string.incidents_dismiss_closed_text),
            onDismiss = { confirmClear = false }, confirmLabel = stringResource(Res.string.delete), destructive = true,
        ) {
            vm.dismissClosedIncidents()
            haptics.confirm()
        }
    }
}

/**
 * Incident row: 44dp category circle (red while an urgent one is unassigned), category and status on the first line,
 * "Urgent", location, age and route on the second, then up to two lines of description
 * and, when others came from the same zone at the same time, a line saying so.
 * Swiped left, it leaves the list: at once if closed, after a question if still open.
 * Long-pressed, it offers the actions of the incident page, for this report only.
 */
@Composable
private fun IncidentRow(
    vm: AppViewModel,
    incident: StaffIncident,
    hint: DuplicateHint?,
    now: Long,
    mine: Boolean,
    awaitsMe: Boolean,
    onDismiss: () -> Unit,
    onAskDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val closed = AckStatus.isClosed(incident.status)
    val alarm = incident.severity == Severity.URGENT && incident.isOpen()
    val place = if (incident.zone.isEmpty()) stringResource(Res.string.zone_unknown) else vm.venue.zoneName(incident.zone)
    val (statusIcon, statusColor) = if (mine) Sym.Person to colors.label else statusLook(incident.status)
    val next = nextFieldStep(incident.status)
    // with large fonts the title and details wrap instead of losing the place
    val lines = if (largeFont()) Int.MAX_VALUE else 1
    SwipeRow(
        Sym.Delete, stringResource(Res.string.delete), keep = !closed,
        onAction = if (closed) onDismiss else onAskDismiss, modifier = modifier,
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        role = Role.Button, onLongClickLabel = stringResource(Res.string.more_options), onLongClick = { menu = true },
                    ) { vm.open(Dest.Incident(incident.incidentId)) }
                    .padding(horizontal = Gutter, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconCircle(
                    categoryIcon(incident.category), if (alarm) colors.red.copy(alpha = 0.15f) else colors.fill,
                    if (alarm) colors.red else colors.label, size = 44.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Labels.category(incident.category)), style = MaterialTheme.typography.headline, maxLines = lines, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(statusIcon, null, Modifier.size(16.dp), tint = statusColor)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(if (mine) Res.string.staff_status_mine else statusWord(incident.status)),
                            style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium,
                            color = statusColor, maxLines = 1,
                        )
                    }
                    // urgency is spelled out, not only colored: a red circle alone fails people who can't tell colors apart
                    val urgent = stringResource(Res.string.sev_urgent)
                    // how many phones it passed through is in the detail; for triage the row needs urgency, place and time
                    val details = listOf(place, agoText(incident.reportedAt, now)).joinToString(" · ")
                    Text(
                        buildAnnotatedString {
                            if (incident.severity == Severity.URGENT) {
                                withStyle(SpanStyle(color = colors.redInk, fontWeight = FontWeight.SemiBold)) { append(urgent) }
                                append(" · ")
                            }
                            append(details)
                        },
                        style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                        maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
                    )
                    if (incident.description.isNotEmpty()) {
                        Text(
                            incident.description, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (incident.cancelRequested && !closed) {
                        StatusLabel(stringResource(Res.string.incident_cancel_requested), Modifier.padding(top = 4.dp), icon = Sym.Info, color = colors.orangeInk)
                    }
                    if (hint != null) StatusLabel(hintText(hint), Modifier.padding(top = 4.dp), icon = Sym.Copy)
                }
            }
            // like the incident page: what's already done isn't offered
            AppMenu(menu, { menu = false }) {
                if (incident.isOpen() || awaitsMe) {
                    MenuRow(stringResource(if (awaitsMe) Res.string.incident_accept else Res.string.incident_take), {
                        menu = false
                        vm.acknowledge(incident.incidentId)
                        haptics.confirm()
                    }, icon = Sym.Check)
                }
                if (next != null && !awaitsMe) {
                    MenuRow(stringResource(fieldStepLabel(next)), {
                        menu = false
                        vm.fieldStep(incident.incidentId, next)
                        haptics.confirm()
                    }, icon = Sym.Walk)
                }
                if (!closed) {
                    MenuRow(stringResource(Res.string.incident_mark_resolved), {
                        menu = false
                        vm.resolve(incident.incidentId)
                        haptics.confirm()
                    }, icon = Sym.CheckCircle)
                }
                MenuRow(stringResource(Res.string.incident_dismiss), {
                    menu = false
                    onAskDismiss()
                }, icon = Sym.Delete, danger = true)
            }
        }
    }
}

/**
 * Incident detail: large category header with status under the name, the staff's next step as a single button,
 * the map, then the report. Reports that came from the same zone at the same time follow, each opening its own page:
 * the button acts on this report only. The category name moves into the bar once the header scrolls away.
 */
@Composable
fun IncidentDetailScreen(vm: AppViewModel, incidentId: String) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val scroll = rememberScrollState()
    var headerPx by remember { mutableIntStateOf(0) }
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val pastHeader by remember { derivedStateOf { headerPx > 0 && scroll.value >= headerPx } }
    val hint = remember(incidents.staff, incidentId) { Clustering.suggestions(incidents.staff)[incidentId] }
    val incident = incidents.staff.firstOrNull { it.incidentId == incidentId }
    if (incident == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val others = incidents.staff
        .filter { it.incidentId != incidentId && hint?.incidentIds?.contains(it.incidentId) == true }
        .sortedByDescending { it.reportedAt }
    val closed = AckStatus.isClosed(incident.status)
    val next = nextFieldStep(incident.status)
    val awaitsMe = Routing.awaitsAcceptance(incident, settings.teamName)
    // the others faded in their own severity color, this report on top at full strength
    val pins = others.mapNotNull { other ->
        incidentPoint(vm.venue, other)?.let { MapPin(it, colors.severity(other.severity).copy(alpha = 0.45f), other.incidentId, other.severity) }
    } + listOfNotNull(incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId, incident.severity) })
    val place = if (incident.zone.isEmpty()) stringResource(Res.string.zone_unknown) else vm.venue.zoneName(incident.zone)
    var menu by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    if (confirmDismiss) {
        ConfirmDialog(
            stringResource(Res.string.incident_dismiss_title), stringResource(Res.string.incident_dismiss_text),
            onDismiss = { confirmDismiss = false }, confirmLabel = stringResource(Res.string.delete), destructive = true,
        ) {
            // without the incident this screen pops itself; another back() would also pop the screen below
            vm.dismissIncident(incident.incidentId)
            haptics.confirm()
        }
    }

    NavScreen(
        title = if (pastHeader) stringResource(Labels.category(incident.category)) else "",
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
        trailing = { backdrop ->
            Box {
                GlassIconButton(Sym.More, stringResource(Res.string.more_options), { menu = true }, backdrop)
                AppMenu(menu, { menu = false }) {
                    MenuRow(stringResource(Res.string.incident_dismiss), {
                        menu = false
                        confirmDismiss = true
                    }, icon = Sym.Delete, danger = true)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            IncidentHeader(
                incident.category, incident.severity == Severity.URGENT, place + " · " + agoText(incident.reportedAt, now),
                Modifier.onSizeChanged { headerPx = it.height },
                status = { StaffStatus(incident.status, incident.teamName, mine = incident.isAssignedToMe(settings.teamName)) },
            )
            ReportOptionNotes(incident, Modifier.padding(horizontal = Gutter))
            if (incident.cancelRequested && !closed) {
                Banner(
                    stringResource(Res.string.incident_cancel_requested), stringResource(Res.string.incident_cancel_requested_text),
                    Modifier.padding(start = Gutter, end = Gutter, bottom = 16.dp), warning = true,
                )
            }
            // one step at a time so staff can't close a case nobody took: first Take, or Accept what the console
            // assigned to the team, then On scene and Handed over, with Mark resolved under them; once closed the
            // buttons go away and the header shows the state
            when {
                closed -> Unit
                incident.isOpen() || awaitsMe -> AppButton(
                    stringResource(if (awaitsMe) Res.string.incident_accept else Res.string.incident_take),
                    {
                        vm.acknowledge(incident.incidentId)
                        haptics.confirm()
                    },
                    Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
                next != null -> {
                    AppButton(
                        stringResource(fieldStepLabel(next)),
                        {
                            vm.fieldStep(incident.incidentId, next)
                            haptics.confirm()
                        },
                        Modifier.fillMaxWidth().padding(horizontal = Gutter),
                    )
                    TextLink(
                        stringResource(Res.string.incident_mark_resolved),
                        {
                            vm.resolve(incident.incidentId)
                            haptics.confirm()
                        },
                        Modifier.fillMaxWidth().wrapContentWidth().padding(top = 4.dp),
                    )
                }
                else -> AppButton(
                    stringResource(Res.string.incident_mark_resolved),
                    {
                        vm.resolve(incident.incidentId)
                        haptics.confirm()
                    },
                    Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
            }
            VenueMap(
                venue = vm.venue,
                modifier = Modifier.padding(start = Gutter, end = Gutter, top = 20.dp).clip(RoundedCornerShape(26.dp)),
                position = position,
                pins = pins,
                highlightZone = incident.zone,
            )
            InsetGroup(Modifier.padding(top = 20.dp)) { StaffReportBlock(incident, settings.teamName, incidentPlace(vm, incident)) }
            if (incident.updates.isNotEmpty()) {
                SectionTitle(stringResource(Res.string.incident_updates_title))
                InsetGroup { UpdateTimeline(incident.updates, vm.venue, vm.c.grid, Modifier.padding(horizontal = Gutter, vertical = 10.dp)) }
            }
            if (hint != null && others.isNotEmpty()) {
                SectionTitle(stringResource(if (hint.sameEvent) Res.string.incident_maybe_same else Res.string.incident_same_zone_title))
                InsetGroup {
                    others.forEachIndexed { index, other ->
                        StaffReportBlock(other, settings.teamName, incidentPlace(vm, other)) { vm.open(Dest.Incident(other.incidentId)) }
                        if (index < others.lastIndex) GroupDivider(start = Gutter)
                    }
                }
                Text(
                    stringResource(Res.string.incident_each_separately), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Grid square, nearest landmark and accuracy of a report, as the console shows them. */
@Composable
private fun incidentPlace(vm: AppViewModel, incident: StaffIncident): String {
    val lat = incident.lat
    val lon = incident.lon
    val square = if (lat != null && lon != null) vm.c.grid.cellAt(lat, lon).orEmpty() else ""
    return placeText(square, incident.landmark, incident.accuracyM, vm.venue)
}

/**
 * One report: where exactly and how to recognise the reporter, what the person wrote, when and from whom.
 * With [onOpen] it's another incident: it shows its own state, since the header speaks for this one, and opens on tap.
 */
@Composable
private fun StaffReportBlock(incident: StaffIncident, team: String, place: String, onOpen: (() -> Unit)? = null) {
    val colors = AppTheme.colors
    val (recognition, said) = Recognition.split(incident.description)
    val empty = said.isEmpty()
    Row(
        Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(horizontal = Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (place.isNotEmpty()) StatusLabel(place, Modifier.padding(bottom = 6.dp), icon = Sym.Place, color = colors.label)
            if (recognition.isNotEmpty()) {
                Text(
                    stringResource(Res.string.incident_recognition, recognition), style = MaterialTheme.typography.body,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                if (empty) stringResource(Res.string.incident_no_description) else said,
                style = MaterialTheme.typography.body, color = if (empty) colors.secondaryLabel else colors.label,
                fontWeight = if (incident.severity == Severity.URGENT) FontWeight.Medium else null,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    stringResource(Res.string.incident_reported_at, Labels.clock(incident.reportedAt)),
                    incident.nickname?.let { stringResource(Res.string.incident_reporter, it) } ?: stringResource(Res.string.incident_anonymous),
                    hopsText(incident.hops),
                ).joinToString(" · "),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp),
            )
            reportFacts(incident.responds, incident.breathes, incident.fromStaff, incident.people, incident.dangerContinues)?.let {
                Text(it, style = MaterialTheme.typography.subheadline, color = colors.label, modifier = Modifier.padding(top = 6.dp))
            }
            if (onOpen != null) {
                StaffStatus(incident.status, incident.teamName, Modifier.padding(top = 8.dp), mine = incident.isAssignedToMe(team))
            }
        }
        if (onOpen != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Sym.Chevron, null, Modifier.size(14.dp), tint = colors.tertiaryLabel)
        }
    }
}
