package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.NoticeExtras
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTemplate
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneSet
import ro.safetyplease.core.announce.target
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.cancel
import ro.safetyplease.ui.resources.notice_composer_title
import ro.safetyplease.ui.resources.notice_confirm_title
import ro.safetyplease.ui.resources.notice_duration
import ro.safetyplease.ui.resources.notice_hours
import ro.safetyplease.ui.resources.notice_in_force
import ro.safetyplease.ui.resources.notice_minutes
import ro.safetyplease.ui.resources.notice_new
import ro.safetyplease.ui.resources.notice_no_shift
import ro.safetyplease.ui.resources.notice_not_sent
import ro.safetyplease.ui.resources.notice_preview
import ro.safetyplease.ui.resources.notice_reach
import ro.safetyplease.ui.resources.notice_second_text
import ro.safetyplease.ui.resources.notice_second_title
import ro.safetyplease.ui.resources.notice_second_word
import ro.safetyplease.ui.resources.notice_send
import ro.safetyplease.ui.resources.notice_sent
import ro.safetyplease.ui.resources.notice_topic_alert
import ro.safetyplease.ui.resources.notice_topic_heat
import ro.safetyplease.ui.resources.notice_topic_schedule
import ro.safetyplease.ui.resources.notice_topic_transport
import ro.safetyplease.ui.resources.control_map_before_evacuation
import ro.safetyplease.ui.resources.notice_withdraw
import ro.safetyplease.ui.resources.notice_withdraw_text
import ro.safetyplease.ui.resources.notice_withdraw_title
import ro.safetyplease.ui.resources.notice_withdrawn
import ro.safetyplease.ui.text.NoticeText
import ro.safetyplease.ui.text.noticeBody
import ro.safetyplease.ui.text.noticeTitle
import ro.safetyplease.ui.text.rememberNow

/** How long a notice holds, in minutes; the operator picks one. */
private val Durations = listOf(30, 60, 120, 240, 720)

/**
 * The console's "Anunț": the notices in force, which the operator can withdraw, and a new one from the event's
 * templates, previewed in both languages. Every notice is confirmed; an evacuation also by a second operator, who types
 * the word. The text never claims that everyone was told: it reaches only phones with Ping Up.
 */
