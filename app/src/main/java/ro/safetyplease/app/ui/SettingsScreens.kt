package ro.safetyplease.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.R
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.data.Role
import ro.safetyplease.app.demo.Demo

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val relayed = mesh.relayed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    var nickname by rememberSaveable { mutableStateOf(settings.nickname) }
    var confirmLeave by remember { mutableStateOf(false) }

    ScreenScaffold(title = stringResource(R.string.settings), onBack = { vm.back() }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioBanner(vm, mesh, onStartMesh)

            Section(stringResource(R.string.settings_profile)) {
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it.take(20) }, label = { Text(stringResource(R.string.nickname)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (nickname.trim() != settings.nickname && nickname.isNotBlank()) {
                    Button(onClick = { vm.setNickname(nickname) }) { Text(stringResource(R.string.save)) }
                }
                Text(
                    stringResource(R.string.settings_node_id, vm.c.identity.nodeId.toHex()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(R.string.settings_role)) {
                Text(
                    when (settings.role) {
                        Role.STAFF -> stringResource(R.string.role_staff, settings.teamName)
                        Role.ANCHOR -> stringResource(R.string.role_anchor, vm.venue.zoneName(settings.anchorZone).ifEmpty { "-" })
                        Role.PARTICIPANT -> stringResource(R.string.role_participant)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.role == Role.PARTICIPANT) {
                    OutlinedButton(onClick = { vm.open(Dest.Scan) }) { Text(stringResource(R.string.settings_scan_staff)) }
                } else {
                    OutlinedButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.settings_leave_staff)) }
                }
            }

            Section(stringResource(R.string.settings_mesh)) {
                Text(
                    listOf(
                        pluralStringResource(R.plurals.mesh_links, mesh.readyLinks, mesh.readyLinks),
                        pluralStringResource(R.plurals.visible_phones, mesh.visiblePeers, mesh.visiblePeers),
                        pluralStringResource(R.plurals.relayed_packets, relayed, relayed),
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.battery_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text(stringResource(R.string.battery_open)) }
            }

            if (Demo.AVAILABLE) {
                Section(stringResource(R.string.demo_title)) {
                    Text(stringResource(R.string.demo_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.open(Dest.Demo) }) { Text(stringResource(R.string.demo_open)) }
                }
            }

            Text(
                stringResource(R.string.settings_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmLeave) {
        ConfirmDialog(stringResource(R.string.settings_leave_staff), stringResource(R.string.settings_leave_staff_text), { confirmLeave = false }) {
            vm.leaveStaff()
        }
    }
}

/** Ecranul unei ancore: doar contoare. Telefonul sta in priza, cu ecranul lasat sa se stinga. */
@Composable
fun AnchorScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Anchor, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(stringResource(R.string.anchor_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        vm.venue.zoneName(settings.anchorZone).ifEmpty { stringResource(R.string.zone_unknown) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.open(Dest.Settings) }) { Icon(AppIcons.Settings, stringResource(R.string.settings)) }
            }
            MeshChip(mesh)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Counter(stringResource(R.string.anchor_peers), mesh.readyLinks.toString(), Modifier.weight(1f))
                Counter(stringResource(R.string.anchor_relayed), mesh.relayed.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Counter(stringResource(R.string.anchor_cached), mesh.cachedReports.toString(), Modifier.weight(1f))
                Counter(stringResource(R.string.anchor_visible), mesh.visiblePeers.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.anchor_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.open(Dest.Settings) }) { Text(stringResource(R.string.settings_leave_staff)) }
        }
    }
}

@Composable
private fun Counter(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.displaySmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
