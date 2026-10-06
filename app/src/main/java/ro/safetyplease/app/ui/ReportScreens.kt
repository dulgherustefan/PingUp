package ro.safetyplease.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.safetyplease.app.R
import ro.safetyplease.app.data.MyReport
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentCategory
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.Severity

/** Categoriile care sunt urgente daca omul nu spune altfel. */
private fun urgentByDefault(category: Int): Boolean =
    category == IncidentCategory.MEDICAL || category == IncidentCategory.VIOLENCE ||
        category == IncidentCategory.FIRE || category == IncidentCategory.CROWD

/** Banda in care lista se estompeaza deasupra butoanelor care plutesc jos. */
private val FadeZone = 24.dp

fun categoryIcon(category: Int): ImageVector = when (category) {
    IncidentCategory.MEDICAL -> Sym.Medical
    IncidentCategory.VIOLENCE -> Sym.Fight
    IncidentCategory.LOST_PERSON -> Sym.PersonSearch
    IncidentCategory.HARASSMENT -> Sym.NoTouch
    IncidentCategory.CROWD -> Sym.Groups
    IncidentCategory.FIRE -> Sym.Fire
    else -> Sym.MoreHoriz
}

/** O categorie de raport: buton jos, iconita verde in stanga si numele; aleasa, ia culoarea de selectie, nu verdele butonului Trimite. */
@Composable
private fun CategoryChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val fill by animateColorAsState(if (selected) colors.selection else colors.cell, tween(Motion.QUICK), label = "chip")
    val ink = if (selected) colors.onSelection else colors.label
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(press, 0.97f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(fill)
            .selectable(selected, interactionSource = press, indication = LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) ink else colors.accent)
        Spacer(Modifier.width(10.dp))
        Text(
            label, style = MaterialTheme.typography.subheadline, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Cat ramane sus, in tab, raportul tau nerezolvat. */
private const val ACTIVE_REPORT_MS = 2 * 60 * 60_000L

/**
 * Tabul Raporteaza: raportul tau inca deschis, daca ai unul, apoi „Ce se intampla?” si o grila de categorii,
 * apoi detaliile optionale intr-un grup ca in setarile iOS.
 * Butonul de trimis pluteste deasupra barei de taburi si urca deasupra tastaturii cat scrii descrierea.
 */
@Composable
fun ReportScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val colors = AppTheme.colors

    var category by rememberSaveable { mutableIntStateOf(0) }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var urgentChosen by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }
    var anonymous by rememberSaveable { mutableStateOf(true) }
    var pickZone by remember { mutableStateOf(false) }
    val autoZone = position?.let { vm.venue.zoneAt(it.lat, it.lon) }
    // null cat timp zona urmeaza GPS-ul; odata aleasa de utilizator, un fix nou nu o mai schimba
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = picked ?: autoZone?.id ?: vm.manualZone
    val waitMs by produceState(vm.rateLimitWaitMs(), incidents.mine) {
        while (true) {
            value = vm.rateLimitWaitMs()
            if (value == 0L) break
            // pana se schimba minutul afisat
            delay(value % 60_000 + 1)
        }
    }
    val requestLocation = rememberLocationRequest(vm)
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val more by remember { derivedStateOf { scroll.canScrollForward } }
    var footer by remember { mutableStateOf(0.dp) }
    val clearance = LocalBottomClearance.current
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // tastatura acopera bara de taburi, deci butonul se aseaza deasupra ei
    val typing = keyboard > clearance
    val now = rememberNow()
    // raportul tau inca deschis sta deasupra formularului, ca sa vezi unde a ajuns fara sa-l cauti
    val active = incidents.mine.maxByOrNull { it.createdAt }?.takeIf { it.isOpen() && now - it.createdAt < ACTIVE_REPORT_MS }

    Box(Modifier.fillMaxSize()) {
        NavScreen(
            title = stringResource(R.string.tab_report),
            background = colors.grouped,
            scrolled = scrolled,
            leading = { MeButton(settings.nickname) { vm.open(Dest.Me) } },
            trailing = { backdrop ->
                if (incidents.mine.isNotEmpty()) {
                    GlassIconButton(Sym.History, stringResource(R.string.report_mine), { vm.open(Dest.MyReports) }, backdrop)
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize()
                    // cu tastatura deschisa lista se opreste deasupra butonului, ca descrierea sa ramana la vedere
                    .padding(bottom = if (typing) (footer - FadeZone).coerceAtLeast(0.dp) else 0.dp)
                    .verticalScroll(scroll)
                    .padding(top = padding.calculateTopPadding(), bottom = if (typing) FadeZone + 8.dp else footer + 8.dp),
            ) {
                if (active != null) {
                    SectionTitle(stringResource(R.string.report_active), top = 8.dp)
                    InsetGroup(Modifier.padding(bottom = 24.dp)) {
                        GroupRow(
                            stringResource(Labels.category(active.category)),
                            subtitle = reportSubtitle(vm, active) + " · " + stepText(active),
                            chevron = true,
                            onClick = { vm.open(Dest.ReportSent(active.incidentId)) },
                            leading = { CategoryCircle(active.category, active.severity == Severity.URGENT, 36.dp) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.report_title), style = MaterialTheme.typography.title2, color = colors.label,
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 14.dp),
                )
                // doua coloane de butoane joase, cu iconita in stanga: se citesc ca o lista de optiuni
                Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IncidentCategory.all.chunked(2).forEach { row ->
                        // butoanele dintr-un rand iau inaltimea celui cu eticheta pe doua randuri
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in row) {
                                CategoryChip(
                                    categoryIcon(c), stringResource(Labels.category(c)), selected = category == c,
                                    onClick = {
                                        category = if (category == c) 0 else c
                                        if (!urgentChosen) urgent = category != 0 && urgentByDefault(category)
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (category != 0) {
                    Text(
                        stringResource(Labels.categoryExample(category)).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
                    )
                }

                SectionTitle(stringResource(R.string.report_details))
                InsetGroup {
                    SwitchRow(
                        stringResource(R.string.report_urgent), urgent,
                        {
                            urgent = it
                            urgentChosen = true
                        },
                        subtitle = stringResource(R.string.report_urgent_label), icon = Sym.Priority,
                    )
                    GroupDivider()
                    GroupRow(
                        stringResource(R.string.report_zone),
                        subtitle = when {
                            zone.isNotEmpty() -> if (autoZone?.id == zone) stringResource(R.string.map_source_gps).replaceFirstChar { it.uppercase() } else null
                            // avem fix GPS, dar in afara zonelor: pozitia pleaca oricum cu raportul
                            position != null -> stringResource(R.string.report_zone_outside)
                            else -> stringResource(R.string.report_zone_manual)
                        },
                        value = if (zone.isNotEmpty()) vm.venue.zoneName(zone) else null,
                        icon = Sym.Place, chevron = true, onClick = { pickZone = true },
                    )
                    GroupDivider()
                    SwitchRow(
                        stringResource(R.string.report_anonymous), anonymous, { anonymous = it },
                        subtitle = stringResource(if (anonymous) R.string.report_anonymous_on else R.string.report_anonymous_off), icon = Sym.Hidden,
                    )
                }
                InputField(
                    description, { description = it.take(Limits.DESCRIPTION_CHARS) }, stringResource(R.string.report_description),
                    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp), maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supporting = "${description.length}/${Limits.DESCRIPTION_CHARS}", background = colors.cell,
                )
            }
        }

        FloatingFooter((if (typing) keyboard else clearance) + 8.dp, more, { footer = it }, Modifier.align(Alignment.BottomCenter)) {
            if (waitMs > 0) {
                StatusLabel(
                    stringResource(R.string.report_rate_limited, (waitMs / 60_000 + 1).toInt()), Modifier.padding(bottom = 8.dp),
                    icon = Sym.Info, color = colors.redInk,
                )
            }
            AppButton(
                stringResource(R.string.report_send),
                {
                    val id = vm.report(category, if (urgent) Severity.URGENT else Severity.MEDIUM, zone, description, anonymous)
                    if (id != null) {
                        if (autoZone == null) vm.manualZone = zone
                        haptics.confirm()
                        picked = null
                        category = 0
                        description = ""
                        urgentChosen = false
                        urgent = false
                        vm.open(Dest.ReportSent(id))
                    }
                },
                Modifier.fillMaxWidth(), enabled = category != 0 && waitMs == 0L,
            )
        }
    }

    if (pickZone) {
        var choice by remember { mutableStateOf(zone) }
        AppDialog(
            onDismiss = { pickZone = false },
            title = stringResource(R.string.report_zone_pick),
            confirmLabel = stringResource(R.string.save),
            onConfirm = {
                picked = choice
                pickZone = false
            },
        ) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                ZoneOption(stringResource(R.string.report_zone_none), choice.isEmpty()) { choice = "" }
                for (z in vm.venue.zones) {
                    GroupDivider(start = 4.dp)
                    ZoneOption(z.name, choice == z.id) { choice = z.id }
                }
            }
            if (position == null && !vm.c.location.hasPermission()) {
                TextLink(
                    stringResource(if (vm.locationBlocked) R.string.location_open_settings else R.string.report_use_location),
                    {
                        pickZone = false
                        requestLocation()
                    },
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                )
            }
        }
    }
}

/** O optiune din lista de zone: numele, cu bifa albastra in dreapta cand e aleasa. */
@Composable
private fun ZoneOption(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.body, color = colors.label, modifier = Modifier.weight(1f))
        if (selected) Icon(Sym.Check, null, Modifier.size(20.dp), tint = colors.accent)
    }
}

