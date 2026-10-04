package ro.safetyplease.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MsgStatus
import ro.safetyplease.app.mesh.MeshState

private enum class MessageFilter { ALL, UNREAD, GROUPS }

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

/** Iconita, cuvantul si culoarea pentru starea unui mesaj trimis de noi. */
class MessageState(val icon: ImageVector, val label: Int, val shortLabel: Int, val tone: Color)

@Composable
fun messageState(status: MsgStatus): MessageState {
    val colors = LocalAppColors.current
    return when (status) {
        MsgStatus.QUEUED -> MessageState(AppIcons.Clock, R.string.msg_queued, R.string.msg_queued_short, colors.wait)
        MsgStatus.SENT -> MessageState(AppIcons.Check, R.string.msg_sent, R.string.msg_sent, colors.textSecondary)
        MsgStatus.DELIVERED -> MessageState(AppIcons.CheckCircle, R.string.msg_delivered, R.string.msg_delivered, colors.ok)
        else -> MessageState(AppIcons.Question, R.string.msg_failed, R.string.msg_failed, colors.wait)
    }
}

@Composable
fun presenceText(vm: AppViewModel, friend: Friend, mesh: MeshState): String = when {
    vm.isLinked(friend.nodeId, mesh) -> stringResource(R.string.presence_near) + " · " + stringResource(R.string.hop_direct)
    vm.isInRange(friend, mesh) -> stringResource(R.string.presence_near)
    friend.lastSeenAt > 0 -> stringResource(R.string.presence_seen, agoText(friend.lastSeenAt)) + " · " + hopsText(friend.lastHops)
    else -> stringResource(R.string.presence_never)
}

@Composable
fun MessagesScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gate = rememberRadioGate(vm, mesh, onStartMesh)
    val context = LocalContext.current
    val colors = LocalAppColors.current

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(MessageFilter.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }

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
    val shown = rows.filter { row ->
        when (filter) {
            MessageFilter.ALL -> true
            MessageFilter.UNREAD -> row.unread > 0
            MessageFilter.GROUPS -> row.group != null
        } && (query.isBlank() || row.title.contains(query.trim(), ignoreCase = true))
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomClearance.current + 84.dp),
        ) {
            item(key = "search") {
                PillTextField(
                    query, { query = it }, stringResource(R.string.messages_search),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp), leading = AppIcons.Search,
                    trailing = if (query.isEmpty()) null else {
                        { IconAction(AppIcons.Close, stringResource(R.string.clear), { query = "" }, tint = colors.textSecondary) }
                    },
                )
            }
            item(key = "status") { StatusLine(mesh, gate, Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp)) }
            item(key = "filters") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPill(stringResource(R.string.filter_all), filter == MessageFilter.ALL, { filter = MessageFilter.ALL }, count = rows.size)
                    FilterPill(
                        stringResource(R.string.filter_unread), filter == MessageFilter.UNREAD, { filter = MessageFilter.UNREAD },
                        count = rows.count { it.unread > 0 },
                    )
                    FilterPill(
                        stringResource(R.string.filter_groups), filter == MessageFilter.GROUPS, { filter = MessageFilter.GROUPS },
                        count = groups.size,
                    )
                }
            }
            item(key = "problem") {
                val cardModifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                when {
                    !gate.hasAccess -> ProblemCard(
                        AppIcons.Bluetooth, stringResource(R.string.problem_access_title), stringResource(R.string.problem_access_text),
                        cardModifier, tone = colors.danger, action = stringResource(R.string.problem_access_action), onAction = gate.requestAccess,
                    )
                    gate.locationOff -> ProblemCard(
                        AppIcons.Place, stringResource(R.string.problem_location_title), stringResource(R.string.problem_location_text),
                        cardModifier, action = stringResource(R.string.problem_location_action),
                        onAction = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    )
                    mesh.radio.bluetoothOn && !mesh.radio.canAdvertise -> ProblemCard(
                        AppIcons.Info, stringResource(R.string.problem_leaf_title), stringResource(R.string.problem_leaf_text), cardModifier,
                    )
                    !settings.batteryHintDismissed && needsBatteryHint(context) -> ProblemCard(
                        AppIcons.Battery, stringResource(R.string.problem_battery_title), stringResource(R.string.problem_battery_text),
                        cardModifier, action = stringResource(R.string.problem_battery_action),
                        onAction = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        dismiss = stringResource(R.string.problem_dismiss), onDismiss = { vm.dismissBatteryHint() },
                    )
                }
            }
            when {
                rows.isEmpty() -> item(key = "empty") {
                    EmptyState(stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty_text)) {
                        AppButton(stringResource(R.string.messages_empty_action), { vm.open(Dest.AddFriend) }, icon = AppIcons.QrCode)
                    }
                }
                shown.isEmpty() -> item(key = "none") {
                    Text(
                        when {
                            query.isNotBlank() -> stringResource(R.string.messages_none_found, query.trim())
                            filter == MessageFilter.UNREAD -> stringResource(R.string.messages_none_unread)
                            else -> stringResource(R.string.messages_none_groups)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                    )
                }
                else -> items(shown, key = { it.id }) { row -> ConversationItem(vm, row, mesh, Modifier.animateItem()) }
            }
        }

        AnimatedVisibility(menuOpen, enter = fadeIn(tween(Motion.QUICK)), exit = fadeOut(tween(100))) {
            Box(
                Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.72f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { menuOpen = false },
            )
        }
        if (rows.isNotEmpty()) {
            AddMenu(
                open = menuOpen,
                onToggle = { menuOpen = !menuOpen },
                onAddFriend = {
                    menuOpen = false
                    vm.open(Dest.AddFriend)
                },
                onNewGroup = {
                    menuOpen = false
                    vm.open(Dest.NewGroup)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = LocalBottomClearance.current + 4.dp),
            )
        }
    }
}

