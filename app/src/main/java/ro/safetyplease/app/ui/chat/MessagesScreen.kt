package ro.safetyplease.app.ui.chat

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MsgStatus
import ro.safetyplease.app.mesh.RadioStatus
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.listTime
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.MeButton
import ro.safetyplease.app.ui.common.RadioGate
import ro.safetyplease.app.ui.common.networkText
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Avatar
import ro.safetyplease.app.ui.designsystem.Banner
import ro.safetyplease.app.ui.designsystem.DeliveryIcon
import ro.safetyplease.app.ui.designsystem.EmptyState
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.LocalBottomClearance
import ro.safetyplease.app.ui.designsystem.LocalReduceMotion
import ro.safetyplease.app.ui.designsystem.NavScreen
import ro.safetyplease.app.ui.designsystem.PinMascot
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.UnreadBadge
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.pressScale
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.needsBatteryHint
import ro.safetyplease.app.ui.rememberRadioGate

private class ConversationRow(
    val id: String,
    val title: String,
    val friend: Friend?,
    val group: Group?,
    val last: ChatMessage?,
    val unread: Int,
)

@Composable
fun messagePreview(vm: AppViewModel, message: ChatMessage): String = when (message.kind) {
    MsgKind.TEXT -> message.text
    MsgKind.QUICK -> stringResource(Labels.quick(message.quickCode))
    MsgKind.ZONE -> stringResource(R.string.msg_zone, zoneLabel(vm, message))
    MsgKind.SYSTEM -> stringResource(R.string.msg_group_invite, vm.c.chat.nameOf(message.senderId))
}

@Composable
fun zoneLabel(vm: AppViewModel, message: ChatMessage): String =
    if (message.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(message.zone)

/** Cuvintele pentru starea unui mesaj trimis de noi. */
@StringRes
fun messageStateLabel(status: MsgStatus): Int = when (status) {
    MsgStatus.QUEUED -> R.string.msg_queued
    MsgStatus.SENT -> R.string.msg_sent
    MsgStatus.DELIVERED -> R.string.msg_delivered
    else -> R.string.msg_failed
}

/**
 * Punctul din fata starii retelei: verde cand ai telefoane in jur, gri fara, rosu cand radioul nu merge.
 * Sta pe loc; un punct care pulseaza trage privirea si cand nu se schimba nimic (NN/g, animatia ca feedback).
 * Se misca doar culoarea, cand se schimba starea, lent ca in bitchat, ca sa nu clipeasca la fiecare telefon care trece.
 */
@Composable
private fun NetworkDot(links: Int, gate: RadioGate) {
    val colors = AppTheme.colors
    val target = when {
        !gate.hasAccess || !gate.bluetoothOn -> colors.red
        links > 0 -> colors.brand
        else -> colors.tertiaryLabel
    }
    val color by animateColorAsState(target, if (LocalReduceMotion.current) snap() else tween(480), label = "net")
    Box(Modifier.padding(end = 6.dp).size(7.dp).background(color, CircleShape))
}

/** Actiunea principala din Mesaje, ca butonul lat din Threema si WhatsApp: jos, la degetul mare. */
@Composable
private fun AddFriendButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(press, 0.96f).height(56.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(colors.accent)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick).padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.PersonAdd, null, Modifier.size(22.dp), tint = colors.onAccent)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.menu_add_friend), style = MaterialTheme.typography.headline, color = colors.onAccent, maxLines = 1)
    }
}

/**
 * Lista de conversatii: titlul centrat cu starea retelei, bula ta in stanga, randurile fara linii intre ele si,
 * jos, butonul „Adauga prieten”. La festival ai cativa prieteni, deci fara cautare si fara filtre;
 * „Grup nou” apare abia cand ai cu cine face un grup.
 */
@Composable
fun MessagesScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow()
    val gate = rememberRadioGate(vm, nearby.radio, onStartMesh)
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    val rows = remember(friends, groups, chat.messages) {
        val byConversation = chat.messages.groupBy { it.conversation }
        fun row(id: String, title: String, friend: Friend?, group: Group?): ConversationRow {
            val messages = byConversation[id].orEmpty()
            return ConversationRow(id, title, friend, group, messages.lastOrNull(), messages.count { !it.read })
        }
        (friends.map { row(Conversations.friend(it.nodeId), it.nickname, it, null) } +
            groups.map { row(Conversations.group(it.id), it.name, null, it) })
            .sortedWith(compareByDescending<ConversationRow> { it.last?.timeMs ?: 0L }.thenBy { it.title.lowercase() })
    }

    Box(Modifier.fillMaxSize()) {
        NavScreen(
            title = stringResource(R.string.tab_messages),
            subtitle = networkText(nearby.readyLinks, gate),
            subtitleLeading = { NetworkDot(nearby.readyLinks, gate) },
            scrolled = scrolled,
            leading = { MeButton(settings.nickname) { vm.open(Dest.Me) } },
            trailing = { backdrop ->
                if (friends.size >= 2) GlassIconButton(Sym.Group, stringResource(R.string.menu_new_group), { vm.open(Dest.NewGroup) }, backdrop)
            },
        ) { padding ->
            // loc sub ultimul rand, ca butonul de jos sa nu-l acopere
            val listPadding = if (rows.isEmpty()) padding else PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 72.dp)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = listPadding) {
                item(key = "problem") { NetworkProblem(vm, nearby.radio, gate, settings.batteryHintDismissed, Modifier.padding(horizontal = Gutter, vertical = 6.dp)) }
                when {
                    rows.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty_text),
                            illustration = { PinMascot(120.dp) },
                        ) {
                            AppButton(stringResource(R.string.messages_empty_action), { vm.open(Dest.AddFriend) }, compact = true)
                        }
                    }
                    else -> items(rows, key = { it.id }) { row ->
                        ConversationItem(vm, row, row.friend != null && nearby.isInRange(row.friend.nodeId), now, Modifier.animateItem())
                    }
                }
            }
        }
        // cu lista goala, ecranul gol are deja butonul; aici ar fi doua
        if (rows.isNotEmpty()) {
            AddFriendButton(
                { vm.open(Dest.AddFriend) },
                Modifier.align(Alignment.BottomEnd).padding(end = Gutter, bottom = LocalBottomClearance.current + 8.dp),
            )
        }
    }
}

