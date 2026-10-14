package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.ResponseCode
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.CategoryCircle
import ro.safetyplease.ui.common.UpdateTimeline
import ro.safetyplease.ui.common.placeText
import ro.safetyplease.ui.common.reportFacts
import ro.safetyplease.ui.control.Action
import ro.safetyplease.ui.control.ConsoleActions
import ro.safetyplease.ui.control.RadioWords
import ro.safetyplease.ui.control.ackFor
import ro.safetyplease.ui.control.ackWord
import ro.safetyplease.ui.control.actions
import ro.safetyplease.ui.control.queue
import ro.safetyplease.ui.control.radioPhrase
import ro.safetyplease.ui.control.reopened
import ro.safetyplease.ui.control.teamChoices
import ro.safetyplease.ui.control.urgent
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title3
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.code_sheet_copied
import ro.safetyplease.ui.resources.control_assign
import ro.safetyplease.ui.resources.control_assign_other
import ro.safetyplease.ui.resources.control_assign_taken
import ro.safetyplease.ui.resources.control_assign_title
import ro.safetyplease.ui.resources.control_close
import ro.safetyplease.ui.resources.control_close_text
import ro.safetyplease.ui.resources.control_close_title
import ro.safetyplease.ui.resources.control_confirm_cancel
import ro.safetyplease.ui.resources.control_confirm_cancel_text
import ro.safetyplease.ui.resources.control_confirm_cancel_title
import ro.safetyplease.ui.resources.control_event_ack
import ro.safetyplease.ui.resources.control_event_assign
import ro.safetyplease.ui.resources.control_event_cancel
import ro.safetyplease.ui.resources.control_event_close
import ro.safetyplease.ui.resources.control_event_report
import ro.safetyplease.ui.resources.control_event_take
import ro.safetyplease.ui.resources.control_history
import ro.safetyplease.ui.resources.control_landmark_none
import ro.safetyplease.ui.resources.control_landmark_title
import ro.safetyplease.ui.resources.control_local_only
import ro.safetyplease.ui.resources.control_none_selected
import ro.safetyplease.ui.resources.control_none_selected_text
import ro.safetyplease.ui.resources.control_radio_no_place
import ro.safetyplease.ui.resources.control_radio_not_urgent
import ro.safetyplease.ui.resources.control_radio_recognition
import ro.safetyplease.ui.resources.control_radio_title
import ro.safetyplease.ui.resources.control_radio_triage
import ro.safetyplease.ui.resources.control_radio_urgent
import ro.safetyplease.ui.resources.control_reason
import ro.safetyplease.ui.resources.control_reason_optional
import ro.safetyplease.ui.resources.control_reassign
import ro.safetyplease.ui.resources.control_reopen
import ro.safetyplease.ui.resources.control_reopen_text
import ro.safetyplease.ui.resources.control_reopen_title
import ro.safetyplease.ui.resources.control_respond_coming
import ro.safetyplease.ui.resources.control_respond_failed
import ro.safetyplease.ui.resources.control_respond_landmark
import ro.safetyplease.ui.resources.control_respond_medical
import ro.safetyplease.ui.resources.control_respond_send
import ro.safetyplease.ui.resources.control_respond_sent
import ro.safetyplease.ui.resources.control_respond_title
import ro.safetyplease.ui.resources.control_respond_unavailable
import ro.safetyplease.ui.resources.control_roles
import ro.safetyplease.ui.resources.control_signature
import ro.safetyplease.ui.resources.control_signed_here
import ro.safetyplease.ui.resources.control_state_assigned
import ro.safetyplease.ui.resources.control_state_open
import ro.safetyplease.ui.resources.control_state_reopened
import ro.safetyplease.ui.resources.control_state_unfounded
import ro.safetyplease.ui.resources.control_unfounded
import ro.safetyplease.ui.resources.control_unfounded_text
import ro.safetyplease.ui.resources.control_unfounded_title
import ro.safetyplease.ui.resources.incident_anonymous
import ro.safetyplease.ui.resources.incident_cancel_requested
import ro.safetyplease.ui.resources.incident_danger_continues
import ro.safetyplease.ui.resources.incident_informational
import ro.safetyplease.ui.resources.incident_no_description
import ro.safetyplease.ui.resources.incident_people
import ro.safetyplease.ui.resources.incident_recognition
import ro.safetyplease.ui.resources.incident_reported_at
import ro.safetyplease.ui.resources.incident_reporter
import ro.safetyplease.ui.resources.incident_take
import ro.safetyplease.ui.resources.incident_updates_title
import ro.safetyplease.ui.resources.msg_copy
import ro.safetyplease.ui.resources.place_near
import ro.safetyplease.ui.resources.sev_urgent
import ro.safetyplease.ui.resources.staff_status_cancelled
import ro.safetyplease.ui.resources.staff_status_closed
import ro.safetyplease.ui.resources.staff_status_resolved
import ro.safetyplease.ui.safety.ReportOptionNotes
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.Replies
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.fill
import ro.safetyplease.ui.text.hopsText
import ro.safetyplease.ui.text.staffStatusText

