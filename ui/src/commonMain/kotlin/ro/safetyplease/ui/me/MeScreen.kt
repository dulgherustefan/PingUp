package ro.safetyplease.ui.me

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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.util.toHex
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.MeshOnText
import ro.safetyplease.ui.common.BigCodeDialog
import ro.safetyplease.ui.common.FullScreenAlertsBanner
import ro.safetyplease.ui.common.MAX_NAME
import ro.safetyplease.ui.common.QrBadge
import ro.safetyplease.ui.common.networkText
import ro.safetyplease.ui.common.rememberBatteryHint
import ro.safetyplease.ui.common.rememberFilePicker
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SwitchRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.map.NavTarget
import ro.safetyplease.ui.me.BatteryGroup
import ro.safetyplease.ui.rememberRadioGate
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.anchor_cached
import ro.safetyplease.ui.resources.anchor_peers
import ro.safetyplease.ui.resources.anchor_relayed
import ro.safetyplease.ui.resources.anchor_text
import ro.safetyplease.ui.resources.anchor_title
import ro.safetyplease.ui.resources.anchor_visible
import ro.safetyplease.ui.resources.blocked_title
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.demo_title
import ro.safetyplease.ui.resources.demo_unlocked
import ro.safetyplease.ui.resources.event_bundled
import ro.safetyplease.ui.resources.event_title
import ro.safetyplease.ui.resources.first_aid_title
import ro.safetyplease.ui.resources.fsi_off_staff_text
import ro.safetyplease.ui.resources.fsi_off_text
import ro.safetyplease.ui.resources.gnss_prewarm
import ro.safetyplease.ui.resources.gnss_prewarm_text
import ro.safetyplease.ui.resources.gnss_row_text
import ro.safetyplease.ui.resources.gnss_searching
import ro.safetyplease.ui.resources.gnss_searching_text
import ro.safetyplease.ui.resources.gnss_title
import ro.safetyplease.ui.resources.layers_saved_place
import ro.safetyplease.ui.resources.me_auto_answer
import ro.safetyplease.ui.resources.me_auto_answer_text
import ro.safetyplease.ui.resources.me_battery
import ro.safetyplease.ui.resources.me_battery_text
import ro.safetyplease.ui.resources.me_edit_name
import ro.safetyplease.ui.resources.me_leave_staff
import ro.safetyplease.ui.resources.me_leave_staff_text
import ro.safetyplease.ui.resources.me_metrics_export
import ro.safetyplease.ui.resources.me_metrics_export_text
import ro.safetyplease.ui.resources.me_metrics_import
import ro.safetyplease.ui.resources.me_metrics_import_text
import ro.safetyplease.ui.resources.me_my_code_label
import ro.safetyplease.ui.resources.me_network
import ro.safetyplease.ui.resources.me_network_log
import ro.safetyplease.ui.resources.me_network_log_text
import ro.safetyplease.ui.resources.me_node_id
import ro.safetyplease.ui.resources.me_note
import ro.safetyplease.ui.resources.me_notices
import ro.safetyplease.ui.resources.me_notices_text
import ro.safetyplease.ui.resources.me_ping_auto
import ro.safetyplease.ui.resources.me_ping_auto_text
import ro.safetyplease.ui.resources.me_ping_sound
import ro.safetyplease.ui.resources.me_ping_white
import ro.safetyplease.ui.resources.me_privacy
import ro.safetyplease.ui.resources.me_role_lead
import ro.safetyplease.ui.resources.me_role_lead_text
import ro.safetyplease.ui.resources.me_share_zone
import ro.safetyplease.ui.resources.me_share_zone_text
import ro.safetyplease.ui.resources.me_sharing
import ro.safetyplease.ui.resources.me_sharing_off
import ro.safetyplease.ui.resources.me_sharing_people
import ro.safetyplease.ui.resources.me_staff_code
import ro.safetyplease.ui.resources.me_title
import ro.safetyplease.ui.resources.me_version
import ro.safetyplease.ui.resources.mesh_switch
import ro.safetyplease.ui.resources.mesh_switch_off
import ro.safetyplease.ui.resources.metrics_export_confirm
import ro.safetyplease.ui.resources.iphone_metrics_diagnostics
import ro.safetyplease.ui.resources.metrics_export_join
import ro.safetyplease.ui.resources.metrics_export_text
import ro.safetyplease.ui.resources.metrics_export_title
import ro.safetyplease.ui.resources.metrics_import_none
import ro.safetyplease.ui.resources.metrics_imported
import ro.safetyplease.ui.resources.metrics_reporters
import ro.safetyplease.ui.resources.name_placeholder
import ro.safetyplease.ui.resources.name_reserved
import ro.safetyplease.ui.resources.open_settings
import ro.safetyplease.ui.resources.pages_title
import ro.safetyplease.ui.resources.relayed_none
import ro.safetyplease.ui.resources.relayed_packets
import ro.safetyplease.ui.resources.role_anchor
import ro.safetyplease.ui.resources.role_control
import ro.safetyplease.ui.resources.role_staff
import ro.safetyplease.ui.resources.route_go_saved
import ro.safetyplease.ui.resources.save
import ro.safetyplease.ui.resources.staff_verified
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.core.data.Role as AppRole

