package ro.safetyplease.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.R
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MsgStatus
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.QuickCode

private class ConversationRow(val id: String, val title: String, val group: Boolean, val last: ChatMessage?, val unread: Int)

@Composable
fun Avatar(name: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, group: Boolean = false) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (group) Icon(AppIcons.People, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        else Text(name.trim().take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun messagePreview(vm: AppViewModel, message: ChatMessage): String = when (message.kind) {
    MsgKind.TEXT -> message.text
    MsgKind.QUICK -> stringResource(Labels.quick(message.quickCode))
    MsgKind.ZONE -> stringResource(R.string.msg_zone, zoneLabel(vm, message))
    MsgKind.SYSTEM -> stringResource(R.string.msg_group_invite, vm.c.chat.nameOf(message.senderId))
}

@Composable
private fun zoneLabel(vm: AppViewModel, message: ChatMessage): String =
    if (message.zone.isEmpty()) stringResource(R.string.zone_unknown) else vm.venue.zoneName(message.zone)

@Composable
fun ChatListScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()

    val byConversation = chat.messages.groupBy { it.conversation }
    val rows = (friends.map { ConversationRow(Conversations.friend(it.nodeId), it.nickname, false, null, 0) } +
        groups.map { ConversationRow(Conversations.group(it.id), it.name, true, null, 0) })
        .map { row ->
            val messages = byConversation[row.id].orEmpty()
            ConversationRow(row.id, row.title, row.group, messages.lastOrNull(), messages.count { !it.read })
        }
        .sortedWith(compareByDescending<ConversationRow> { it.last?.timeMs ?: 0L }.thenBy { it.title.lowercase() })

    if (rows.isEmpty()) {
        EmptyState(AppIcons.Chat, stringResource(R.string.chat_empty_title), stringResource(R.string.chat_empty_text)) {
            Button(onClick = { vm.tab = Tab.FRIENDS }) { Text(stringResource(R.string.chat_empty_action)) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { row ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.open(Dest.Conversation(row.id)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(row.title, group = row.group)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        row.last?.let { messagePreview(vm, it) } ?: stringResource(R.string.chat_no_messages),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    row.last?.let { Text(Labels.clock(it.timeMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (row.unread > 0) Badge { Text(row.unread.toString()) }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
fun ConversationScreen(vm: AppViewModel, conversation: String) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val friend: Friend? = friends.firstOrNull { Conversations.friend(it.nodeId) == conversation }
    val group: Group? = groups.firstOrNull { Conversations.group(it.id) == conversation }
    val messages = chat.messages.filter { it.conversation == conversation }
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(conversation) {
        vm.enterConversation(conversation, friend?.nodeId)
        onDispose { vm.leaveConversation(conversation) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        vm.c.chat.markRead(conversation)
    }
    if (friend == null && group == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }

    val subtitle = when {
        group != null -> pluralStringResource(R.plurals.group_members, group.members.size, group.members.size)
        friend != null && vm.isInRange(friend, mesh) -> stringResource(R.string.friend_in_range)
        friend != null && friend.lastSeenAt > 0 -> stringResource(
            R.string.friend_last_seen, Labels.ago(friend.lastSeenAt),
            pluralStringResource(R.plurals.hops, friend.lastHops, friend.lastHops),
        )
        else -> stringResource(R.string.friend_never_seen)
    }

    ScreenScaffold(title = friend?.nickname ?: group!!.name, subtitle = subtitle, onBack = { vm.back() }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { "${it.senderId}:${it.msgId}" }) { message ->
                    MessageBubble(vm, message, showSender = group != null)
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {
                        if (!vm.sendMyZone(conversation)) Toast.makeText(context, R.string.chat_zone_unknown, Toast.LENGTH_LONG).show()
                    },
                    label = { Text(stringResource(R.string.quick_my_zone)) },
                    leadingIcon = { Icon(AppIcons.Place, null, Modifier.size(16.dp)) },
                )
                for (code in QuickCode.all) {
                    AssistChip(onClick = { vm.sendQuick(conversation, code) }, label = { Text(stringResource(Labels.quick(code))) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Limits.TEXT_BYTES / 2) },
                    placeholder = { Text(stringResource(R.string.chat_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        vm.sendText(conversation, draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) { Icon(AppIcons.Send, stringResource(R.string.send), tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

@Composable
private fun MessageBubble(vm: AppViewModel, message: ChatMessage, showSender: Boolean) {
    if (message.kind == MsgKind.SYSTEM) {
        Text(
            messagePreview(vm, message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        return
    }
    val mine = message.fromMe
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender && !mine) {
                    Text(vm.c.chat.nameOf(message.senderId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
                when (message.kind) {
                    MsgKind.ZONE -> Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                            vm.open(Dest.Pin(message.lat, message.lon, message.zone, vm.c.chat.nameOf(message.senderId)))
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.msg_zone, zoneLabel(vm, message)))
                            Text(stringResource(R.string.msg_zone_open), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    else -> Text(messagePreview(vm, message))
                }
            }
        }
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val meta = buildString {
                append(Labels.clock(message.timeMs))
                if (BuildConfig.DEBUG && !mine) append(" · ").append(pluralStringResource(R.plurals.hops, message.hops, message.hops))
                if (mine && message.recipients > 1) append(" · ${message.delivered}/${message.recipients}")
            }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mine) {
                Spacer(Modifier.width(4.dp))
                val (icon, tint) = when (message.status) {
                    MsgStatus.QUEUED -> AppIcons.Clock to MaterialTheme.colorScheme.onSurfaceVariant
                    MsgStatus.SENT -> AppIcons.Check to MaterialTheme.colorScheme.onSurfaceVariant
                    MsgStatus.DELIVERED -> AppIcons.DoneAll to Palette.Mesh
                    else -> AppIcons.Close to MaterialTheme.colorScheme.error
                }
                Icon(icon, stringResource(statusLabel(message.status)), tint = tint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun statusLabel(status: MsgStatus): Int = when (status) {
    MsgStatus.QUEUED -> R.string.msg_queued
    MsgStatus.SENT -> R.string.msg_sent
    MsgStatus.DELIVERED -> R.string.msg_delivered
    else -> R.string.msg_failed
}
