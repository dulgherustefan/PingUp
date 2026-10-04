package ro.safetyplease.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
fun ReportScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val haptics = rememberHaptics()

    var category by rememberSaveable { mutableIntStateOf(0) }
    var details by rememberSaveable { mutableStateOf(false) }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var urgentChosen by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }
    var anonymous by rememberSaveable { mutableStateOf(true) }
    val autoZone = position?.let { vm.venue.zoneAt(it.lat, it.lon) }
    var zone by rememberSaveable(autoZone?.id) { mutableStateOf(autoZone?.id ?: vm.manualZone) }
    val waitMs = vm.rateLimitWaitMs()
    val requestLocation = rememberLocationRequest(vm)

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomClearance.current + 84.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.report_title), style = MaterialTheme.typography.headlineMedium, color = colors.text,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (incidents.mine.isNotEmpty()) TextAction(stringResource(R.string.report_mine), { vm.open(Dest.MyReports) })
            }
            for (c in IncidentCategory.all) {
                CategoryCard(c, selected = category == c) {
                    category = if (category == c) 0 else c
                    if (!urgentChosen) urgent = category != 0 && urgentByDefault(category)
                }
            }

            val chevron by animateFloatAsState(if (details) 180f else 0f, tween(Motion.QUICK, easing = Motion.Standard), label = "chevron")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { details = !details }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.report_details), style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
                Icon(AppIcons.ChevronDown, null, tint = colors.textSecondary, modifier = Modifier.size(22.dp).rotate(chevron))
            }
            AnimatedVisibility(
                details,
                enter = fadeIn(tween(Motion.STANDARD)) + expandVertically(tween(Motion.STANDARD, easing = Motion.Enter)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(Motion.QUICK, easing = Motion.Exit)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel(stringResource(R.string.report_urgent))
                        Spacer(Modifier.height(10.dp))
                        Segmented(
                            listOf(false to stringResource(R.string.no), true to stringResource(R.string.yes)), urgent,
                            {
                                urgent = it
                                urgentChosen = true
                            },
                            Modifier.fillMaxWidth(), selectedColor = { if (it) colors.danger else null },
                        )
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        SectionLabel(stringResource(R.string.report_zone))
                        Text(
                            when {
                                autoZone != null -> stringResource(R.string.report_zone_auto, autoZone.name)
                                // avem fix GPS, dar in afara zonelor: pozitia pleaca oricum cu raportul
                                position != null -> stringResource(R.string.report_zone_outside)
                                else -> stringResource(R.string.report_zone_manual)
                            },
                            style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (z in vm.venue.zones) {
                                ZoneChip(z.name, selected = zone == z.id) { zone = if (zone == z.id) "" else z.id }
                            }
                        }
                        if (position == null && !vm.c.location.hasPermission()) {
                            TextAction(stringResource(R.string.report_use_location), requestLocation)
                        }
                    }
                    Column {
                        PillTextField(
                            description, { description = it.take(Limits.DESCRIPTION_CHARS) }, stringResource(R.string.report_description),
                            Modifier.fillMaxWidth(), maxLines = 4,
                        )
                        Text(
                            "${description.length}/${Limits.DESCRIPTION_CHARS}", style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
                        )
                    }
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                SectionLabel(stringResource(R.string.report_anonymous))
                                Text(
                                    stringResource(if (anonymous) R.string.report_anonymous_on else R.string.report_anonymous_off),
                                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                                )
                            }
                            AppSwitch(anonymous) { anonymous = it }
                        }
                    }
                }
            }
            if (waitMs > 0) {
                Text(
                    stringResource(R.string.report_rate_limited, (waitMs / 60_000 + 1).toInt()),
                    style = MaterialTheme.typography.bodyMedium, color = colors.danger, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // butonul sta lipit jos, deasupra barei; fundalul se estompeaza sub el ca lista sa nu se bata cu textul
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background), endY = 60f))
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = LocalBottomClearance.current + 8.dp),
        ) {
            AppButton(
                stringResource(R.string.report_send),
                {
                    val id = vm.report(category, if (urgent) Severity.URGENT else Severity.MEDIUM, zone, description, anonymous)
                    if (id != null) {
                        if (autoZone == null) vm.manualZone = zone
                        haptics.confirm()
                        category = 0
                        details = false
                        description = ""
                        urgentChosen = false
                        vm.open(Dest.ReportSent(id))
                    }
                },
                Modifier.fillMaxWidth(), enabled = category != 0 && waitMs == 0L,
            )
        }
    }
}

@Composable
private fun CategoryCard(category: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, selected = selected, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (selected) colors.accentSoft else colors.cardHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.category(category), null, tint = if (selected) colors.accent else colors.text, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Labels.category(category)), style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(stringResource(Labels.categoryExample(category)), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    selected,
                    enter = scaleIn(tween(Motion.QUICK, easing = Motion.Enter), initialScale = 0.6f) + fadeIn(tween(Motion.QUICK)),
                    exit = scaleOut(tween(100), targetScale = 0.6f) + fadeOut(tween(100)),
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(38.dp).clip(CircleShape).background(if (selected) colors.accent else colors.cardHigh).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.onAccent else colors.text, maxLines = 1)
        }
    }
}