/**
 * Butoanele care plutesc jos, peste lista. Cand lista trece pe sub ele, fundalul se estompeaza in spatele lor,
 * ca marginea de derulare din iOS. [onHeight] primeste cat ocupa, cu estomparea si [bottom] cu tot.
 */
@Composable
private fun FloatingFooter(
    bottom: Dp,
    fade: Boolean,
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val edge by animateFloatAsState(if (fade) 1f else 0f, tween(Motion.QUICK), label = "footerEdge")
    Box(modifier.fillMaxWidth().onSizeChanged { onHeight(with(density) { it.height.toDp() }) }) {
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = edge }.background(
                Brush.verticalGradient(listOf(colors.grouped.copy(alpha = 0f), colors.grouped), endY = with(density) { FadeZone.toPx() }),
            ),
        )
        Column(
            Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = FadeZone, bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally, content = content,
        )
    }
}

/** Pasul la care a ajuns raportul, de la 0 (asteapta un telefon) la 4 (rezolvat). */
fun reportStep(report: MyReport): Int = when {
    report.status >= AckStatus.RESOLVED -> 4
    report.status >= AckStatus.ACKNOWLEDGED -> 3
    report.status >= AckStatus.RECEIVED -> 2
    report.sent -> 1
    else -> 0
}

/** Raportul inca asteapta ajutor: nici rezolvat, nici anulat de tine. */
private fun MyReport.isOpen() = status < AckStatus.RESOLVED && !cancelled

