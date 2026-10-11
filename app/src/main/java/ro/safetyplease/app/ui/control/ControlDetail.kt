package ro.safetyplease.app.ui.control

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.agoText
import ro.safetyplease.app.text.hopsText
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.common.CategoryCircle
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppDialog
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.ButtonKind
import ro.safetyplease.app.ui.designsystem.EmptyState
import ro.safetyplease.app.ui.designsystem.InputField
import ro.safetyplease.app.ui.designsystem.StatusLabel
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TouchTarget
import ro.safetyplease.app.ui.designsystem.body
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.rememberHaptics
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title3
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.util.truncateUtf8

/** A reason is a line in the log, not a report. */
private const val MAX_REASON = 200

@Composable
internal fun DetailPane(vm: AppViewModel, case: Case?, entries: List<LogEntry>, now: Long, modifier: Modifier) {
    Box(modifier.clip(PaneShape).background(AppTheme.colors.cell)) {
        if (case == null) {
            EmptyState(stringResource(R.string.control_none_selected), stringResource(R.string.control_none_selected_text), icon = Sym.Notes)
        } else {
            // a new incident starts at the top, with no dialog left open
            key(case.incidentId) { CaseDetail(vm, case, entries, now) }
        }
    }
}

/**
 * The chosen incident: category, place and age, its status on the console (and on the network when that differs),
 * the phrase to read on the radio, the operator's actions, the report and its history in the log.
 */
@Composable
private fun CaseDetail(vm: AppViewModel, case: Case, entries: List<LogEntry>, now: Long) {
    val c = vm.c
    val context = LocalContext.current
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val decisions = remember(c) { ConsoleActions(c) }
    var dialog by remember { mutableStateOf<Action?>(null) }
    val r = case.report
    val zoneName = if (r.zone.isEmpty()) "" else vm.venue.zoneName(r.zone)
    val phrase = radioPhrase(r, zoneName, rememberRadioWords())
    var copied by remember(phrase) { mutableStateOf(false) }
    val history = remember(entries, case.incidentId) { entries.filter { it.incidentId == case.incidentId } }
    val nickname = incidents.staff.firstOrNull { it.incidentId == case.incidentId }?.nickname
    val (_, statusIcon, statusColor) = caseLook(case)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryCircle(r.category, case.urgent && case.state == CaseState.OPEN, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Labels.category(r.category)), style = MaterialTheme.typography.title3)
                Text(
                    listOf(r.grid, zoneName, agoText(r.reportedAt, now), hopsText(r.hops)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                )
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (case.urgent) StatusLabel(stringResource(R.string.sev_urgent), icon = Sym.Priority, color = colors.redInk)
            StatusLabel(statusText(case), icon = statusIcon, color = statusColor)
        }
        if (case.localOnly) {
            Note(stringResource(R.string.control_local_only, Labels.staffStatus(context, case.network, case.networkTeam)), colors.orangeInk)
        }
        if (case.unfounded) Note(stringResource(R.string.control_unfounded_note), colors.secondaryLabel)

        Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fill).padding(12.dp)) {
            Text(
                stringResource(R.string.control_radio_title), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold,
                color = colors.secondaryLabel,
            )
            Text(phrase, style = MaterialTheme.typography.body, modifier = Modifier.padding(top = 4.dp))
            AppButton(
                stringResource(if (copied) R.string.code_sheet_copied else R.string.msg_copy),
                {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("stație", phrase))
                    copied = true
                    haptics.confirm()
                },
                Modifier.padding(top = 10.dp), kind = ButtonKind.Secondary, compact = true, icon = if (copied) Sym.Check else Sym.Copy,
            )
        }

        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions(case).chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { i, action ->
                        val primary = row == 0 && i == 0 && case.state != CaseState.CLOSED
                        AppButton(
                            stringResource(actionLabel(action, case)),
                            {
                                if (action == Action.TAKE) {
                                    decisions.run(case, action)
                                    haptics.confirm()
                                } else {
                                    dialog = action
                                }
                            },
                            Modifier.weight(1f), kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary, compact = true,
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(stringResource(R.string.control_actions_note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }

        Text(
            r.description.ifEmpty { stringResource(R.string.incident_no_description) }, style = MaterialTheme.typography.body,
            color = if (r.description.isEmpty()) colors.secondaryLabel else colors.label, modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            listOf(
                stringResource(R.string.incident_reported_at, Labels.clock(r.reportedAt)),
                nickname?.let { stringResource(R.string.incident_reporter, it) } ?: stringResource(R.string.incident_anonymous),
            ).joinToString(" · "),
            style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp),
        )

        Text(stringResource(R.string.control_history), style = MaterialTheme.typography.headline, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        history.forEach { HistoryRow(it) }
    }

    when (val open = dialog) {
        null, Action.TAKE -> Unit
        Action.ASSIGN -> AssignDialog(
            case, teamChoices(entries, stringArrayResource(R.array.control_roles).toList(), settings.teamName), { dialog = null },
        ) { team, reason ->
            decisions.run(case, Action.ASSIGN, team, reason)
            haptics.confirm()
        }
        else -> ReasonDialog(open, { dialog = null }) { reason ->
            decisions.run(case, open, reason = reason)
            haptics.confirm()
        }
    }
}

/** The status in a sentence, for the detail; the queue uses the short form of [caseLook]. */
@Composable
private fun statusText(case: Case): String = when (case.state) {
    CaseState.OPEN -> stringResource(if (case.localOnly) R.string.control_state_reopened else R.string.control_state_open)
    CaseState.ASSIGNED -> stringResource(R.string.control_state_assigned, case.team)
    CaseState.CLOSED -> when {
        case.unfounded -> stringResource(R.string.control_state_unfounded)
        case.network == AckStatus.CANCELLED -> stringResource(R.string.staff_status_cancelled)
        else -> stringResource(R.string.staff_status_resolved, case.team)
    }
}

private fun actionLabel(action: Action, case: Case): Int = when (action) {
    Action.ASSIGN -> if (case.state == CaseState.OPEN) R.string.control_assign else R.string.control_reassign
    Action.TAKE -> R.string.incident_take
    Action.RESOLVE -> R.string.control_resolve
    Action.UNFOUNDED -> R.string.control_unfounded
    Action.REOPEN -> R.string.control_reopen
}

@Composable
private fun Note(text: String, color: Color) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Sym.Info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.footnote, color = color)
    }
}

