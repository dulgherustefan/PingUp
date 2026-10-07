package ro.safetyplease.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.MapPin
import ro.safetyplease.app.ui.common.VenueMap
import ro.safetyplease.app.ui.common.presenceText
import ro.safetyplease.app.ui.designsystem.ActionTile
import ro.safetyplease.app.ui.designsystem.AppMenu
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Avatar
import ro.safetyplease.app.ui.designsystem.Backdrop
import ro.safetyplease.app.ui.designsystem.DeliveryIcon
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.IconBtn
import ro.safetyplease.app.ui.designsystem.MenuRow
import ro.safetyplease.app.ui.designsystem.Motion
import ro.safetyplease.app.ui.designsystem.NavHeight
import ro.safetyplease.app.ui.designsystem.NavText
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.TextLink
import ro.safetyplease.app.ui.designsystem.TopEdge
import ro.safetyplease.app.ui.designsystem.TouchTarget
import ro.safetyplease.app.ui.designsystem.backdropSource
import ro.safetyplease.app.ui.designsystem.body
import ro.safetyplease.app.ui.designsystem.caption1
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.glass
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.rememberBackdrop
import ro.safetyplease.app.ui.designsystem.rememberHaptics
import ro.safetyplease.app.ui.designsystem.screenBackground
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title2
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.QuickCode
import ro.safetyplease.core.venue.GeoPoint
import kotlin.math.max

/** Messages from the same person closer than this form one run of grouped bubbles. */
private const val RUN_WINDOW_MS = 3 * 60_000L

private val Bubble = 18.dp
private val BubbleTight = 4.dp

/** A conversation list item: a day header or a message with its position in the run. */
private sealed interface ChatItem {
    val key: String

    class Day(val timeMs: Long) : ChatItem {
        override val key = "day:$timeMs"
    }

    class Message(val message: ChatMessage, val first: Boolean, val last: Boolean) : ChatItem {
        override val key = "${message.senderId}:${message.msgId}"
    }
}

private fun chatItems(messages: List<ChatMessage>): List<ChatItem> = buildList {
    messages.forEachIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        if (previous == null || Labels.daysBetween(previous.timeMs, message.timeMs) != 0) add(ChatItem.Day(message.timeMs))
        fun joined(a: ChatMessage?, b: ChatMessage?) = a != null && b != null && a.kind != MsgKind.SYSTEM && b.kind != MsgKind.SYSTEM &&
            a.senderId == b.senderId && b.timeMs - a.timeMs <= RUN_WINDOW_MS && Labels.daysBetween(a.timeMs, b.timeMs) == 0
        add(ChatItem.Message(message, first = !joined(previous, message), last = !joined(message, next)))
    }
}