/** Pasul raportului intr-un rand de lista; o alerta anulata spune doar asta. */
@Composable
private fun stepText(report: MyReport): String = when {
    report.status == AckStatus.CANCELLED -> stringResource(R.string.report_cancelled)
    report.cancelled -> stringResource(R.string.report_cancelling)
    else -> stepLabels(report)[reportStep(report)]
}

@Composable
private fun stepLabels(report: MyReport): List<String> = listOf(
    stringResource(R.string.status_waiting_link),
    stringResource(R.string.status_sending),
    stringResource(R.string.status_received),
    if (report.status >= AckStatus.ACKNOWLEDGED && report.teamName.isNotEmpty()) stringResource(R.string.status_acknowledged, report.teamName)
    else stringResource(R.string.status_acknowledged_pending),
    stringResource(R.string.status_resolved),
)

/**
 * Drumul raportului, ca urmarirea unei comenzi pe iPhone: pasii facuti au bifa albastra, pasul curent un inel
 * albastru cu punct si ora, cei care urmeaza doar un inel gri; intre ei, o linie subtire.
 */
@Composable
fun ReportTimeline(report: MyReport, now: Long, modifier: Modifier = Modifier) {
    val current = reportStep(report)
    val labels = stepLabels(report)
    val colors = AppTheme.colors
    Column(modifier) {
        labels.forEachIndexed { index, label ->
            val done = index < current || current == labels.lastIndex
            val isCurrent = index == current && !done
            val last = index == labels.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // inelele au 20, cat cercul desenat in iconita de 24 cu bifa
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            done -> Icon(Sym.CheckCircle, null, Modifier.size(24.dp), tint = colors.accent)
                            isCurrent -> Box(Modifier.size(20.dp).border(2.dp, colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                            }
                            else -> Box(Modifier.size(20.dp).border(1.5.dp, colors.tertiaryLabel, CircleShape))
                        }
                    }
                    if (!last) Box(Modifier.width(1.5.dp).weight(1f).background(if (index < current) colors.accent else colors.separator))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 1.dp, bottom = if (last) 0.dp else 22.dp)) {
                    Text(
                        label, style = if (isCurrent) MaterialTheme.typography.headline else MaterialTheme.typography.body,
                        color = if (done || isCurrent) colors.label else colors.secondaryLabel,
                    )
                    if (isCurrent && report.updatedAt > 0) {
                        Text(agoText(report.updatedAt, now), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                    }
                }
            }
        }
    }
}

/** Cercul categoriei: rosu pal cu iconita rosie cand e urgent, gri cu iconita neagra in rest. */
@Composable
private fun CategoryCircle(category: Int, urgent: Boolean, size: Dp) {
    val colors = AppTheme.colors
    IconCircle(
        categoryIcon(category), if (urgent) colors.red.copy(alpha = 0.15f) else colors.fill,
        if (urgent) colors.red else colors.label, size,
    )
}

