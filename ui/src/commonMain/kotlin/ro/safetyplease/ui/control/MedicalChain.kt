package ro.safetyplease.ui.control

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.DensityStep
import ro.safetyplease.core.control.HandOver
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.StaffDensity
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Signs
import ro.safetyplease.core.incidents.UrgentEscalation
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.densityWord
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.chain_cannot_move
import ro.safetyplease.ui.resources.chain_density
import ro.safetyplease.ui.resources.chain_handover
import ro.safetyplease.ui.resources.chain_man_down
import ro.safetyplease.ui.resources.chain_overdue
import ro.safetyplease.ui.resources.chain_top_priority
import ro.safetyplease.ui.resources.chain_unconscious
import ro.safetyplease.ui.resources.chain_waiting
import ro.safetyplease.ui.resources.density_row
import ro.safetyplease.ui.resources.density_section
import ro.safetyplease.ui.resources.density_section_empty
import ro.safetyplease.ui.resources.density_value
import ro.safetyplease.ui.resources.rule_confusion
import ro.safetyplease.ui.resources.rule_heavy_bleeding
import ro.safetyplease.ui.resources.rule_high_temperature
import ro.safetyplease.ui.resources.rule_hot_skin
import ro.safetyplease.ui.resources.rule_man_down
import ro.safetyplease.ui.resources.rule_not_breathing
import ro.safetyplease.ui.resources.rule_seizure
import ro.safetyplease.ui.resources.rule_unconscious
import ro.safetyplease.ui.resources.rule_unresponsive
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow

/** The words of a rule of the priority table; a rule this build doesn't name shows its id. */
fun ruleLabel(id: String): StringResource? = when (id) {
    "not_breathing" -> Res.string.rule_not_breathing
    "unconscious" -> Res.string.rule_unconscious
    "unresponsive" -> Res.string.rule_unresponsive
    "man_down" -> Res.string.rule_man_down
    "seizure" -> Res.string.rule_seizure
    "high_temperature" -> Res.string.rule_high_temperature
    "hot_skin" -> Res.string.rule_hot_skin
    "confusion" -> Res.string.rule_confusion
    "heavy_bleeding" -> Res.string.rule_heavy_bleeding
    else -> null
}

/** Top priority first, then the queue's own order. */
val topFirst: Comparator<Case> = compareByDescending { it.report.topRule.isNotEmpty() }

/** The console's two-minute deadline for a case: from opening it, or from the reopen the incident holds. */
fun caseDeadline(case: Case, incident: StaffIncident?): Long? = UrgentEscalation.consoleDeadline(
    case.urgent, case.state == CaseState.OPEN, case.report.at,
    incident?.takeIf { it.status == AckStatus.REOPENED }?.statusAt,
)

/** "1:05": minutes and seconds. */
fun clockText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "${s / 60}:${Labels.two(s % 60)}"
}

/** The queue row's running time for an urgent case nobody has yet; red once it passed two minutes. */
@Composable
fun UrgentTimer(case: Case, incident: StaffIncident?) {
    val deadline = caseDeadline(case, incident) ?: return
    val now = rememberNow(1_000)
    val colors = AppTheme.colors
    val overdue = now >= deadline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.Clock, null, Modifier.size(12.dp), tint = if (overdue) colors.redInk else colors.secondaryLabel)
        Spacer(Modifier.width(3.dp))
        Text(
            clockText(now - (deadline - UrgentEscalation.ACK_WITHIN_MS)), style = MaterialTheme.typography.footnote,
            fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal, color = if (overdue) colors.redInk else colors.secondaryLabel,
        )
    }
}

/**
 * What the medical chain adds to a case: the rule that made it top priority (on the console also from a temperature
 * noted on the card), the report's flags, the two-minute timer and the hand-over card.
 */
@Composable
fun MedicalPanel(vm: AppViewModel, case: Case, incident: StaffIncident?, history: List<LogEntry>) {
    val colors = AppTheme.colors
    val notes by vm.c.staffChain.handOver.state.collectAsStateWithLifecycle()
    var card by remember { mutableStateOf(false) }
    val r = case.report
    val noted = remember(notes, history, incident) {
        HandOver.card(incident, history, notes.notes, case.incidentId)
    }
    val rule = r.topRule.ifEmpty {
        incident?.let { vm.c.staffChain.priority.match(Signs.of(it, noted.temperatureC))?.id }.orEmpty()
    }
    val lines = buildList {
        if (rule.isNotEmpty()) add(stringResource(Res.string.chain_top_priority, ruleLabel(rule)?.let { stringResource(it) } ?: rule) to colors.redInk)
        if (r.unconscious) add(stringResource(Res.string.chain_unconscious) to colors.redInk)
        if (r.manDown) add(stringResource(Res.string.chain_man_down) to colors.redInk)
        if (r.cannotMove) add(stringResource(Res.string.chain_cannot_move) to colors.orangeInk)
        if (r.density > 0) add(stringResource(Res.string.chain_density, stringResource(densityWord(r.density)), r.density) to colors.orangeInk)
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        for ((text, color) in lines) {
            Text(text, style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(top = 2.dp))
        }
        caseDeadline(case, incident)?.let { deadline ->
            val now = rememberNow(1_000)
            val waited = clockText(now - (deadline - UrgentEscalation.ACK_WITHIN_MS))
            val overdue = now >= deadline
            Text(
                stringResource(if (overdue) Res.string.chain_overdue else Res.string.chain_waiting, waited),
                style = MaterialTheme.typography.footnote, color = if (overdue) colors.redInk else colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        AppButton(
            stringResource(Res.string.chain_handover), { card = true }, Modifier.padding(top = 10.dp),
            kind = ButtonKind.Secondary, compact = true, icon = Sym.Notes,
        )
    }
    if (card) HandOverDialog(vm, case.incidentId) { card = false }
}

/** The latest level of each zone staff reported, with its age: a quiet line each, above the queue. */
@Composable
fun DensityRows(vm: AppViewModel, now: Long) {
    val log by vm.c.staffChain.density.state.collectAsStateWithLifecycle()
    val latest = remember(log, now) {
        StaffDensity.latest(log.readings).values.filter { now - it.receivedAt < SHOWN_MS }.sortedByDescending { it.level }
    }
    val colors = AppTheme.colors
    for (reading in latest) {
        Text(
            stringResource(
                Res.string.density_row, vm.venue.zoneName(reading.zone), stringResource(densityWord(reading.level)), reading.level,
                agoText(reading.receivedAt, now),
            ),
            style = MaterialTheme.typography.footnote,
            color = if (reading.level >= StaffDensity.INCIDENT_LEVEL) colors.orangeInk else colors.label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        GroupDivider(start = 0.dp)
    }
}

/** A level older than this no longer says how the zone is now. */
const val SHOWN_MS = 60 * 60_000L

/** The edition report's density timeline: a line per change, zone by zone. */
suspend fun densitySection(steps: List<DensityStep>, venue: Venue, time: (Long) -> String): DocSection = DocSection(
    getString(Res.string.density_section),
    if (steps.isEmpty()) {
        listOf(DocLine(getString(Res.string.density_section_empty)))
    } else {
        steps.map { DocLine("${venue.zoneName(it.zone)} · ${time(it.at)}", getString(Res.string.density_value, getString(densityWord(it.level)), it.level)) }
    },
)
