package ro.safetyplease.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.incidents.Clustering
import ro.safetyplease.app.incidents.IncidentCluster
import ro.safetyplease.app.incidents.StatusFilter
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.Severity

/**
 * Iconita si culoarea starii, pentru text: nepreluat e portocaliu, preluat e gri, rezolvat e verde.
 * Culorile de text, nu cele de suprafata, ca sa se citeasca si ziua, in plin soare.
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

/** Starea intr-un singur cuvant, pentru randurile din lista. */
@StringRes
private fun statusWord(status: Int): Int = when (status) {
    AckStatus.CANCELLED -> R.string.status_cancelled
    AckStatus.RESOLVED -> R.string.status_resolved
    AckStatus.ACKNOWLEDGED -> R.string.incident_state_taken
    else -> R.string.staff_status_new
}

/** Starea cu numele echipei: „Preluat de Echipa 2”. */
@Composable
private fun StaffStatus(status: Int, team: String, modifier: Modifier = Modifier) {
    val (icon, color) = statusLook(status)
    StatusLabel(Labels.staffStatus(LocalContext.current, status, team), modifier, icon = icon, color = color)
}

/**
 * Tabul Incidente, ca tabul de apeluri din Signal pe iPhone: bula ta in stanga sus, filtrul cu segmente sub bara,
 * apoi cate un rand pe incident, fara linii intre ele: categoria in cerc, locul si vechimea dedesubt, starea in dreapta.
 */
@Composable
fun IncidentsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow()
    var filter by rememberSaveable { mutableStateOf(StatusFilter.OPEN) }
    val clusters = remember(incidents.staff) { Clustering.cluster(incidents.staff) }
    // urgentele nepreluate stau primele, rezolvatele la coada
    val shown = remember(clusters, filter) {
        clusters.filter { Clustering.matches(it, filter) }.sortedWith(
            compareByDescending<IncidentCluster> { it.severity == Severity.URGENT && it.status < AckStatus.ACKNOWLEDGED }
                .thenBy { it.status >= AckStatus.RESOLVED }
                .thenByDescending { it.severity }
                .thenByDescending { it.latestAt },
        )
    }
    val openCount = clusters.count { Clustering.matches(it, StatusFilter.OPEN) }
    val openLabel = stringResource(R.string.filter_open)
    val options = listOf(
        StatusFilter.OPEN to if (openCount > 0) "$openLabel · $openCount" else openLabel,
        StatusFilter.TAKEN to stringResource(R.string.filter_taken),
        StatusFilter.RESOLVED to stringResource(R.string.filter_resolved),
        StatusFilter.ALL to stringResource(R.string.filter_all),
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
                    // un filtru gol nu inseamna ca n-a venit nimic
                    if (clusters.isEmpty()) EmptyState(stringResource(R.string.incidents_empty_title), stringResource(R.string.incidents_empty_text), icon = Sym.Bell)
                    else EmptyState(stringResource(R.string.incidents_filter_empty), null, icon = Sym.Check)
                }
            } else {
                items(shown, key = { it.lead.incidentId }) { cluster -> ClusterRow(vm, cluster, now, Modifier.animateItem()) }
            }
        }
    }
}

/**
 * Randul unui incident: cercul de 44 cu categoria (rosu cat timp o urgenta nu e preluata), categoria si starea
 * pe primul rand, „Urgent”, locul, vechimea si drumul pe al doilea, apoi descrierea pe cel mult doua randuri.
 */
@Composable
private fun ClusterRow(vm: AppViewModel, cluster: IncidentCluster, now: Long, modifier: Modifier = Modifier) {
    val lead = cluster.lead
    val colors = AppTheme.colors
    val alarm = cluster.severity == Severity.URGENT && cluster.status < AckStatus.ACKNOWLEDGED
    val place = if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone)
    val title = stringResource(Labels.category(lead.category)) +
        if (cluster.count > 1) " · " + pluralStringResource(R.plurals.reports, cluster.count, cluster.count) else ""
    val (statusIcon, statusColor) = statusLook(cluster.status)
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button) { vm.open(Dest.Incident(lead.incidentId)) }
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
            // urgenta se scrie, nu doar se coloreaza: cercul rosu singur nu ajunge la cine nu deosebeste culorile
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
}

/**
 * Un incident, ca pagina unui contact din Signal: categoria mare sus, cu starea sub nume, pasul urmator al
 * staff-ului intr-un singur buton, harta cu rapoartele, apoi rapoartele intr-un grup. Numele categoriei apare in bara abia cand antetul iese din ecran.
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
            cluster.incidents.forEach { vm.dismissIncident(it.incidentId) }
            vm.back()
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
            // un singur pas inainte, ca staff-ul sa nu inchida un caz pe care nu l-a preluat nimeni:
            // intai Preiau, apoi Marcheaza rezolvat; rezolvat sau anulat, butonul dispare si ramane starea din antet
            when {
                cluster.status == AckStatus.CANCELLED -> Unit
                canTake -> AppButton(
                    stringResource(R.string.incident_take),
                    {
                        cluster.incidents.filter { it.status < AckStatus.ACKNOWLEDGED }.forEach { vm.acknowledge(it.incidentId) }
                        haptics.confirm()
                    },
                    Modifier.fillMaxWidth().padding(horizontal = Gutter),
                )
                canResolve -> AppButton(
                    stringResource(R.string.incident_mark_resolved),
                    {
                        cluster.incidents.filter { it.status < AckStatus.RESOLVED }.forEach { vm.resolve(it.incidentId) }
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

/** Un raport din grup: ce a scris omul, cand si de la cine a venit, apoi starea lui. */
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
