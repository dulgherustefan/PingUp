package ro.safetyplease.app.ui.control

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.common.MapCamera
import ro.safetyplease.ui.common.MapPin
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.networkText
import ro.safetyplease.ui.common.venueAspect
import ro.safetyplease.ui.rememberRadioGate
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.ConsoleView
import ro.safetyplease.core.control.Coverage
import ro.safetyplease.core.incidents.AlertPlanner
import ro.safetyplease.core.incidents.CrowdSignal
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.common.CategoryCircle
import ro.safetyplease.ui.common.MeButton
import ro.safetyplease.ui.control.actions
import ro.safetyplease.ui.control.casePoint
import ro.safetyplease.ui.control.crowdSignals
import ro.safetyplease.ui.control.mutedMinutes
import ro.safetyplease.ui.control.queue
import ro.safetyplease.ui.control.reopened
import ro.safetyplease.ui.control.urgent
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavHeight
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_coverage_cellular_legend
import ro.safetyplease.ui.resources.control_coverage_mesh_legend
import ro.safetyplease.ui.resources.control_export
import ro.safetyplease.ui.resources.control_filter_assigned
import ro.safetyplease.ui.resources.control_mute
import ro.safetyplease.ui.resources.control_muted
import ro.safetyplease.ui.resources.control_pane_incident
import ro.safetyplease.ui.resources.control_pane_points
import ro.safetyplease.ui.resources.control_pane_queue
import ro.safetyplease.ui.resources.control_queue_cancel_requested
import ro.safetyplease.ui.resources.control_state_open
import ro.safetyplease.ui.resources.control_state_reopened
import ro.safetyplease.ui.resources.control_title
import ro.safetyplease.ui.resources.control_word_unfounded
import ro.safetyplease.ui.resources.crowd_signal
import ro.safetyplease.ui.resources.edition_open
import ro.safetyplease.ui.resources.filter_active
import ro.safetyplease.ui.resources.filter_resolved
import ro.safetyplease.ui.resources.incident_informational
import ro.safetyplease.ui.resources.incident_state_closed
import ro.safetyplease.ui.resources.incidents_empty_text
import ro.safetyplease.ui.resources.incidents_empty_title
import ro.safetyplease.ui.resources.incidents_filter_empty
import ro.safetyplease.ui.resources.map_title
import ro.safetyplease.ui.resources.me_network
import ro.safetyplease.ui.resources.notice_composer_title
import ro.safetyplease.ui.resources.sev_urgent
import ro.safetyplease.ui.resources.status_cancelled
import ro.safetyplease.ui.resources.status_resolved
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow

private enum class Pane(val label: StringResource) {
    QUEUE(Res.string.control_pane_queue),
    INCIDENT(Res.string.control_pane_incident),
    MAP(Res.string.map_title),
    POINTS(Res.string.control_pane_points),
    NETWORK(Res.string.me_network),
}

/** From this width the map sits between the queue and the incident; below it, the queue sits beside one pane. */
private val WideWidth = 1000.dp

/** Below this width, one pane at a time. */
private val MediumWidth = 600.dp
private val QueueWidth = 340.dp
private val DetailWidth = 380.dp
private val PaneGap = 12.dp
internal val PaneShape = RoundedCornerShape(22.dp)

/**
 * The control-room console, for a tablet in landscape next to an anchor: the incident queue, the venue map with the
 * site grid, the chosen incident with its radio phrase and the operator's actions, and the state of the network.
 * No server and no cloud: the shift log stays on this phone until the operator exports it.
 */