/** Linia de stare a retelei: iconita, cuvinte si, cand Bluetooth e oprit, butonul care il porneste. */
@Composable
fun StatusLine(mesh: MeshState, gate: RadioGate, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val links = mesh.readyLinks
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        val textStyle = MaterialTheme.typography.bodySmall
        when {
            !gate.hasAccess -> {
                Icon(AppIcons.Bluetooth, null, tint = colors.danger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_no_access), style = textStyle, color = colors.textSecondary, modifier = Modifier.weight(1f))
            }
            !gate.bluetoothOn -> {
                Icon(AppIcons.Bluetooth, null, tint = colors.danger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_bt_off), style = textStyle, color = colors.textSecondary)
                TextAction(stringResource(R.string.status_bt_enable), gate.enableBluetooth)
            }
            links == 0 -> {
                Icon(AppIcons.Signal, null, tint = colors.wait, modifier = Modifier.size(18.dp).pulse())
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_alone), style = textStyle, color = colors.textSecondary, modifier = Modifier.weight(1f))
            }
            else -> {
                Icon(AppIcons.Signal, null, tint = colors.ok, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    pluralStringResource(R.plurals.connected_phones, links, links), style = textStyle, color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(vm: AppViewModel, row: ConversationRow, mesh: MeshState, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val friend = row.friend
    val near = friend != null && vm.isInRange(friend, mesh)
    val source = remember { MutableInteractionSource() }
    val unreadLabel = if (row.unread > 0) pluralStringResource(R.plurals.unread_messages, row.unread, row.unread) else ""
    Row(
        modifier.fillMaxWidth().pressScale(source, pressed = 0.985f)
            .clickable(source, LocalIndication.current, role = Role.Button) { vm.open(Dest.Conversation(row.id)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.title, 52.dp, near = near, group = row.group != null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title, style = MaterialTheme.typography.titleMedium, color = colors.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                row.last?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(Labels.clock(it.timeMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (near) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colors.ok))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    when {
                        friend != null -> if (near) stringResource(R.string.presence_near)
                        else if (friend.lastSeenAt > 0) stringResource(R.string.presence_seen, agoText(friend.lastSeenAt))
                        else stringResource(R.string.presence_never)
                        else -> row.group?.let { pluralStringResource(R.plurals.group_members, it.members.size, it.members.size) }.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val last = row.last
                if (last != null && last.fromMe && last.kind != MsgKind.SYSTEM) {
                    val state = messageState(last.status)
                    Icon(state.icon, null, tint = state.tone, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    when {
                        last == null -> stringResource(R.string.messages_no_messages)
                        last.fromMe && last.kind != MsgKind.SYSTEM ->
                            stringResource(messageState(last.status).shortLabel) + " · " + messagePreview(vm, last)
                        row.group != null && last.kind != MsgKind.SYSTEM -> vm.c.chat.nameOf(last.senderId) + ": " + messagePreview(vm, last)
                        else -> messagePreview(vm, last)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.unread > 0) colors.text else colors.textSecondary,
                    fontWeight = if (row.unread > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (row.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    CountBadge(row.unread, colors.accent, colors.onAccent, Modifier.semantics { contentDescription = unreadLabel })
                }
            }
        }
    }
}

/** „+”: un cerc de accent jos-dreapta, cu meniul „Adauga prieten” si „Grup nou”. */
@Composable
private fun AddMenu(open: Boolean, onToggle: () -> Unit, onAddFriend: () -> Unit, onNewGroup: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val rotation by animateFloatAsState(if (open) 45f else 0f, tween(Motion.QUICK, easing = Motion.Standard), label = "plus")
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            open,
            enter = fadeIn(tween(Motion.QUICK)) + scaleIn(tween(Motion.QUICK, easing = Motion.Enter), initialScale = 0.86f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
        ) {
            Column(
                Modifier.shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(RoundedCornerShape(24.dp)).background(colors.cardHigh).width(IntrinsicSize.Max).padding(6.dp),
            ) {
                MenuRow(AppIcons.QrCode, stringResource(R.string.menu_add_friend), onAddFriend)
                MenuRow(AppIcons.People, stringResource(R.string.menu_new_group), onNewGroup)
            }
        }
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.size(58.dp).pressScale(source, pressed = 0.93f)
                .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(CircleShape).background(colors.accent)
                .clickable(source, LocalIndication.current, role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Add, stringResource(if (open) R.string.close else R.string.menu_open), tint = colors.onAccent,
                modifier = Modifier.size(26.dp).rotate(rotation),
            )
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = LocalAppColors.current.text) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        Spacer(Modifier.width(8.dp))
    }
}
