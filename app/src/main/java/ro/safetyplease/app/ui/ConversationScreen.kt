package ro.safetyplease.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MsgStatus
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.QuickCode

/** Mesajele aceluiasi om, la mai putin de atat unul de altul, stau sub un singur antet cu nume si ora. */
private const val GROUP_WINDOW_MS = 3 * 60_000L

@Composable
fun ConversationScreen(vm: AppViewModel, conversation: String) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val haptics = rememberHaptics()

    val friend: Friend? = friends.firstOrNull { Conversations.friend(it.nodeId) == conversation }
    val group: Group? = groups.firstOrNull { Conversations.group(it.id) == conversation }
    val messages = remember(chat.messages, conversation) { chat.messages.filter { it.conversation == conversation } }
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
    val title = friend?.nickname ?: group!!.name
    val near = friend != null && vm.isInRange(friend, mesh)
    val subtitle = when {
        friend != null -> presenceText(vm, friend, mesh)
        else -> pluralStringResource(R.plurals.group_members, group!!.members.size, group.members.size)
    }
    val lastMine = messages.lastOrNull { it.fromMe && it.kind != MsgKind.SYSTEM }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TopBar(
            title = title, subtitle = subtitle, onBack = { vm.back() },
            leading = { Avatar(title, 40.dp, near = near, group = group != null) },
            onTitleClick = { vm.open(Dest.Profile(conversation)) },
        )
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.messages_no_messages), stringResource(R.string.chat_empty_text))
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                itemsIndexed(messages, key = { _, it -> "${it.senderId}:${it.msgId}" }) { index, message ->
                    val previous = messages.getOrNull(index - 1)
                    val startsRun = previous == null || previous.kind == MsgKind.SYSTEM || previous.senderId != message.senderId ||
                        message.timeMs - previous.timeMs > GROUP_WINDOW_MS
                    MessageItem(
                        vm, message,
                        showHeader = startsRun,
                        verboseState = message === lastMine,
                        onCopy = { text ->
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("mesaj", text))
                        },
                        modifier = Modifier.animateItem().padding(top = if (startsRun) 10.dp else 3.dp),
                    )
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickPill(stringResource(R.string.quick_my_zone), AppIcons.Place) {
                if (vm.sendMyZone(conversation)) haptics.confirm()
                else Toast.makeText(context, R.string.chat_zone_unknown, Toast.LENGTH_LONG).show()
            }
            for (code in QuickCode.all) {
                QuickPill(stringResource(Labels.quick(code))) {
                    vm.sendQuick(conversation, code)
                    haptics.confirm()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            PillTextField(
                draft, { draft = it.take(Limits.TEXT_BYTES / 2) }, stringResource(R.string.chat_hint),
                Modifier.weight(1f), maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            SendButton(enabled = draft.isNotBlank()) {
                vm.sendText(conversation, draft)
                draft = ""
                haptics.confirm()
            }
        }
    }
}