/** Primul lucru care lipseste ca reteaua sa mearga, cu butonul care il rezolva. */
@Composable
private fun NetworkProblem(vm: AppViewModel, radio: RadioStatus, gate: RadioGate, batteryDismissed: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    when {
        !gate.hasAccess -> Banner(
            stringResource(R.string.problem_access_title), stringResource(R.string.problem_access_text), modifier, warning = true,
            action = stringResource(if (gate.accessBlocked) R.string.open_settings else R.string.problem_access_action),
            onAction = gate.requestAccess,
        )
        !gate.bluetoothOn -> Banner(
            stringResource(R.string.status_bt_off), stringResource(R.string.problem_bt_text), modifier, warning = true,
            action = stringResource(R.string.status_bt_enable), onAction = gate.enableBluetooth,
        )
        gate.locationOff -> Banner(
            stringResource(R.string.problem_location_title), stringResource(R.string.problem_location_text), modifier, warning = true,
            action = stringResource(R.string.problem_location_action),
            onAction = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
        radio.bluetoothOn && !radio.canAdvertise -> Banner(
            stringResource(R.string.problem_leaf_title), stringResource(R.string.problem_leaf_text), modifier,
        )
        !batteryDismissed && needsBatteryHint(context) -> Banner(
            stringResource(R.string.problem_battery_title), stringResource(R.string.problem_battery_text), modifier,
            action = stringResource(R.string.problem_battery_action),
            onAction = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
            dismiss = stringResource(R.string.problem_dismiss), onDismiss = { vm.dismissBatteryHint() },
        )
    }
}

/**
 * Randul unei conversatii, ca in Signal pe iPhone: bula de 56, numele ingrosat si ora pe primul rand,
 * ultimul mesaj pe doua randuri dedesubt; necititele intr-un cerc albastru sub ora.
 */
@Composable
private fun ConversationItem(vm: AppViewModel, row: ConversationRow, near: Boolean, now: Long, modifier: Modifier = Modifier) {
    val last = row.last
    val colors = AppTheme.colors
    val unreadLabel = if (row.unread > 0) pluralStringResource(R.plurals.unread_messages, row.unread, row.unread) else null
    val nearLabel = if (near) stringResource(R.string.presence_near) else null
    // starea se citeste dupa nume, previzualizare si ora; o descriere ar acoperi tot textul randului
    val state = listOfNotNull(unreadLabel, nearLabel).joinToString(", ")
    val you = stringResource(R.string.msg_you)
    val snippet = when {
        last == null -> buildAnnotatedString { append(stringResource(R.string.messages_no_messages)) }
        else -> {
            val prefix = when {
                last.kind == MsgKind.SYSTEM -> null
                row.group != null && last.fromMe -> you
                row.group != null -> vm.c.chat.nameOf(last.senderId)
                else -> null
            }
            val preview = messagePreview(vm, last)
            buildAnnotatedString {
                if (prefix != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(prefix) }
                    append(": ")
                }
                append(preview)
            }
        }
    }
    Row(
        modifier.fillMaxWidth().clickable { vm.open(Dest.Conversation(row.id)) }
            .padding(horizontal = Gutter, vertical = 12.dp)
            .then(if (state.isNotEmpty()) Modifier.semantics { stateDescription = state } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.title, 56.dp, near = near, group = row.group != null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title, style = MaterialTheme.typography.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (last != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(listTime(last.timeMs, now), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, maxLines = 1)
                }
            }
            Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.Top) {
                Text(
                    snippet, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                when {
                    row.unread > 0 -> UnreadBadge(row.unread, Modifier.padding(start = 6.dp))
                    last != null && last.fromMe && last.kind != MsgKind.SYSTEM -> DeliveryIcon(
                        last.status, if (last.status == MsgStatus.FAILED) colors.red else colors.secondaryLabel, colors.background,
                        Modifier.padding(start = 6.dp, top = 3.dp),
                    )
                }
            }
        }
    }
}
