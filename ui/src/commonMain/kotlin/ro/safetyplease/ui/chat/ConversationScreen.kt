package ro.safetyplease.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import kotlin.math.max
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.QuickCode
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.common.BackHandler
import ro.safetyplease.ui.common.LostConfirmDialog
import ro.safetyplease.ui.common.MapPin
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.common.presenceText
import ro.safetyplease.ui.designsystem.ActionTile
import ro.safetyplease.ui.designsystem.AppMenu
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.Backdrop
import ro.safetyplease.ui.designsystem.BarInset
import ro.safetyplease.ui.designsystem.DeliveryIcon
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GlassSize
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.IconBtn
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.LocalStatusBarTop
import ro.safetyplease.ui.designsystem.MenuRow
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.NavHeight
import ro.safetyplease.ui.designsystem.Radius
import ro.safetyplease.ui.designsystem.RevealValue
import ro.safetyplease.ui.designsystem.Swap
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.TopEdge
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.animatedColor
import ro.safetyplease.ui.designsystem.backdropSource
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.caption1
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.glass
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.listItem
import ro.safetyplease.ui.designsystem.motionSpec
import ro.safetyplease.ui.designsystem.pressScale
import ro.safetyplease.ui.designsystem.rememberBackdrop
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.screenBackground
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.tabular
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.chat_empty_text
import ro.safetyplease.ui.resources.chat_hint
import ro.safetyplease.ui.resources.chat_quick
import ro.safetyplease.ui.resources.chat_send_zone
import ro.safetyplease.ui.resources.come_button
import ro.safetyplease.ui.resources.come_sent
import ro.safetyplease.ui.resources.group_members
import ro.safetyplease.ui.resources.lost_button
import ro.safetyplease.ui.resources.meet_propose
import ro.safetyplease.ui.resources.more_options
import ro.safetyplease.ui.resources.msg_copy
import ro.safetyplease.ui.resources.msg_delete
import ro.safetyplease.ui.resources.msg_failed
import ro.safetyplease.ui.resources.msg_group_delivered
import ro.safetyplease.ui.resources.msg_queued
import ro.safetyplease.ui.resources.msg_resend
import ro.safetyplease.ui.resources.open_profile
import ro.safetyplease.ui.resources.open_zone
import ro.safetyplease.ui.resources.seen_ask
import ro.safetyplease.ui.resources.seen_lighting
import ro.safetyplease.ui.resources.seen_sent
import ro.safetyplease.ui.resources.send
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.dayLabel
import ro.safetyplease.ui.text.hopsText
import ro.safetyplease.ui.text.rememberNow

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
        fun joined(a: ChatMessage?, b: ChatMessage?) = a != null && b != null && !a.kind.isNotice && !b.kind.isNotice &&
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
    val meetings by vm.c.chat.meetings.collectAsStateWithLifecycle()
    val now = rememberNow()
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
    var barPx by remember { mutableIntStateOf(0) }
    var pinPx by remember { mutableIntStateOf(0) }
    var meetingPicker by rememberSaveable { mutableStateOf(false) }
    var lostAsk by rememberSaveable { mutableStateOf(false) }
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
    val lit = rememberLighting(vm, friend?.nodeId)
    val meeting = meetings.firstOrNull { it.conversation == conversation }
    val subtitle = when {
        lit -> stringResource(Res.string.seen_lighting)
        friend != null -> presenceText(friend, nearby, now)
        else -> pluralStringResource(Res.plurals.group_members, group!!.members.size, group.members.size)
    }
    val lastMine = messages.lastOrNull { it.fromMe && !it.kind.isNotice }
    // what was there when the conversation opened stays put; what comes after rises in once
    val shownAtOpen = remember(conversation) { items.mapTo(HashSet()) { it.key } }
    val risen = remember(conversation) { HashSet<String>() }
    val sendZone = {
        // with no known zone, open the map; the zone picked there is sent to the conversation
        if (vm.sendMyZone(conversation)) haptics.confirm()
        else vm.open(Dest.Map(sendTo = conversation))
    }
    val top = LocalStatusBarTop.current ?: WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val composerHeight = with(density) { composerPx.toDp() }
    // the list moves down with the pin as it opens, not before
    val pinHeight by animateDpAsState(
        if (meeting != null) with(density) { pinPx.toDp() } + 8.dp else 0.dp, motionSpec(tween(Motion.STANDARD, easing = Motion.Standard)), label = "pin",
    )
    val extras = buildList {
        add(QuickAction(Sym.Flag, stringResource(Res.string.meet_propose)) {
            meetingPicker = true
            quick = false
        })
        if (group != null) {
            add(QuickAction(Sym.PersonSearch, stringResource(Res.string.lost_button)) {
                lostAsk = true
                quick = false
            })
        }
        if (friend != null) {
            add(QuickAction(Sym.Walk, stringResource(Res.string.come_button)) {
                vm.askFriend(Res.string.come_sent) { vm.c.chat.requestComing(friend.nodeId) }
                haptics.confirm()
                quick = false
            })
        }
    }
    // the header grows with the system font size instead of capping the name
    val bar = maxOf(NavHeight, with(density) { barPx.toDp() })

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).screenBackground(colors.background)) {
            // the phrase tapped on the empty screen becomes the first bubble: the list fades in over it
            AnimatedContent(
                messages.isEmpty(), Modifier.fillMaxSize(),
                transitionSpec = { fadeIn(tween(Motion.STANDARD, easing = Motion.Enter)) togetherWith fadeOut(tween(Motion.QUICK)) },
                contentAlignment = Alignment.Center,
                label = "firstMessage",
            ) { empty ->
            if (empty) {
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
                    contentPadding = PaddingValues(top = top + bar + 16.dp + pinHeight, bottom = composerHeight + 8.dp),
                    // a short conversation sits next to the composer, not at the top
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is ChatItem.Day -> DayHeader(item.timeMs, listItem())
                            is ChatItem.Message -> {
                                // only what arrives while the conversation is open rises in, and only once
                                val rises = remember { item.key !in shownAtOpen && risen.add(item.key) }
                                MessageItem(
                                    vm, item.message, item.first, item.last, inGroup = group != null,
                                    detail = item.message === lastMine,
                                    onCopy = { text ->
                                        vm.phone.copyText(text)
                                    },
                                    modifier = listItem().padding(top = if (item.first) 12.dp else 2.dp).rise(rises, item.message.fromMe),
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        TopEdge(top, colors.background, scrolled, bar)
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = NavHeight).onSizeChanged { barPx = it.height }
                .padding(horizontal = BarInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop)
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(Res.string.open_profile), role = Role.Button) { vm.open(Dest.Profile(conversation)) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(title, GlassSize, near = friend != null && nearby.isInRange(friend.nodeId), group = group != null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val tone = animatedColor(if (lit) colors.accent else colors.secondaryLabel, "presence")
                    Swap(subtitle) {
                        Text(it, style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (friend != null) {
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Sym.Torch, stringResource(Res.string.seen_ask), {
                    vm.askFriend(Res.string.seen_sent) { vm.c.chat.pingFriend(friend.nodeId) }
                    haptics.confirm()
                }, backdrop)
            }
        }
        RevealValue(meeting, Modifier.statusBarsPadding().padding(top = bar + 4.dp)) { shown ->
            MeetingPin(vm, shown, now, backdrop, Modifier.onSizeChanged { pinPx = it.height })
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerPx = it.height }
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            val reduce = LocalReduceMotion.current
            AnimatedVisibility(
                quick,
                // the panel opens on a spring, like the keyboard, and closes quickly
                enter = if (reduce) fadeIn(tween(Motion.QUICK))
                else fadeIn(tween(Motion.STANDARD)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f, visibilityThreshold = IntSize.VisibilityThreshold)),
                exit = if (reduce) fadeOut(tween(90)) else fadeOut(tween(100)) + shrinkVertically(tween(Motion.QUICK, easing = Motion.Exit)),
            ) {
                QuickPanel(
                    backdrop,
                    extras,
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
        if (meetingPicker) MeetingDialog(vm, conversation) { meetingPicker = false }
        if (lostAsk) LostConfirmDialog(vm, conversation) { lostAsk = false }
    }
}

/** Day label above its first message. */
@Composable
private fun DayHeader(timeMs: Long, modifier: Modifier = Modifier) {
    Text(
        dayLabel(timeMs), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Medium,
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
    val hint = stringResource(Res.string.chat_hint)
    val turn by animateFloatAsState(if (quickOpen) 45f else 0f, motionSpec(spring(dampingRatio = 0.6f, stiffness = 500f)), label = "plus")
    val plusPress = remember { MutableInteractionSource() }
    val sendPress = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier.pressScale(plusPress).size(TouchTarget).glass(backdrop, CircleShape)
                .clickable(plusPress, LocalIndication.current, role = Role.Button, onClick = onToggleQuick),
            contentAlignment = Alignment.Center,
        ) { Icon(Sym.Add, stringResource(Res.string.chat_quick), Modifier.size(20.dp).rotate(turn), tint = colors.label) }
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
            // the zone button folds away as the send button opens, at the same pace, so the field never jumps
            val reduce = LocalReduceMotion.current
            AnimatedVisibility(
                !hasText,
                enter = if (reduce) fadeIn(tween(Motion.QUICK))
                else fadeIn(tween(Motion.QUICK)) + expandHorizontally(tween(Motion.QUICK, easing = Motion.Standard), expandFrom = Alignment.Start),
                exit = if (reduce) fadeOut(tween(90)) else fadeOut(tween(90)) + shrinkHorizontally(tween(Motion.QUICK, easing = Motion.Exit), shrinkTowards = Alignment.Start),
            ) { IconBtn(Sym.Place, stringResource(Res.string.chat_send_zone), onZone) }
        }
        val reduce = LocalReduceMotion.current
        AnimatedContent(
            hasText,
            transitionSpec = {
                val size = SizeTransform(clip = false) { _, _ -> tween(Motion.QUICK, easing = Motion.Standard) }
                if (reduce) (fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))).using(size)
                else ((fadeIn(tween(Motion.QUICK)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 600f), 0.7f)) togetherWith (fadeOut(tween(90)) + scaleOut(tween(90), 0.7f))).using(size)
            },
            label = "send",
        ) { text ->
            if (text) {
                Box(
                    Modifier.padding(start = 12.dp).pressScale(sendPress).size(TouchTarget).clip(CircleShape).background(colors.accent)
                        .clickable(sendPress, LocalIndication.current, onClickLabel = stringResource(Res.string.send), role = Role.Button, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) { Icon(Sym.Send, stringResource(Res.string.send), Modifier.size(20.dp), tint = colors.onAccent) }
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

/** A tile under the quick phrases that does something instead of sending a phrase. */
private class QuickAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Quick messages, in a card above the composer, and under them the meeting point and "Vin la tine". */
@Composable
private fun QuickPanel(backdrop: Backdrop, extras: List<QuickAction>, onQuick: (Int) -> Unit) {
    val icons = mapOf(QuickCode.WHERE_ARE_YOU to Sym.Help, QuickCode.COMING to Sym.Walk, QuickCode.MEET_AT_POINT to Sym.Flag)
    Column(
        Modifier.padding(horizontal = Gutter).fillMaxWidth().glass(backdrop, RoundedCornerShape(Radius.Card)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (code in QuickPhrases) {
                ActionTile(icons[code] ?: Sym.Chat, stringResource(Labels.quick(code)), { onQuick(code) }, Modifier.weight(1f).fillMaxHeight())
            }
        }
        // the actions keep the phrases' three columns, so the two rows read as one grid
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in extras) ActionTile(action.icon, action.label, action.onClick, Modifier.weight(1f).fillMaxHeight())
            repeat(QuickPhrases.size - extras.size) { Spacer(Modifier.weight(1f)) }
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
            stringResource(Res.string.chat_empty_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
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
    if (message.kind.isNotice) {
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
    // the route is known only for received messages: a delivery receipt doesn't say which way ours went
    val route = if (mine) null else hopsText(message.hops)

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
                    Modifier.then(if (message.kind == MsgKind.ZONE) Modifier.width(ZoneMapWidth) else Modifier.widthIn(max = if (inGroup) 285.dp else 317.dp))
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
                            onClickLabel = if (message.kind == MsgKind.ZONE) stringResource(Res.string.open_zone) else null,
                            onLongClickLabel = stringResource(Res.string.more_options),
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
                                Modifier.clearAndSetSemantics { contentDescription = listOfNotNull(stateWords, clock, route).joinToString(", ") },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(listOfNotNull(clock, route).joinToString(" · "), style = MaterialTheme.typography.caption1, color = meta)
                                if (mine) {
                                    Spacer(Modifier.width(4.dp))
                                    DeliveryIcon(message.status, meta, colors.bubbleOut, iconSize = 12.dp)
                                }
                            }
                        }
                    }
                }
                AppMenu(menu, { menu = false }) {
                    MenuRow(stringResource(Res.string.msg_copy), {
                        menu = false
                        onCopy(preview)
                    }, icon = Sym.Copy)
                    if (mine && message.status != MsgStatus.DELIVERED) {
                        MenuRow(stringResource(Res.string.msg_resend), {
                            menu = false
                            vm.resend(message)
                        }, icon = Sym.Refresh)
                    }
                    MenuRow(stringResource(Res.string.msg_delete), {
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
        // with the route next to the time, at large font sizes the bottom line would stick out of the bubble
        val foot = measurables[1].measure(Constraints(maxWidth = constraints.maxWidth))
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

private val ZoneMapWidth = 240.dp

/** Mini map in a shared-zone bubble, with a dot where the sender was. */
@Composable
private fun ZoneMap(vm: AppViewModel, message: ChatMessage) {
    val lat = message.lat
    val lon = message.lon
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else vm.venue.zone(message.zone)?.center
    VenueMap(
        venue = vm.venue,
        modifier = Modifier.width(ZoneMapWidth),
        pins = listOfNotNull(point?.let { MapPin(it, AppTheme.colors.accent) }),
        highlightZone = message.zone,
        compact = true,
    )
}

/** Under the last sent message: delivery state in words. A failed message gets its retry button here. */
@Composable
private fun OwnState(message: ChatMessage, detail: Boolean, onResend: () -> Unit) {
    val colors = AppTheme.colors
    val state = when {
        message.status == MsgStatus.FAILED -> OwnWords.FAILED
        !detail -> OwnWords.NONE
        message.status == MsgStatus.QUEUED -> OwnWords.QUEUED
        message.recipients > 1 -> OwnWords.GROUP
        else -> OwnWords.NONE
    }
    Swap(state, contentAlignment = Alignment.TopEnd) { shown ->
        when (shown) {
            OwnWords.FAILED -> Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.msg_failed), style = MaterialTheme.typography.footnote, color = colors.redInk)
                TextLink(stringResource(Res.string.msg_resend), onResend, style = MaterialTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold))
            }
            OwnWords.QUEUED -> Text(
                stringResource(Res.string.msg_queued), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, end = Gutter),
            )
            OwnWords.GROUP -> Text(
                stringResource(Res.string.msg_group_delivered, message.delivered, message.recipients), style = MaterialTheme.typography.footnote.tabular(),
                color = colors.secondaryLabel, modifier = Modifier.padding(top = 4.dp, end = Gutter),
            )
            OwnWords.NONE -> Spacer(Modifier.width(0.dp))
        }
    }
}

private enum class OwnWords { FAILED, QUEUED, GROUP, NONE }

/**
 * A bubble arriving while the conversation is open: it rises a little from the composer as it fades in, from its own
 * side. Under reduced motion it only fades, with the list.
 */
@Composable
private fun Modifier.rise(play: Boolean, mine: Boolean): Modifier {
    if (!play || LocalReduceMotion.current) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
    val lift = with(LocalDensity.current) { 16.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        translationY = (1f - p) * lift
        val grow = 0.92f + 0.08f * p
        scaleX = grow
        scaleY = grow
        transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
    }
}