/** A reason is a line in the log, not a report. */
private const val MAX_REASON = 200

/** In a pane shorter than this (a phone in landscape) the operator's actions stay pinned under the scrolling detail. */
private val PinActionsBelow: Dp = 420.dp

@Composable
fun DetailPane(vm: AppViewModel, case: Case?, entries: List<LogEntry>, now: Long, modifier: Modifier) {
    BoxWithConstraints(modifier.clip(PaneShape).background(AppTheme.colors.cell)) {
        if (case == null) {
            EmptyState(stringResource(Res.string.control_none_selected), stringResource(Res.string.control_none_selected_text), icon = Sym.Notes)
        } else {
            // a new incident starts at the top, with no dialog left open
            key(case.incidentId) { CaseDetail(vm, case, entries, now, pinActions = maxHeight < PinActionsBelow) }
        }
    }
}

/**
 * The chosen incident: category, place and age, its status on the console (and on the network when that differs),
 * the phrase to read on the radio, the operator's actions, the report and its history in the log.
 */
@Composable
private fun CaseDetail(vm: AppViewModel, case: Case, entries: List<LogEntry>, now: Long, pinActions: Boolean) {
    val c = vm.c
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val decisions = remember(c) { ConsoleActions(c) }
    var dialog by remember { mutableStateOf<Action?>(null) }
    val r = case.report
    val zoneName = if (r.zone.isEmpty()) "" else vm.venue.zoneName(r.zone)
    val phrase = radioPhrase(r, zoneName, vm.venue.landmark(r.landmark)?.name.orEmpty(), radioWords(r.people))
    val (hint, said) = Recognition.split(r.description)
    var copied by remember(phrase) { mutableStateOf(false) }
    val history = remember(entries, case.incidentId) { entries.filter { it.incidentId == case.incidentId } }
    val incident = incidents.staff.firstOrNull { it.incidentId == case.incidentId }
    val nickname = incident?.nickname
    val (_, statusIcon, statusColor) = caseLook(case)

    val onAction: (Action) -> Unit = { action ->
        if (action == Action.TAKE) {
            decisions.run(case, action)
            haptics.confirm()
        } else {
            dialog = action
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryCircle(r.category, case.urgent && case.state == CaseState.OPEN, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Labels.category(r.category)), style = MaterialTheme.typography.title3)
                    Text(
                        listOf(placeText(r.grid, r.landmark, r.accuracyM, vm.venue), zoneName, agoText(r.reportedAt, now), hopsText(r.hops))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                    )
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (case.urgent) StatusLabel(stringResource(Res.string.sev_urgent), icon = Sym.Priority, color = colors.redInk)
                StatusLabel(statusText(case), icon = statusIcon, color = statusColor)
            }
            if (case.localOnly) {
                Note(stringResource(Res.string.control_local_only, staffStatusText(case.network, case.networkTeam)), colors.orangeInk)
            }
            if (case.cancelRequested) Note(stringResource(Res.string.incident_cancel_requested), colors.orangeInk)
            if (Priority.isInformational(r.category)) Note(stringResource(Res.string.incident_informational), colors.secondaryLabel)
            MedicalPanel(vm, case, incident, history)
            incident?.let { ReportOptionNotes(it, Modifier.padding(top = 4.dp)) }

            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fill).padding(12.dp)) {
                Text(
                    stringResource(Res.string.control_radio_title), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold,
                    color = colors.secondaryLabel,
                )
                Text(phrase, style = MaterialTheme.typography.body, modifier = Modifier.padding(top = 4.dp))
                AppButton(
                    stringResource(if (copied) Res.string.code_sheet_copied else Res.string.msg_copy),
                    {
                        vm.phone.copyText(phrase)
                        copied = true
                        haptics.confirm()
                    },
                    Modifier.padding(top = 10.dp), kind = ButtonKind.Secondary, compact = true, icon = if (copied) Sym.Check else Sym.Copy,
                )
            }

            if (!pinActions) ActionButtons(case, Modifier.padding(top = 16.dp), onAction)

            ReplyPanel(vm, incident, r.landmark)
            val updates = incident?.updates.orEmpty()
            if (updates.isNotEmpty()) {
                Text(
                    stringResource(Res.string.incident_updates_title), style = MaterialTheme.typography.headline,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                UpdateTimeline(updates, vm.venue, c.grid)
            }

            if (hint.isNotEmpty()) {
                Text(
                    stringResource(Res.string.incident_recognition, hint), style = MaterialTheme.typography.body, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            Text(
                said.ifEmpty { stringResource(Res.string.incident_no_description) }, style = MaterialTheme.typography.body,
                color = if (said.isEmpty()) colors.secondaryLabel else colors.label,
                modifier = Modifier.padding(top = if (hint.isEmpty()) 20.dp else 4.dp),
            )
            Text(
                listOf(
                    stringResource(Res.string.incident_reported_at, Labels.clock(r.reportedAt)),
                    nickname?.let { stringResource(Res.string.incident_reporter, it) } ?: stringResource(Res.string.incident_anonymous),
                ).joinToString(" · "),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp),
            )
            reportFacts(r.responds, r.breathes, r.fromStaff, r.people, r.dangerContinues)?.let {
                Text(it, style = MaterialTheme.typography.subheadline, modifier = Modifier.padding(top = 6.dp))
            }

            Text(stringResource(Res.string.control_history), style = MaterialTheme.typography.headline, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            history.forEach { HistoryRow(it) }
        }
        if (pinActions) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.separator))
            ActionRow(case, Modifier.padding(vertical = 10.dp), onAction)
        }
    }

    when (val open = dialog) {
        null, Action.TAKE -> Unit
        Action.ASSIGN -> AssignDialog(
            case, teamChoices(entries, stringArrayResource(Res.array.control_roles).toList(), settings.teamName), { dialog = null },
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

/**
 * Quick answers to the reporter, predefined and sealed to the report's reply key, with what this console already
 * sent. "Go to a landmark" asks which one, starting from the one nearest to the reporter.
 */
@Composable
private fun ReplyPanel(vm: AppViewModel, incident: StaffIncident?, near: Int) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var picking by remember { mutableStateOf(false) }
    Text(stringResource(Res.string.control_respond_title), style = MaterialTheme.typography.headline, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    if (incident == null || incident.replyKey.isEmpty()) {
        Text(stringResource(Res.string.control_respond_unavailable), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        return
    }
    val failed = stringResource(Res.string.control_respond_failed)
    fun send(code: Int, landmark: Int = 0) {
        if (vm.respond(incident.incidentId, code, landmark)) {
            haptics.confirm()
        } else {
            haptics.reject()
            vm.phone.toast(failed, long = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wide = Modifier.fillMaxWidth()
        AppButton(stringResource(Res.string.control_respond_coming), { send(ResponseCode.COMING) }, wide, kind = ButtonKind.Secondary, compact = true)
        AppButton(stringResource(Res.string.control_respond_landmark), { picking = true }, wide, kind = ButtonKind.Secondary, compact = true)
        AppButton(stringResource(Res.string.control_respond_medical), { send(ResponseCode.GO_TO_MEDICAL) }, wide, kind = ButtonKind.Secondary, compact = true)
    }
    incident.responses.asReversed().forEach { r ->
        Text(
            stringResource(Res.string.control_respond_sent, Labels.clock(r.at), stringResource(Replies.response(r.code), Replies.landmarkName(vm.venue, r.landmark))),
            style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (picking) LandmarkDialog(vm.venue, near, { picking = false }) { send(ResponseCode.GO_TO_LANDMARK, it) }
}

/** The numbered landmarks of the site plan, to send the reporter to one. */
@Composable
private fun LandmarkDialog(venue: Venue, near: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val landmarks = remember(venue) { venue.pois.filter { venue.landmark(it.number) == it }.sortedBy { it.number } }
    var picked by remember { mutableIntStateOf(near.takeIf { n -> landmarks.any { it.number == n } } ?: 0) }
    AppDialog(
        onDismiss, stringResource(Res.string.control_landmark_title), stringResource(Res.string.control_respond_send),
        onConfirm = {
            onPick(picked)
            onDismiss()
        },
        confirmEnabled = picked != 0,
    ) {
        if (landmarks.isEmpty()) {
            Text(stringResource(Res.string.control_landmark_none), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            landmarks.forEach { poi ->
                Chip(Replies.landmarkName(venue, poi.number).let { if (it == poi.number.toString()) it else "${poi.number} · $it" }, selected = picked == poi.number) {
                    picked = poi.number
                }
            }
        }
    }
}

/** The operator's actions for the incident, two to a row; the first is the main one while the incident is not closed. */
@Composable
private fun ActionButtons(case: Case, modifier: Modifier, onAction: (Action) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions(case).chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { i, action ->
                    val primary = row == 0 && i == 0 && case.state != CaseState.CLOSED
                    AppButton(
                        stringResource(actionLabel(action, case)), { onAction(action) },
                        Modifier.weight(1f), kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary, compact = true,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The same actions pinned under a short pane, a phone's in landscape: one row that scrolls sideways, since rows of two
 * could take more than the pane has and push the last ones below its edge.
 */
@Composable
private fun ActionRow(case: Case, modifier: Modifier, onAction: (Action) -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions(case).forEachIndexed { i, action ->
            val primary = i == 0 && case.state != CaseState.CLOSED
            AppButton(
                stringResource(actionLabel(action, case)), { onAction(action) },
                kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary, compact = true,
            )
        }
    }
}

/** The status in a sentence, for the detail; the queue uses the short form of [caseLook]. */
@Composable
private fun statusText(case: Case): String = when (case.state) {
    CaseState.OPEN -> stringResource(if (case.reopened) Res.string.control_state_reopened else Res.string.control_state_open)
    CaseState.ASSIGNED -> when (case.network) {
        AckStatus.ON_SCENE, AckStatus.HANDED_OVER -> staffStatusText(case.network, case.team)
        else -> stringResource(Res.string.control_state_assigned, case.team)
    }
    CaseState.CLOSED -> when {
        case.unfounded -> stringResource(Res.string.control_state_unfounded)
        case.network == AckStatus.CANCELLED -> stringResource(Res.string.staff_status_cancelled)
        case.network == AckStatus.CLOSED_BY_CONTROL -> stringResource(Res.string.staff_status_closed)
        else -> stringResource(Res.string.staff_status_resolved, case.team)
    }
}

private fun actionLabel(action: Action, case: Case): StringResource = when (action) {
    Action.ASSIGN -> if (case.state == CaseState.OPEN) Res.string.control_assign else Res.string.control_reassign
    Action.TAKE -> Res.string.incident_take
    Action.CLOSE -> Res.string.control_close
    Action.UNFOUNDED -> Res.string.control_unfounded
    Action.CONFIRM_CANCEL -> Res.string.control_confirm_cancel
    Action.REOPEN -> Res.string.control_reopen
}

@Composable
private fun Note(text: String, color: Color) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Sym.Info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.footnote, color = color)
    }
}

/** The words of the radio phrase for a report about [people] people, in the app's language. */
@Composable
private fun radioWords(people: Int): RadioWords {
    val categories = IncidentCategory.all.associateWith { stringResource(Labels.category(it)) }
    val other = stringResource(Labels.category(IncidentCategory.OTHER))
    val answers = (Answer.UNASKED..Answer.UNKNOWN).associateWith { stringResource(Labels.answer(it)) }
    val triage = stringResource(Res.string.control_radio_triage)
    val near = stringResource(Res.string.place_near)
    val recognition = stringResource(Res.string.control_radio_recognition)
    // the phrase is for one report, so its count is the only one asked for
    val peopleText = pluralStringResource(Res.plurals.incident_people, people, people)
    return RadioWords(
        category = { categories[it] ?: other },
        urgent = stringResource(Res.string.control_radio_urgent),
        notUrgent = stringResource(Res.string.control_radio_not_urgent),
        unknownPlace = stringResource(Res.string.control_radio_no_place),
        triage = { responds, breathes -> triage.fill(answers[responds] ?: answers.getValue(Answer.UNASKED), answers[breathes] ?: answers.getValue(Answer.UNASKED)) },
        people = { peopleText },
        dangerContinues = stringResource(Res.string.incident_danger_continues),
        near = { near.fill(it) },
        recognition = { recognition.fill(it) },
    )
}

/** One log entry of the incident: the console's time, what happened and, below, the route, the signature or the reason. */
@Composable
private fun HistoryRow(e: LogEntry) {
    val colors = AppTheme.colors
    val title = when (e.event) {
        LogEvent.REPORT -> stringResource(Res.string.control_event_report)
        LogEvent.ACK -> stringResource(Res.string.control_event_ack, stringResource(ackWord(e.status)), e.team)
        LogEvent.ASSIGN -> stringResource(Res.string.control_event_assign, e.team)
        LogEvent.TAKE -> stringResource(Res.string.control_event_take)
        LogEvent.RESOLVE -> stringResource(Res.string.control_event_close)
        LogEvent.UNFOUNDED -> stringResource(Res.string.control_state_unfounded)
        LogEvent.REOPEN -> stringResource(Res.string.control_state_reopened)
        LogEvent.CANCEL_REQUEST -> stringResource(Res.string.incident_cancel_requested)
        LogEvent.CANCEL -> stringResource(Res.string.control_event_cancel)
        LogEvent.EXPORT, LogEvent.NOTICE, LogEvent.NOTICE_CANCEL -> return
    }
    val detail = when (e.event) {
        LogEvent.REPORT -> hopsText(e.hops)
        LogEvent.ACK -> listOf(
            if (e.own) stringResource(Res.string.control_signed_here) else hopsText(e.hops),
            stringResource(Res.string.control_signature, e.signature.take(8)),
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
    val colors = AppTheme.colors
    var picked by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    val team = other.trim().ifEmpty { picked }
    AppDialog(
        onDismiss, stringResource(Res.string.control_assign_title), stringResource(Res.string.control_assign),
        onConfirm = {
            onAssign(team, reason)
            onDismiss()
        },
        confirmEnabled = team.isNotEmpty(),
    ) {
        if (ackFor(Action.ASSIGN, case.network) == null) {
            Text(
                stringResource(Res.string.control_assign_taken, staffStatusText(case.network, case.networkTeam)),
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
            other, { other = it.truncateUtf8(Limits.TEAM_BYTES) }, stringResource(Res.string.control_assign_other),
            Modifier.padding(top = 10.dp),
        )
        InputField(reason, { reason = it.take(MAX_REASON) }, stringResource(Res.string.control_reason_optional), Modifier.padding(top = 8.dp))
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

/**
 * Closing asks for an optional reason; unfounded, reopen and confirming a cancel need one, since they stay in the log
 * as decisions.
 */
@Composable
private fun ReasonDialog(action: Action, onDismiss: () -> Unit, onConfirm: (reason: String) -> Unit) {
    val (title, text, confirm) = when (action) {
        Action.CLOSE -> Triple(Res.string.control_close_title, Res.string.control_close_text, Res.string.control_close)
        Action.UNFOUNDED -> Triple(Res.string.control_unfounded_title, Res.string.control_unfounded_text, Res.string.close)
        Action.CONFIRM_CANCEL -> Triple(Res.string.control_confirm_cancel_title, Res.string.control_confirm_cancel_text, Res.string.control_confirm_cancel)
        else -> Triple(Res.string.control_reopen_title, Res.string.control_reopen_text, Res.string.control_reopen)
    }
    val required = action != Action.CLOSE
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
            stringResource(if (required) Res.string.control_reason else Res.string.control_reason_optional), Modifier.padding(top = 12.dp),
        )
    }
}