/** Conversation: messages scroll under the top bar and the composer. */
@Composable
fun ConversationScreen(vm: AppViewModel, conversation: String) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val now = rememberNow()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val colors = AppTheme.colors

    val friend: Friend? = friends.firstOrNull { Conversations.friend(it.nodeId) == conversation }
    val group: Group? = groups.firstOrNull { Conversations.group(it.id) == conversation }
    val messages = remember(chat.messages, conversation) { chat.messages.filter { it.conversation == conversation } }
    val items = remember(messages) { chatItems(messages) }
    var draft by rememberSaveable { mutableStateOf("") }
    var quick by rememberSaveable { mutableStateOf(false) }
    var composerPx by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = items.lastIndex.coerceAtLeast(0))
    val backdrop = rememberBackdrop()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    // follow new messages only while the user is at the bottom; only their own scrolling changes that, not the keyboard
    var stick by rememberSaveable { mutableStateOf(true) }
    val userScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                stick = !listState.canScrollForward
                return Offset.Zero
            }
        }
    }
    BackHandler(enabled = quick) { quick = false }

    DisposableEffect(conversation) {
        vm.enterConversation(conversation, friend?.nodeId)
        onDispose { vm.leaveConversation(conversation) }
    }
    // keyed on the last message, not the count, so deleting an old message doesn't move the list
    LaunchedEffect(items.lastOrNull()?.key) {
        val mine = (items.lastOrNull() as? ChatItem.Message)?.message?.fromMe == true
        if (items.isNotEmpty() && (stick || mine)) {
            stick = true
            listState.animateScrollToItem(items.lastIndex)
        }
        vm.c.chat.markRead(conversation)
    }
    LaunchedEffect(composerPx) {
        if (stick && items.isNotEmpty()) listState.scrollToItem(items.lastIndex)
    }
    if (friend == null && group == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val title = friend?.nickname ?: group!!.name
    val subtitle = when {
        friend != null -> presenceText(friend, nearby, now)
        else -> pluralStringResource(R.plurals.group_members, group!!.members.size, group.members.size)
    }
    val lastMine = messages.lastOrNull { it.fromMe && it.kind != MsgKind.SYSTEM }
    val sendZone = {
        // with no known zone, open the map; the zone picked there is sent to the conversation
        if (vm.sendMyZone(conversation)) haptics.confirm()
        else vm.open(Dest.Map(sendTo = conversation))
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val composerHeight = with(density) { composerPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).screenBackground(colors.background)) {
            if (messages.isEmpty()) {
                FirstMessage(
                    title, near = friend != null && nearby.isInRange(friend.nodeId), group = group != null,
                    onQuick = { code ->
                        vm.sendQuick(conversation, code)
                        haptics.confirm()
                    },
                    modifier = Modifier.align(Alignment.Center).padding(bottom = composerHeight),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().nestedScroll(userScroll),
                    state = listState,
                    contentPadding = PaddingValues(top = top + NavHeight + 16.dp, bottom = composerHeight + 8.dp),
                    // a short conversation sits next to the composer, not at the top
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is ChatItem.Day -> DayHeader(item.timeMs, Modifier.animateItem())
                            is ChatItem.Message -> MessageItem(
                                vm, item.message, item.first, item.last, inGroup = group != null,
                                detail = item.message === lastMine,
                                onCopy = { text ->
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("mesaj", text))
                                },
                                modifier = Modifier.animateItem().padding(top = if (item.first) 12.dp else 2.dp),
                            )
                        }
                    }
                }
            }
        }

        TopEdge(top, colors.background, scrolled)
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(NavHeight).padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop)
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(R.string.open_profile), role = Role.Button) { vm.open(Dest.Profile(conversation)) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(title, 40.dp, near = friend != null && nearby.isInRange(friend.nodeId), group = group != null)
                Spacer(Modifier.width(12.dp))
                NavText {
                    Column {
                        Text(title, style = MaterialTheme.typography.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subtitle, style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium, color = colors.secondaryLabel,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerPx = it.height }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            AnimatedVisibility(
                quick,
                // the panel opens on a spring, like the keyboard, and closes quickly
                enter = fadeIn(tween(Motion.STANDARD)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(Motion.QUICK, easing = Motion.Exit)),
            ) {
                QuickPanel(
                    backdrop,
                    onQuick = { code ->
                        vm.sendQuick(conversation, code)
                        haptics.confirm()
                        quick = false
                    },
                )
            }
            Composer(
                backdrop = backdrop,
                draft = draft,
                onDraft = { draft = it.take(Limits.TEXT_BYTES / 2) },
                quickOpen = quick,
                onToggleQuick = {
                    quick = !quick
                    if (quick) focus.clearFocus()
                },
                onFocused = { quick = false },
                onZone = sendZone,
                onSend = {
                    vm.sendText(conversation, draft)
                    draft = ""
                    haptics.confirm()
                },
            )
        }
    }
}

/** Day label above its first message. */
@Composable
private fun DayHeader(timeMs: Long, modifier: Modifier = Modifier) {
    Text(
        Labels.dayLabel(LocalResources.current, timeMs), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium,
        color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 16.dp, bottom = 4.dp),
    )
}

