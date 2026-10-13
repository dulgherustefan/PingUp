package ro.safetyplease.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.event.MyProgram
import ro.safetyplease.core.event.Program
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.Hint
import ro.safetyplease.ui.designsystem.IconBtn
import ro.safetyplease.ui.designsystem.IconCircle
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.layers_mine
import ro.safetyplease.ui.resources.layers_now_tag
import ro.safetyplease.ui.resources.layers_plan_message
import ro.safetyplease.ui.resources.layers_program_empty
import ro.safetyplease.ui.resources.layers_program_title
import ro.safetyplease.ui.resources.layers_remind
import ro.safetyplease.ui.resources.layers_remind_at
import ro.safetyplease.ui.resources.layers_remind_lead
import ro.safetyplease.ui.resources.layers_reminder_note
import ro.safetyplease.ui.resources.layers_share_plan
import ro.safetyplease.ui.resources.time_minutes_short
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.formatDate
import ro.safetyplease.ui.text.rememberDateNames
import ro.safetyplease.ui.text.rememberNow

/**
 * The stages' schedule from the event pack, one night at a time and stage by stage. A tap puts a set in "Planul meu";
 * the bell asks for a reminder, so many minutes before the set plus the walk there. "Planul meu" goes to a friend or a
 * group as an ordinary message.
 */
@Composable
fun ProgramScreen(vm: AppViewModel) {
    val program = vm.c.program
    val schedule = program.schedule
    val mine by program.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val zone = TimeZone.currentSystemDefault()
    val days = remember(schedule) { Program.days(schedule, zone) }
    var dayText by rememberSaveable { mutableStateOf(Program.currentDay(schedule, now / 1000, zone)?.toString()) }
    val day = dayText?.let(LocalDate::parse)
    var sharing by remember { mutableStateOf(false) }
    val names = rememberDateNames()
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val colors = AppTheme.colors
    val header = stringResource(Res.string.layers_plan_message)
    val picked = program.picked(mine)
    val origin = program.origin()

    fun line(item: ScheduleItem) = "${Labels.clock(item.start * 1000)} ${item.title} (${vm.venue.zoneName(item.stage)})"

    if (sharing) {
        ConversationPicker(vm, { sharing = false }) { conversation ->
            vm.sendText(conversation, Program.planText(picked, header) { line(it) })
            vm.open(Dest.Conversation(conversation))
        }
    }

    NavScreen(
        title = stringResource(Res.string.layers_program_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            if (schedule.isEmpty() || day == null) {
                item(key = "empty") { Hint(stringResource(Res.string.layers_program_empty), Modifier.padding(Gutter)) }
                return@LazyColumn
            }
            if (days.size > 1) {
                item(key = "days") {
                    Box(Modifier.padding(start = Gutter, end = Gutter, top = 8.dp)) {
                        SegmentedControl(
                            days.map { d -> d.toString() to formatDate("EEE d", noonOf(d, zone), names) },
                            day.toString(), { dayText = it },
                        )
                    }
                }
            }
            item(key = "lead") {
                SectionTitle(stringResource(Res.string.layers_remind_lead, mine.leadMin))
                Box(Modifier.padding(horizontal = Gutter)) {
                    SegmentedControl(
                        Program.LEAD_OPTIONS.map { it to stringResource(Res.string.time_minutes_short, it) },
                        mine.leadMin, { program.setLead(it) },
                    )
                }
            }
            val tonight = Program.onDay(schedule, day, zone)
            for (stage in Program.stages(tonight)) {
                val sets = tonight.filter { it.stage == stage }
                item(key = "stage-$stage") { SectionTitle(vm.venue.zoneName(stage)) }
                item(key = "sets-$stage") {
                    InsetGroup {
                        sets.forEachIndexed { index, item ->
                            SetRow(vm, item, mine, now, origin)
                            if (index < sets.lastIndex) GroupDivider(start = 64.dp)
                        }
                    }
                }
            }
            if (picked.isNotEmpty()) {
                item(key = "mine-title") { SectionTitle(stringResource(Res.string.layers_mine)) }
                item(key = "mine") {
                    InsetGroup {
                        picked.forEachIndexed { index, item ->
                            GroupRow(item.title, subtitle = line(item), icon = Sym.Check, tint = colors.accent)
                            if (index < picked.lastIndex) GroupDivider()
                        }
                    }
                    AppButton(
                        stringResource(Res.string.layers_share_plan), { sharing = true },
                        Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
                    )
                }
            }
            item(key = "note") { Hint(stringResource(Res.string.layers_reminder_note), Modifier.padding(Gutter)) }
        }
    }
}

@Composable
private fun SetRow(vm: AppViewModel, item: ScheduleItem, mine: MyProgram, now: Long, origin: GeoPoint?) {
    val program = vm.c.program
    val colors = AppTheme.colors
    val key = Program.key(item)
    val chosen = key in mine.picked
    val reminded = key in mine.reminders
    val on = item.start * 1000 <= now && now < item.end * 1000
    val parts = listOfNotNull(
        "${Labels.clock(item.start * 1000)}–${Labels.clock(item.end * 1000)}",
        if (on) stringResource(Res.string.layers_now_tag) else null,
        if (reminded && now < item.start * 1000) {
            val at = Program.remindAt(item, mine.leadMin, program.walkMeters(origin, item))
            stringResource(Res.string.layers_remind_at, Labels.clock(at * 1000))
        } else null,
    )
    GroupRow(
        item.title,
        subtitle = parts.joinToString(" · "),
        onClick = { program.togglePicked(item) },
        leading = { IconCircle(Sym.Check, if (chosen) colors.accent else colors.fill, if (chosen) colors.onAccent else colors.fill, 36.dp) },
        trailing = {
            IconBtn(
                if (reminded) Sym.BellFill else Sym.Bell, stringResource(Res.string.layers_remind), { program.toggleReminder(item) },
                tint = if (reminded) colors.accent else colors.secondaryLabel,
            )
        },
    )
}

/** Midday of [day] in [zone], in milliseconds: the moment that names the day whatever the clock change. */
private fun noonOf(day: LocalDate, zone: TimeZone): Long = day.atTime(LocalTime(12, 0)).toInstant(zone).toEpochMilliseconds()
