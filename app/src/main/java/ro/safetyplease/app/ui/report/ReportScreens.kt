package ro.safetyplease.app.ui.report

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.safetyplease.app.Emergency
import ro.safetyplease.app.R
import ro.safetyplease.app.platform.torch.Torch
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.Replies
import ro.safetyplease.app.text.agoText
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.common.CategoryCircle
import ro.safetyplease.app.ui.common.IncidentHeader
import ro.safetyplease.app.ui.common.MapGlyph
import ro.safetyplease.app.ui.common.MeButton
import ro.safetyplease.app.ui.common.RadioProblemBanner
import ro.safetyplease.app.ui.common.categoryIcon
import ro.safetyplease.app.ui.common.helpOrigin
import ro.safetyplease.app.ui.common.placeLine
import ro.safetyplease.app.ui.common.routeText
import ro.safetyplease.app.ui.rememberLocationRequest
import ro.safetyplease.app.ui.rememberRadioGate
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.incidents.ESCALATE_AFTER_MS
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.incidents.escalationAt
import ro.safetyplease.core.incidents.needsEscalation
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.ResponseCode
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.StaffFields
import ro.safetyplease.core.protocol.Triage
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.ReportSpot
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppMenu
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.IconBtn
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.LocalBottomClearance
import ro.safetyplease.ui.designsystem.MenuRow
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.SwipeRow
import ro.safetyplease.ui.designsystem.SwitchRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.pressScale
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.isOpen
import ro.safetyplease.ui.pickedZoneAge

/** A lost child is always urgent and a lost friend never is, so neither offers the urgent switch. */
private fun hasFixedUrgency(category: Int): Boolean =
    category == IncidentCategory.LOST_CHILD || category == IncidentCategory.LOST_FRIEND

/** Band where the list fades out above the floating bottom buttons. */
private val FadeZone = 24.dp

/**
 * Report category button: low, green icon on the left, then the name. Selected, it takes the selection color,
 * not the green of the Send button.
 */
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
            color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two columns while every word of every label fits on one line of a button; otherwise one. */
internal fun categoryColumns(widestWordPx: Int, roomPerColumnPx: Int): Int = if (widestWordPx <= roomPerColumnPx) 2 else 1

/** What a category button takes besides its label: padding, icon and the space after it. */
private val ChipChrome = 12.dp * 2 + 20.dp + 10.dp
private val ChipGap = 8.dp

/** A triage question with three big answers; tapping the chosen one again takes the answer back. */
@Composable
private fun TriageQuestion(question: String, answer: Int, onAnswer: (Int) -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.padding(start = Gutter, end = Gutter, top = 12.dp)) {
        Text(question, style = MaterialTheme.typography.headline, color = colors.label, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in listOf(Answer.YES, Answer.NO, Answer.UNKNOWN)) {
                val selected = answer == option
                val fill by animateColorAsState(if (selected) colors.selection else colors.cell, tween(Motion.QUICK), label = "answer")
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).fillMaxHeight().pressScale(press, 0.97f).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                        .background(fill)
                        .selectable(selected, interactionSource = press, indication = LocalIndication.current, role = Role.RadioButton) {
                            onAnswer(if (selected) Answer.UNASKED else option)
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(Labels.answer(option)).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) colors.onSelection else colors.label, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** How many people a staff report is about: minus, the number (or "don't know" at 0), plus. */
@Composable
private fun PeopleRow(people: Int, onChange: (Int) -> Unit) {
    val colors = AppTheme.colors
    GroupRow(
        stringResource(R.string.report_staff_people), icon = Sym.Group,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Sym.Remove, stringResource(R.string.report_staff_fewer), { onChange((people - 1).coerceAtLeast(0)) }, tint = colors.accent)
                Text(
                    if (people == 0) stringResource(R.string.report_staff_people_unknown) else people.toString(),
                    style = MaterialTheme.typography.body, color = colors.label, textAlign = TextAlign.Center,
                    modifier = Modifier.width(64.dp),
                )
                IconBtn(Sym.Add, stringResource(R.string.report_staff_more), { onChange((people + 1).coerceAtMost(MAX_PEOPLE)) }, tint = colors.accent)
            }
        },
    )
}

/** The most a staff report can count: one byte on the wire. */
private const val MAX_PEOPLE = 255

/** How long your unresolved report stays pinned at the top of the tab. */
private const val ACTIVE_REPORT_MS = 2 * 60 * 60_000L

/**
 * Report tab: your still-open report if there is one, then "Ce se intampla?" and a grid of categories.
 * Details appear only after a category is picked, folded into one row that shows what's set (urgent, anonymous, zone),
 * so most reports take two taps; a medical one asks two triage questions on the same screen, and staff see the fields
 * of a staff report unfolded. The send button floats above the tab bar and rises above the keyboard while typing.
 */
