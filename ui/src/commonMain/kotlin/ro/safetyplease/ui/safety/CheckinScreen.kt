package ro.safetyplease.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.safety.Checkin
import ro.safetyplease.core.safety.CheckinManager
import ro.safetyplease.core.safety.CheckinPhase
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.common.NotificationsOffBanner
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.largeTitle
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.checkin_alerted_none
import ro.safetyplease.ui.resources.checkin_alerted_none_title
import ro.safetyplease.ui.resources.checkin_alerted_text
import ro.safetyplease.ui.resources.checkin_alerted_title
import ro.safetyplease.ui.resources.checkin_arrived
import ro.safetyplease.ui.resources.checkin_arrived_safe
import ro.safetyplease.ui.resources.checkin_asked_title
import ro.safetyplease.ui.resources.checkin_asked_wait
import ro.safetyplease.ui.resources.checkin_cancel
import ro.safetyplease.ui.resources.checkin_close_silent
import ro.safetyplease.ui.resources.checkin_extend
import ro.safetyplease.ui.resources.checkin_intro
import ro.safetyplease.ui.resources.checkin_minus_5
import ro.safetyplease.ui.resources.checkin_no_friends_add
import ro.safetyplease.ui.resources.checkin_no_friends_text
import ro.safetyplease.ui.resources.checkin_no_friends_title
import ro.safetyplease.ui.resources.checkin_note
import ro.safetyplease.ui.resources.checkin_notif_off
import ro.safetyplease.ui.resources.checkin_place_hint
import ro.safetyplease.ui.resources.checkin_place_lodging
import ro.safetyplease.ui.resources.checkin_place_parking
import ro.safetyplease.ui.resources.checkin_place_station
import ro.safetyplease.ui.resources.checkin_plus_5
import ro.safetyplease.ui.resources.checkin_reassure
import ro.safetyplease.ui.resources.checkin_start
import ro.safetyplease.ui.resources.checkin_title
import ro.safetyplease.ui.resources.checkin_until
import ro.safetyplease.ui.resources.checkin_waiting_title
import ro.safetyplease.ui.resources.checkin_waiting_who
import ro.safetyplease.ui.resources.checkin_when
import ro.safetyplease.ui.resources.checkin_where
import ro.safetyplease.ui.resources.checkin_who
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.rememberNow

private val Presets = listOf(15, 30, 45, 60, 90, 120)

/** The longest a name of the place may be, in characters; the wire takes 32 bytes of it. */
private const val PLACE_CHARS = 24

/**
 * "Check-in pe drum": where, by when, who to tell. The phone asks "Ai ajuns?" at the time and tells the chosen friends
 * only if nobody answers.
 */
@Composable
fun CheckinScreen(vm: AppViewModel) {
    val data by vm.c.checkin.state.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(Res.string.checkin_title),
        background = AppTheme.colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(bottom = 24.dp)) {
            FriendAlertCards(vm, data.alerts)
            val current = data.current
            if (current == null) CheckinSetup(vm, data.places, friends) else CheckinProgress(vm, current)
        }
    }
}