@Composable
fun ControlScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val c = vm.c
    val settings by vm.settings.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val log by c.consoleLog.state.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val heard by c.heard.collectAsStateWithLifecycle()
    val points by c.points.collectAsStateWithLifecycle()
    val mutedUntil by c.mutedUntil.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, mesh.radio, onStartMesh)
    val now = rememberNow()
    val colors = AppTheme.colors
    // folding a long log is done off the main thread
    val cases by produceState(emptyMap<String, Case>(), log, incidents.staff) {
        value = withContext(Dispatchers.Default) { ConsoleView.cases(log.entries, incidents.staff, c.grid) }
    }
    var filter by rememberSaveable { mutableStateOf(CaseState.OPEN) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pane by rememberSaveable { mutableStateOf(Pane.QUEUE) }
    var confirmExport by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var composeNotice by remember { mutableStateOf(false) }
    var layer by rememberSaveable { mutableStateOf(MapLayer.INCIDENTS) }
    val squares = remember(heard, now) { Coverage.squares(heard.values, now) }
    val selected = selectedId?.let(cases::get)
    val onMap = remember(cases) { cases.values.filter { it.state != CaseState.CLOSED } }

    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(NavHeight).padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
            MeButton(settings.nickname) { vm.open(Dest.Me) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.control_title), style = MaterialTheme.typography.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(settings.teamName, networkText(mesh.readyLinks, gate)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // the alarms sound until someone has the incident; the operator can buy ten quiet minutes, never more
            val muted = mutedMinutes(mutedUntil, now)
            AppButton(
                if (muted > 0) stringResource(Res.string.control_muted, muted) else stringResource(Res.string.control_mute),
                { c.mutedUntil.value = if (muted > 0) 0 else c.clock.wallMs() + AlertPlanner.MUTE_MS },
                kind = if (muted > 0) ButtonKind.Primary else ButtonKind.Secondary, compact = true, icon = Sym.Bell,
            )
            Spacer(Modifier.width(8.dp))
            AppButton(stringResource(Res.string.edition_open), { showReport = true }, kind = ButtonKind.Secondary, compact = true)
            Spacer(Modifier.width(8.dp))
            AppButton(stringResource(Res.string.control_export), { confirmExport = true }, kind = ButtonKind.Secondary, compact = true)
            Spacer(Modifier.width(8.dp))
            AppButton(stringResource(Res.string.notice_composer_title), { composeNotice = true }, compact = true, icon = Sym.Report)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = PaneGap, end = PaneGap, bottom = PaneGap)) {
            val wide = maxWidth >= WideWidth
            val select = { id: String ->
                selectedId = id
                if (!wide) pane = Pane.INCIDENT
            }
            val queuePane = @Composable { modifier: Modifier ->
                QueuePane(cases, filter, { filter = it }, selectedId, select, vm.venue, now, modifier)
            }
            val detailPane = @Composable { modifier: Modifier -> DetailPane(vm, selected, log.entries, now, modifier) }
            val mapPane = @Composable { modifier: Modifier ->
                val shades = coverageShades(colors, squares, layer)
                MapPane(vm.venue, c.grid, onMap, selected, select, pointRings(points, now), shades, layer, { layer = it }, modifier)
            }
            val networkPane = @Composable { modifier: Modifier -> NetworkPane(mesh, heard, gate, now, c.grid, modifier) }
            val pointsPane = @Composable { modifier: Modifier -> PointsPane(vm.venue, points, now, modifier) }
            when {
                wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PaneGap)) {
                    queuePane(Modifier.width(QueueWidth).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(PaneGap)) {
                        mapPane(Modifier.fillMaxWidth().weight(0.62f))
                        Row(Modifier.fillMaxWidth().weight(0.38f), horizontalArrangement = Arrangement.spacedBy(PaneGap)) {
                            networkPane(Modifier.weight(1f).fillMaxHeight())
                            pointsPane(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    detailPane(Modifier.width(DetailWidth).fillMaxHeight())
                }
                else -> {
                    val medium = maxWidth >= MediumWidth
                    val panes = if (medium) Pane.entries - Pane.QUEUE else Pane.entries
                    val shown = if (pane in panes) pane else Pane.INCIDENT
                    val queueWidth = QueueWidth.coerceAtMost(maxWidth * 0.42f)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PaneGap)) {
                        if (medium) queuePane(Modifier.width(queueWidth).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SegmentedControl(panes.map { it to stringResource(it.label) }, shown, { pane = it })
                            val rest = Modifier.fillMaxWidth().weight(1f)
                            when (shown) {
                                Pane.QUEUE -> queuePane(rest)
                                Pane.INCIDENT -> detailPane(rest)
                                Pane.MAP -> mapPane(rest)
                                Pane.POINTS -> pointsPane(rest)
                                Pane.NETWORK -> networkPane(rest)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmExport) ExportDialog(c, log.entries.size, vm.viewModelScope) { confirmExport = false }
    if (showReport) ReportDialog(c, vm.viewModelScope) { showReport = false }
    if (composeNotice) NoticeComposer(vm) { composeNotice = false }
}

/**
 * A console is used in landscape and stays on. Lasts as long as the phone is the console, Me sheet included;
 * a recreation for the rotation keeps it, and large screens may ignore the orientation.
 */
@Composable
fun ControlWindow() {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity, view) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            if (activity != null && !activity.isChangingConfigurations) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** Status in a word or two, with its icon and color, as the queue shows it. */
@Composable
internal fun caseLook(case: Case): Triple<String, ImageVector, Color> {
    val colors = AppTheme.colors
    return when (case.state) {
        CaseState.OPEN -> Triple(
            stringResource(if (case.reopened) Res.string.control_state_reopened else Res.string.control_state_open), Sym.Bell, colors.orangeInk,
        )
        CaseState.ASSIGNED -> Triple(case.team, Sym.Person, colors.label)
        CaseState.CLOSED -> when {
            case.unfounded -> Triple(stringResource(Res.string.control_word_unfounded), Sym.Close, colors.secondaryLabel)
            case.network == AckStatus.CANCELLED -> Triple(stringResource(Res.string.status_cancelled), Sym.Close, colors.secondaryLabel)
            case.network == AckStatus.CLOSED_BY_CONTROL -> Triple(stringResource(Res.string.incident_state_closed), Sym.CheckCircle, colors.accent)
            else -> Triple(stringResource(Res.string.status_resolved), Sym.CheckCircle, colors.accent)
        }
    }
}

@Composable
private fun QueuePane(
    cases: Map<String, Case>,
    filter: CaseState,
    onFilter: (CaseState) -> Unit,
    selectedId: String?,
    onSelect: (String) -> Unit,
    venue: Venue,
    now: Long,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val rows = remember(cases, filter) { queue(cases.values, filter) }
    fun counted(label: String, state: CaseState): String {
        val count = cases.values.count { it.state == state }
        return if (count > 0) "$label · $count" else label
    }
    val options = listOf(
        CaseState.OPEN to counted(stringResource(Res.string.filter_active), CaseState.OPEN),
        CaseState.ASSIGNED to counted(stringResource(Res.string.control_filter_assigned), CaseState.ASSIGNED),
        CaseState.CLOSED to stringResource(Res.string.filter_resolved),
    )
    Column(modifier.clip(PaneShape).background(colors.cell)) {
        SegmentedControl(options, filter, onFilter, Modifier.padding(12.dp))
        // a quiet line per zone with several crowd reports; the operator decides, nothing sounds and nothing goes out
        val crowd = remember(cases, now) { crowdSignals(cases.values, now) }
        for (signal in crowd) {
            CrowdSignalRow(signal, venue)
            GroupDivider(start = 0.dp)
        }
        if (rows.isEmpty()) {
            // an empty filter doesn't mean nothing arrived
            if (cases.isEmpty()) EmptyState(stringResource(Res.string.incidents_empty_title), stringResource(Res.string.incidents_empty_text), icon = Sym.Bell)
            else EmptyState(stringResource(Res.string.incidents_filter_empty), null, icon = Sym.Check)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.incidentId }) { case ->
                    QueueRow(case, venue, now, case.incidentId == selectedId) { onSelect(case.incidentId) }
                    GroupDivider(start = 58.dp)
                }
            }
        }
    }
}

