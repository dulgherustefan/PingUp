package ro.safetyplease.ui.safety

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.card112_row_text
import ro.safetyplease.ui.resources.card112_title
import ro.safetyplease.ui.resources.checkin_alerts_row
import ro.safetyplease.ui.resources.checkin_row_running
import ro.safetyplease.ui.resources.checkin_row_text
import ro.safetyplease.ui.resources.checkin_title
import ro.safetyplease.ui.resources.incident_note_later
import ro.safetyplease.ui.resources.incident_note_no_reply
import ro.safetyplease.ui.resources.incident_note_witness
import ro.safetyplease.ui.resources.unwell_row_text
import ro.safetyplease.ui.resources.unwell_title
import ro.safetyplease.ui.text.Labels

/** On the report tab: the check-in on the way, "Nu sunt bine" and the 112 card, one tap from where a person looks for help. */
@Composable
fun SafetyEntries(vm: AppViewModel) {
    val data by vm.c.checkin.state.collectAsStateWithLifecycle()
    val running = data.current
    InsetGroup(Modifier.padding(top = 12.dp)) {
        GroupRow(
            stringResource(Res.string.checkin_title),
            subtitle = when {
                data.alerts.isNotEmpty() -> stringResource(Res.string.checkin_alerts_row)
                running != null -> stringResource(Res.string.checkin_row_running, running.label, Labels.clock(running.deadlineAt))
                else -> stringResource(Res.string.checkin_row_text)
            },
            icon = Sym.Clock, chevron = true, onClick = { vm.open(Dest.Safety(SafetyScreen.CHECKIN)) },
        )
        GroupDivider()
        GroupRow(
            stringResource(Res.string.unwell_title), subtitle = stringResource(Res.string.unwell_row_text),
            icon = Sym.Help, chevron = true, onClick = { vm.open(Dest.Safety(SafetyScreen.UNWELL)) },
        )
        GroupDivider()
        Card112Entry(vm)
    }
}

/** One row that opens the 112 card, under a report that is waiting for help. */
@Composable
fun Card112Row(vm: AppViewModel) {
    InsetGroup(Modifier.padding(top = 12.dp)) { Card112Entry(vm) }
}

@Composable
private fun Card112Entry(vm: AppViewModel) {
    GroupRow(
        stringResource(Res.string.card112_title), subtitle = stringResource(Res.string.card112_row_text),
        icon = Sym.Phone, chevron = true, onClick = { vm.open(Dest.Safety(SafetyScreen.CARD_112)) },
    )
}

/** What the reporter said besides the category, for the staff who read the report: earlier, for someone else, no reply. */
@Composable
fun ReportOptionNotes(incident: StaffIncident, modifier: Modifier = Modifier) {
    val notes = listOfNotNull(
        incident.happenedMinAgo.takeIf { it > 0 }?.let { stringResource(Res.string.incident_note_later, durationText(it)) },
        stringResource(Res.string.incident_note_witness).takeIf { incident.witness },
        stringResource(Res.string.incident_note_no_reply).takeIf { incident.noReply },
    )
    if (notes.isEmpty()) return
    Column(modifier.padding(vertical = 4.dp)) {
        notes.forEach { Text(it, style = MaterialTheme.typography.footnote, color = AppTheme.colors.orangeInk) }
    }
}
