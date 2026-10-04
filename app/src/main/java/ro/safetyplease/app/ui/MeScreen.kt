package ro.safetyplease.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.R
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.data.Role
import ro.safetyplease.app.demo.Demo

const val MAX_NAME = 20

/** Tabul Eu. Ancora nu are bara de jos, asa ca il deschide ca ecran separat, cu [onBack]. */
@Composable
fun MeScreen(vm: AppViewModel, onStartMesh: () -> Unit, onBack: (() -> Unit)?) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, mesh, onStartMesh)
    val context = LocalContext.current
    val colors = LocalAppColors.current
    var editName by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val relayed = mesh.relayed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar(stringResource(R.string.tab_me), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomClearance.current + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(settings.nickname, 60.dp, near = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.name_label), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        Text(settings.nickname, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconAction(AppIcons.Edit, stringResource(R.string.me_edit_name), { editName = true }, tint = colors.textSecondary)
                }
                Spacer(Modifier.height(14.dp))
                AppButton(stringResource(R.string.me_my_code), { vm.open(Dest.AddFriend) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AppIcons.QrCode)
            }

            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel(stringResource(R.string.me_role))
                Text(
                    when (settings.role) {
                        Role.STAFF -> stringResource(R.string.role_staff, settings.teamName)
                        Role.ANCHOR -> stringResource(R.string.role_anchor, vm.venue.zoneName(settings.anchorZone).ifEmpty { "-" })
                        Role.PARTICIPANT -> stringResource(R.string.role_participant)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                if (settings.role == Role.PARTICIPANT) {
                    AppButton(stringResource(R.string.me_staff_code), { vm.open(Dest.AddFriend) }, kind = ButtonKind.Secondary, compact = true)
                } else {
                    AppButton(stringResource(R.string.me_leave_staff), { confirmLeave = true }, kind = ButtonKind.Secondary, compact = true)
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel(stringResource(R.string.me_network))
                StatusLine(mesh, gate)
                if (!gate.hasAccess) {
                    AppButton(stringResource(R.string.problem_access_action), gate.requestAccess, compact = true)
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    if (relayed == 0) stringResource(R.string.relayed_none) else pluralStringResource(R.plurals.relayed_packets, relayed, relayed),
                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                )
            }

            if (Build.MANUFACTURER.lowercase() in setOf("samsung", "xiaomi", "redmi", "poco")) {
                AppCard(Modifier.fillMaxWidth()) {
                    SectionLabel(stringResource(R.string.me_battery))
                    Text(
                        stringResource(R.string.battery_text), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                    AppButton(
                        stringResource(R.string.battery_open),
                        { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        kind = ButtonKind.Secondary, compact = true,
                    )
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                SectionLabel(stringResource(R.string.me_about))
                Spacer(Modifier.height(10.dp))
                InfoRow(stringResource(R.string.me_version), stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(10.dp))
                InfoRow(stringResource(R.string.me_node_id), vm.c.identity.nodeId.toHex())
                if (Demo.AVAILABLE) {
                    Spacer(Modifier.height(14.dp))
                    AppButton(stringResource(R.string.demo_title), { vm.open(Dest.Demo) }, kind = ButtonKind.Secondary, icon = AppIcons.Bug, compact = true)
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
        AlertDialog(
            onDismissRequest = { editName = false },
            containerColor = colors.card,
            titleContentColor = colors.text,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.name_label), style = MaterialTheme.typography.titleLarge) },
            text = {
                PillTextField(
                    name, { name = it.take(MAX_NAME) }, stringResource(R.string.name_placeholder), Modifier.fillMaxWidth(),
                    container = colors.cardHigh,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            },
            confirmButton = { TextAction(stringResource(R.string.save), save) },
            dismissButton = { TextAction(stringResource(R.string.cancel), { editName = false }, color = colors.textSecondary) },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            stringResource(R.string.me_leave_staff), stringResource(R.string.me_leave_staff_text), { confirmLeave = false },
            confirmLabel = stringResource(R.string.me_leave_staff),
        ) { vm.leaveStaff() }
    }
}

/** Ecranul unei ancore: doar contoare. Telefonul sta in priza, cu ecranul lasat sa se stinga. */
@Composable
fun AnchorScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Anchor, null, tint = colors.accent, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(R.string.anchor_title), style = MaterialTheme.typography.headlineMedium, color = colors.text)
                Text(
                    vm.venue.zoneName(settings.anchorZone).ifEmpty { stringResource(R.string.zone_unknown) },
                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                )
            }
            IconAction(AppIcons.Person, stringResource(R.string.tab_me), { vm.open(Dest.Me) }, tint = colors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Counter(stringResource(R.string.anchor_peers), mesh.readyLinks.toString(), Modifier.weight(1f))
            Counter(stringResource(R.string.anchor_relayed), mesh.relayed.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Counter(stringResource(R.string.anchor_cached), mesh.cachedReports.toString(), Modifier.weight(1f))
            Counter(stringResource(R.string.anchor_visible), mesh.visiblePeers.toString(), Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.anchor_text), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Counter(label: String, value: String, modifier: Modifier) {
    val colors = LocalAppColors.current
    AppCard(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = colors.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}
