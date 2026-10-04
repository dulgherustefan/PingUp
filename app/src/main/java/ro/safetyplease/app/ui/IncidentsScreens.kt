package ro.safetyplease.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

/** Eticheta starii: iconita si cuvinte, in culoarea starii. */
@Composable
private fun StaffStatus(status: Int, team: String) {
    val colors = LocalAppColors.current
    val tone = when (status) {
        AckStatus.RESOLVED -> colors.textSecondary
        AckStatus.ACKNOWLEDGED -> colors.ok
        else -> colors.wait
    }
    val icon = when (status) {
        AckStatus.RESOLVED -> AppIcons.CheckCircle
        AckStatus.ACKNOWLEDGED -> AppIcons.Check
        else -> AppIcons.Clock
    }
    StateLabel(icon, Labels.staffStatus(LocalContext.current, status, team), tone)
}

@Composable
fun IncidentsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(StatusFilter.OPEN) }
    val clusters = remember(incidents.staff) { Clustering.cluster(incidents.staff) }
    // urgentele nepreluate stau primele, rezolvatele la coada
    val shown = clusters.filter { Clustering.matches(it, filter) }.sortedWith(
        compareByDescending<IncidentCluster> { it.severity == Severity.URGENT && it.status < AckStatus.ACKNOWLEDGED }
            .thenBy { it.status >= AckStatus.RESOLVED }
            .thenByDescending { it.severity }
            .thenByDescending { it.latestAt }
    )

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((value, label) in listOf(
                StatusFilter.OPEN to R.string.filter_open, StatusFilter.TAKEN to R.string.filter_taken,
                StatusFilter.RESOLVED to R.string.filter_resolved, StatusFilter.ALL to R.string.filter_all,
            )) {
                FilterPill(stringResource(label), filter == value, { filter = value }, count = clusters.count { Clustering.matches(it, value) })
            }
        }
        if (shown.isEmpty()) {
            EmptyState(stringResource(R.string.incidents_empty_title), stringResource(R.string.incidents_empty_text), Modifier.padding(top = 40.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomClearance.current + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.lead.incidentId }) { cluster -> ClusterCard(vm, cluster, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun ClusterCard(vm: AppViewModel, cluster: IncidentCluster, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val lead = cluster.lead
    val team = cluster.incidents.firstOrNull { it.status == cluster.status }?.teamName.orEmpty()
    val tone = colors.severity(cluster.severity)
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().pressScale(source, pressed = 0.985f).height(IntrinsicSize.Min).clip(CardShape).background(colors.card)
            .clickable(source, LocalIndication.current, role = Role.Button) { vm.open(Dest.Incident(lead.incidentId)) },
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(tone))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.category(lead.category), null, tint = colors.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(Labels.category(lead.category)), style = MaterialTheme.typography.titleMedium, color = colors.text,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (cluster.count > 1) {
                    Text(
                        pluralStringResource(R.plurals.reports, cluster.count, cluster.count), style = MaterialTheme.typography.labelMedium,
                        color = colors.text, modifier = Modifier.clip(CircleShape).background(colors.cardHigh).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone),
                style = MaterialTheme.typography.bodyLarge, color = colors.text,
            )
            Text(
                agoText(cluster.latestAt) + " · " + hopsText(lead.hops),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            )
            if (lead.description.isNotEmpty()) {
                Text(lead.description, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cluster.severity == Severity.URGENT) StateLabel(AppIcons.Warning, stringResource(R.string.sev_urgent), colors.danger)
                StaffStatus(cluster.status, team)
            }
        }
    }
}

@Composable
fun IncidentDetailScreen(vm: AppViewModel, incidentId: String) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val haptics = rememberHaptics()
    val cluster = Clustering.cluster(incidents.staff).firstOrNull { c -> c.incidents.any { it.incidentId == incidentId } }
    if (cluster == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val lead = cluster.lead
    val pins = cluster.incidents.mapNotNull { incident ->
        incidentPoint(vm.venue, incident)?.let { MapPin(it, colors.severity(incident.severity), incident.incidentId) }
    }
    ScreenScaffold(
        title = stringResource(Labels.category(lead.category)),
        subtitle = if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone),
        onBack = { vm.back() },
        bottomBar = {
            // Scaffold nu adauga singur spatiul barei de gesturi pentru un bottomBar propriu
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    stringResource(R.string.incident_resolve),
                    {
                        cluster.incidents.filter { it.status < AckStatus.RESOLVED }.forEach { vm.resolve(it.incidentId) }
                        haptics.confirm()
                    },
                    Modifier.weight(1f).height(56.dp), kind = ButtonKind.Secondary, enabled = cluster.status < AckStatus.RESOLVED,
                )
                AppButton(
                    stringResource(R.string.incident_take),
                    {
                        cluster.incidents.filter { it.status < AckStatus.ACKNOWLEDGED }.forEach { vm.acknowledge(it.incidentId) }
                        haptics.confirm()
                    },
                    Modifier.weight(1f).height(56.dp), enabled = cluster.status < AckStatus.ACKNOWLEDGED,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VenueMap(venue = vm.venue, position = position, pins = pins, highlightZone = lead.zone)
            for (incident in cluster.incidents.sortedByDescending { it.reportedAt }) IncidentCard(incident)
        }
    }
}

@Composable
private fun IncidentCard(incident: StaffIncident) {
    val colors = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (incident.severity == Severity.URGENT) StateLabel(AppIcons.Warning, stringResource(R.string.sev_urgent), colors.danger)
            StaffStatus(incident.status, incident.teamName)
        }
        Text(
            if (incident.description.isEmpty()) stringResource(R.string.incident_no_description) else incident.description,
            style = MaterialTheme.typography.bodyLarge,
            color = if (incident.description.isEmpty()) colors.textSecondary else colors.text,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
        )
        Text(
            listOf(
                stringResource(R.string.incident_reported_at, Labels.clock(incident.reportedAt)),
                incident.nickname?.let { stringResource(R.string.incident_reporter, it) } ?: stringResource(R.string.incident_anonymous),
                hopsText(incident.hops),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
        )
    }
}
