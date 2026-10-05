package ro.safetyplease.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.R
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.data.Role as AppRole
import ro.safetyplease.app.demo.Demo

const val MAX_NAME = 20

/**
 * Setarile, ca foaia din Signal pe iPhone: X-ul de sticla in dreapta sus, profilul cu butonul QR intr-un card,
 * apoi grupuri de randuri cu iconite simple pe fundalul gri.
 */
@Composable
fun MeScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, mesh, onStartMesh)
    val context = LocalContext.current
    val colors = AppTheme.colors
    var editName by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val relayed = mesh.relayed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val relayedText = if (relayed == 0) stringResource(R.string.relayed_none) else pluralStringResource(R.plurals.relayed_packets, relayed, relayed)
    val role = when (settings.role) {
        AppRole.STAFF -> stringResource(R.string.role_staff, settings.teamName)
        AppRole.ANCHOR -> stringResource(R.string.role_anchor, vm.venue.zoneName(settings.anchorZone).ifEmpty { "-" })
        AppRole.PARTICIPANT -> stringResource(R.string.role_participant)
    }
    val networkAction = when {
        !gate.hasAccess -> gate.requestAccess
        !gate.bluetoothOn -> gate.enableBluetooth
        else -> null
    }

    NavScreen(
        title = stringResource(R.string.settings_title), background = colors.grouped, scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(R.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            InsetGroup {
                SettingsProfile(settings.nickname, role, onEdit = { editName = true }, onCode = { vm.open(Dest.AddFriend) })
            }

            InsetGroup {
                GroupRow(
                    stringResource(R.string.me_my_code), subtitle = stringResource(R.string.me_my_code_label), icon = Sym.QrCode,
                    chevron = true, onClick = { vm.open(Dest.AddFriend) },
                )
                GroupDivider()
                GroupRow(
                    stringResource(R.string.me_edit_name), subtitle = stringResource(R.string.me_note), icon = Sym.Person,
                    chevron = true, onClick = { editName = true },
                )
            }

            InsetGroup {
                if (settings.role == AppRole.PARTICIPANT) {
                    GroupRow(stringResource(R.string.me_staff_code), icon = Sym.QrScan, chevron = true, onClick = { vm.open(Dest.AddFriend) })
                } else {
                    GroupRow(stringResource(R.string.me_leave_staff), icon = Sym.Logout, tint = colors.red, onClick = { confirmLeave = true })
                }
            }

            InsetGroup {
                GroupRow(
                    stringResource(R.string.me_network), subtitle = networkText(mesh, gate) + "\n" + relayedText, icon = Sym.Bluetooth,
                    chevron = networkAction != null, onClick = networkAction,
                )
                if (Build.MANUFACTURER.lowercase() in setOf("samsung", "xiaomi", "redmi", "poco")) {
                    GroupDivider()
                    GroupRow(
                        stringResource(R.string.me_battery), subtitle = stringResource(R.string.battery_text), icon = Sym.Battery, chevron = true,
                        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                    )
                }
            }

            InsetGroup {
                GroupRow(
                    stringResource(R.string.me_version), icon = Sym.Info,
                    value = stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
                )
                GroupDivider()
                GroupRow(stringResource(R.string.me_node_id), subtitle = vm.c.identity.nodeId.toHex(), icon = Sym.Notes)
                if (Demo.AVAILABLE) {
                    GroupDivider()
                    GroupRow(stringResource(R.string.demo_title), icon = Sym.Science, chevron = true, onClick = { vm.open(Dest.Demo) })
                }
            }
        }
    }

    if (editName) {
        var name by remember { mutableStateOf(settings.nickname) }
        val save = {
            if (name.isNotBlank()) vm.setNickname(name)
            editName = false
        }
        AppDialog(
            onDismiss = { editName = false }, title = stringResource(R.string.me_edit_name),
            confirmLabel = stringResource(R.string.save), onConfirm = save, confirmEnabled = name.isNotBlank(),
        ) {
            InputField(
                name, { name = it.take(MAX_NAME) }, stringResource(R.string.name_placeholder), Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                supporting = stringResource(R.string.me_note),
            )
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            stringResource(R.string.me_leave_staff), stringResource(R.string.me_leave_staff_text), { confirmLeave = false },
            confirmLabel = stringResource(R.string.me_leave_staff), destructive = true,
        ) { vm.leaveStaff() }
    }
}

/** Randul de profil din capul setarilor: bula de 72, numele si rolul, iar in dreapta cercul gri cu codul QR. */
@Composable
private fun SettingsProfile(name: String, role: String, onEdit: () -> Unit, onCode: () -> Unit) {
    val colors = AppTheme.colors
    val codeLabel = stringResource(R.string.me_my_code)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.me_edit_name), role = Role.Button, onClick = onEdit)
            .padding(start = Gutter, top = 12.dp, end = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, 72.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.title2, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                role, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onCode).semantics { contentDescription = codeLabel },
            contentAlignment = Alignment.Center,
        ) { IconCircle(Sym.QrCode, colors.fill, colors.label) }
    }
}

/** Ecranul unei ancore: patru contoare mari. Telefonul sta in priza, cu ecranul lasat sa se stinga. */
@Composable
fun AnchorScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(R.string.anchor_title),
        subtitle = vm.venue.zoneName(settings.anchorZone).ifEmpty { stringResource(R.string.zone_unknown) },
        background = AppTheme.colors.grouped, scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Settings, stringResource(R.string.open_settings), { vm.open(Dest.Me) }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(start = Gutter, end = Gutter, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnchorCounter(mesh.readyLinks.toString(), stringResource(R.string.anchor_peers), Modifier.weight(1f))
                AnchorCounter(mesh.relayed.toString(), stringResource(R.string.anchor_relayed), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnchorCounter(mesh.cachedReports.toString(), stringResource(R.string.anchor_cached), Modifier.weight(1f))
                AnchorCounter(mesh.visiblePeers.toString(), stringResource(R.string.anchor_visible), Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.anchor_text), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Un contor al ancorei: cifra mare si, dedesubt, ce numara. Cele doua din acelasi rand au aceeasi inaltime. */
@Composable
private fun AnchorCounter(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(AppTheme.colors.cell).padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(value, style = MaterialTheme.typography.title1, maxLines = 1)
        Text(
            label, style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