@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Switch(
        checked = checked, onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent, checkedTrackColor = colors.accent,
            uncheckedThumbColor = colors.textSecondary, uncheckedTrackColor = colors.cardHigh, uncheckedBorderColor = colors.textSecondary.copy(alpha = 0.5f),
        ),
    )
}

/** Pasul la care a ajuns raportul, de la 0 (asteapta un telefon) la 4 (rezolvat). */
fun reportStep(report: MyReport): Int = when {
    report.status >= AckStatus.RESOLVED -> 4
    report.status >= AckStatus.ACKNOWLEDGED -> 3
    report.status >= AckStatus.RECEIVED -> 2
    report.sent -> 1
    else -> 0
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

/** Drumul raportului, pas cu pas, cu starea scrisa. */
@Composable
fun ReportTimeline(report: MyReport, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val current = reportStep(report)
    val labels = stepLabels(report)
    val tone = if (current == 0) colors.wait else colors.ok
    Column(modifier) {
        labels.forEachIndexed { index, label ->
            val done = index < current
            val isCurrent = index == current
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        // cat raportul e inca pe drum, pasul curent respira usor
                        Modifier.size(28.dp).then(if (isCurrent && current < 2) Modifier.pulse() else Modifier).clip(CircleShape)
                            .then(
                                when {
                                    isCurrent -> Modifier.background(tone)
                                    done -> Modifier.background(colors.ok.copy(alpha = 0.18f))
                                    else -> Modifier.border(1.5.dp, colors.textSecondary.copy(alpha = 0.35f), CircleShape)
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isCurrent -> Icon(
                                if (current == 0) AppIcons.Clock else AppIcons.Check, null,
                                tint = if (colors.dark) Color(0xFF1A1F1C) else Color.White, modifier = Modifier.size(16.dp),
                            )
                            done -> Icon(AppIcons.Check, null, tint = colors.ok, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (index < labels.lastIndex) {
                        Box(
                            Modifier.padding(vertical = 3.dp).width(2.dp).weight(1f).heightIn(min = 14.dp).clip(CircleShape)
                                .background(if (done) colors.ok.copy(alpha = 0.5f) else colors.cardHigh),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.padding(top = 3.dp, bottom = if (index < labels.lastIndex) 14.dp else 0.dp)) {
                    Text(
                        label,
                        style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = if (done || isCurrent) colors.text else colors.textSecondary,
                    )
                    if (isCurrent && report.updatedAt > 0) {
                        Text(agoText(report.updatedAt), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun ReportSentScreen(vm: AppViewModel, incidentId: String) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val report = incidents.mine.firstOrNull { it.incidentId == incidentId }
    if (report == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    ScreenScaffold(
        title = stringResource(R.string.report_next_title), onBack = { vm.back() },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = LocalBottomClearance.current + 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppButton(stringResource(R.string.done), { vm.back() }, Modifier.fillMaxWidth())
                TextAction(stringResource(R.string.report_mine), {
                    vm.back()
                    vm.open(Dest.MyReports)
                })
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HopLine(Modifier.width(210.dp), dots = 6, dotSize = 11.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.report_next_text), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            AppCard(Modifier.fillMaxWidth().enter(delayMs = 60)) {
                ReportHeader(vm, report)
                Spacer(Modifier.height(18.dp))
                ReportTimeline(report)
            }
        }
    }
}

@Composable
private fun ReportHeader(vm: AppViewModel, report: MyReport) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.cardHigh), contentAlignment = Alignment.Center) {
            Icon(AppIcons.category(report.category), null, tint = colors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(Labels.category(report.category)), style = MaterialTheme.typography.titleMedium, color = colors.text)
            Text(
                listOfNotNull(report.zone.takeIf { it.isNotEmpty() }?.let { vm.venue.zoneName(it) }, Labels.clock(report.createdAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            )
        }
        if (report.severity == Severity.URGENT) StateLabel(AppIcons.Warning, stringResource(R.string.sev_urgent), colors.danger)
    }
}

@Composable
fun MyReportsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val mine = incidents.mine.sortedByDescending { it.createdAt }
    ScreenScaffold(title = stringResource(R.string.report_mine), onBack = { vm.back() }) { padding ->
        if (mine.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.report_mine_empty_title), stringResource(R.string.report_mine_empty_text))
            }
        } else LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(mine, key = { it.incidentId }) { report ->
                val step = reportStep(report)
                val tone = if (step == 0) colors.wait else colors.ok
                AppCard(Modifier.fillMaxWidth().animateItem(), onClick = { vm.open(Dest.ReportSent(report.incidentId)) }) {
                    ReportHeader(vm, report)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(5) { index ->
                            Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(if (index <= step) tone else colors.cardHigh))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    StateLabel(if (step == 0) AppIcons.Clock else AppIcons.CheckCircle, stepLabels(report)[step], tone)
                }
            }
        }
    }
}