@Composable
private fun CheckinSetup(vm: AppViewModel, places: List<String>, friends: List<Friend>) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var label by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    var saved by rememberSaveable { mutableStateOf("") }
    val chosen = chosenIds(saved)
    val now = rememberNow()
    val defaults = listOf(
        stringResource(Res.string.checkin_place_parking), stringResource(Res.string.checkin_place_station), stringResource(Res.string.checkin_place_lodging),
    )
    val less = stringResource(Res.string.checkin_minus_5)
    val more = stringResource(Res.string.checkin_plus_5)
    Text(
        stringResource(Res.string.checkin_intro), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
        modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
    )
    // the question comes as a notification: without them the friends could be told before the person is asked
    NotificationsOffBanner(vm.phone, stringResource(Res.string.checkin_notif_off), Modifier.padding(horizontal = Gutter, vertical = 8.dp))

    SectionTitle(stringResource(Res.string.checkin_where), top = 12.dp)
    Pills(Modifier.padding(horizontal = Gutter)) { (defaults + places.filterNot { it in defaults }).forEach { place -> Pill(place, label == place, { label = place }) } }
    InputField(
        label, { label = it.take(PLACE_CHARS) }, stringResource(Res.string.checkin_place_hint),
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), background = colors.cell,
    )

    SectionTitle(stringResource(Res.string.checkin_when), top = 12.dp)
    Pills(Modifier.padding(horizontal = Gutter)) { Presets.forEach { preset -> Pill(durationText(preset), minutes == preset, { minutes = preset }) } }
    Row(
        Modifier.padding(horizontal = Gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(
            "−5", { minutes = (minutes - 5).coerceAtLeast(5) }, Modifier.semantics { contentDescription = less }, kind = ButtonKind.Secondary, compact = true,
        )
        Text(
            stringResource(Res.string.checkin_until, Labels.clock(now + minutes * CheckinManager.MINUTE_MS)) + " · " + durationText(minutes),
            style = MaterialTheme.typography.subheadline, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
        )
        AppButton(
            "+5", { minutes = (minutes + 5).coerceAtMost(CheckinManager.MAX_MINUTES) }, Modifier.semantics { contentDescription = more },
            kind = ButtonKind.Secondary, compact = true,
        )
    }

    SectionTitle(stringResource(Res.string.checkin_who), top = 12.dp)
    if (friends.isEmpty()) {
        EmptyState(
            stringResource(Res.string.checkin_no_friends_title), stringResource(Res.string.checkin_no_friends_text), icon = Sym.PersonAdd,
            action = { AppButton(stringResource(Res.string.checkin_no_friends_add), { vm.open(Dest.AddFriend) }, kind = ButtonKind.Secondary) },
        )
    } else {
        InsetGroup {
            friends.forEachIndexed { index, friend ->
                if (index > 0) GroupDivider()
                val on = friend.nodeId in chosen
                GroupRow(
                    friend.nickname, icon = Sym.Person,
                    onClick = { saved = savedIds(toggled(chosen, friend.nodeId, CheckinManager.MAX_FRIENDS)) },
                    trailing = { if (on) Icon(Sym.CheckCircle, null, tint = colors.accent) },
                )
            }
        }
    }

    AppButton(
        stringResource(Res.string.checkin_start),
        {
            if (vm.c.checkin.begin(label, minutes, chosen)) haptics.confirm() else haptics.reject()
        },
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 20.dp), enabled = label.isNotBlank() && chosen.isNotEmpty(),
    )
    Text(
        stringResource(Res.string.checkin_note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
        modifier = Modifier.padding(horizontal = Gutter * 2),
    )
}

@Composable
private fun CheckinProgress(vm: AppViewModel, current: Checkin) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val now = rememberNow(1_000)
    val names = current.friends.joinToString(", ") { vm.c.chat.nameOf(it) }
    val reassurance = stringResource(Res.string.checkin_reassure, current.label)
    val title: String
    val big: String
    val sub: String
    when (current.phase) {
        CheckinPhase.WAITING -> {
            title = stringResource(Res.string.checkin_waiting_title, current.label)
            big = countdownText(current.deadlineAt - now)
            sub = stringResource(Res.string.checkin_waiting_who, Labels.clock(current.deadlineAt), names)
        }
        CheckinPhase.ASKED -> {
            title = stringResource(Res.string.checkin_asked_title, current.label)
            big = countdownText(current.askedAt + CheckinManager.GRACE_MS - now)
            sub = stringResource(Res.string.checkin_asked_wait, names)
        }
        CheckinPhase.ALERTED -> {
            title = stringResource(if (current.told == 0) Res.string.checkin_alerted_none_title else Res.string.checkin_alerted_title)
            big = ""
            sub = if (current.told == 0) {
                stringResource(Res.string.checkin_alerted_none, current.label)
            } else {
                pluralStringResource(Res.plurals.checkin_alerted_text, current.told, current.told, current.label)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
        if (big.isNotEmpty()) {
            Text(
                big, style = MaterialTheme.typography.largeTitle, fontWeight = FontWeight.SemiBold, color = colors.label,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        Text(sub, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center)
    }
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (current.phase == CheckinPhase.ALERTED) {
            AppButton(
                stringResource(Res.string.checkin_arrived_safe),
                {
                    vm.c.checkin.arrived(reassurance)
                    haptics.confirm()
                },
                Modifier.fillMaxWidth(), icon = Sym.Check,
            )
            AppButton(stringResource(Res.string.checkin_close_silent), { vm.c.checkin.arrived() }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        } else {
            AppButton(
                stringResource(Res.string.checkin_arrived),
                {
                    vm.c.checkin.arrived()
                    haptics.confirm()
                },
                Modifier.fillMaxWidth(), icon = Sym.Check,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (extra in listOf(15, 30)) {
                    AppButton(
                        stringResource(Res.string.checkin_extend, extra),
                        {
                            if (vm.c.checkin.extend(extra)) haptics.confirm()
                        },
                        Modifier.weight(1f), kind = ButtonKind.Secondary,
                    )
                }
            }
            TextLink(stringResource(Res.string.checkin_cancel), { vm.c.checkin.cancel() }, Modifier.align(Alignment.CenterHorizontally))
        }
    }
    Text(
        stringResource(Res.string.checkin_note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
        modifier = Modifier.padding(horizontal = Gutter * 2, vertical = 20.dp),
    )
}
