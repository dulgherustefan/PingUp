package ro.safetyplease.ui.safety

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.safety.ReportOptions
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SwitchRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.report_options_later
import ro.safetyplease.ui.resources.report_options_later_at
import ro.safetyplease.ui.resources.report_options_later_text
import ro.safetyplease.ui.resources.report_options_no_reply
import ro.safetyplease.ui.resources.report_options_no_reply_note
import ro.safetyplease.ui.resources.report_options_no_reply_text
import ro.safetyplease.ui.resources.report_options_title
import ro.safetyplease.ui.resources.report_options_witness
import ro.safetyplease.ui.resources.report_options_witness_text
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.rememberNow

/**
 * What the person chose among the report's safety options: that it happened earlier ([laterMin], 0 for now), that the
 * report can't be answered ([noReply]) and that it happened to someone else ([witness]).
 */
class ReportOptionsState(later: Int = 0, noReply: Boolean = false, witness: Boolean = false) {
    var laterMin by mutableIntStateOf(later)
    var noReply by mutableStateOf(noReply)
    var witness by mutableStateOf(witness)

    /** Only what [category] offers: a choice made for another category and left behind goes nowhere. */
    fun extras(category: Int): ReportExtras = ReportOptions.extras(
        laterMin.takeIf { offersLaterAndNoReply(category) }, noReply && offersLaterAndNoReply(category), witness && offersWitness(category),
    )

    fun reset() {
        laterMin = 0
        noReply = false
        witness = false
    }

    companion object {
        val Saver: Saver<ReportOptionsState, Any> = listSaver(
            save = { listOf(it.laterMin, if (it.noReply) 1 else 0, if (it.witness) 1 else 0) },
            restore = { ReportOptionsState(it[0], it[1] == 1, it[2] == 1) },
        )
    }
}

/** Earlier and without a reply: for what is hard to say in the moment, not for what needs a team now. */
fun offersLaterAndNoReply(category: Int): Boolean =
    category == IncidentCategory.HARASSMENT || category == IncidentCategory.VIOLENCE || category == IncidentCategory.OTHER

/** For someone else: any report but a friend lost, which is always about oneself. */
fun offersWitness(category: Int): Boolean = category != 0 && category != IncidentCategory.LOST_FRIEND

@Composable
fun rememberReportOptions(): ReportOptionsState = rememberSaveable(saver = ReportOptionsState.Saver) { ReportOptionsState() }

/**
 * The safety options of a report: "S-a întâmplat mai devreme" (up to a day back), "Raportez pentru altcineva" and
 * "Anonim, fără răspuns", with a plain note that an anonymous report can't be answered. Shows nothing for a category
 * that offers none of them. [bleed] is for a screen that already pads its sides by the gutter, as the discreet report's.
 */
@Composable
fun ReportOptionsSection(state: ReportOptionsState, category: Int, bleed: Boolean = false) {
    val later = offersLaterAndNoReply(category)
    val witness = offersWitness(category)
    if (!later && !witness) return
    Column(if (bleed) Modifier.bleed(Gutter) else Modifier) { Options(state, later, witness) }
}

/** Wider than its parent by [by] on each side, to undo the parent's padding. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
private fun Options(state: ReportOptionsState, later: Boolean, witness: Boolean) {
    val colors = AppTheme.colors
    val now = rememberNow()
    SectionTitle(stringResource(Res.string.report_options_title))
    InsetGroup {
        if (later) {
            SwitchRow(
                stringResource(Res.string.report_options_later), state.laterMin > 0,
                { state.laterMin = if (it) ReportOptions.laterChoices.first() else 0 },
                subtitle = stringResource(Res.string.report_options_later_text), icon = Sym.History,
            )
            GroupDivider()
        }
        if (witness) {
            SwitchRow(
                stringResource(Res.string.report_options_witness), state.witness, { state.witness = it },
                subtitle = stringResource(Res.string.report_options_witness_text), icon = Sym.Group,
            )
        }
        if (later) {
            GroupDivider()
            SwitchRow(
                stringResource(Res.string.report_options_no_reply), state.noReply, { state.noReply = it },
                subtitle = stringResource(Res.string.report_options_no_reply_text), icon = Sym.Hidden,
            )
        }
    }
    if (later && state.laterMin > 0) {
        Column(Modifier.padding(top = 12.dp)) {
            Pills(Modifier.padding(horizontal = Gutter)) {
                ReportOptions.laterChoices.forEach { minutes -> Pill(durationText(minutes), state.laterMin == minutes, { state.laterMin = minutes }) }
            }
            Text(
                stringResource(Res.string.report_options_later_at, Labels.clock(now - state.laterMin * 60_000L)),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
            )
        }
    }
    if (later && state.noReply) {
        Text(
            stringResource(Res.string.report_options_no_reply_note), style = MaterialTheme.typography.footnote, color = colors.redInk,
            modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
        )
    }
}
