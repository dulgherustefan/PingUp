package ro.safetyplease.app.ui.control

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.AppLocale
import ro.safetyplease.app.R
import ro.safetyplease.app.text.NoticeText
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppMenu
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.MenuRow
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTemplate
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.announce.NoticeTopic

/** How long a notice holds, in minutes; the operator picks one. */
private val Durations = listOf(30, 60, 120, 240, 720)

/**
 * The console's "Anunț": the notices in force, which the operator can withdraw, and a new one from the event's
 * templates, previewed in both languages. Every notice is confirmed; an evacuation also by a second operator, who types
 * the word. The text never claims that everyone was told: it reaches only phones with Ping Up.
 */
@Composable
internal fun NoticeComposer(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = vm.c
    val colors = AppTheme.colors
    val context = LocalContext.current
    val res = LocalResources.current
    val language = AppLocale.language(context)
    val board by c.notices.board.collectAsStateWithLifecycle()
    val now = rememberNow()
    var topic by rememberSaveable { mutableIntStateOf(NoticeTopic.ALERT) }
    // the parameters live with the template, so neither outlives the dialog
    var templateId by remember { mutableIntStateOf(0) }
    val params = remember { mutableStateListOf<String>() }
    var zone by rememberSaveable { mutableStateOf("") }
    var zoneMenu by remember { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    var confirm by remember { mutableStateOf(false) }
    var second by remember { mutableStateOf(false) }
    var withdraw by remember { mutableStateOf<Int?>(null) }
    val template = c.noticeTemplates.byId(templateId)?.takeIf { it.topic == topic }
    val ready = template != null && params.size == template.params.size && params.all { it.isNotBlank() } && c.notices.canSign

    fun choose(t: NoticeTemplate) {
        templateId = t.id
        params.clear()
        repeat(t.params.size) { params.add("") }
    }

    fun send() {
        val t = template ?: return
        val sent = c.notices.publish(t, params.map { it.trim() }, zone, minutes * 60L)
        Toast.makeText(context, if (sent != null) R.string.notice_sent else R.string.notice_not_sent, Toast.LENGTH_LONG).show()
        if (sent != null) onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(28.dp)).background(colors.grouped).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.notice_composer_title), style = MaterialTheme.typography.title2)
            Text(stringResource(R.string.notice_reach), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
            if (!c.notices.canSign) Text(stringResource(R.string.notice_no_shift), style = MaterialTheme.typography.subheadline, color = colors.red)

            val active = board.active(now / 1000)
            if (active.isNotEmpty()) {
                Text(stringResource(R.string.notice_in_force), style = MaterialTheme.typography.headline)
                InsetGroup {
                    active.forEachIndexed { i, (_, notice) ->
                        if (i > 0) GroupDivider(start = 16.dp)
                        GroupRow(
                            NoticeText.title(res, notice), subtitle = NoticeText.body(res, c.noticeTemplates, vm.venue, notice, language),
                            trailing = {
                                AppButton(stringResource(R.string.notice_withdraw), { withdraw = notice.topic }, kind = ButtonKind.Danger, compact = true, enabled = c.notices.canSign)
                            },
                        )
                    }
                }
            }

            Text(stringResource(R.string.notice_new), style = MaterialTheme.typography.headline)
            SegmentedControl(NoticeTopic.KNOWN.map { it to res.getString(topicLabel(it)) }, topic, { topic = it })
            InsetGroup {
                c.noticeTemplates.forTopic(topic).forEachIndexed { i, t ->
                    if (i > 0) GroupDivider(start = 16.dp)
                    GroupRow(
                        NoticeText.title(res, t.topic, t.level), subtitle = t.text(language),
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
                Box {
                    GroupRow(
                        stringResource(R.string.notice_zone), value = if (zone.isEmpty()) stringResource(R.string.notice_zone_all) else vm.venue.zoneName(zone),
                        chevron = true, onClick = { zoneMenu = true }, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.cell),
                    )
                    AppMenu(zoneMenu, { zoneMenu = false }) {
                        MenuRow(stringResource(R.string.notice_zone_all), {
                            zone = ""
                            zoneMenu = false
                        })
                        vm.venue.zones.forEach { z ->
                            MenuRow(z.name, {
                                zone = z.id
                                zoneMenu = false
                            })
                        }
                    }
                }
                Text(stringResource(R.string.notice_duration), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                SegmentedControl(Durations.map { it to durationLabel(it) }, minutes, { minutes = it })
                Text(stringResource(R.string.notice_preview), style = MaterialTheme.typography.headline)
                for (lang in listOf("ro", "en")) {
                    Text(
                        NoticeTemplates.fill(template.text(lang), params), style = MaterialTheme.typography.subheadline,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.cell).padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f), kind = ButtonKind.Secondary)
                AppButton(
                    stringResource(R.string.notice_send), { confirm = true }, Modifier.weight(1f), enabled = ready,
                    kind = if (template?.let { it.topic == NoticeTopic.ALERT && it.level == NoticeLevel.EVACUATION } == true) ButtonKind.Danger else ButtonKind.Primary,
                )
            }
        }
    }

    if (confirm && template != null) {
        val evacuation = template.topic == NoticeTopic.ALERT && template.level == NoticeLevel.EVACUATION
        ConfirmDialog(
            stringResource(R.string.notice_confirm_title, NoticeText.title(res, template.topic, template.level)),
            NoticeTemplates.fill(template.text(language), params) + "\n\n" + stringResource(R.string.notice_reach),
            { confirm = false }, stringResource(R.string.notice_send), destructive = evacuation,
        ) { if (evacuation) second = true else send() }
    }
    if (second) SecondOperator({ second = false }) {
        second = false
        send()
    }
    withdraw?.let { t ->
        ConfirmDialog(
            stringResource(R.string.notice_withdraw_title, NoticeText.title(res, t, board.notices.firstOrNull { it.topic == t }?.notice()?.level ?: 0)),
            stringResource(R.string.notice_withdraw_text), { withdraw = null }, stringResource(R.string.notice_withdraw), destructive = true,
        ) {
            Toast.makeText(context, if (c.notices.cancel(t) != null) R.string.notice_withdrawn else R.string.notice_not_sent, Toast.LENGTH_LONG).show()
        }
    }
}

/** An evacuation goes out only when a second operator types the word, so one tap can't empty a site. */
@Composable
private fun SecondOperator(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val word = stringResource(R.string.notice_second_word)
    AppDialog(
        onDismiss, stringResource(R.string.notice_second_title), stringResource(R.string.notice_send), onConfirm,
        confirmEnabled = typed.trim().equals(word, ignoreCase = true), destructive = true,
    ) {
        Text(
            stringResource(R.string.notice_second_text, word), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        InputField(typed, { typed = it }, word)
    }
}

private fun topicLabel(topic: Int): Int = when (topic) {
    NoticeTopic.ALERT -> R.string.notice_topic_alert
    NoticeTopic.HEAT -> R.string.notice_topic_heat
    NoticeTopic.SCHEDULE -> R.string.notice_topic_schedule
    else -> R.string.notice_topic_transport
}

@Composable
private fun durationLabel(minutes: Int): String =
    if (minutes < 60) stringResource(R.string.notice_minutes, minutes) else stringResource(R.string.notice_hours, minutes / 60)
