package ro.safetyplease.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.R
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.data.Role as AppRole

const val MAX_NAME = 20

private const val DEMO_UNLOCK_TAPS = 7

/**
 * Ecranul tau, ca profilul din Threema: codul tau mare sus, cu numele (atingi numele ca sa-l schimbi), apoi doar ce
 * mai poate schimba un om la festival: codul de staff si, pe telefoanele care opresc aplicatiile, bateria.
 * Uneltele tehnice (reteaua, ID-ul, modul demo) apar abia dupa 7 atingeri pe Versiune.
 */
@Composable
fun MeScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, mesh.radio, onStartMesh)
    val context = LocalContext.current
    val colors = AppTheme.colors
    var editName by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var bigCode by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }
    val haptics = rememberHaptics()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val code = remember(settings.nickname) { vm.myQrText() }
    val role = when (settings.role) {
        AppRole.STAFF -> stringResource(R.string.role_staff, settings.teamName)
        AppRole.ANCHOR -> stringResource(R.string.role_anchor, vm.venue.zoneName(settings.anchorZone).ifEmpty { "-" })
        AppRole.PARTICIPANT -> null
    }

    NavScreen(
        title = stringResource(R.string.me_title), background = colors.grouped, scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(R.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrBadge(code, settings.nickname, { bigCode = true }, Modifier.padding(horizontal = Gutter))
            Text(
                listOfNotNull(role, stringResource(R.string.me_my_code_label)).joinToString("\n"),
                style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
            TextLink(stringResource(R.string.me_edit_name), { editName = true }, Modifier.padding(top = 4.dp))

            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                InsetGroup {
                    if (settings.role == AppRole.PARTICIPANT) {
                        GroupRow(stringResource(R.string.me_staff_code), icon = Sym.QrScan, chevron = true, onClick = { vm.open(Dest.AddFriend) })
                    } else {
                        GroupRow(stringResource(R.string.me_leave_staff), icon = Sym.Logout, tint = colors.redInk, onClick = { confirmLeave = true })
                    }
                    if (Build.MANUFACTURER.lowercase() in setOf("samsung", "xiaomi", "redmi", "poco")) {
                        GroupDivider()
                        GroupRow(
                            stringResource(R.string.me_battery), subtitle = stringResource(R.string.battery_text), icon = Sym.Battery, chevron = true,
                            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        )
                    }
                }

                InsetGroup(Modifier.padding(bottom = 24.dp)) {
                    GroupRow(
                        stringResource(R.string.me_version), icon = Sym.Info,
                        value = stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME,
                        // ca „Numarul versiunii” din Android: 7 atingeri deschid uneltele de test
                        onClick = if (Demo.AVAILABLE && !vm.demoUnlocked) {
                            {
                                versionTaps++
                                if (versionTaps >= DEMO_UNLOCK_TAPS) {
                                    vm.demoUnlocked = true
                                    haptics.confirm()
                                    Toast.makeText(context, R.string.demo_unlocked, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else null,
                    )
                    if (vm.demoUnlocked) {
                        val relayed = mesh.relayed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val relayedText = if (relayed == 0) stringResource(R.string.relayed_none) else pluralStringResource(R.plurals.relayed_packets, relayed, relayed)
                        GroupDivider()
                        GroupRow(stringResource(R.string.me_network), subtitle = networkText(mesh.readyLinks, gate) + "\n" + relayedText, icon = Sym.Bluetooth)
                        GroupDivider()
                        GroupRow(stringResource(R.string.me_node_id), subtitle = vm.c.identity.nodeId.toHex(), icon = Sym.Notes)
                        GroupDivider()
                        GroupRow(stringResource(R.string.demo_title), icon = Sym.Science, chevron = true, onClick = { vm.open(Dest.Demo) })
                    }
                }
            }
        }
    }

    if (bigCode) BigCodeDialog(code, settings.nickname) { bigCode = false }
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