@Composable
private fun rememberRadioWords(): RadioWords {
    val res = LocalResources.current
    return remember(res) {
        RadioWords(
            category = { res.getString(Labels.category(it)) },
            urgent = res.getString(R.string.control_radio_urgent),
            notUrgent = res.getString(R.string.control_radio_not_urgent),
            unknownPlace = res.getString(R.string.control_radio_no_place),
        )
    }
}

/** One log entry of the incident: the console's time, what happened and, below, the route, the signature or the reason. */
@Composable
private fun HistoryRow(e: LogEntry) {
    val colors = AppTheme.colors
    val title = when (e.event) {
        LogEvent.REPORT -> stringResource(R.string.control_event_report)
        LogEvent.ACK -> stringResource(R.string.control_event_ack, stringResource(ackWord(e.status)), e.team)
        LogEvent.ASSIGN -> stringResource(R.string.control_event_assign, e.team)
        LogEvent.TAKE -> stringResource(R.string.control_event_take)
        LogEvent.RESOLVE -> stringResource(R.string.control_event_resolve)
        LogEvent.UNFOUNDED -> stringResource(R.string.control_state_unfounded)
        LogEvent.REOPEN -> stringResource(R.string.control_state_reopened)
        LogEvent.EXPORT -> return
    }
    val detail = when (e.event) {
        LogEvent.REPORT -> hopsText(e.hops)
        LogEvent.ACK -> listOf(
            if (e.own) stringResource(R.string.control_signed_here) else hopsText(e.hops),
            stringResource(R.string.control_signature, e.signature.take(8)),
        ).joinToString(" · ")
        else -> e.reason
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(Labels.clock(e.at), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.subheadline)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
    }
}

/** Pick a team heard on the network or a role, or type another; when the network can't carry it, says so first. */
@Composable
private fun AssignDialog(case: Case, choices: List<String>, onDismiss: () -> Unit, onAssign: (team: String, reason: String) -> Unit) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    var picked by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val team = other.trim().ifEmpty { picked }
    AppDialog(
        onDismiss, stringResource(R.string.control_assign_title), stringResource(R.string.control_assign),
        onConfirm = {
            onAssign(team, reason)
            onDismiss()
        },
        confirmEnabled = team.isNotEmpty(),
    ) {
        if (ackFor(Action.ASSIGN, case.network) == null) {
            Text(
                stringResource(R.string.control_assign_taken, Labels.staffStatus(context, case.network, case.networkTeam)),
                style = MaterialTheme.typography.footnote, color = colors.orangeInk, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { choice ->
                Chip(choice, selected = other.isBlank() && picked == choice) {
                    picked = choice
                    other = ""
                }
            }
        }
        InputField(
            other, { other = it.truncateUtf8(Limits.TEAM_BYTES) }, stringResource(R.string.control_assign_other),
            Modifier.padding(top = 10.dp),
        )
        InputField(reason, { reason = it.take(MAX_REASON) }, stringResource(R.string.control_reason_optional), Modifier.padding(top = 8.dp))
    }
}

/** A team or role to pick: a pill, filled when chosen. */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier.heightIn(min = TouchTarget).clip(CircleShape).background(if (selected) colors.selection else colors.fill)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.subheadline, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onSelection else colors.label, maxLines = 1,
        )
    }
}

/** Resolve asks for an optional reason; unfounded and reopen need one, since they stay in the log as decisions. */
@Composable
private fun ReasonDialog(action: Action, onDismiss: () -> Unit, onConfirm: (reason: String) -> Unit) {
    val (title, text, confirm) = when (action) {
        Action.RESOLVE -> Triple(R.string.control_resolve_title, R.string.control_resolve_text, R.string.control_resolve)
        Action.UNFOUNDED -> Triple(R.string.control_unfounded_title, R.string.control_unfounded_text, R.string.close)
        else -> Triple(R.string.control_reopen_title, R.string.control_reopen_text, R.string.control_reopen)
    }
    val required = action != Action.RESOLVE
    var reason by remember { mutableStateOf("") }
    AppDialog(
        onDismiss, stringResource(title), stringResource(confirm),
        onConfirm = {
            onConfirm(reason)
            onDismiss()
        },
        confirmEnabled = !required || reason.isNotBlank(),
    ) {
        Text(
            stringResource(text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        InputField(
            reason, { reason = it.take(MAX_REASON) },
            stringResource(if (required) R.string.control_reason else R.string.control_reason_optional), Modifier.padding(top = 12.dp),
        )
    }
}