private const val DEMO_UNLOCK_TAPS = 7

/**
 * Your profile: your code large at the top with your name (tap to change it), then only what someone at a festival
 * might change: the staff code and, where the phone may stop the app in the background, battery settings; then the
 * first-aid sheets.
 * Technical tools (network, ID, demo mode) unlock after 7 taps on Version.
 */
@Composable
fun MeScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, mesh.radio, onStartMesh)
    val colors = AppTheme.colors
    var editName by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var bigCode by remember { mutableStateOf(false) }
    var confirmMetrics by remember { mutableStateOf(false) }
    // the GNSS diagnostic, where the phone reports its chip
    val gnss = vm.phone.gnss
    var gnssPanel by remember { mutableStateOf(false) }
    val online by remember(gnss) { gnss?.online ?: MutableStateFlow(false) }.collectAsStateWithLifecycle()
    val searching by remember(gnss) { gnss?.searching ?: MutableStateFlow(false) }.collectAsStateWithLifecycle()
    var versionTaps by remember { mutableIntStateOf(0) }
    val unlocked = stringResource(Res.string.demo_unlocked)
    val haptics = rememberHaptics()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val autoAnswer by vm.autoAnswer.collectAsStateWithLifecycle()
    val code = remember(settings.nickname) { vm.myQrText() }
    val role = when (settings.role) {
        AppRole.STAFF -> stringResource(Res.string.role_staff, settings.teamName)
        AppRole.CONTROL -> stringResource(Res.string.role_control, settings.teamName)
        AppRole.ANCHOR -> stringResource(Res.string.role_anchor, vm.venue.zoneName(settings.anchorZone).ifEmpty { "-" })
        AppRole.PARTICIPANT -> null
    }

    NavScreen(
        title = stringResource(Res.string.me_title), background = colors.grouped, scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(Res.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrBadge(code, settings.nickname, { bigCode = true }, Modifier.padding(horizontal = Gutter))
            Text(
                listOfNotNull(
                    role, stringResource(Res.string.staff_verified).takeIf { role != null && vm.verified },
                    stringResource(Res.string.me_my_code_label),
                ).joinToString("\n"),
                style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
            TextLink(stringResource(Res.string.me_edit_name), { editName = true }, Modifier.padding(top = 4.dp))

            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // an anchor only relays; everyone else may get an evacuation, staff and the console their alarms too
                if (settings.role != AppRole.ANCHOR) {
                    FullScreenAlertsBanner(
                        vm.phone, stringResource(if (settings.role == AppRole.PARTICIPANT) Res.string.fsi_off_text else Res.string.fsi_off_staff_text),
                        Modifier.padding(horizontal = Gutter),
                    )
                }
                InsetGroup {
                    SwitchRow(
                        stringResource(Res.string.mesh_switch), settings.meshEnabled,
                        onCheckedChange = { on ->
                            vm.setMeshEnabled(on)
                            if (on) onStartMesh()
                        },
                        subtitle = stringResource(if (settings.meshEnabled) MeshOnText else Res.string.mesh_switch_off),
                        icon = Sym.Bluetooth,
                    )
                }
                InsetGroup {
                    if (settings.role == AppRole.PARTICIPANT) {
                        GroupRow(stringResource(Res.string.me_staff_code), icon = Sym.QrScan, chevron = true, onClick = { vm.open(Dest.AddFriend) })
                    } else {
                        GroupRow(stringResource(Res.string.me_leave_staff), icon = Sym.Logout, tint = colors.redInk, onClick = { confirmLeave = true })
                    }
                    // the maker's own steps where it has some; elsewhere the exemption Android needs to start the mesh again
                    val steps = vm.phone.batterySteps
                    val batteryHint = rememberBatteryHint(vm.phone)
                    if (steps != null || batteryHint) {
                        GroupDivider()
                        GroupRow(
                            stringResource(Res.string.me_battery), subtitle = stringResource(steps ?: Res.string.me_battery_text), icon = Sym.Battery,
                            chevron = true, onClick = vm.phone::requestBatteryExemption,
                        )
                    }
                }

                BatteryGroup(vm)

                // offline the first fix needs the orbit data the chip downloads while there is internet
                if (online || searching) {
                    InsetGroup {
                        if (searching) {
                            GroupRow(stringResource(Res.string.gnss_searching), subtitle = stringResource(Res.string.gnss_searching_text), icon = Sym.Place)
                        } else {
                            GroupRow(stringResource(Res.string.gnss_prewarm), subtitle = stringResource(Res.string.gnss_prewarm_text), icon = Sym.Place)
                        }
                    }
                }

                // the place kept with a long press on the map, a walk away
                settings.savedPlace?.takeIf { vm.c.walkRouter.covers(it) }?.let { saved ->
                    val savedName = stringResource(Res.string.layers_saved_place)
                    InsetGroup {
                        GroupRow(
                            stringResource(Res.string.route_go_saved), icon = Sym.Walk, tint = colors.accent, chevron = true,
                            onClick = { vm.navigate(NavTarget.Place(saved, savedName)) },
                        )
                    }
                }

                InsetGroup {
                    GroupRow(
                        stringResource(Res.string.me_sharing), icon = Sym.MyLocation, chevron = true,
                        value = sharing?.let { pluralStringResource(Res.plurals.me_sharing_people, it.people, it.people) } ?: stringResource(Res.string.me_sharing_off),
                        onClick = { if (sharing != null) vm.open(Dest.Sharing) else vm.openShare() },
                    )
                    GroupDivider()
                    SwitchRow(
                        stringResource(Res.string.me_auto_answer), autoAnswer, vm::setAutoAnswer,
                        subtitle = stringResource(Res.string.me_auto_answer_text), icon = Sym.PersonSearch,
                    )
                    GroupDivider()
                    SwitchRow(
                        stringResource(Res.string.me_ping_auto), settings.pingAutoAccept, vm::setPingAutoAccept,
                        subtitle = stringResource(Res.string.me_ping_auto_text), icon = Sym.Torch,
                    )
                    GroupDivider()
                    SwitchRow(stringResource(Res.string.me_ping_white), settings.pingWhiteScreen, vm::setPingWhiteScreen, icon = Sym.Phone)
                    GroupDivider()
                    SwitchRow(stringResource(Res.string.me_ping_sound), settings.pingSound, vm::setPingSound, icon = Sym.Bell)
                    GroupDivider()
                    SwitchRow(
                        stringResource(Res.string.me_share_zone), settings.shareZone, vm::setShareZone,
                        subtitle = stringResource(Res.string.me_share_zone_text), icon = Sym.Place,
                    )
                    // staff always gets the organiser's notices; a participant chooses
                    if (settings.role == AppRole.PARTICIPANT) {
                        GroupDivider()
                        SwitchRow(
                            stringResource(Res.string.me_notices), settings.notices, vm::setNotices,
                            subtitle = stringResource(Res.string.me_notices_text), icon = Sym.Bell,
                        )
                    }
                    if (blocked.isNotEmpty()) {
                        GroupDivider()
                        GroupRow(
                            stringResource(Res.string.blocked_title), value = blocked.size.toString(), icon = Sym.Hidden, chevron = true,
                            onClick = { vm.open(Dest.Blocked) },
                        )
                    }
                }

                // until certificates carry it (2.18), the lead is set here by hand
                if (settings.role == AppRole.STAFF) {
                    InsetGroup {
                        SwitchRow(
                            stringResource(Res.string.me_role_lead), settings.roleLead, vm::setRoleLead,
                            subtitle = stringResource(Res.string.me_role_lead_text), icon = Sym.Bell,
                        )
                    }
                }
                ManDownSetting(vm)

                if (settings.role != AppRole.PARTICIPANT || vm.demoUnlocked) PilotGroup(vm, settings.role, settings.networkLog, gnss?.let { { gnssPanel = true } }) { confirmMetrics = true }

                InsetGroup {
                    GroupRow(
                        stringResource(Res.string.event_title), icon = Sym.Flag, chevron = true, onClick = { vm.open(Dest.Event) },
                        value = vm.c.event?.let { it.name.ifEmpty { it.id } } ?: stringResource(Res.string.event_bundled),
                    )
                    GroupDivider()
                    GroupRow(stringResource(Res.string.first_aid_title), icon = Sym.Medical, chevron = true, onClick = { vm.open(Dest.FirstAid) })
                    GroupDivider()
                    GroupRow(stringResource(Res.string.pages_title), icon = Sym.Info, chevron = true, onClick = { vm.open(Dest.Pages) })
                }

                InsetGroup(Modifier.padding(bottom = 24.dp)) {
                    GroupRow(stringResource(Res.string.me_privacy), icon = Sym.Hidden, chevron = true, onClick = { vm.open(Dest.Privacy) })
                    GroupDivider()
                    GroupRow(
                        stringResource(Res.string.me_version), icon = Sym.Info,
                        // the app's name as the phone shows it, set by the build, not one of the shared texts
                        value = vm.phone.appName + " " + vm.phone.appVersion,
                        // like Android's "Build number": 7 taps unlock the test tools
                        onClick = if (vm.phone.demoAvailable && !vm.demoUnlocked) {
                            {
                                versionTaps++
                                if (versionTaps >= DEMO_UNLOCK_TAPS) {
                                    vm.demoUnlocked = true
                                    haptics.confirm()
                                    vm.phone.toast(unlocked)
                                }
                            }
                        } else null,
                    )
                    if (vm.demoUnlocked) {
                        val relayed = mesh.relayed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val relayedText = if (relayed == 0) stringResource(Res.string.relayed_none) else pluralStringResource(Res.plurals.relayed_packets, relayed, relayed)
                        GroupDivider()
                        GroupRow(stringResource(Res.string.me_network), subtitle = networkText(mesh.readyLinks, gate) + "\n" + relayedText, icon = Sym.Bluetooth)
                        GroupDivider()
                        GroupRow(stringResource(Res.string.me_node_id), subtitle = vm.c.identity.nodeId.toHex(), icon = Sym.Notes)
                        GroupDivider()
                        GroupRow(stringResource(Res.string.demo_title), icon = Sym.Science, chevron = true, onClick = { vm.open(Dest.Demo) })
                    }
                }
            }
        }
    }

    if (bigCode) BigCodeDialog(code, settings.nickname) { bigCode = false }
    if (gnssPanel && gnss != null) GnssPanel(vm, gnss) { gnssPanel = false }
    if (confirmMetrics) {
        AppDialog(
            { confirmMetrics = false }, stringResource(Res.string.metrics_export_title), stringResource(Res.string.metrics_export_confirm),
            onConfirm = {
                vm.viewModelScope.launch { shareMetrics(vm.phone, vm.c) }
                confirmMetrics = false
            },
        ) {
            val join = if (settings.role == AppRole.CONTROL) "\n\n" + stringResource(Res.string.metrics_export_join) else ""
            val reports = remember { vm.phone.diagnosticFiles().isNotEmpty() }
            val diagnostics = if (reports) "\n\n" + stringResource(Res.string.iphone_metrics_diagnostics) else ""
            Text(
                stringResource(Res.string.metrics_export_text) + join + diagnostics,
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (editName) {
        var name by remember { mutableStateOf(settings.nickname) }
        val save = {
            if (name.isNotBlank() && vm.nameAllowed(name)) {
                vm.setNickname(name)
                editName = false
            }
        }
        AppDialog(
            onDismiss = { editName = false }, title = stringResource(Res.string.me_edit_name),
            confirmLabel = stringResource(Res.string.save), onConfirm = save, confirmEnabled = name.isNotBlank() && vm.nameAllowed(name),
        ) {
            InputField(
                name, { name = it.take(MAX_NAME) }, stringResource(Res.string.name_placeholder), Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                supporting = stringResource(if (vm.nameAllowed(name)) Res.string.me_note else Res.string.name_reserved),
            )
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            stringResource(Res.string.me_leave_staff), stringResource(Res.string.me_leave_staff_text), { confirmLeave = false },
            confirmLabel = stringResource(Res.string.me_leave_staff), destructive = true,
        ) { vm.leaveStaff() }
    }
}

/**
 * The pilot's tools: on organiser phones the opt-in mobile network log, everywhere the metrics export and, where the
 * phone reports it, the GNSS diagnostic, and on the console the reporters' metrics to join with its log.
 */
@Composable
private fun PilotGroup(vm: AppViewModel, role: AppRole, networkLog: Boolean, onGnss: (() -> Unit)?, onExport: () -> Unit) {
    val records by vm.c.reporterMetrics.state.collectAsStateWithLifecycle()
    val pick = rememberFilePicker(listOf("application/json", "application/octet-stream", "text/plain"), multiple = true) { files ->
        vm.viewModelScope.launch {
            val added = importMetrics(vm.c, files)
            vm.phone.toast(if (added == 0) getString(Res.string.metrics_import_none) else getPluralString(Res.plurals.metrics_imported, added, added))
        }
    }
    InsetGroup {
        if (role != AppRole.PARTICIPANT) {
            SwitchRow(
                stringResource(Res.string.me_network_log), networkLog, vm::setNetworkLog,
                subtitle = stringResource(Res.string.me_network_log_text), icon = Sym.Phone,
            )
            GroupDivider()
        }
        GroupRow(
            stringResource(Res.string.me_metrics_export), subtitle = stringResource(Res.string.me_metrics_export_text), icon = Sym.Notes,
            chevron = true, onClick = onExport,
        )
        if (onGnss != null) {
            GroupDivider()
            GroupRow(stringResource(Res.string.gnss_title), subtitle = stringResource(Res.string.gnss_row_text), icon = Sym.Science, chevron = true, onClick = onGnss)
        }
        if (role == AppRole.CONTROL) {
            val count = records.reports.size
            GroupDivider()
            GroupRow(
                stringResource(Res.string.me_metrics_import),
                subtitle = if (count == 0) stringResource(Res.string.me_metrics_import_text) else pluralStringResource(Res.plurals.metrics_reporters, count, count),
                icon = Sym.Add, chevron = true,
                onClick = pick,
            )
        }
    }
}

/** Anchor screen: four large counters. The phone stays plugged in and the screen may turn off. */
@Composable
fun AnchorScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(Res.string.anchor_title),
        subtitle = vm.venue.zoneName(settings.anchorZone).ifEmpty { stringResource(Res.string.zone_unknown) },
        background = AppTheme.colors.grouped, scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Settings, stringResource(Res.string.open_settings), { vm.open(Dest.Me) }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(start = Gutter, end = Gutter, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnchorCounter(mesh.readyLinks.toString(), stringResource(Res.string.anchor_peers), Modifier.weight(1f))
                AnchorCounter(mesh.relayed.toString(), stringResource(Res.string.anchor_relayed), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnchorCounter(mesh.cachedReports.toString(), stringResource(Res.string.anchor_cached), Modifier.weight(1f))
                AnchorCounter(mesh.visiblePeers.toString(), stringResource(Res.string.anchor_visible), Modifier.weight(1f))
            }
            Text(
                stringResource(Res.string.anchor_text), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** An anchor counter: a big number with its label below. Both counters in a row share the same height. */
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