/** Antetul unui raport sau incident, ca antetul unui contact in Signal: cercul categoriei, numele si unde. Rosu cand e urgent. */
@Composable
fun IncidentHeader(category: Int, urgent: Boolean, subtitle: String, modifier: Modifier = Modifier, status: @Composable () -> Unit = {}) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CategoryCircle(category, urgent, 88.dp)
        Text(
            stringResource(Labels.category(category)), style = MaterialTheme.typography.title2, color = colors.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
        )
        Text(
            subtitle, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 2.dp),
        )
        // starea sta sub nume, ca in fisa unui contact din Signal, nu pierduta intre butoane si harta
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (urgent) StatusLabel(stringResource(R.string.sev_urgent), icon = Sym.Priority, color = colors.redInk)
            status()
        }
    }
}

@Composable
private fun reportSubtitle(vm: AppViewModel, report: MyReport): String =
    listOfNotNull(report.zone.takeIf { it.isNotEmpty() }?.let { vm.venue.zoneName(it) }, Labels.clock(report.createdAt)).joinToString(" · ")

/** Dupa trimitere: ce ai raportat, drumul raportului pana la staff si, jos, butonul de gata. */
@Composable
fun ReportSentScreen(vm: AppViewModel, incidentId: String) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val report = incidents.mine.firstOrNull { it.incidentId == incidentId }
    val now = rememberNow()
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val more by remember { derivedStateOf { scroll.canScrollForward } }
    var footer by remember { mutableStateOf(0.dp) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val haptics = rememberHaptics()
    if (report == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    Box(Modifier.fillMaxSize()) {
        NavScreen(
            title = stringResource(R.string.report_next_title),
            background = colors.grouped,
            scrolled = scrolled,
            leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = padding.calculateTopPadding(), bottom = footer + 8.dp)) {
                val withdrawn = report.cancelled || report.status == AckStatus.CANCELLED
                IncidentHeader(
                    report.category, report.severity == Severity.URGENT && !withdrawn, reportSubtitle(vm, report),
                    status = {
                        if (withdrawn) {
                            StatusLabel(stepText(report), icon = Sym.Close, color = colors.secondaryLabel)
                        }
                    },
                )
                Text(
                    stringResource(
                        when {
                            report.status == AckStatus.CANCELLED -> R.string.report_cancelled_text
                            report.cancelled -> R.string.report_cancelling_text
                            else -> R.string.report_next_text
                        },
                    ),
                    style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
                // drumul spre staff nu mai conteaza pentru o alerta anulata
                if (!withdrawn) {
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        ReportTimeline(report, now, Modifier.padding(horizontal = Gutter, vertical = 16.dp))
                    }
                }
            }
        }
        // TextLink are 44 de atins, deci loc destul sub el
        FloatingFooter(LocalBottomClearance.current, more, { footer = it }, Modifier.align(Alignment.BottomCenter)) {
            AppButton(stringResource(R.string.done), { vm.back() }, Modifier.fillMaxWidth())
            if (report.isOpen()) {
                TextLink(stringResource(R.string.report_cancel), { confirm = Confirm.CANCEL }, Modifier.padding(top = 4.dp), color = colors.redInk)
            } else {
                TextLink(stringResource(R.string.report_delete), { confirm = Confirm.DELETE }, Modifier.padding(top = 4.dp), color = colors.redInk)
            }
        }
    }

    when (confirm) {
        Confirm.CANCEL -> ConfirmDialog(
            stringResource(R.string.report_cancel_title), stringResource(R.string.report_cancel_text),
            onDismiss = { confirm = null }, confirmLabel = stringResource(R.string.report_cancel_confirm), destructive = true,
        ) {
            vm.cancelReport(report.incidentId)
            haptics.confirm()
        }
        Confirm.DELETE -> ConfirmDialog(
            stringResource(R.string.report_delete_title), stringResource(R.string.report_delete_text),
            onDismiss = { confirm = null }, confirmLabel = stringResource(R.string.delete), destructive = true,
        ) {
            vm.deleteReport(report.incidentId)
            vm.back()
        }
        null -> Unit
    }
}

private enum class Confirm { CANCEL, DELETE }

/** Rapoartele trimise, cele noi sus, intr-un grup: categoria, unde si cand, si pasul la care au ajuns. */
@Composable
fun MyReportsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val mine = incidents.mine.sortedByDescending { it.createdAt }
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    NavScreen(
        title = stringResource(R.string.report_mine),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        if (mine.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.report_mine_empty_title), stringResource(R.string.report_mine_empty_text), icon = Sym.Report)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
                item(key = "reports") {
                    InsetGroup(Modifier.padding(top = 8.dp)) {
                        mine.forEachIndexed { index, report ->
                            GroupRow(
                                stringResource(Labels.category(report.category)),
                                subtitle = reportSubtitle(vm, report) + " · " + stepText(report),
                                chevron = true,
                                onClick = { vm.open(Dest.ReportSent(report.incidentId)) },
                                leading = { CategoryCircle(report.category, report.severity == Severity.URGENT, 36.dp) },
                            )
                            if (index < mine.lastIndex) GroupDivider(start = 64.dp)
                        }
                    }
                }
            }
        }
    }
}
