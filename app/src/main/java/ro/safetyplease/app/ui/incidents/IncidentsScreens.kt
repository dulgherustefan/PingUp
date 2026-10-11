package ro.safetyplease.app.ui.incidents

import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.agoText
import ro.safetyplease.app.text.hopsText
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.IncidentHeader
import ro.safetyplease.app.ui.common.MapPin
import ro.safetyplease.app.ui.common.MeButton
import ro.safetyplease.app.ui.common.VenueMap
import ro.safetyplease.app.ui.common.categoryIcon
import ro.safetyplease.app.ui.common.incidentPoint
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppMenu
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.ConfirmDialog
import ro.safetyplease.app.ui.designsystem.EmptyState
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.GroupDivider
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.IconCircle
import ro.safetyplease.app.ui.designsystem.InsetGroup
import ro.safetyplease.app.ui.designsystem.MenuRow
import ro.safetyplease.app.ui.designsystem.NavScreen
import ro.safetyplease.app.ui.designsystem.SectionTitle
import ro.safetyplease.app.ui.designsystem.SegmentedControl
import ro.safetyplease.app.ui.designsystem.StatusLabel
import ro.safetyplease.app.ui.designsystem.SwipeRow
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TextLink
import ro.safetyplease.app.ui.designsystem.body
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.rememberHaptics
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Clustering
import ro.safetyplease.core.incidents.DuplicateHint
import ro.safetyplease.core.incidents.StatusFilter
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Severity

/**
 * Status icon and color for text: unassigned is orange, taken is gray, resolved is green.
 * Text colors, not surface ones, so they read in direct sunlight.
 */
@Composable
private fun statusLook(status: Int): Pair<ImageVector, Color> {
    val colors = AppTheme.colors
    return when (status) {
        AckStatus.CANCELLED -> Sym.Close to colors.secondaryLabel
        AckStatus.RESOLVED -> Sym.CheckCircle to colors.accent
        AckStatus.ACKNOWLEDGED -> Sym.Check to colors.secondaryLabel
        else -> Sym.Bell to colors.orangeInk
    }
}

/** Status in one word, for list rows. */
@StringRes
private fun statusWord(status: Int): Int = when (status) {
    AckStatus.CANCELLED -> R.string.status_cancelled
    AckStatus.RESOLVED -> R.string.status_resolved
    AckStatus.ACKNOWLEDGED -> R.string.incident_state_taken
    else -> R.string.staff_status_new
}

/** Status with the team name: "Preluat de Echipa 2". */
@Composable
private fun StaffStatus(status: Int, team: String, modifier: Modifier = Modifier) {
    val (icon, color) = statusLook(status)
    StatusLabel(Labels.staffStatus(LocalContext.current, status, team), modifier, icon = icon, color = color)
}

/** One row per report under [filter]: unassigned urgent ones first, closed ones last, then severity and recency. */
internal fun incidentRows(staff: List<StaffIncident>, filter: StatusFilter): List<StaffIncident> =
    staff.filter(filter::matches).sortedWith(
        compareByDescending<StaffIncident> { it.severity == Severity.URGENT && it.status < AckStatus.ACKNOWLEDGED }
            .thenBy { it.status >= AckStatus.RESOLVED }
            .thenByDescending { it.severity }
            .thenByDescending { it.reportedAt },
    )

/**
 * A crowd or a fire has many witnesses: "Posibil același incident · 3 rapoarte".
 * A patient never gets that: the line only says how many others came from the zone.
 */
@Composable
private fun hintText(hint: DuplicateHint): String =
    if (hint.sameEvent) stringResource(R.string.incident_maybe_same) + " · " + pluralStringResource(R.plurals.reports, hint.count, hint.count)
    else pluralStringResource(R.plurals.incident_same_zone, hint.count - 1, hint.count - 1)

/**
 * Incidents tab: your avatar top left, a segmented filter under the bar, then one row per incident.
 * At the end of the "Inchise" filter, a link removes them all from the list.
 */