/** Composer: + button, a pill text field with the send-my-zone button, and the send button once there's text. */
@Composable
private fun Composer(
    backdrop: Backdrop,
    draft: String,
    onDraft: (String) -> Unit,
    quickOpen: Boolean,
    onToggleQuick: () -> Unit,
    onFocused: () -> Unit,
    onZone: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = AppTheme.colors
    val hasText = draft.isNotBlank()
    val hint = stringResource(R.string.chat_hint)
    val turn by animateFloatAsState(if (quickOpen) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "plus")
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier.size(TouchTarget).glass(backdrop, CircleShape).clickable(role = Role.Button, onClick = onToggleQuick),
            contentAlignment = Alignment.Center,
        ) { Icon(Sym.Add, stringResource(R.string.chat_quick), Modifier.size(20.dp).rotate(turn), tint = colors.label) }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f).heightIn(min = TouchTarget).glass(backdrop, RoundedCornerShape(24.dp)),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp, top = 13.dp, bottom = 13.dp)
                    .onFocusChanged { if (it.isFocused) onFocused() }.semantics { contentDescription = hint },
                textStyle = MaterialTheme.typography.body.copy(color = colors.label),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) Text(hint, style = MaterialTheme.typography.body, color = colors.secondaryLabel, maxLines = 1)
                        inner()
                    }
                },
            )
            if (!hasText) IconBtn(Sym.Place, stringResource(R.string.chat_send_zone), onZone)
        }
        AnimatedContent(
            hasText,
            transitionSpec = { (fadeIn(tween(Motion.QUICK)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 600f), 0.5f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(90), 0.6f)) },
            label = "send",
        ) { text ->
            if (text) {
                Box(
                    Modifier.padding(start = 12.dp).size(TouchTarget).clip(CircleShape).background(colors.accent)
                        .clickable(onClickLabel = stringResource(R.string.send), role = Role.Button, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) { Icon(Sym.Send, stringResource(R.string.send), Modifier.size(20.dp), tint = colors.onAccent) }
            } else {
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}

/**
 * Phrases sent with one tap. Your zone has its own button in the composer; "low battery" was dropped
 * from this list, but it still displays when someone else sends it.
 */
private val QuickPhrases = listOf(QuickCode.WHERE_ARE_YOU, QuickCode.COMING, QuickCode.MEET_AT_POINT)

/** Quick messages, in a card above the composer. */
@Composable
private fun QuickPanel(backdrop: Backdrop, onQuick: (Int) -> Unit) {
    val icons = mapOf(QuickCode.WHERE_ARE_YOU to Sym.Help, QuickCode.COMING to Sym.Walk, QuickCode.MEET_AT_POINT to Sym.Flag)
    Row(
        Modifier.padding(horizontal = Gutter).fillMaxWidth().glass(backdrop, RoundedCornerShape(26.dp)).padding(10.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (code in QuickPhrases) {
            ActionTile(icons[code] ?: Sym.Chat, stringResource(Labels.quick(code)), { onQuick(code) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * Empty conversation: the friend's avatar, a line about how messages travel, and quick phrases
 * so the first message is one tap away.
 */
@Composable
private fun FirstMessage(title: String, near: Boolean, group: Boolean, onQuick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(title, 88.dp, near = near, group = group)
        Text(
            title, style = MaterialTheme.typography.title2, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.chat_empty_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (code in QuickPhrases) {
                Box(Modifier.heightIn(min = TouchTarget).clip(CircleShape).clickable(role = Role.Button) { onQuick(code) }, contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Labels.quick(code)), style = MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.label, modifier = Modifier.clip(CircleShape).background(colors.fill).padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    vm: AppViewModel,
    message: ChatMessage,
    first: Boolean,
    last: Boolean,
    inGroup: Boolean,
    detail: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val preview = messagePreview(vm, message)
    val colors = AppTheme.colors
    if (message.kind == MsgKind.SYSTEM) {
        Text(
            preview, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        )
        return
    }
    val mine = message.fromMe
    var menu by remember { mutableStateOf(false) }
    val ink = if (mine) colors.onBubbleOut else colors.label
    val meta = if (mine) colors.onBubbleOutSecondary else colors.secondaryLabel
    // corners facing the edge tighten when bubbles from the same person are grouped
    val shape = if (mine) {
        RoundedCornerShape(topStart = Bubble, bottomStart = Bubble, topEnd = if (first) Bubble else BubbleTight, bottomEnd = if (last) Bubble else BubbleTight)
    } else {
        RoundedCornerShape(topEnd = Bubble, bottomEnd = Bubble, topStart = if (first) Bubble else BubbleTight, bottomStart = if (last) Bubble else BubbleTight)
    }
    val sender = if (!mine && inGroup) vm.c.chat.nameOf(message.senderId) else null
    val clock = Labels.clock(message.timeMs)
    val stateWords = if (mine) stringResource(messageStateLabel(message.status)) else null

    Column(modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(
            Modifier.padding(start = if (mine) 48.dp else if (inGroup) 12.dp else Gutter, end = if (mine) Gutter else 48.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!mine && inGroup) {
                // the sender's avatar sits next to the last bubble in a run; the others keep the space empty
                if (last) Avatar(sender.orEmpty(), 28.dp) else Spacer(Modifier.width(28.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (mine && message.status == MsgStatus.FAILED) {
                DeliveryIcon(MsgStatus.FAILED, colors.red, colors.background, Modifier.padding(end = 8.dp, bottom = 10.dp), iconSize = 20.dp)
            }
            Box {
                Column(
                    Modifier.widthIn(max = if (inGroup) 285.dp else 317.dp)
                        .drawBehind {
                            val outline = shape.createOutline(size, layoutDirection, this)
                            if (mine) {
                                drawOutline(outline, colors.bubbleOut)
                            } else {
                                drawOutline(outline, colors.bubbleIn)
                            }
                        }
                        .clip(shape)
                        .combinedClickable(
                            // keep enabled: disabling would also block the long-press menu
                            onClickLabel = if (message.kind == MsgKind.ZONE) stringResource(R.string.open_zone) else null,
                            onLongClickLabel = stringResource(R.string.more_options),
                            onClick = {
                                if (message.kind == MsgKind.ZONE) {
                                    vm.open(Dest.Pin(message.lat, message.lon, message.zone, vm.c.chat.nameOf(message.senderId)))
                                }
                            },
                            onLongClick = {
                                haptics.longPress()
                                menu = true
                            },
                        ),
                ) {
                    if (message.kind == MsgKind.ZONE) ZoneMap(vm, message)
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)) {
                        if (sender != null && first) {
                            Text(
                                sender, style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold,
                                color = colors.names[(message.senderId.hashCode() and 0x7fffffff) % colors.names.size],
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                        BubbleText(preview, ink, bold = message.kind == MsgKind.ZONE) {
                            Row(
                                Modifier.clearAndSetSemantics { contentDescription = listOfNotNull(stateWords, clock).joinToString(", ") },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(clock, style = MaterialTheme.typography.caption1, color = meta)
                                if (mine) {
                                    Spacer(Modifier.width(4.dp))
                                    DeliveryIcon(message.status, meta, colors.bubbleOut, iconSize = 12.dp)
                                }
                            }
                        }
                    }
                }
                AppMenu(menu, { menu = false }) {
                    MenuRow(stringResource(R.string.msg_copy), {
                        menu = false
                        onCopy(preview)
                    }, icon = Sym.Copy)
                    if (mine && message.status != MsgStatus.DELIVERED) {
                        MenuRow(stringResource(R.string.msg_resend), {
                            menu = false
                            vm.resend(message)
                        }, icon = Sym.Refresh)
                    }
                    MenuRow(stringResource(R.string.msg_delete), {
                        menu = false
                        vm.deleteMessage(message)
                    }, icon = Sym.Delete, danger = true)
                }
            }
        }
        if (mine) OwnState(message, detail, onResend = { vm.resend(message) })
    }
}

/** Bubble text with the time in the bottom corner: on the last line if it fits, otherwise on a new line. */
@Composable
private fun BubbleText(text: String, color: Color, bold: Boolean, footer: @Composable () -> Unit) {
    val holder = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        content = {
            Text(
                text, style = MaterialTheme.typography.body, color = color, fontWeight = if (bold) FontWeight.SemiBold else null,
                onTextLayout = { holder[0] = it },
            )
            footer()
        },
    ) { measurables, constraints ->
        val text = measurables[0].measure(constraints.copy(minWidth = 0))
        val foot = measurables[1].measure(Constraints())
        val gap = 6.dp.roundToPx()
        val lastRight = holder[0]?.let { it.getLineRight(it.lineCount - 1).toInt() } ?: text.width
        val inline = lastRight + gap + foot.width <= constraints.maxWidth
        if (inline) {
            val width = max(text.width, lastRight + gap + foot.width)
            val height = max(text.height, foot.height)
            layout(width, height) {
                text.place(0, 0)
                foot.place(width - foot.width, height - foot.height)
            }
        } else {
            val width = max(text.width, foot.width)
            val down = 3.dp.roundToPx()
            layout(width, text.height + down + foot.height) {
                text.place(0, 0)
                foot.place(width - foot.width, text.height + down)
            }
        }
    }
}

/** Mini map in a shared-zone bubble, with a dot where the sender was. */
@Composable
private fun ZoneMap(vm: AppViewModel, message: ChatMessage) {
    val lat = message.lat
    val lon = message.lon
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else vm.venue.zone(message.zone)?.center
    VenueMap(
        venue = vm.venue,
        modifier = Modifier.width(240.dp),
        pins = listOfNotNull(point?.let { MapPin(it, AppTheme.colors.accent) }),
        highlightZone = message.zone,
        compact = true,
    )
}

/** Under the last sent message: delivery state in words. A failed message gets its retry button here. */
@Composable
private fun OwnState(message: ChatMessage, detail: Boolean, onResend: () -> Unit) {
    val colors = AppTheme.colors
    when {
        message.status == MsgStatus.FAILED -> Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.msg_failed), style = MaterialTheme.typography.footnote, color = colors.redInk)
            TextLink(stringResource(R.string.msg_resend), onResend)
        }
        !detail -> Unit
        message.status == MsgStatus.QUEUED -> Text(
            stringResource(R.string.msg_queued), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
            modifier = Modifier.padding(top = 4.dp, end = Gutter),
        )
        message.recipients > 1 -> Text(
            stringResource(R.string.msg_group_delivered, message.delivered, message.recipients), style = MaterialTheme.typography.footnote,
            color = colors.secondaryLabel, modifier = Modifier.padding(top = 4.dp, end = Gutter),
        )
    }
}
