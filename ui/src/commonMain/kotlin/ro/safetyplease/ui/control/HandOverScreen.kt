package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.CardLine
import ro.safetyplease.core.control.CardStep
import ro.safetyplease.core.control.HandOver
import ro.safetyplease.core.control.HandOverCard
import ro.safetyplease.core.control.HandOverMark
import ro.safetyplease.core.control.HandOverNote
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.core.util.formatDecimal
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.common.QrCode
import ro.safetyplease.ui.common.exportStamp
import ro.safetyplease.ui.common.freshExportDir
import ro.safetyplease.ui.common.placeLine
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.ScreenScaffold
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.chain_unconscious
import ro.safetyplease.ui.resources.code_sheet_copied
import ro.safetyplease.ui.resources.delete
import ro.safetyplease.ui.resources.edition_footer
import ro.safetyplease.ui.resources.edition_generated
import ro.safetyplease.ui.resources.handover_add
import ro.safetyplease.ui.resources.handover_case
import ro.safetyplease.ui.resources.handover_coords
import ro.safetyplease.ui.resources.handover_local
import ro.safetyplease.ui.resources.handover_note_hint
import ro.safetyplease.ui.resources.handover_place
import ro.safetyplease.ui.resources.handover_prealert
import ro.safetyplease.ui.resources.handover_qr
import ro.safetyplease.ui.resources.handover_qr_cut
import ro.safetyplease.ui.resources.handover_qr_description
import ro.safetyplease.ui.resources.handover_qr_hide
import ro.safetyplease.ui.resources.handover_remove
import ro.safetyplease.ui.resources.handover_share
import ro.safetyplease.ui.resources.handover_timeline
import ro.safetyplease.ui.resources.handover_title
import ro.safetyplease.ui.resources.handover_to
import ro.safetyplease.ui.resources.handover_value
import ro.safetyplease.ui.resources.handover_zone
import ro.safetyplease.ui.resources.incident_triage
import ro.safetyplease.ui.resources.mark_ambulance
import ro.safetyplease.ui.resources.mark_at_post
import ro.safetyplease.ui.resources.mark_cooling
import ro.safetyplease.ui.resources.mark_found
import ro.safetyplease.ui.resources.mark_handed_to
import ro.safetyplease.ui.resources.mark_left
import ro.safetyplease.ui.resources.mark_note
import ro.safetyplease.ui.resources.mark_prealerted
import ro.safetyplease.ui.resources.mark_pulse
import ro.safetyplease.ui.resources.mark_score
import ro.safetyplease.ui.resources.mark_temperature
import ro.safetyplease.ui.resources.msg_copy
import ro.safetyplease.ui.resources.place_accuracy
import ro.safetyplease.ui.resources.place_near
import ro.safetyplease.ui.resources.prealert_ambulance
import ro.safetyplease.ui.resources.prealert_cooling
import ro.safetyplease.ui.resources.prealert_found
import ro.safetyplease.ui.resources.prealert_intro
import ro.safetyplease.ui.resources.prealert_left
import ro.safetyplease.ui.resources.prealert_no_cooling
import ro.safetyplease.ui.resources.prealert_pulse
import ro.safetyplease.ui.resources.prealert_reported
import ro.safetyplease.ui.resources.prealert_score
import ro.safetyplease.ui.resources.prealert_temperature
import ro.safetyplease.ui.resources.prealert_triage
import ro.safetyplease.ui.resources.save
import ro.safetyplease.ui.resources.step_assigned
import ro.safetyplease.ui.resources.step_closed
import ro.safetyplease.ui.resources.step_handed_over
import ro.safetyplease.ui.resources.step_on_scene
import ro.safetyplease.ui.resources.step_opened
import ro.safetyplease.ui.resources.step_received
import ro.safetyplease.ui.resources.step_reported
import ro.safetyplease.ui.resources.step_taken
import ro.safetyplease.ui.resources.unit_celsius
import ro.safetyplease.ui.resources.unit_pulse
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.fill
import ro.safetyplease.ui.text.rememberNow

