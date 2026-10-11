package ro.safetyplease.app.ui.incidents

import androidx.annotation.StringRes
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
import ro.safetyplease.core.incidents.IncidentCluster
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

private fun dismissCluster(vm: AppViewModel, cluster: IncidentCluster) = cluster.incidents.forEach { vm.dismissIncident(it.incidentId) }

// Take and Resolve only touch the reports in the group that aren't in that state already
private fun takeCluster(vm: AppViewModel, cluster: IncidentCluster) =
    cluster.incidents.filter { it.status < AckStatus.ACKNOWLEDGED }.forEach { vm.acknowledge(it.incidentId) }

private fun resolveCluster(vm: AppViewModel, cluster: IncidentCluster) =
    cluster.incidents.filter { it.status < AckStatus.RESOLVED }.forEach { vm.resolve(it.incidentId) }

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
    var confirmDismiss by remember { mutableStateOf<IncidentCluster?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val clusters = remember(incidents.staff) { Clustering.cluster(incidents.staff) }
    // unassigned urgent incidents first, resolved ones last
    val shown = remember(clusters, filter) {
        clusters.filter { Clustering.matches(it, filter) }.sortedWith(
            compareByDescending<IncidentCluster> { it.severity == Severity.URGENT && it.status < AckStatus.ACKNOWLEDGED }
                .thenBy { it.status >= AckStatus.RESOLVED }
                .thenByDescending { it.severity }
                .thenByDescending { it.latestAt },
        )
    }
    // two filters: what's still open and what's closed; unassigned urgent ones are always first
    val activeCount = clusters.count { Clustering.matches(it, StatusFilter.ACTIVE) }
    val activeLabel = stringResource(R.string.filter_active)
    val closedCount = clusters.count { Clustering.matches(it, StatusFilter.RESOLVED) }
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
            if (clusters.isNotEmpty()) {
                item(key = "filters") {
                    SegmentedControl(options, filter, { filter = it }, Modifier.padding(start = Gutter, end = Gutter, top = 6.dp, bottom = 10.dp))
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    // an empty filter doesn't mean nothing arrived
                    if (clusters.isEmpty()) EmptyState(stringResource(R.string.incidents_empty_title), stringResource(R.string.incidents_empty_text), icon = Sym.Bell)
                    else EmptyState(stringResource(R.string.incidents_filter_empty), null, icon = Sym.Check)
                }
            } else {
                items(shown, key = { it.lead.incidentId }) { cluster ->
                    ClusterRow(
                        vm, cluster, now,
                        onDismiss = { dismissCluster(vm, cluster) },
                        onAskDismiss = { confirmDismiss = cluster },
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

    confirmDismiss?.let { cluster ->
        ConfirmDialog(
            stringResource(R.string.incident_dismiss_title), stringResource(R.string.incident_dismiss_text),
            onDismiss = { confirmDismiss = null }, confirmLabel = stringResource(R.string.delete), destructive = true,
        ) {
            dismissCluster(vm, cluster)
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
 * "Urgent", location, age and route on the second, then up to two lines of description.
 * Swiped left, it leaves the list: at once if closed, after a question if still open.
 * Long-pressed, it offers the actions of the incident page.
 */
@Composable
private fun ClusterRow(
    vm: AppViewModel,
    cluster: IncidentCluster,
    now: Long,
    onDismiss: () -> Unit,
    onAskDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lead = cluster.lead
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val closed = cluster.status >= AckStatus.RESOLVED
    val alarm = cluster.severity == Severity.URGENT && cluster.status < AckStatus.ACKNOWLEDGED
    val place = if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone)
    val title = stringResource(Labels.category(lead.category)) +
        if (cluster.count > 1) " · " + pluralStringResource(R.plurals.reports, cluster.count, cluster.count) else ""
    val (statusIcon, statusColor) = statusLook(cluster.status)
    SwipeRow(
        Sym.Delete, stringResource(R.string.delete), keep = !closed,
        onAction = if (closed) onDismiss else onAskDismiss, modifier = modifier,
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        role = Role.Button, onLongClickLabel = stringResource(R.string.more_options), onLongClick = { menu = true },
                    ) { vm.open(Dest.Incident(lead.incidentId)) }
                    .padding(horizontal = Gutter, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconCircle(
                    categoryIcon(lead.category), if (alarm) colors.red.copy(alpha = 0.15f) else colors.fill,
                    if (alarm) colors.red else colors.label, size = 44.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title, style = MaterialTheme.typography.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(statusIcon, null, Modifier.size(16.dp), tint = statusColor)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(statusWord(cluster.status)), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium,
                            color = statusColor, maxLines = 1,
                        )
                    }
                    // urgency is spelled out, not only colored: a red circle alone fails people who can't tell colors apart
                    val urgent = stringResource(R.string.sev_urgent)
                    val details = listOf(place, agoText(cluster.latestAt, now), hopsText(lead.hops)).joinToString(" · ")
                    Text(
                        buildAnnotatedString {
                            if (cluster.severity == Severity.URGENT) {
                                withStyle(SpanStyle(color = colors.redInk, fontWeight = FontWeight.SemiBold)) { append(urgent) }
                                append(" · ")
                            }
                            append(details)
                        },
                        style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
                    )
                    if (lead.description.isNotEmpty()) {
                        Text(
                            lead.description, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            // like the incident page: what's already done isn't offered
            AppMenu(menu, { menu = false }) {
                if (cluster.status < AckStatus.ACKNOWLEDGED) {
                    MenuRow(stringResource(R.string.incident_take), {
                        menu = false
                        takeCluster(vm, cluster)
                        haptics.confirm()
                    }, icon = Sym.Check)
                }
                if (cluster.status < AckStatus.RESOLVED) {
                    MenuRow(stringResource(R.string.incident_mark_resolved), {
                        menu = false
                        resolveCluster(vm, cluster)
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
 * the map with the reports, then the reports in a group. The category name moves into the bar once the header scrolls away.
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
    val cluster = remember(incidents.staff, incidentId) {
        Clustering.cluster(incidents.staff).firstOrNull { c -> c.incidents.any { it.incidentId == incidentId } }
    }
    if (cluster == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val lead = cluster.lead
    val canTake = cluster.status < AckStatus.ACKNOWLEDGED
    val canResolve = cluster.status < AckStatus.RESOLVED
    val pins = cluster.incidents.mapNotNull { incident ->
        incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId) }
    }
    val place = if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone)
    val reports = cluster.incidents.sortedByDescending { it.reportedAt }
    var menu by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    if (confirmDismiss) {
        ConfirmDialog(
            stringResource(R.string.incident_dismiss_title), stringResource(R.string.incident_dismiss_text),
            onDismiss = { confirmDismiss = false }, confirmLabel = stringResource(R.string.delete), destructive = true,
        ) {
            // without the incident this screen pops itself; another back() would also pop the screen below
            dismissCluster(vm, cluster)
            haptics.confirm()
        }
    }

    NavScreen(
        title = if (pastHeader) stringResource(Labels.category(lead.category)) else "",
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
                lead.category, cluster.severity == Severity.URGENT, place + " · " + agoText(cluster.latestAt, now),
                Modifier.onSizeChanged { headerPx = it.height },
                status = { StaffStatus(cluster.status, cluster.incidents.firstOrNull { it.status == cluster.status }?.teamName.orEmpty()) },
            )
            // one step at a time so staff can't close a case nobody took: first Take, then Mark resolved;
            // once resolved or cancelled the button goes away and the header shows the state
            when {
                cluster.status == AckStatus.CANCELLED -> Unit
                canTake -> AppButton(
                    stringResource(R.string.incident_take),
                    {
                        takeCluster(vm, cluster)
                        haptics.confirm()
                    },
                    Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
                canResolve -> AppButton(
                    stringResource(R.string.incident_mark_resolved),
                    {
                        resolveCluster(vm, cluster)
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
                highlightZone = lead.zone,
            )
            SectionTitle(pluralStringResource(R.plurals.reports, cluster.count, cluster.count))
            InsetGroup {
                reports.forEachIndexed { index, incident ->
                    StaffReportBlock(incident)
                    if (index < reports.lastIndex) GroupDivider(start = Gutter)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One report in the group: what the person wrote, when and from whom, then its state. */
@Composable
private fun StaffReportBlock(incident: StaffIncident) {
    val colors = AppTheme.colors
    val empty = incident.description.isEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp)) {
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
        StaffStatus(incident.status, incident.teamName, Modifier.padding(top = 8.dp))
    }
}
