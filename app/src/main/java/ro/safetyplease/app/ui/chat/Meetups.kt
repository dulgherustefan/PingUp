package ro.safetyplease.app.ui.chat

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.safetyplease.app.R
import ro.safetyplease.app.platform.torch.Torch
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppDialog
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Backdrop
import ro.safetyplease.app.ui.designsystem.ButtonKind
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.IosSwitch
import ro.safetyplease.app.ui.designsystem.SegmentedControl
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TextLink
import ro.safetyplease.app.ui.designsystem.TouchTarget
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.glass
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title2
import ro.safetyplease.core.chat.FriendAsk
import ro.safetyplease.core.chat.MeetingPlace
import ro.safetyplease.core.chat.MeetingProposal
import ro.safetyplease.core.chat.TorchPulse
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.venue.GeoPoint
import java.util.Calendar
import java.util.Locale

/**
 * Friends' requests to this phone, "Fă-te văzut" and "Vin la tine", each asked in a dialog; a yes to the first flashes
 * the torch. [AppRoot] hosts it once, over every screen.
 */
@Composable
fun MeetupRequests(vm: AppViewModel) {
    val chat = vm.c.chat
    val pings by chat.pingAsks.collectAsStateWithLifecycle()
    val comes by chat.comeAsks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // answers go through the mesh context, so the ask stays in the list a moment after the user's tap
    var answered by remember { mutableStateOf(emptySet<FriendAsk>()) }
    var pulsingFor by remember { mutableStateOf<Long?>(null) }
    val ping = pings.firstOrNull { it !in answered }
    val come = comes.firstOrNull { it !in answered }

    fun answerPing(ask: FriendAsk, yes: Boolean) {
        answered = answered + ask
        chat.answerPing(ask.sender, yes)
        if (yes) pulsingFor = ask.sender
    }

    LaunchedEffect(ping, settings.pingAutoAccept) {
        if (ping != null && settings.pingAutoAccept && pulsingFor == null) answerPing(ping, true)
    }
    val pulsing = pulsingFor
    when {
        pulsing != null -> SeenPulse(vm, chat.nameOf(pulsing), settings) { pulsingFor = null }
        ping != null && !settings.pingAutoAccept -> SeenDialog(vm, ping, settings, onAnswer = { answerPing(ping, it) })
        come != null -> ComeDialog(chat.nameOf(come.sender)) { yes, precision ->
            answered = answered + come
            chat.answerComing(come.sender, yes, precision)
        }
    }
}

@Composable
private fun SeenDialog(vm: AppViewModel, ask: FriendAsk, settings: Settings, onAnswer: (Boolean) -> Unit) {
    val name = vm.c.chat.nameOf(ask.sender)
    AppDialog(
        onDismiss = { onAnswer(false) }, title = stringResource(R.string.seen_title, name),
        confirmLabel = stringResource(R.string.seen_accept), onConfirm = { onAnswer(true) }, dismissLabel = stringResource(R.string.not_now),
    ) {
        Text(
            stringResource(R.string.seen_text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        DialogSwitch(stringResource(R.string.seen_auto), settings.pingAutoAccept, onChange = vm::setPingAutoAccept)
        DialogSwitch(stringResource(R.string.seen_white), settings.pingWhiteScreen, onChange = vm::setPingWhiteScreen)
        DialogSwitch(stringResource(R.string.seen_sound), settings.pingSound, onChange = vm::setPingSound)
    }
}

@Composable
private fun DialogSwitch(title: String, checked: Boolean, color: Color = AppTheme.colors.label, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.subheadline, color = color, modifier = Modifier.weight(1f))
        IosSwitch(checked, null)
    }
}

/**
 * The torch flashes in [TorchPulse] steps, the phone vibrates in the same rhythm, and, if chosen, the screen turns white
 * and a beep marks each flash. Leaving it (Stop, or the screen going away) puts everything out. It always shows, with the
 * auto-accept switch, so a phone that lit up by itself says why and how to stop it.
 */