@Composable
fun ReportScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, nearby.radio, onStartMesh)
    val position by vm.position.collectAsStateWithLifecycle()
    val spot by vm.spot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val colors = AppTheme.colors

    // the first fix can take a while, so GPS starts as soon as the screen shows; coming back from a permission dialog
    // or the settings, the location row checks again what it can offer
    var locationCheck by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        vm.phone.startLocation()
        locationCheck++
        onPauseOrDispose { }
    }

    var category by rememberSaveable { mutableIntStateOf(0) }
    var urgent by rememberSaveable { mutableStateOf(false) }
    var responds by rememberSaveable { mutableIntStateOf(Answer.UNASKED) }
    var breathes by rememberSaveable { mutableIntStateOf(Answer.UNASKED) }
    var people by rememberSaveable { mutableIntStateOf(0) }
    var danger by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }
    var recognition by rememberSaveable { mutableStateOf("") }
    var describing by remember { mutableStateOf(false) }
    var anonymous by rememberSaveable { mutableStateOf(true) }
    // details stay folded into one summary row; whoever has more to add expands it, and staff always see them
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val staff = settings.role.hasStaffKeys
    val triage = Triage(responds, breathes).takeIf { category == IncidentCategory.MEDICAL && (responds != Answer.UNASKED || breathes != Answer.UNASKED) }
    val severity = Priority.severity(category, urgent && !hasFixedUrgency(category), triage)
    var pickZone by remember { mutableStateOf(false) }
    val autoZone = position?.let { vm.venue.zoneAt(it.lat, it.lon) }
    // null while the zone follows GPS; once the user picks one, a new fix doesn't change it
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = picked ?: autoZone?.id ?: settings.manualZone
    val waitMs by produceState(vm.rateLimitWaitMs(), incidents.mine) {
        while (true) {
            value = vm.rateLimitWaitMs()
            if (value == 0L) break
            // until the displayed minute changes
            delay(value % 60_000 + 1)
        }
    }
    val requestLocation = rememberLocationRequest(vm) { locationCheck++ }
    val recognisable = !staff && category != 0 && category != IncidentCategory.LOST_FRIEND
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val more by remember { derivedStateOf { scroll.canScrollForward } }
    var footer by remember { mutableStateOf(0.dp) }
    val clearance = LocalBottomClearance.current
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    // the keyboard covers the tab bar, so the button sits above the keyboard
    val typing = keyboard > clearance
    val now = rememberNow()
    // a zone picked by hand a while ago says how old it is, so a stale one doesn't go out unnoticed
    val zoneAge = pickedZoneAge(settings, now)?.takeIf { picked == null && autoZone == null && zone == settings.manualZone }
        ?.let { agoText(settings.manualZoneAt, now) }
    // your still-open report sits above the form so you can see its progress without looking for it
    val active = incidents.mine.maxByOrNull { it.createdAt }?.takeIf { it.isOpen() && now - it.createdAt < ACTIVE_REPORT_MS }
    var activeMenu by remember { mutableStateOf(false) }
    var cancelId by remember { mutableStateOf<String?>(null) }

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
                    // with the keyboard open the list stops above the button so the description stays visible
                    .padding(bottom = if (typing) (footer - FadeZone).coerceAtLeast(0.dp) else 0.dp)
                    .verticalScroll(scroll)
                    .padding(top = padding.calculateTopPadding(), bottom = if (typing) FadeZone + 8.dp else footer + 8.dp),
            ) {
                // a report written now would only wait on this phone; say so before it's written
                RadioProblemBanner(gate, Modifier.padding(horizontal = Gutter, vertical = 8.dp), forReport = true)
                if (active != null) {
                    SectionTitle(stringResource(R.string.report_active), top = 8.dp)
                    InsetGroup(Modifier.padding(bottom = 24.dp)) {
                        Box {
                            GroupRow(
                                stringResource(Labels.category(active.category)),
                                subtitle = reportSubtitle(vm, active) + " · " + stepText(active, now),
                                chevron = true,
                                onClick = { vm.open(Dest.ReportSent(active.incidentId)) },
                                onLongClick = { activeMenu = true },
                                leading = { CategoryCircle(active.category, active.severity == Severity.URGENT, 36.dp) },
                            )
                            AppMenu(activeMenu, { activeMenu = false }) {
                                MenuRow(stringResource(R.string.report_cancel), {
                                    activeMenu = false
                                    cancelId = active.incidentId
                                }, icon = Sym.Close, danger = true)
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.report_title), style = MaterialTheme.typography.title2, color = colors.label,
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 14.dp),
                )
                // two columns of low buttons with the icon on the left: they read as a list of options.
                // With large text a word could break mid-way ("Aglomerat-ie"), so then it's one column.
                val labels = IncidentCategory.all.map { stringResource(Labels.category(it)) }
                val measurer = rememberTextMeasurer()
                val chipStyle = MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold)
                BoxWithConstraints(Modifier.padding(horizontal = Gutter)) {
                    val density = LocalDensity.current
                    val widest = remember(labels, chipStyle, density) {
                        labels.flatMap { it.split(' ') }.maxOf { measurer.measure(it, chipStyle).size.width }
                    }
                    val columns = categoryColumns(widest, with(density) { ((maxWidth - ChipGap) / 2 - ChipChrome).roundToPx() })
                    Column(verticalArrangement = Arrangement.spacedBy(ChipGap)) {
                        IncidentCategory.all.chunked(columns).forEach { row ->
                            // buttons in a row take the height of the one whose label wraps to two lines
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
                                for (c in row) {
                                    CategoryChip(
                                        categoryIcon(c), stringResource(Labels.category(c)), selected = category == c,
                                        onClick = {
                                            category = if (category == c) 0 else c
                                            // a report carries the exact location, so starting one is when to ask for it, once
                                            val ask = !vm.locationAskedForReport && !vm.locationBlocked && !vm.phone.locationAllowed()
                                            if (category != 0 && ask) {
                                                vm.locationAskedForReport = true
                                                requestLocation()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (row.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (vm.venue.safeguarding) {
                    InsetGroup(Modifier.padding(top = 12.dp)) {
                        GroupRow(
                            stringResource(R.string.discreet_title), subtitle = stringResource(R.string.discreet_row_text),
                            icon = Sym.Hidden, chevron = true, onClick = { vm.open(Dest.Discreet) },
                        )
                    }
                }
                if (category != 0) {
                    Text(
                        stringResource(Labels.categoryExample(category)).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
                    )
                }
                // the questions sit on the screen with Send, so a medical report stays two or three taps
                if (category == IncidentCategory.MEDICAL) {
                    SectionTitle(stringResource(R.string.report_triage_title), top = 20.dp)
                    TriageQuestion(stringResource(R.string.report_triage_responds), responds) { responds = it }
                    TriageQuestion(stringResource(R.string.report_triage_breathes), breathes) { breathes = it }
                    Text(
                        stringResource(R.string.report_triage_note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp),
                    )
                }
                if (category == IncidentCategory.LOST_FRIEND) {
                    Banner(
                        stringResource(R.string.report_help_meeting), stringResource(R.string.report_lost_friend_note),
                        Modifier.padding(start = Gutter, end = Gutter, top = 16.dp),
                        action = stringResource(R.string.map_meeting_point), onAction = { vm.open(Dest.Map(spot = Venue.MEETING)) },
                    )
                }
                if (category == IncidentCategory.LOST_CHILD) {
                    Banner(
                        stringResource(R.string.sev_urgent), stringResource(R.string.report_lost_child_note),
                        Modifier.padding(start = Gutter, end = Gutter, top = 16.dp), warning = true,
                    )
                }
                if (category != 0) {
                    InsetGroup(Modifier.padding(top = 20.dp)) { LocationRow(vm, spot, locationCheck, requestLocation) }
                    Text(
                        stringResource(R.string.report_location_note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                        modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
                    )
                }
                if (recognisable) {
                    InputField(
                        recognition, { recognition = it.take(Recognition.MAX_CHARS) }, stringResource(R.string.report_recognition),
                        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 16.dp).onFocusChanged { describing = it.hasFocus },
                        supporting = stringResource(R.string.report_recognition_hint), background = colors.cell,
                    )
                }

                if (category != 0 && !showDetails && !staff) {
                    val summary = listOf(
                        stringResource(Labels.urgency(severity)),
                        stringResource(if (anonymous) R.string.report_anonymous else R.string.report_named),
                        when {
                            zone.isEmpty() -> stringResource(R.string.report_zone_none)
                            zoneAge != null -> stringResource(R.string.report_zone_with_age, vm.venue.zoneName(zone), zoneAge)
                            else -> vm.venue.zoneName(zone)
                        },
                    ).joinToString(" · ")
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        GroupRow(
                            stringResource(R.string.report_add_details), subtitle = summary, icon = Sym.Notes,
                            chevron = true, onClick = { showDetails = true },
                        )
                    }
                }
                if (category != 0 && (showDetails || staff)) {
                    if (staff) {
                        SectionTitle(stringResource(R.string.report_staff_title))
                        InsetGroup {
                            PeopleRow(people) { people = it }
                            GroupDivider()
                            SwitchRow(
                                stringResource(R.string.report_staff_danger), danger, { danger = it },
                                subtitle = stringResource(R.string.report_staff_danger_text), icon = Sym.Priority,
                            )
                        }
                        Text(
                            stringResource(R.string.report_staff_team, settings.teamName), style = MaterialTheme.typography.footnote,
                            color = colors.secondaryLabel, modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
                        )
                    }
                    SectionTitle(stringResource(R.string.report_details))
                    InsetGroup {
                        // a "no" to a triage question already made it urgent
                        if (!hasFixedUrgency(category) && triage?.critical != true) {
                            SwitchRow(
                                stringResource(R.string.report_urgent), urgent, { urgent = it },
                                subtitle = stringResource(R.string.report_urgent_label), icon = Sym.Priority,
                            )
                            GroupDivider()
                        }
                        GroupRow(
                            stringResource(R.string.report_zone),
                            subtitle = when {
                                zone.isNotEmpty() && autoZone?.id == zone -> stringResource(R.string.map_source_gps).replaceFirstChar { it.uppercase() }
                                zone.isNotEmpty() -> zoneAge?.let { stringResource(R.string.report_zone_picked_ago, it) }
                                // we have a GPS fix outside every zone: the position is still sent with the report
                                position != null -> stringResource(R.string.report_zone_outside)
                                else -> stringResource(R.string.report_zone_manual)
                            },
                            value = if (zone.isNotEmpty()) vm.venue.zoneName(zone) else null,
                            icon = Sym.Place, chevron = true, onClick = { pickZone = true },
                        )
                        // staff reports go in the team's name
                        if (!staff) {
                            GroupDivider()
                            SwitchRow(
                                stringResource(R.string.report_anonymous), anonymous, { anonymous = it },
                                subtitle = stringResource(if (anonymous) R.string.report_anonymous_on else R.string.report_anonymous_off), icon = Sym.Hidden,
                            )
                        }
                    }
                    InputField(
                        description, { description = it.take(Limits.DESCRIPTION_CHARS) },
                        stringResource(if (staff) R.string.report_description_staff else R.string.report_description),
                        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp)
                            .onFocusChanged { describing = it.hasFocus },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        supporting = "${description.length}/${Limits.DESCRIPTION_CHARS}", background = colors.cell,
                    )
                }
            }
        }

        FloatingFooter((if (typing) keyboard else clearance) + 8.dp, more, { footer = it }, Modifier.align(Alignment.BottomCenter)) {
            // the app doesn't replace 112, so calling it stays one tap away while reporting, except while typing the description
            if (!(describing && typing)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.report_not_112), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    Call112Button(compact = true)
                }
            }
            if (waitMs > 0) {
                StatusLabel(
                    pluralStringResource(R.plurals.report_rate_limited, vm.rateLimitMax(), vm.rateLimitMax(), (waitMs / 60_000 + 1).toInt()),
                    Modifier.padding(bottom = 8.dp),
                    icon = Sym.Info, color = colors.redInk,
                )
            }
            AppButton(
                stringResource(R.string.report_send),
                {
                    val fields = if (staff) StaffFields(people, danger) else null
                    val id = vm.report(category, severity, zone, description, anonymous, triage, fields, if (recognisable) recognition else "")
                    if (id != null) {
                        if (autoZone == null) vm.setManualZone(zone)
                        haptics.confirm()
                        picked = null
                        category = 0
                        description = ""
                        recognition = ""
                        urgent = false
                        responds = Answer.UNASKED
                        breathes = Answer.UNASKED
                        people = 0
                        danger = false
                        showDetails = false
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
            if (position == null && !vm.phone.locationAllowed()) {
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
    cancelId?.let { CancelReportDialog(vm, it) { cancelId = null } }
}

/** A zone option: the name, with a check on the right when selected. */
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
 * What the report will say of where you are: the zone, the nearest landmark and how precise the fix is; else why it
 * can't say, and a tap that fixes it. [check] changes when the permission or the phone's setting may have changed.
 */
@Composable
private fun LocationRow(vm: AppViewModel, spot: ReportSpot?, check: Int, onAllow: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val permitted = remember(check) { vm.phone.locationAllowed() }
    val gpsOn = remember(check) { vm.phone.locationOn() }
    val row: Pair<String, (() -> Unit)?> = when {
        spot != null -> {
            val zone = vm.venue.zoneAt(spot.point.lat, spot.point.lon)?.name.orEmpty()
            val landmark = spot.landmark?.name.orEmpty()
            val where = zone.ifEmpty { if (landmark.isEmpty()) res.getString(R.string.report_location_gps) else "" }
            val line = placeLine(
                where, landmark, spot.accuracyM, { res.getString(R.string.place_near, it) }, { res.getString(R.string.place_accuracy, it) },
            )
            line.replaceFirstChar { it.uppercase() } to null
        }
        !permitted -> stringResource(if (vm.locationBlocked) R.string.report_location_blocked else R.string.report_location_missing) to onAllow
        !gpsOn -> stringResource(R.string.report_location_gps_off) to { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        else -> stringResource(R.string.report_location_searching) to null
    }
    val (subtitle, onClick) = row
    GroupRow(
        stringResource(R.string.report_location), subtitle = subtitle, icon = Sym.MyLocation, chevron = onClick != null, onClick = onClick,
    )
}

/**
 * Buttons floating at the bottom, over the list. When the list scrolls under them the background fades behind them.
 * [onHeight] receives the space they take, fade and [bottom] included.
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

/**
 * Report step in a list row; a cancelled alert just says so, or that staff checks the cancel; an urgent one nobody
 * confirmed in time says that.
 */
@Composable
private fun stepText(report: MyReport, now: Long): String = when {
    report.status == AckStatus.CANCELLED -> stringResource(R.string.report_cancelled)
    report.cancelled && !AckStatus.isClosed(report.status) ->
        stringResource(if (IncidentManager.cancelIsChecked(report)) R.string.report_cancel_checked else R.string.report_cancelling)
    report.needsEscalation(now) -> stringResource(R.string.report_escalate_title)
    else -> stepName(reportStep(report), report)
}

@Composable
private fun stepName(step: Int, report: MyReport): String = stepLabel(step, report).let { label ->
    if (label == R.string.status_acknowledged) stringResource(label, report.teamName) else stringResource(label)
}

/** Opens the dialer with 112 filled in; the person places the call. */
@Composable
private fun Call112Button(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    AppButton(
        stringResource(R.string.emergency_call), { Emergency.dial(context) }, modifier, ButtonKind.Secondary,
        compact = compact, icon = Sym.Phone,
    )
}

/**
 * Report timeline, like order tracking: done steps get a check, the current one a ring with a dot and the time,
 * upcoming ones a gray ring; a thin line between them.
 */
@Composable
fun ReportTimeline(report: MyReport, now: Long, modifier: Modifier = Modifier) {
    val current = reportStep(report)
    val labels = (0..4).map { stepName(it, report) }
    val colors = AppTheme.colors
    Column(modifier) {
        labels.forEachIndexed { index, label ->
            val done = index < current || current == labels.lastIndex
            val isCurrent = index == current && !done
            val last = index == labels.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // rings are 20dp, the size of the circle inside the 24dp check icon
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
                    if (isCurrent) {
                        val detail = listOfNotNull(
                            stepNote(index)?.let { stringResource(it) },
                            if (report.updatedAt > 0) agoText(report.updatedAt, now) else null,
                        )
                        if (detail.isNotEmpty()) {
                            Text(detail.joinToString(" · "), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun reportSubtitle(vm: AppViewModel, report: MyReport): String =
    listOfNotNull(report.zone.takeIf { it.isNotEmpty() }?.let { vm.venue.zoneName(it) }, Labels.clock(report.createdAt)).joinToString(" · ")

/**
 * After sending: what you reported, the report's path to staff and, at the bottom, 112 and the Done button.
 * While no phone has taken the report, or an urgent one has gone unconfirmed too long, it says what to do meanwhile.
 */
@Composable
fun ReportSentScreen(vm: AppViewModel, incidentId: String, onStartMesh: () -> Unit) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, nearby.radio, onStartMesh)
    val position by vm.position.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val report = incidents.mine.firstOrNull { it.incidentId == incidentId }
    val now = rememberNow()
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val more by remember { derivedStateOf { scroll.canScrollForward } }
    var footer by remember { mutableStateOf(0.dp) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    if (report == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val escalated = rememberEscalated(report)
    val haptics = rememberHaptics()
    var heard by rememberSaveable(incidentId) { mutableIntStateOf(report.responses.size) }
    LaunchedEffect(report.responses.size) {
        // the notification sounds; on this screen the phone also gives a nudge
        if (report.responses.size > heard) haptics.confirm()
        heard = report.responses.size
    }
    Box(Modifier.fillMaxSize()) {
        NavScreen(
            title = stringResource(R.string.report_next_title),
            background = colors.grouped,
            scrolled = scrolled,
            leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = padding.calculateTopPadding(), bottom = footer + 8.dp)) {
                // where staff checks the cancel, help may still come, so the path to staff stays on screen
                val checking = report.cancelled && IncidentManager.cancelIsChecked(report) && !AckStatus.isClosed(report.status)
                val withdrawn = report.status == AckStatus.CANCELLED || (report.cancelled && !checking)
                val waiting = reportStep(report) == 0 && !withdrawn
                val lostFriend = report.category == IncidentCategory.LOST_FRIEND
                IncidentHeader(
                    report.category, report.severity == Severity.URGENT && !withdrawn, reportSubtitle(vm, report),
                    status = {
                        if (withdrawn || checking) {
                            StatusLabel(stepText(report, now), icon = if (checking) Sym.Info else Sym.Close, color = colors.secondaryLabel)
                        }
                    },
                )
                Text(
                    stringResource(
                        when {
                            report.status == AckStatus.CANCELLED -> R.string.report_cancelled_text
                            checking -> R.string.report_cancel_checked_text
                            report.cancelled && !AckStatus.isClosed(report.status) -> R.string.report_cancelling_text
                            waiting -> R.string.report_waiting_text
                            lostFriend -> R.string.report_lost_friend_sent_text
                            else -> R.string.report_next_text
                        },
                    ),
                    style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
                // "it leaves on its own" holds only while the radio can send
                if (waiting) RadioProblemBanner(gate, Modifier.padding(start = Gutter, end = Gutter, top = 20.dp), forReport = true)
                if (report.responses.isNotEmpty()) Responses(vm, report)
                if (escalated) {
                    Banner(
                        stringResource(R.string.report_escalate_title), stringResource(R.string.report_escalate_text),
                        Modifier.padding(start = Gutter, end = Gutter, top = 20.dp), warning = true,
                    )
                }
                val stayPut = staysPut(report, escalated)
                if (stayPut) StayPutBanner(Modifier.padding(start = Gutter, end = Gutter, top = 20.dp))
                if (lostFriend && !withdrawn) {
                    MeetingSteps(vm, helpOrigin(position, report, settings.manualZone, vm.venue))
                } else if (waiting || escalated) {
                    // staying put contradicts the escalation, which sends you to a steward; the banner may say it already
                    HelpSteps(vm, helpOrigin(position, report, settings.manualZone, vm.venue), stay = !escalated && !stayPut)
                }
                if (IncidentManager.canUpdate(report)) UpdateSteps(vm, report)
                // the path to staff no longer matters for a cancelled alert
                if (!withdrawn) {
                    InsetGroup(Modifier.padding(top = 20.dp)) {
                        ReportTimeline(report, now, Modifier.padding(horizontal = Gutter, vertical = 16.dp))
                    }
                }
            }
        }
        // TextLink has a 48dp touch target, so there's enough room under it
        FloatingFooter(LocalBottomClearance.current, more, { footer = it }, Modifier.align(Alignment.BottomCenter)) {
            // the call button keeps its full label; Done takes the rest of the row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Call112Button()
                AppButton(stringResource(R.string.done), { vm.back() }, Modifier.weight(1f))
            }
            if (report.isOpen()) {
                TextLink(stringResource(R.string.report_cancel), { confirm = Confirm.CANCEL }, Modifier.padding(top = 4.dp), color = colors.redInk)
            } else {
                TextLink(stringResource(R.string.report_delete), { confirm = Confirm.DELETE }, Modifier.padding(top = 4.dp), color = colors.redInk)
            }
        }
    }

    when (confirm) {
        Confirm.CANCEL -> CancelReportDialog(vm, report.incidentId) { confirm = null }
        // without the report this screen pops itself; another back() would also pop the screen below
        Confirm.DELETE -> DeleteReportDialog(vm, report.incidentId) { confirm = null }
        null -> Unit
    }
}

private enum class Confirm { CANCEL, DELETE }

/** True from the moment an urgent report has waited [ESCALATE_AFTER_MS] for a person on staff, until one confirms it. */
@Composable
private fun rememberEscalated(report: MyReport): Boolean {
    val at = escalationAt(report)
    val escalated by produceState(report.needsEscalation(System.currentTimeMillis()), at) {
        value = at != null && System.currentTimeMillis() >= at
        if (at != null && !value) {
            delay(at - System.currentTimeMillis())
            value = true
        }
    }
    return escalated
}

/** Stay where the team will look for you, with the phone's torch to be seen; the torch goes out with the banner. */
@Composable
private fun StayPutBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val torch = remember(context) { Torch(context.applicationContext) }
    val lit by torch.on.collectAsStateWithLifecycle()
    DisposableEffect(torch) {
        torch.watch()
        onDispose { torch.release() }
    }
    Banner(
        stringResource(R.string.report_stay_title), stringResource(R.string.report_stay_text), modifier,
        action = if (torch.available) stringResource(if (lit) R.string.torch_off else R.string.torch_on) else null,
        onAction = { if (!torch.set(!lit)) Toast.makeText(context, R.string.torch_failed, Toast.LENGTH_LONG).show() },
    )
}

/**
 * What to do meanwhile: walk to the nearest medical point (with distance and direction when we know where you are)
 * or, if [stay], stay put and ask the people around; the first-aid sheets, the nearest defibrillator and 112.
 */
@Composable
private fun HelpSteps(vm: AppViewModel, origin: GeoPoint?, stay: Boolean) {
    val context = LocalContext.current
    val medical = vm.venue.nearest(MEDICAL, origin)
    SectionTitle(stringResource(R.string.report_help_title))
    InsetGroup {
        if (medical != null) {
            GroupRow(
                stringResource(R.string.report_help_medical),
                subtitle = routeText(origin, medical.point),
                icon = Sym.Walk, chevron = true, onClick = { vm.open(Dest.Map(spot = MEDICAL)) },
            )
        } else {
            GroupRow(stringResource(R.string.report_help_steward), icon = Sym.Walk)
        }
        if (stay) {
            GroupDivider()
            GroupRow(stringResource(R.string.report_help_stay), subtitle = stringResource(R.string.report_help_stay_text), icon = Sym.Place)
        }
        GroupDivider()
        GroupRow(
            stringResource(R.string.first_aid_title), subtitle = stringResource(R.string.report_help_first_aid_text), icon = Sym.Medical,
            chevron = true, onClick = { vm.open(Dest.FirstAid) },
        )
        vm.venue.nearest(Venue.DEA, origin)?.let { dea ->
            GroupDivider()
            GroupRow(
                stringResource(R.string.nearest_dea), subtitle = routeText(origin, dea.point), icon = MapGlyph.Dea,
                chevron = true, onClick = { vm.open(Dest.Map(spot = Venue.DEA)) },
            )
        }
        GroupDivider()
        GroupRow(
            stringResource(R.string.emergency_call), subtitle = stringResource(R.string.report_help_call_text), icon = Sym.Phone,
            onClick = { Emergency.dial(context) },
        )
    }
}

/** A friend lost in the crowd: the meeting point, with the way there, and the information point. */
@Composable
private fun MeetingSteps(vm: AppViewModel, origin: GeoPoint?) {
    val meeting = vm.venue.nearest(Venue.MEETING, origin)
    SectionTitle(stringResource(R.string.report_help_title))
    InsetGroup {
        if (meeting != null) {
            GroupRow(
                stringResource(R.string.report_help_meeting), subtitle = routeText(origin, meeting.point),
                icon = Sym.Walk, chevron = true, onClick = { vm.open(Dest.Map(spot = Venue.MEETING)) },
            )
            GroupDivider()
        }
        GroupRow(stringResource(R.string.report_help_info), subtitle = stringResource(R.string.report_help_info_text), icon = Sym.Info)
    }
}

/** The organisers' answers, newest first; the way to the medical point opens the map. */
@Composable
private fun Responses(vm: AppViewModel, report: MyReport) {
    val res = LocalResources.current
    SectionTitle(stringResource(R.string.report_responses_title))
    InsetGroup {
        report.responses.asReversed().forEachIndexed { i, r ->
            if (i > 0) GroupDivider()
            val medical = r.code == ResponseCode.GO_TO_MEDICAL
            GroupRow(
                Replies.response(res, vm.venue, r.code, r.landmark), subtitle = Labels.clock(r.receivedAt),
                icon = if (medical) Sym.Medical else Sym.Chat, chevron = medical,
                onClick = if (medical) ({ vm.open(Dest.Map(spot = MEDICAL)) }) else null,
            )
        }
    }
}

/**
 * What the reporter can tell staff since: still in need, moved to where the phone is now, or sorted out by itself.
 * One a minute; the rows wait in between.
 */
@Composable
private fun UpdateSteps(vm: AppViewModel, report: MyReport) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val now = rememberNow(5_000)
    val wait = remember(report.updates.size, now) { vm.updateWaitMs(report.incidentId) } ?: return
    val ready = wait == 0L
    val located = vm.knowsWhereIAm()
    fun send(kind: Int) = if (vm.updateReport(report.incidentId, kind)) haptics.confirm() else haptics.reject()
    SectionTitle(stringResource(R.string.report_update_title))
    InsetGroup {
        GroupRow(
            stringResource(R.string.report_update_active), icon = Sym.Bell, tint = if (ready) colors.label else colors.secondaryLabel,
            onClick = { send(UpdateKind.STILL_ACTIVE) }.takeIf { ready },
        )
        GroupDivider()
        GroupRow(
            stringResource(R.string.report_update_moved),
            subtitle = stringResource(if (located) R.string.report_update_moved_text else R.string.report_update_moved_unknown),
            icon = Sym.MyLocation, tint = if (ready && located) colors.label else colors.secondaryLabel,
            onClick = { send(UpdateKind.MOVED) }.takeIf { ready && located },
        )
        GroupDivider()
        GroupRow(
            stringResource(R.string.report_update_resolved), icon = Sym.CheckCircle, tint = if (ready) colors.label else colors.secondaryLabel,
            onClick = { send(UpdateKind.RESOLVED_ITSELF) }.takeIf { ready },
        )
    }
    report.updates.lastOrNull()?.let { last ->
        Text(
            listOfNotNull(
                stringResource(R.string.report_update_last, Labels.clock(last.at)), stringResource(R.string.report_update_wait).takeIf { !ready },
            ).joinToString(" "),
            style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
        )
    }
}

/** Asked before cancelling an alert: staff will learn that help is no longer needed, or check it first. */
@Composable
private fun CancelReportDialog(vm: AppViewModel, incidentId: String, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val checked = incidents.mine.firstOrNull { it.incidentId == incidentId }?.let(IncidentManager::cancelIsChecked) == true
    ConfirmDialog(
        stringResource(R.string.report_cancel_title),
        stringResource(if (checked) R.string.report_cancel_text_checked else R.string.report_cancel_text),
        onDismiss = onDismiss, confirmLabel = stringResource(R.string.report_cancel_confirm), destructive = true,
    ) {
        vm.cancelReport(incidentId)
        haptics.confirm()
    }
}

@Composable
private fun DeleteReportDialog(vm: AppViewModel, incidentId: String, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    ConfirmDialog(
        stringResource(R.string.report_delete_title), stringResource(R.string.report_delete_text),
        onDismiss = onDismiss, confirmLabel = stringResource(R.string.delete), destructive = true,
    ) {
        vm.deleteReport(incidentId)
        haptics.confirm()
    }
}

/** Sent reports, newest first, in a group: category, where and when, and the step they reached. */
@Composable
fun MyReportsScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val mine = incidents.mine.sortedByDescending { it.createdAt }
    val closed = mine.count { !it.isOpen() }
    val now = rememberNow()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    var menu by remember { mutableStateOf(false) }
    var cancelId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var clearClosed by remember { mutableStateOf(false) }
    NavScreen(
        title = stringResource(R.string.report_mine),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
        trailing = { backdrop ->
            if (closed > 0) {
                Box {
                    GlassIconButton(Sym.More, stringResource(R.string.more_options), { menu = true }, backdrop)
                    AppMenu(menu, { menu = false }) {
                        MenuRow(stringResource(R.string.report_delete_closed), {
                            menu = false
                            clearClosed = true
                        }, icon = Sym.Delete, danger = true)
                    }
                }
            }
        },
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
                            // each row keeps its own swipe state: after a delete the next row doesn't inherit it
                            key(report.incidentId) {
                                MyReportRow(vm, report, now, onCancel = { cancelId = report.incidentId }, onDelete = { deleteId = report.incidentId })
                            }
                            if (index < mine.lastIndex) GroupDivider(start = 64.dp)
                        }
                    }
                }
            }
        }
    }

    cancelId?.let { CancelReportDialog(vm, it) { cancelId = null } }
    deleteId?.let { DeleteReportDialog(vm, it) { deleteId = null } }
    if (clearClosed) {
        ConfirmDialog(
            pluralStringResource(R.plurals.report_delete_closed_title, closed, closed), stringResource(R.string.report_delete_closed_text),
            onDismiss = { clearClosed = false }, confirmLabel = stringResource(R.string.delete), destructive = true,
        ) {
            vm.deleteClosedReports()
            haptics.confirm()
        }
    }
}

/**
 * A report in the history. Swiped left, a closed one is deleted at once, while an open one is cancelled after a question
 * and stays in the list as "Se anuleaza". Long-pressed, it offers the same action in a menu.
 */
@Composable
private fun MyReportRow(vm: AppViewModel, report: MyReport, now: Long, onCancel: () -> Unit, onDelete: () -> Unit) {
    val open = report.isOpen()
    var menu by remember { mutableStateOf(false) }
    SwipeRow(
        if (open) Sym.Close else Sym.Delete,
        stringResource(if (open) R.string.report_cancel_confirm else R.string.delete),
        keep = open,
        onAction = { if (open) onCancel() else vm.deleteReport(report.incidentId) },
    ) {
        Box {
            GroupRow(
                stringResource(Labels.category(report.category)),
                subtitle = reportSubtitle(vm, report) + " · " + stepText(report, now),
                chevron = true,
                onClick = { vm.open(Dest.ReportSent(report.incidentId)) },
                onLongClick = { menu = true },
                leading = { CategoryCircle(report.category, report.severity == Severity.URGENT, 36.dp) },
            )
            AppMenu(menu, { menu = false }) {
                if (open) {
                    MenuRow(stringResource(R.string.report_cancel), {
                        menu = false
                        onCancel()
                    }, icon = Sym.Close, danger = true)
                } else {
                    MenuRow(stringResource(R.string.report_delete), {
                        menu = false
                        onDelete()
                    }, icon = Sym.Delete, danger = true)
                }
            }
        }
    }
}