fun markLabel(mark: HandOverMark): StringResource = when (mark) {
    HandOverMark.FOUND -> Res.string.mark_found
    HandOverMark.AT_POST -> Res.string.mark_at_post
    HandOverMark.TEMPERATURE -> Res.string.mark_temperature
    HandOverMark.PULSE -> Res.string.mark_pulse
    HandOverMark.SCORE -> Res.string.mark_score
    HandOverMark.COOLING_STARTED -> Res.string.mark_cooling
    HandOverMark.AMBULANCE_CALLED -> Res.string.mark_ambulance
    HandOverMark.HOSPITAL_PREALERTED -> Res.string.mark_prealerted
    HandOverMark.HANDED_TO -> Res.string.mark_handed_to
    HandOverMark.LEFT_SITE -> Res.string.mark_left
    HandOverMark.NOTE -> Res.string.mark_note
}

private fun stepLabel(step: CardStep): StringResource? = when (step) {
    CardStep.REPORTED -> Res.string.step_reported
    CardStep.OPENED -> Res.string.step_opened
    CardStep.RECEIVED -> Res.string.step_received
    CardStep.ASSIGNED -> Res.string.step_assigned
    CardStep.TAKEN -> Res.string.step_taken
    CardStep.ON_SCENE -> Res.string.step_on_scene
    CardStep.HANDED_OVER -> Res.string.step_handed_over
    CardStep.CLOSED -> Res.string.step_closed
    CardStep.MARK -> null
}

/** Marks that carry a value, and how it is typed. */
private fun valueKeyboard(mark: HandOverMark): KeyboardType? = when (mark) {
    HandOverMark.TEMPERATURE -> KeyboardType.Decimal
    HandOverMark.PULSE, HandOverMark.SCORE -> KeyboardType.Number
    HandOverMark.HANDED_TO, HandOverMark.NOTE -> KeyboardType.Text
    else -> null
}

/** A hand-over QR code holds this many bytes at most: a denser one is hard to read off a screen, and past 2953 none fits. */
const val QR_MAX_BYTES = 1_200

/** The card's text for the QR code, cut to [QR_MAX_BYTES]; true when it was cut. */
fun handOverQr(plain: String): Pair<String, Boolean> {
    val text = plain.truncateUtf8(QR_MAX_BYTES)
    return text to (text.length < plain.length)
}

/** The card in the app's language: the facts, the timeline and the pre-alert, as the screen, the QR and the PDF give them. */
class HandOverText(val facts: List<DocLine>, val timeline: List<DocLine>, val preAlert: String) {
    val plain: String
        get() = (facts.map { "${it.label}: ${it.value}" } + "" + timeline.map { "${it.value} ${it.label}" } + "" + preAlert).joinToString("\n")
}

private suspend fun lineText(line: CardLine): String {
    val mark = line.mark
    val label = if (mark != null) getString(markLabel(mark)) else stepLabel(line.step)?.let { getString(it) }.orEmpty()
    val value = when (mark) {
        HandOverMark.TEMPERATURE -> getString(Res.string.unit_celsius, line.value)
        HandOverMark.PULSE -> getString(Res.string.unit_pulse, line.value)
        else -> line.value
    }
    return listOf(label, line.team, value).filter { it.isNotEmpty() }.joinToString(" · ")
}

suspend fun handOverText(card: HandOverCard, incident: StaffIncident?, c: PingUpSession): HandOverText {
    val venue = c.venue
    val category = incident?.let { getString(Labels.category(it.category)) }.orEmpty()
    val facts = buildList {
        add(DocLine(getString(Res.string.handover_case, card.incidentId.take(8)), category))
        if (incident != null) {
            if (incident.zone.isNotEmpty()) add(DocLine(getString(Res.string.handover_zone), venue.zoneName(incident.zone)))
            val lat = incident.lat
            val lon = incident.lon
            val square = if (lat != null && lon != null) c.grid.cellAt(lat, lon).orEmpty() else ""
            val near = getString(Res.string.place_near)
            val accuracy = getString(Res.string.place_accuracy)
            val place = placeLine(square, venue.landmark(incident.landmark)?.name.orEmpty(), incident.accuracyM, { near.fill(it) }, { accuracy.fill(it) })
            if (place.isNotEmpty()) add(DocLine(getString(Res.string.handover_place), place))
            if (lat != null && lon != null) add(DocLine(getString(Res.string.handover_coords), "${formatDecimal(lat, 5)}, ${formatDecimal(lon, 5)}"))
            if (incident.responds != Answer.UNASKED || incident.breathes != Answer.UNASKED) {
                add(DocLine(getString(Res.string.incident_triage, getString(Labels.answer(incident.responds)), getString(Labels.answer(incident.breathes)))))
            }
            if (incident.unconscious) add(DocLine(getString(Res.string.chain_unconscious)))
        }
    }
    val timeline = card.lines.map { DocLine(lineText(it), Labels.clock(it.at)) }
    return HandOverText(facts, timeline, preAlert(card, incident, venue.name, category))
}

