package ro.safetyplease.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.MyReport
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentCategory
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.Severity

private fun defaultSeverity(category: Int): Int = when (category) {
    IncidentCategory.MEDICAL, IncidentCategory.VIOLENCE, IncidentCategory.FIRE, IncidentCategory.CROWD -> Severity.URGENT
    IncidentCategory.OTHER -> Severity.LOW
    else -> Severity.MEDIUM
}

@Composable
fun ReportScreen(vm: AppViewModel) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    if (category == 0) CategoryPicker(vm) { category = it }
    else ReportForm(vm, category) { category = 0 }
}

@Composable
private fun CategoryPicker(vm: AppViewModel, onPick: (Int) -> Unit) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.report_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.report_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (row in IncidentCategory.all.chunked(2)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in row) CategoryTile(c, Modifier.weight(1f).fillMaxHeight()) { onPick(c) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val mine = incidents.mine.sortedByDescending { it.createdAt }
        if (mine.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.report_mine), style = MaterialTheme.typography.titleMedium)
            for (report in mine.take(10)) ReportStatusCard(vm, report)
        }
    }
}

@Composable
private fun CategoryTile(category: Int, modifier: Modifier, onClick: () -> Unit) {
    val accent = Palette.severity(defaultSeverity(category))
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.category(category), null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Text(stringResource(Labels.category(category)), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ReportForm(vm: AppViewModel, category: Int, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val position by vm.position.collectAsStateWithLifecycle()
    var severity by rememberSaveable { mutableIntStateOf(defaultSeverity(category)) }
    var description by rememberSaveable { mutableStateOf("") }
    var anonymous by rememberSaveable { mutableStateOf(true) }
    val autoZone = position?.let { vm.venue.zoneAt(it.lat, it.lon) }
    var zone by rememberSaveable(autoZone?.id) { mutableStateOf(autoZone?.id ?: vm.manualZone) }
    val waitMs = vm.rateLimitWaitMs()

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.category(category), null, tint = Palette.severity(severity), modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Labels.category(category)), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
        }

        Button(
            onClick = {
                if (vm.report(category, severity, zone, description, anonymous)) {
                    if (autoZone == null) vm.manualZone = zone
                    onClose()
                }
            },
            enabled = waitMs == 0L,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.severity(severity), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.report_send), style = MaterialTheme.typography.titleMedium) }
        if (waitMs > 0) {
            Text(
                stringResource(R.string.report_rate_limited, (waitMs / 60_000 + 1).toInt()),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(stringResource(R.string.report_optional), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.report_severity), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf(Severity.LOW, Severity.MEDIUM, Severity.URGENT)) {
                FilterChip(
                    selected = severity == s,
                    onClick = { severity = s },
                    label = { Text(stringResource(Labels.severity(s))) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.severity(s).copy(alpha = 0.28f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }

        Text(stringResource(R.string.report_zone), style = MaterialTheme.typography.titleMedium)
        when {
            autoZone != null ->
                Text(stringResource(R.string.report_zone_auto, autoZone.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
            // avem fix GPS, dar in afara zonelor: pozitia pleaca oricum cu raportul, deci nu spunem ca lipseste
            position != null ->
                Text(stringResource(R.string.report_zone_outside), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            else ->
                Text(stringResource(R.string.report_zone_manual), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (z in vm.venue.zones) {
                FilterChip(selected = zone == z.id, onClick = { zone = if (zone == z.id) "" else z.id }, label = { Text(z.name) })
            }
        }

        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(Limits.DESCRIPTION_CHARS) },
            label = { Text(stringResource(R.string.report_description)) },
            supportingText = { Text("${description.length}/${Limits.DESCRIPTION_CHARS}", Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.report_anonymous), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (anonymous) R.string.report_anonymous_on else R.string.report_anonymous_off),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = anonymous, onCheckedChange = { anonymous = it })
        }
    }
}

@Composable
fun ReportStatusCard(vm: AppViewModel, report: MyReport) {
    val steps = listOf(
        stringResource(if (report.sent) R.string.status_sending else R.string.status_waiting_link),
        stringResource(R.string.status_received),
        if (report.status >= AckStatus.ACKNOWLEDGED && report.teamName.isNotEmpty()) stringResource(R.string.status_acknowledged, report.teamName)
        else stringResource(R.string.status_acknowledged_pending),
        stringResource(R.string.status_resolved),
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.category(report.category), null, tint = Palette.severity(report.severity), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(Labels.category(report.category)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(Labels.clock(report.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (report.zone.isNotEmpty()) {
                Text(vm.venue.zoneName(report.zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            steps.forEachIndexed { index, label ->
                val done = index <= report.status
                val current = index == report.status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .background(if (done) Palette.status(maxOf(index, 1)).copy(alpha = if (current) 1f else 0.55f) else MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(AppIcons.Check, null, tint = Color.Black, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