@Composable
private fun SeenPulse(vm: AppViewModel, name: String, settings: Settings, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        val torch = Torch(context.applicationContext)
        val vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
        val tone = if (settings.pingSound) runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() else null
        view.keepScreenOn = true
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(TorchPulse.vibration(), -1))
            for (step in TorchPulse.steps()) {
                torch.set(step.lit)
                if (step.lit) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, step.ms.toInt())
                delay(step.ms)
            }
        } finally {
            torch.release()
            vibrator?.cancel()
            tone?.release()
            view.keepScreenOn = false
            done()
        }
    }
    if (!settings.pingWhiteScreen) {
        AppDialog(onDone, stringResource(R.string.seen_screen_title, name), stringResource(R.string.seen_stop), onDone, dismissLabel = null) {
            Text(
                stringResource(R.string.seen_screen_text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            DialogSwitch(stringResource(R.string.seen_auto), settings.pingAutoAccept, onChange = vm::setPingAutoAccept)
        }
        return
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.White).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.seen_screen_title, name), style = MaterialTheme.typography.title2, color = Color.Black, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.seen_screen_text), style = MaterialTheme.typography.subheadline, color = Color.Black,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            AppButton(stringResource(R.string.seen_stop), onDone, kind = ButtonKind.Secondary)
            Spacer(Modifier.size(24.dp))
            DialogSwitch(stringResource(R.string.seen_auto), settings.pingAutoAccept, Color.Black, vm::setPingAutoAccept)
        }
    }
}