@Composable
fun IncidentsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var filter by rememberSaveable { mutableStateOf(StatusFilter.ACTIVE) }
    var confirmDismiss by remember { mutableStateOf<StaffIncident?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val staff = incidents.staff
    val hints = remember(staff) { Clustering.suggestions(staff) }
    val shown = remember(staff, filter) { incidentRows(staff, filter) }
    // two filters: what's still open and what's closed; unassigned urgent ones are always first
    val activeCount = staff.count(StatusFilter.ACTIVE::matches)
    val activeLabel = stringResource(R.string.filter_active)
    val closedCount = staff.count(StatusFilter.RESOLVED::matches)
    val options = listOf(
        StatusFilter.ACTIVE to if (activeCount > 0) "$activeLabel · $activeCount" else activeLabel,
        StatusFilter.RESOLVED to stringResource(R.string.filter_resolved),
    )
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    NavScreen(
        title = stringResource(R.string.tab_incidents),
        scrolled = scrolled,
        leading = { MeButton(settings.nickname) { vm.open(Dest.Me) } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            if (staff.isNotEmpty()) {
                item(key = "filters") {
                    SegmentedControl(options, filter, { filter = it }, Modifier.padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp))
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    // an empty filter doesn't mean nothing arrived
                    if (staff.isEmpty()) EmptyState(stringResource(R.string.incidents_empty_title), stringResource(R.string.incidents_empty_text), icon = Sym.Bell)
                    else EmptyState(stringResource(R.string.incidents_filter_empty), null, icon = Sym.Check)
                }
            } else {
                items(shown, key = { it.incidentId }) { incident ->
                    IncidentRow(
                        vm, incident, hints[incident.incidentId], now,
                        onDismiss = { vm.dismissIncident(incident.incidentId) },
                        onAskDismiss = { confirmDismiss = incident },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (filter == StatusFilter.RESOLVED) {
                    item(key = "clear") {
                        TextLink(
                            stringResource(R.string.incidents_dismiss_closed), { confirmClear = true },
                            Modifier.animateItem().fillMaxWidth().wrapContentWidth().padding(top = 8.dp), color = colors.redInk,
                        )
                    }
                }
            }
        }
    }

    confirmDismiss?.let { incident ->
        ConfirmDialog(
            stringResource(R.string.incident_dismiss_title), stringResource(R.string.incident_dismiss_text),
            onDismiss = { confirmDismiss = null }, confirmLabel = stringResource(R.string.delete), destructive = true,
        ) {
            vm.dismissIncident(incident.incidentId)
            haptics.confirm()
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            pluralStringResource(R.plurals.incidents_dismiss_closed_title, closedCount, closedCount),
            stringResource(R.string.incidents_dismiss_closed_text),
            onDismiss = { confirmClear = false }, confirmLabel = stringResource(R.string.delete), destructive = true,
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
    onDismiss: () -> Unit,
    onAskDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val closed = incident.status >= AckStatus.RESOLVED
    val alarm = incident.severity == Severity.URGENT && incident.status < AckStatus.ACKNOWLEDGED
    val place = if (incident.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(incident.zone)
    val (statusIcon, statusColor) = statusLook(incident.status)
    SwipeRow(
        Sym.Delete, stringResource(R.string.delete), keep = !closed,
        onAction = if (closed) onDismiss else onAskDismiss, modifier = modifier,
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        role = Role.Button, onLongClickLabel = stringResource(R.string.more_options), onLongClick = { menu = true },
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
                            stringResource(Labels.category(incident.category)), style = MaterialTheme.typography.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(statusIcon, null, Modifier.size(16.dp), tint = statusColor)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(statusWord(incident.status)), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium,
                            color = statusColor, maxLines = 1,
                        )
                    }
                    // urgency is spelled out, not only colored: a red circle alone fails people who can't tell colors apart
                    val urgent = stringResource(R.string.sev_urgent)
                    val details = listOf(place, agoText(incident.reportedAt, now), hopsText(incident.hops)).joinToString(" · ")
                    Text(
                        buildAnnotatedString {
                            if (incident.severity == Severity.URGENT) {
                                withStyle(SpanStyle(color = colors.redInk, fontWeight = FontWeight.SemiBold)) { append(urgent) }
                                append(" · ")
                            }
                            append(details)
                        },
                        style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
                    )
                    if (incident.description.isNotEmpty()) {
                        Text(
                            incident.description, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (hint != null) StatusLabel(hintText(hint), Modifier.padding(top = 4.dp), icon = Sym.Copy)
                }
            }
            // like the incident page: what's already done isn't offered
            AppMenu(menu, { menu = false }) {
                if (incident.status < AckStatus.ACKNOWLEDGED) {
                    MenuRow(stringResource(R.string.incident_take), {
                        menu = false
                        vm.acknowledge(incident.incidentId)
                        haptics.confirm()
                    }, icon = Sym.Check)
                }
                if (incident.status < AckStatus.RESOLVED) {
                    MenuRow(stringResource(R.string.incident_mark_resolved), {
                        menu = false
                        vm.resolve(incident.incidentId)
                        haptics.confirm()
                    }, icon = Sym.CheckCircle)
                }
                MenuRow(stringResource(R.string.incident_dismiss), {
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
    val canTake = incident.status < AckStatus.ACKNOWLEDGED
    val canResolve = incident.status < AckStatus.RESOLVED
    // the others in gray, this report on top in its severity color
    val pins = others.mapNotNull { other -> incidentPoint(vm.venue, other)?.let { MapPin(it, colors.secondaryLabel, other.incidentId) } } +
        listOfNotNull(incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId) })
    val place = if (incident.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(incident.zone)
    var menu by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    if (confirmDismiss) {
        ConfirmDialog(
            stringResource(R.string.incident_dismiss_title), stringResource(R.string.incident_dismiss_text),
            onDismiss = { confirmDismiss = false }, confirmLabel = stringResource(R.string.delete), destructive = true,
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
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
        trailing = { backdrop ->
            Box {
                GlassIconButton(Sym.More, stringResource(R.string.more_options), { menu = true }, backdrop)
                AppMenu(menu, { menu = false }) {
                    MenuRow(stringResource(R.string.incident_dismiss), {
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
                status = { StaffStatus(incident.status, incident.teamName) },
            )
            // one step at a time so staff can't close a case nobody took: first Take, then Mark resolved;
            // once resolved or cancelled the button goes away and the header shows the state
            when {
                incident.status == AckStatus.CANCELLED -> Unit
                canTake -> AppButton(
                    stringResource(R.string.incident_take),
                    {
                        vm.acknowledge(incident.incidentId)
                        haptics.confirm()
                    },
                    Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
                canResolve -> AppButton(
                    stringResource(R.string.incident_mark_resolved),
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
            InsetGroup(Modifier.padding(top = 20.dp)) { StaffReportBlock(incident) }
            if (hint != null && others.isNotEmpty()) {
                SectionTitle(stringResource(if (hint.sameEvent) R.string.incident_maybe_same else R.string.incident_same_zone_title))
                InsetGroup {
                    others.forEachIndexed { index, other ->
                        StaffReportBlock(other) { vm.open(Dest.Incident(other.incidentId)) }
                        if (index < others.lastIndex) GroupDivider(start = Gutter)
                    }
                }
                Text(
                    stringResource(R.string.incident_each_separately), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * One report: what the person wrote, when and from whom.
 * With [onOpen] it's another incident: it shows its own state, since the header speaks for this one, and opens on tap.
 */
@Composable
private fun StaffReportBlock(incident: StaffIncident, onOpen: (() -> Unit)? = null) {
    val colors = AppTheme.colors
    val empty = incident.description.isEmpty()
    Row(
        Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(horizontal = Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (empty) stringResource(R.string.incident_no_description) else incident.description,
                style = MaterialTheme.typography.body, color = if (empty) colors.secondaryLabel else colors.label,
                fontWeight = if (incident.severity == Severity.URGENT) FontWeight.Medium else null,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    stringResource(R.string.incident_reported_at, Labels.clock(incident.reportedAt)),
                    incident.nickname?.let { stringResource(R.string.incident_reporter, it) } ?: stringResource(R.string.incident_anonymous),
                    hopsText(incident.hops),
                ).joinToString(" · "),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp),
            )
            if (onOpen != null) StaffStatus(incident.status, incident.teamName, Modifier.padding(top = 8.dp))
        }
        if (onOpen != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Sym.Chevron, null, Modifier.size(14.dp), tint = colors.tertiaryLabel)
        }
    }
}