/** Short enough to read out: what the crew and the hospital need, the times of each step, nothing that names anyone. */
private suspend fun preAlert(card: HandOverCard, incident: StaffIncident?, venue: String, category: String): String {
    fun at(line: CardLine?) = line?.let { Labels.clock(it.at) }
    val parts = buildList {
        card.lines.firstOrNull { it.step == CardStep.REPORTED }?.let { add(getString(Res.string.prealert_reported, Labels.clock(it.at))) }
        if (incident != null && (incident.responds != Answer.UNASKED || incident.breathes != Answer.UNASKED)) {
            add(getString(Res.string.prealert_triage, getString(Labels.answer(incident.responds)), getString(Labels.answer(incident.breathes))))
        }
        at(card.last(HandOverMark.FOUND))?.let { add(getString(Res.string.prealert_found, it)) }
        card.last(HandOverMark.TEMPERATURE)?.let { add(getString(Res.string.prealert_temperature, it.value, Labels.clock(it.at))) }
        card.last(HandOverMark.PULSE)?.let { add(getString(Res.string.prealert_pulse, it.value, Labels.clock(it.at))) }
        card.last(HandOverMark.SCORE)?.let { add(getString(Res.string.prealert_score, it.value, Labels.clock(it.at))) }
        add(at(card.last(HandOverMark.COOLING_STARTED))?.let { getString(Res.string.prealert_cooling, it) } ?: getString(Res.string.prealert_no_cooling))
        at(card.last(HandOverMark.AMBULANCE_CALLED))?.let { add(getString(Res.string.prealert_ambulance, it)) }
        at(card.last(HandOverMark.LEFT_SITE))?.let { add(getString(Res.string.prealert_left, it)) }
    }
    return getString(Res.string.prealert_intro, venue, category) + ", " + parts.joinToString(", ") + "."
}

/** The card as a PDF in the export folder, then the share sheet: for the ambulance crew, by hand. */
private suspend fun shareHandOver(phone: PhoneUi, c: PingUpSession, text: HandOverText, now: Long) {
    val title = getString(Res.string.handover_title)
    val doc = ReportDoc(
        title,
        listOf(c.venue.name, getString(Res.string.edition_generated, Labels.clock(now))).filter { it.isNotEmpty() }.joinToString(" · "),
        listOf(
            DocSection(title, text.facts),
            DocSection(getString(Res.string.handover_timeline), text.timeline),
            DocSection(getString(Res.string.handover_prealert), listOf(DocLine(text.preAlert))),
        ),
    )
    val footer = getString(Res.string.edition_footer)
    val appName = phone.appName
    val file = withContext(Dispatchers.IO) {
        val dir = freshExportDir(c.exportDir)
        (dir / "${HandOver.PDF_PREFIX}${exportStamp(now)}.pdf").also { writeReportPdf(doc, { page -> footer.fill(appName, c.venue.name, page) }, it) }
    }
    phone.shareFiles(listOf(file))
}

/**
 * The hand-over card on the full screen: the facts, the pre-alert to read out, the timeline, one tap per step the team
 * notes, a QR code with the whole card as text and the PDF for the crew. It is health data: it stays on this phone.
 */