@Composable
private fun ComeDialog(name: String, onAnswer: (Boolean, Int) -> Unit) {
    var precision by remember { mutableIntStateOf(SharePrecision.EXACT) }
    AppDialog(
        onDismiss = { onAnswer(false, precision) }, title = stringResource(R.string.come_title, name),
        confirmLabel = stringResource(R.string.come_accept), onConfirm = { onAnswer(true, precision) }, dismissLabel = stringResource(R.string.not_now),
    ) {
        Text(
            stringResource(R.string.come_text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        SegmentedControl(
            listOf(SharePrecision.EXACT to stringResource(R.string.come_exact), SharePrecision.APPROXIMATE to stringResource(R.string.come_approx)),
            precision, { precision = it },
        )
    }
}

/** "Ne vedem la Reperul 14 la 22:30", or the venue's meeting point, or a spot on the map. */
@Composable
private fun placeText(place: MeetingPlace, middle: Boolean = false): String = when {
    place.landmark != 0 -> stringResource(R.string.meet_place_landmark, place.landmark)
    place.venuePoint -> stringResource(R.string.meet_place_venue)
    middle -> stringResource(R.string.meet_place_middle)
    else -> stringResource(R.string.meet_place_point)
}

private fun clockText(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

/** The conversation's meeting point, pinned under the top bar, with its one-tap yes. */
@Composable
fun MeetingPin(vm: AppViewModel, proposal: MeetingProposal, now: Long, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val chat = vm.c.chat
    val mine = proposal.proposer == vm.c.identity.nodeId
    val place = placeText(proposal.place)
    val title = proposal.minuteOfDay?.let { stringResource(R.string.meet_title_time, place, clockText(it)) }
        ?: stringResource(R.string.meet_title, place)
    val by = if (mine) stringResource(R.string.meet_by_me) else stringResource(R.string.meet_by, chat.nameOf(proposal.proposer))
    val left = stringResource(R.string.meet_left, ((proposal.expiresAtMs - now + 59_999) / 60_000).toInt().coerceAtLeast(1))
    val coming = if (proposal.accepted.isEmpty()) null else pluralStringResource(R.plurals.meet_coming, proposal.accepted.size, proposal.accepted.size)
    val point = proposal.place.point ?: vm.venue.landmark(proposal.place.landmark)?.let { GeoPoint(it.lat, it.lon) }
    Row(
        modifier.padding(horizontal = Gutter).fillMaxWidth().glass(backdrop, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = point != null, role = Role.Button) {
                vm.open(Dest.Pin(point?.lat, point?.lon, "", place.replaceFirstChar { it.uppercase() }))
            }
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Flag, null, Modifier.size(22.dp), tint = colors.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.SemiBold, color = colors.label)
            Text(
                listOfNotNull(by, coming, left).joinToString(" · "), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
            )
        }
        val accepted = vm.c.identity.nodeId in proposal.accepted
        when {
            mine -> TextLink(stringResource(R.string.meet_withdraw), { chat.withdrawMeeting(proposal.conversation) })
            accepted -> Text(stringResource(R.string.meet_accepted), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
            else -> AppButton(stringResource(R.string.meet_accept), { chat.acceptMeeting(proposal.conversation) }, compact = true)
        }
    }
}

/** Minutes ahead the meeting can be set for; it stays pinned this long after that. */
private val WhenOptions = listOf(0, 15, 30, 60)
private const val MEETING_GRACE_MIN = 30

/** Picks the place and the time of a meeting point: the venue's, the middle of the group, or a numbered landmark. */
@Composable
fun MeetingDialog(vm: AppViewModel, conversation: String, onDismiss: () -> Unit) {
    val venue = vm.venue
    val middle = remember(conversation) { vm.c.chat.meetingCentroid(conversation) }
    val choices = remember(conversation, middle) {
        listOfNotNull(venue.meetingPoint?.let(MeetingPlace::venue), middle?.let(MeetingPlace::point)) +
            venue.pois.map { it.number }.filter { it in 1..255 }.distinct().sorted().map(MeetingPlace::landmark)
    }
    var chosen by remember { mutableStateOf(choices.firstOrNull()) }
    var ahead by remember { mutableIntStateOf(WhenOptions[2]) }
    AppDialog(
        onDismiss, stringResource(R.string.meet_dialog_title), stringResource(R.string.meet_send),
        onConfirm = {
            chosen?.let { place ->
                val minute = if (ahead == 0) null else Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) + ahead } % (24 * 60)
                vm.askFriend(null) { vm.c.chat.proposeMeeting(conversation, place, minute, ahead + MEETING_GRACE_MIN) }
            }
            onDismiss()
        },
        confirmEnabled = chosen != null,
    ) {
        if (choices.isEmpty()) {
            Text(stringResource(R.string.meet_none), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center)
            return@AppDialog
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            for (place in choices) {
                val selected = place == chosen
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget).clip(RoundedCornerShape(12.dp))
                        .selectable(selected, role = Role.RadioButton) { chosen = place }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name = venue.landmark(place.landmark)?.name.orEmpty()
                    val label = placeText(place, middle = place.point != null && !place.venuePoint).replaceFirstChar { it.uppercase() }
                    Text(
                        if (name.isEmpty()) label else "$label · $name", style = MaterialTheme.typography.subheadline,
                        color = AppTheme.colors.label, modifier = Modifier.weight(1f),
                    )
                    if (selected) Icon(Sym.Check, null, Modifier.size(20.dp), tint = AppTheme.colors.accent)
                }
            }
        }
        Box(Modifier.padding(top = 12.dp)) {
            SegmentedControl(
                WhenOptions.map { it to if (it == 0) stringResource(R.string.meet_when_now) else stringResource(R.string.meet_when_in, it) },
                ahead, { ahead = it },
            )
        }
    }
}

/** The friend's name in the header says so while their torch is lit for us. */
@Composable
fun rememberLighting(vm: AppViewModel, friendId: Long?): Boolean {
    val lighting by vm.c.chat.lighting.collectAsStateWithLifecycle()
    return friendId != null && lighting.any { it.friend == friendId }
}