@Composable
private fun QuickPill(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = 48.dp).pressScale(source).clickable(source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(38.dp).clip(CircleShape).border(1.dp, colors.textSecondary.copy(alpha = 0.4f), CircleShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.text, maxLines = 1)
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(52.dp).pressScale(source, enabled, pressed = 0.9f).clip(CircleShape)
            .background(if (enabled) colors.accent else colors.card)
            .clickable(source, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Send, stringResource(R.string.send),
            tint = if (enabled) colors.onAccent else colors.textSecondary, modifier = Modifier.size(22.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    vm: AppViewModel,
    message: ChatMessage,
    showHeader: Boolean,
    verboseState: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val haptics = rememberHaptics()
    val preview = messagePreview(vm, message)
    if (message.kind == MsgKind.SYSTEM) {
        Text(
            preview, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
            textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        return
    }
    val mine = message.fromMe
    var menu by remember { mutableStateOf(false) }
    // doar mesajele abia trimise sau primite intra animat; istoricul apare pe loc
    val fresh = remember(message.msgId) { System.currentTimeMillis() - message.timeMs < 1_500 }

    Column(modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine && showHeader) {
            Row(Modifier.padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(vm.c.chat.nameOf(message.senderId), style = MaterialTheme.typography.labelMedium, color = colors.text)
                Spacer(Modifier.width(6.dp))
                Text(Labels.clock(message.timeMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
        Box {
            val shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp, bottomEnd = if (mine) 6.dp else 20.dp, bottomStart = if (mine) 20.dp else 6.dp,
            )
            val bubbleText = if (mine) colors.onForest else colors.text
            Box(
                Modifier.then(if (fresh) Modifier.bubbleEnter(fromEnd = mine) else Modifier)
                    .widthIn(max = 296.dp).clip(shape).background(if (mine) colors.forest else colors.bubbleOther)
                    .then(if (mine) Modifier else Modifier.border(1.dp, colors.hairline, shape))
                    .combinedClickable(
                        onClick = {
                            if (message.kind == MsgKind.ZONE) {
                                vm.open(Dest.Pin(message.lat, message.lon, message.zone, vm.c.chat.nameOf(message.senderId)))
                            }
                        },
                        onLongClick = {
                            haptics.longPress()
                            menu = true
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (message.kind == MsgKind.ZONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (mine) Color.White.copy(alpha = 0.16f) else colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) { Icon(AppIcons.Place, null, tint = if (mine) Color.White else colors.accent, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(preview, style = MaterialTheme.typography.bodyLarge, color = bubbleText)
                            Text(
                                stringResource(R.string.msg_zone_open), style = MaterialTheme.typography.labelMedium,
                                color = if (mine) Brand.Sage else colors.accent,
                            )
                        }
                    }
                } else {
                    Text(preview, style = MaterialTheme.typography.bodyLarge, color = bubbleText)
                }
            }
            DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                containerColor = colors.cardHigh, shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(horizontal = 6.dp).width(220.dp)) {
                    MenuRow(AppIcons.Copy, stringResource(R.string.msg_copy), {
                        menu = false
                        onCopy(preview)
                    })
                    if (mine && message.status != MsgStatus.DELIVERED) {
                        MenuRow(AppIcons.Retry, stringResource(R.string.msg_resend), {
                            menu = false
                            vm.resend(message)
                        })
                    }
                    MenuRow(AppIcons.Delete, stringResource(R.string.msg_delete), {
                        menu = false
                        vm.deleteMessage(message)
                    }, tint = colors.danger)
                }
            }
        }
        if (mine) OwnState(message, verboseState, onResend = { vm.resend(message) })
    }
}

/** Starea de sub balon: mereu iconita si ora; scrisa in cuvinte la ultimul mesaj trimis si la cele cu probleme. */
@Composable
private fun OwnState(message: ChatMessage, verbose: Boolean, onResend: () -> Unit) {
    val colors = LocalAppColors.current
    AnimatedContent(
        targetState = message.status to message.delivered,
        transitionSpec = { fadeIn(tween(Motion.STANDARD)) togetherWith fadeOut(tween(100)) },
        label = "messageState",
    ) { (status, delivered) ->
        val state = messageState(status)
        val problem = status == MsgStatus.QUEUED || status == MsgStatus.FAILED
        val words = when {
            problem -> stringResource(state.label)
            !verbose -> null
            message.recipients > 1 -> stringResource(R.string.msg_group_delivered, delivered, message.recipients)
            else -> stringResource(state.label)
        }
        Row(Modifier.padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(state.icon, if (words == null) stringResource(state.label) else null, tint = state.tone, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            if (words != null) {
                Text(words, style = MaterialTheme.typography.labelMedium, color = if (status == MsgStatus.SENT) colors.textSecondary else state.tone)
                Text(" · ", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
            Text(Labels.clock(message.timeMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            if (status == MsgStatus.FAILED) {
                Spacer(Modifier.width(4.dp))
                TextAction(stringResource(R.string.msg_resend), onResend)
            }
        }
    }
}