@Composable
fun NoticeComposer(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = vm.c
    val colors = AppTheme.colors
    val language = c.language()
    val sentText = stringResource(Res.string.notice_sent)
    val notSentText = stringResource(Res.string.notice_not_sent)
    val withdrawnText = stringResource(Res.string.notice_withdrawn)
    val board by c.notices.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    var topic by rememberSaveable { mutableIntStateOf(NoticeTopic.ALERT) }
    // the parameters live with the template, so neither outlives the dialog
    var templateId by remember { mutableIntStateOf(0) }
    val params = remember { mutableStateListOf<String>() }
    var zones by remember { mutableStateOf(emptySet<String>()) }
    // no plan is preselected: the operator picks one, or "no plan", knowingly
    var plan by remember { mutableStateOf<PlanChoice?>(null) }
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    var confirm by remember { mutableStateOf(false) }
    var second by remember { mutableStateOf(false) }
    var withdraw by remember { mutableStateOf<Int?>(null) }
    val template = c.noticeTemplates.byId(templateId)?.takeIf { it.topic == topic }
    val evacuation = template?.let { it.topic == NoticeTopic.ALERT && it.level == NoticeLevel.EVACUATION } == true
    val ready = template != null && params.size == template.params.size && params.all { it.isNotBlank() } && c.notices.canSign &&
        (!evacuation || plan != null)
    val mapPending by c.mapEditor.edits.collectAsStateWithLifecycle()

    fun choose(t: NoticeTemplate) {
        templateId = t.id
        params.clear()
        repeat(t.params.size) { params.add("") }
    }

    fun send() {
        val t = template ?: return
        // one zone also goes in the zone field, which phones from before zone sets read
        // the phones pick the exits from the zones as the console left them: the map state goes first
        if (evacuation) c.mapEditor.sendNow()
        val extras = if (evacuation) Evacuations.extras(vm.venue, plan?.id, zones) else NoticeExtras(zones = ZoneSet.target(vm.venue, zones))
        val sent = extras?.let { c.notices.publish(t, params.map { it.trim() }, zones.singleOrNull().orEmpty(), minutes * 60L, it) }
        vm.phone.toast(if (sent != null) sentText else notSentText, long = true)
        if (sent != null) onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(28.dp)).background(colors.grouped).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.notice_composer_title), style = MaterialTheme.typography.title2)
            Text(stringResource(Res.string.notice_reach), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
            if (!c.notices.canSign) Text(stringResource(Res.string.notice_no_shift), style = MaterialTheme.typography.subheadline, color = colors.red)

            val active = board.active(now / 1000)
            if (active.isNotEmpty()) {
                Text(stringResource(Res.string.notice_in_force), style = MaterialTheme.typography.headline)
                InsetGroup {
                    active.forEachIndexed { i, (_, notice) ->
                        if (i > 0) GroupDivider(start = 16.dp)
                        GroupRow(
                            noticeTitle(notice), subtitle = noticeBody(c.noticeTemplates, vm.venue, notice, language),
                            trailing = {
                                AppButton(stringResource(Res.string.notice_withdraw), { withdraw = notice.topic }, kind = ButtonKind.Danger, compact = true, enabled = c.notices.canSign)
                            },
                        )
                    }
                }
            }

            Text(stringResource(Res.string.notice_new), style = MaterialTheme.typography.headline)
            SegmentedControl(NoticeTopic.KNOWN.map { it to stringResource(topicLabel(it)) }, topic, { topic = it })
            InsetGroup {
                c.noticeTemplates.forTopic(topic).forEachIndexed { i, t ->
                    if (i > 0) GroupDivider(start = 16.dp)
                    GroupRow(
                        noticeTitle(t.topic, t.level), subtitle = t.text(language),
                        icon = if (t.id == templateId) Sym.CheckCircle else null, tint = colors.accent, onClick = { choose(t) },
                    )
                }
            }
            if (template != null) {
                template.params.indices.forEach { i ->
                    InputField(
                        params.getOrElse(i) { "" }, { if (NoticeTemplates.fits(it)) params[i] = it }, template.paramLabel(i, language),
                    )
                }
                ZoneSetPicker(vm.venue, zones, evacuation) { zones = it }
                if (evacuation) {
                    EvacuationPlanPicker(vm.venue, plan) { plan = it }
                    if (mapPending.isNotEmpty()) {
                        Text(stringResource(Res.string.control_map_before_evacuation), style = MaterialTheme.typography.footnote, color = colors.orange)
                    }
                }
                Text(stringResource(Res.string.notice_duration), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                SegmentedControl(Durations.map { it to durationLabel(it) }, minutes, { minutes = it })
                Text(stringResource(Res.string.notice_preview), style = MaterialTheme.typography.headline)
                for (lang in listOf("ro", "en")) {
                    Text(
                        NoticeTemplates.fill(template.text(lang), params), style = MaterialTheme.typography.subheadline,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.cell).padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(stringResource(Res.string.cancel), onDismiss, Modifier.weight(1f), kind = ButtonKind.Secondary)
                AppButton(
                    stringResource(Res.string.notice_send), { confirm = true }, Modifier.weight(1f), enabled = ready,
                    kind = if (evacuation) ButtonKind.Danger else ButtonKind.Primary,
                )
            }
        }
    }

    if (confirm && template != null) {
        ConfirmDialog(
            stringResource(Res.string.notice_confirm_title, noticeTitle(template.topic, template.level)),
            NoticeTemplates.fill(template.text(language), params) + "\n\n" + targetsSummary(vm.venue, zones, plan?.id.takeIf { evacuation }) +
                "\n\n" + stringResource(Res.string.notice_reach),
            { confirm = false }, stringResource(Res.string.notice_send), destructive = evacuation,
        ) { if (evacuation) second = true else send() }
    }
    if (second) SecondOperator({ second = false }) {
        second = false
        send()
    }
    withdraw?.let { t ->
        ConfirmDialog(
            stringResource(Res.string.notice_withdraw_title, noticeTitle(t, board.notices.firstOrNull { it.topic == t }?.notice()?.level ?: 0)),
            stringResource(Res.string.notice_withdraw_text), { withdraw = null }, stringResource(Res.string.notice_withdraw), destructive = true,
        ) {
            vm.phone.toast(if (c.notices.cancel(t) != null) withdrawnText else notSentText, long = true)
        }
    }
}

/** An evacuation goes out only when a second operator types the word, so one tap can't empty a site. */
@Composable
private fun SecondOperator(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val word = stringResource(Res.string.notice_second_word)
    AppDialog(
        onDismiss, stringResource(Res.string.notice_second_title), stringResource(Res.string.notice_send), onConfirm,
        confirmEnabled = typed.trim().equals(word, ignoreCase = true), destructive = true,
    ) {
        Text(
            stringResource(Res.string.notice_second_text, word), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        InputField(typed, { typed = it }, word)
    }
}

private fun topicLabel(topic: Int): StringResource = when (topic) {
    NoticeTopic.ALERT -> Res.string.notice_topic_alert
    NoticeTopic.HEAT -> Res.string.notice_topic_heat
    NoticeTopic.SCHEDULE -> Res.string.notice_topic_schedule
    else -> Res.string.notice_topic_transport
}

@Composable
private fun durationLabel(minutes: Int): String =
    if (minutes < 60) stringResource(Res.string.notice_minutes, minutes) else stringResource(Res.string.notice_hours, minutes / 60)