/** One incident: category and status, then a cancel to check, urgency, square, zone and age, then the reporter's words. */
@Composable
private fun QueueRow(case: Case, venue: Venue, now: Long, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val r = case.report
    val (status, icon, color) = caseLook(case)
    val place = listOf(r.grid, if (r.zone.isEmpty()) "" else venue.zoneName(r.zone)).filter { it.isNotEmpty() }
        .ifEmpty { listOf(stringResource(Res.string.zone_unknown)) }
    val urgent = stringResource(Res.string.sev_urgent)
    val cancelToCheck = stringResource(Res.string.control_queue_cancel_requested).takeIf { case.cancelRequested }
    val informational = stringResource(Res.string.incident_informational).takeIf { Priority.isInformational(r.category) }
    Row(
        Modifier.fillMaxWidth().background(if (selected) colors.fill else Color.Transparent)
            .selectable(selected, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CategoryCircle(r.category, case.urgent && case.state == CaseState.OPEN, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Labels.category(r.category)), style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(icon, null, Modifier.size(14.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(status, style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium, color = color, maxLines = 1)
            }
            // urgency is spelled out, not only colored
            Text(
                buildAnnotatedString {
                    // the operator's next step, seen without opening the case
                    if (cancelToCheck != null) {
                        withStyle(SpanStyle(color = colors.orangeInk, fontWeight = FontWeight.SemiBold)) { append(cancelToCheck) }
                        append(" · ")
                    }
                    if (case.urgent) {
                        withStyle(SpanStyle(color = colors.redInk, fontWeight = FontWeight.SemiBold)) { append(urgent) }
                        append(" · ")
                    }
                    if (informational != null) append("$informational · ")
                    append((place + agoText(r.reportedAt, now)).joinToString(" · "))
                },
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // the reporter's words first; a recognition hint alone shows as it came
            val said = Recognition.split(r.description).second.ifEmpty { r.description }
            if (said.isNotEmpty()) {
                Text(said, style = MaterialTheme.typography.footnote, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The map with the site grid: open and assigned incidents as pins, the chosen one ringed, the points ringed in the
 * color of their status, and, on the coverage layers, the squares the anchors cover, with a line saying what the
 * colors mean.
 */
@Composable
private fun MapPane(
    venue: Venue,
    grid: SiteGrid,
    cases: List<Case>,
    selected: Case?,
    onSelect: (String) -> Unit,
    rings: Map<Int, Color>,
    shades: Map<Pair<Int, Int>, Color>,
    layer: MapLayer,
    onLayer: (MapLayer) -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val camera = remember { MapCamera() }
    val pins = remember(cases, colors) {
        cases.mapNotNull { case -> casePoint(case, venue)?.let { MapPin(it, colors.severity(case.report.severity), case.incidentId, case.report.severity) } }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedControl(MapLayer.entries.map { it to stringResource(it.label) }, layer, onLayer)
        if (layer != MapLayer.INCIDENTS) {
            Text(
                stringResource(if (layer == MapLayer.MESH) Res.string.control_coverage_mesh_legend else Res.string.control_coverage_cellular_legend),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            VenueMap(
                venue = venue,
                modifier = Modifier.width(fit(maxWidth, maxHeight, venueAspect(venue))),
                pins = if (layer == MapLayer.INCIDENTS) pins else emptyList(),
                highlightZone = selected?.report?.zone.orEmpty(),
                onPinTap = { onSelect(it.key) },
                camera = camera,
                target = selected?.let { casePoint(it, venue) },
                targetColor = colors.label,
                grid = grid,
                squareShades = shades,
                poiRings = rings,
            )
        }
    }
}

/** The widest map of [aspect] that fits the pane. */
private fun fit(width: Dp, height: Dp, aspect: Float): Dp = min(width, height * aspect)

/** "5 rapoarte de aglomerație din Scena principală în 7 min": a line, not an alert. */
@Composable
private fun CrowdSignalRow(signal: CrowdSignal, venue: Venue) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.Groups, null, Modifier.size(18.dp), tint = colors.orangeInk)
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(Res.plurals.crowd_signal, signal.count, signal.count, venue.zoneName(signal.zone), signal.minutes),
            style = MaterialTheme.typography.footnote, color = colors.label,
        )
    }
}
