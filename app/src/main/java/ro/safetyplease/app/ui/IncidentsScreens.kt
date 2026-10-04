package ro.safetyplease.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.R
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.incidents.Clustering
import ro.safetyplease.app.incidents.IncidentCluster
import ro.safetyplease.app.incidents.StatusFilter
import ro.safetyplease.app.protocol.AckStatus

@Composable
private fun StatusPill(status: Int, team: String) {
    val color = Palette.status(status)
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Text(
            Labels.staffStatus(LocalContext.current, status, team),
            style = MaterialTheme.typography.labelMedium, color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun IncidentsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(StatusFilter.OPEN) }
    val clusters = Clustering.cluster(incidents.staff)
    val shown = clusters.filter { Clustering.matches(it, filter) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((value, label) in listOf(
                StatusFilter.OPEN to R.string.filter_open, StatusFilter.TAKEN to R.string.filter_taken,
                StatusFilter.RESOLVED to R.string.filter_resolved, StatusFilter.ALL to R.string.filter_all,
            )) {
                val count = clusters.count { Clustering.matches(it, value) }
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text("${stringResource(label)} · $count") })
            }
        }
        if (shown.isEmpty()) {
            EmptyState(AppIcons.Shield, stringResource(R.string.incidents_empty_title), stringResource(R.string.incidents_empty_text))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.lead.incidentId }) { cluster -> ClusterCard(vm, cluster) }
            }
        }
    }
}

@Composable
private fun ClusterCard(vm: AppViewModel, cluster: IncidentCluster) {
    val lead = cluster.lead
    val team = cluster.incidents.firstOrNull { it.status == cluster.status }?.teamName.orEmpty()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { vm.open(Dest.Incident(lead.incidentId)) },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(Palette.severity(cluster.severity)))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.category(lead.category), null, tint = Palette.severity(cluster.severity), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Labels.category(lead.category)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (cluster.count > 1) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(
                                pluralStringResource(R.plurals.reports, cluster.count, cluster.count),
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    listOf(
                        stringResource(Labels.severity(cluster.severity)),
                        if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone),
                        agoText(cluster.latestAt),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lead.description.isNotEmpty()) Text(lead.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusPill(cluster.status, team)
            }
        }
    }
}

@Composable
fun IncidentDetailScreen(vm: AppViewModel, incidentId: String) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val cluster = Clustering.cluster(incidents.staff).firstOrNull { c -> c.incidents.any { it.incidentId == incidentId } }
    if (cluster == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val lead = cluster.lead
    val pins = cluster.incidents.mapNotNull { incident ->
        incidentPoint(vm.venue, incident)?.let { MapPin(it, Palette.severity(incident.severity), incident.incidentId) }
    }
    ScreenScaffold(
        title = stringResource(Labels.category(lead.category)),
        subtitle = if (lead.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(lead.zone),
        onBack = { vm.back() },
        bottomBar = {
            // Scaffold nu adauga singur spatiul barei de gesturi pentru un bottomBar propriu
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { cluster.incidents.filter { it.status < AckStatus.RESOLVED }.forEach { vm.resolve(it.incidentId) } },
                    enabled = cluster.status < AckStatus.RESOLVED,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(stringResource(R.string.incident_resolve)) }
                Button(
                    onClick = { cluster.incidents.filter { it.status < AckStatus.ACKNOWLEDGED }.forEach { vm.acknowledge(it.incidentId) } },
                    enabled = cluster.status < AckStatus.ACKNOWLEDGED,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Mesh, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(stringResource(R.string.incident_take)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VenueMap(venue = vm.venue, position = position, pins = pins, selectedZone = lead.zone)
            for (incident in cluster.incidents.sortedByDescending { it.reportedAt }) IncidentCard(incident)
        }
    }
}

@Composable
private fun IncidentCard(incident: StaffIncident) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Labels.severity(incident.severity)), color = Palette.severity(incident.severity),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                StatusPill(incident.status, incident.teamName)
            }
            Text(
                if (incident.description.isEmpty()) stringResource(R.string.incident_no_description) else incident.description,
                color = if (incident.description.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val details = buildList {
                add(stringResource(R.string.incident_reported_at, Labels.clock(incident.reportedAt)))
                add(incident.nickname?.let { stringResource(R.string.incident_reporter, it) } ?: stringResource(R.string.incident_anonymous))
                if (BuildConfig.DEBUG) add(pluralStringResource(R.plurals.hops, incident.hops, incident.hops))
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