@Composable
fun HandOverDialog(vm: AppViewModel, incidentId: String, onDismiss: () -> Unit) {
    val c = vm.c
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val log by c.consoleLog.state.collectAsStateWithLifecycle()
    val stored by c.staffChain.handOver.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    // read through the book, so a note past 72 hours is never shown
    val notes = remember(stored, now) { c.staffChain.handOver.read(c.clock.wallMs()) }
    val incident = incidents.staff.firstOrNull { it.incidentId == incidentId }
    val entries = if (settings.role == Role.CONTROL) log.entries.filter { it.incidentId == incidentId } else emptyList()
    val card = remember(incident, entries, notes) { HandOver.card(incident, entries, notes, incidentId) }
    val text by produceState<HandOverText?>(null, card, incident) { value = handOverText(card, incident, c) }
    var adding by remember { mutableStateOf<HandOverMark?>(null) }
    var removing by remember { mutableStateOf<CardLine?>(null) }
    var qr by remember { mutableStateOf(false) }
    var copied by remember(text?.preAlert) { mutableStateOf(false) }

    fun note(mark: HandOverMark, value: String = "") {
        c.staffChain.handOver.note(incidentId, mark, c.clock.wallMs(), value)
        haptics.confirm()
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenScaffold(
            stringResource(Res.string.handover_title), onDismiss,
            actions = {
                AppButton(
                    stringResource(Res.string.handover_share),
                    { text?.let { t -> vm.viewModelScope.launch { shareHandOver(vm.phone, c, t, c.clock.wallMs()) } } },
                    kind = ButtonKind.Secondary, enabled = text != null, compact = true,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = Gutter)) {
                val t = text ?: return@Column
                for (fact in t.facts) {
                    Text(
                        if (fact.value.isEmpty()) fact.label else "${fact.label}: ${fact.value}",
                        style = MaterialTheme.typography.subheadline, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    stringResource(Res.string.handover_local), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    modifier = Modifier.padding(top = 6.dp),
                )

                SectionTitle(stringResource(Res.string.handover_prealert), top = 16.dp)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fill).padding(12.dp)) {
                    Text(t.preAlert, style = MaterialTheme.typography.body)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(
                            stringResource(if (copied) Res.string.code_sheet_copied else Res.string.msg_copy),
                            {
                                vm.phone.copyText(t.preAlert)
                                copied = true
                                haptics.confirm()
                            },
                            kind = ButtonKind.Secondary, compact = true, icon = if (copied) Sym.Check else Sym.Copy,
                        )
                        AppButton(
                            stringResource(if (qr) Res.string.handover_qr_hide else Res.string.handover_qr), { qr = !qr },
                            kind = ButtonKind.Secondary, compact = true, icon = Sym.QrScan,
                        )
                    }
                }
                if (qr) {
                    val (code, cut) = remember(t.plain) { handOverQr(t.plain) }
                    if (cut) {
                        Text(
                            stringResource(Res.string.handover_qr_cut), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    QrCode(
                        code, colors.label, stringResource(Res.string.handover_qr_description),
                        Modifier.padding(top = 12.dp).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                }

                SectionTitle(stringResource(Res.string.handover_add), top = 16.dp)
                HandOverMark.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mark in pair) {
                            AppButton(
                                stringResource(markLabel(mark)),
                                { if (valueKeyboard(mark) == null) note(mark) else adding = mark },
                                Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true,
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                SectionTitle(stringResource(Res.string.handover_timeline), top = 16.dp)
                card.lines.forEachIndexed { i, line ->
                    val row = t.timeline.getOrNull(i) ?: return@forEachIndexed
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (line.step == CardStep.MARK) Modifier.clickable { removing = line } else Modifier)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(row.value, style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                        Text(row.label, style = MaterialTheme.typography.subheadline, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    adding?.let { mark -> ValueDialog(mark, { adding = null }) { note(mark, it) } }
    removing?.let { line ->
        val mark = line.mark ?: return@let
        ConfirmDialog(
            stringResource(markLabel(mark)), stringResource(Res.string.handover_remove, Labels.clock(line.at)), { removing = null },
            confirmLabel = stringResource(Res.string.delete), destructive = true,
        ) { c.staffChain.handOver.remove(HandOverNote(incidentId, line.at, mark, line.value)) }
    }
}

@Composable
private fun ValueDialog(mark: HandOverMark, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val keyboard = valueKeyboard(mark) ?: KeyboardType.Text
    val label = when (mark) {
        HandOverMark.HANDED_TO -> Res.string.handover_to
        HandOverMark.NOTE -> Res.string.handover_note_hint
        else -> Res.string.handover_value
    }
    AppDialog(
        onDismiss, stringResource(markLabel(mark)), stringResource(Res.string.save),
        onConfirm = {
            onSave(value)
            onDismiss()
        },
        confirmEnabled = value.isNotBlank(),
    ) {
        InputField(
            value, { value = it.take(HandOver.MAX_VALUE_CHARS) }, stringResource(label), Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        )
    }
}
