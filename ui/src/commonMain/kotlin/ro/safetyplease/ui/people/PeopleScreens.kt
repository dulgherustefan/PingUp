package ro.safetyplease.ui.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.location.LocationFix
import ro.safetyplease.core.position.AskLimit
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.ScanOutcome
import ro.safetyplease.ui.Tab
import ro.safetyplease.ui.common.BigCodeDialog
import ro.safetyplease.ui.common.FriendSpot
import ro.safetyplease.ui.common.FriendSpotLines
import ro.safetyplease.ui.common.QrBadge
import ro.safetyplease.ui.common.QrCardWidth
import ro.safetyplease.ui.common.friendStatus
import ro.safetyplease.ui.common.untilText
import ro.safetyplease.ui.designsystem.ActionTile
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InputField
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.LocalBottomClearance
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.caption1
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.add_friend_allow_camera
import ro.safetyplease.ui.resources.add_friend_done
import ro.safetyplease.ui.resources.add_friend_mine_hint
import ro.safetyplease.ui.resources.add_friend_no_camera
import ro.safetyplease.ui.resources.add_friend_scan
import ro.safetyplease.ui.resources.add_friend_scan_hint
import ro.safetyplease.ui.resources.add_friend_tab_code
import ro.safetyplease.ui.resources.add_friend_tab_scan
import ro.safetyplease.ui.resources.add_friend_text_link
import ro.safetyplease.ui.resources.add_friend_title
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.block_text
import ro.safetyplease.ui.resources.block_title
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.code_sheet_copied
import ro.safetyplease.ui.resources.code_sheet_copy
import ro.safetyplease.ui.resources.code_sheet_mine
import ro.safetyplease.ui.resources.code_sheet_placeholder
import ro.safetyplease.ui.resources.code_sheet_theirs
import ro.safetyplease.ui.resources.code_sheet_use
import ro.safetyplease.ui.resources.done
import ro.safetyplease.ui.resources.event_expired
import ro.safetyplease.ui.resources.friend_remove_text
import ro.safetyplease.ui.resources.friend_remove_title
import ro.safetyplease.ui.resources.group_create
import ro.safetyplease.ui.resources.group_leave_text
import ro.safetyplease.ui.resources.group_leave_title
import ro.safetyplease.ui.resources.group_members
import ro.safetyplease.ui.resources.group_name
import ro.safetyplease.ui.resources.group_new
import ro.safetyplease.ui.resources.group_no_friends
import ro.safetyplease.ui.resources.group_pick
import ro.safetyplease.ui.resources.menu_add_friend
import ro.safetyplease.ui.resources.msg_you
import ro.safetyplease.ui.resources.open_settings
import ro.safetyplease.ui.resources.pos_ask_wait
import ro.safetyplease.ui.resources.pos_none
import ro.safetyplease.ui.resources.pos_on_map
import ro.safetyplease.ui.resources.pos_title
import ro.safetyplease.ui.resources.presence_never
import ro.safetyplease.ui.resources.profile_added
import ro.safetyplease.ui.resources.profile_block
import ro.safetyplease.ui.resources.profile_created
import ro.safetyplease.ui.resources.profile_last_seen
import ro.safetyplease.ui.resources.profile_last_zone
import ro.safetyplease.ui.resources.profile_last_zone_none
import ro.safetyplease.ui.resources.profile_leave_group
import ro.safetyplease.ui.resources.profile_message
import ro.safetyplease.ui.resources.profile_remove_friend
import ro.safetyplease.ui.resources.profile_report
import ro.safetyplease.ui.resources.profile_share_mine
import ro.safetyplease.ui.resources.profile_where_are_you
import ro.safetyplease.ui.resources.profile_where_asked
import ro.safetyplease.ui.resources.remove
import ro.safetyplease.ui.resources.report_contact_friend
import ro.safetyplease.ui.resources.report_contact_group
import ro.safetyplease.ui.resources.scan_anchor_on
import ro.safetyplease.ui.resources.scan_control_on
import ro.safetyplease.ui.resources.scan_event_in_use
import ro.safetyplease.ui.resources.scan_not_now
import ro.safetyplease.ui.resources.scan_own_code
import ro.safetyplease.ui.resources.scan_revocations
import ro.safetyplease.ui.resources.scan_revoked
import ro.safetyplease.ui.resources.scan_staff_on
import ro.safetyplease.ui.resources.scan_unknown
import ro.safetyplease.ui.resources.scan_wrong_event
import ro.safetyplease.ui.resources.seen_ask
import ro.safetyplease.ui.resources.seen_sent
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.dateText
import ro.safetyplease.ui.text.hopsText
import ro.safetyplease.ui.text.rememberNow

/** Viewfinder corners and a line sweeping up and down: the camera is looking for a code. */
@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val sweep = if (reduce) 0.5f else {
        val transition = rememberInfiniteTransition(label = "scan")
        transition.animateFloat(
            initialValue = 0.18f, targetValue = 0.82f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "scanLine",
        ).value
    }
    Canvas(modifier) {
        val inset = size.width * 0.14f
        val arm = size.width * 0.1f
        val stroke = 4.dp.toPx()
        val color = Color.White
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        for ((x, dx) in listOf(left to 1f, right to -1f)) {
            for ((y, dy) in listOf(top to 1f, bottom to -1f)) {
                drawLine(color, Offset(x, y), Offset(x + arm * dx, y), stroke, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y + arm * dy), stroke, StrokeCap.Round)
            }
        }
        if (!reduce) {
            val y = size.height * sweep
            drawLine(color.copy(alpha = 0.8f), Offset(left + arm * 0.4f, y), Offset(right - arm * 0.4f, y), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Add friend: a segmented switch on top, then the green card with your code or the camera for theirs. */
@Composable
fun AddFriendScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val colors = AppTheme.colors
    val code = remember(settings.nickname) { vm.myQrText() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val camera = rememberCameraAccess()
    LaunchedEffect(tab) { if (tab == 1 && !camera.granted) camera.request() }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }
    var lastText by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var bigCode by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }

    fun handle(text: String) {
        // the camera sees the same code many times per second
        if (text == lastText) return
        lastText = text
        val result = vm.handleScan(text)
        outcome = result
        if (result.success) haptics.confirm() else haptics.reject()
    }

    val success = outcome?.takeIf { it.success }
    NavScreen(
        title = stringResource(Res.string.add_friend_title),
        background = colors.grouped,
        scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(Res.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SegmentedControl(
                listOf(0 to stringResource(Res.string.add_friend_tab_code), 1 to stringResource(Res.string.add_friend_tab_scan)),
                tab,
                {
                    tab = it
                    outcome = null
                    lastText = ""
                },
                Modifier.padding(top = 8.dp),
            )
            if (success != null) {
                Icon(Sym.CheckCircle, null, Modifier.padding(top = 48.dp).size(64.dp), tint = colors.accent)
                Text(
                    when (success) {
                        is ScanOutcome.FriendAdded -> stringResource(Res.string.add_friend_done, success.name)
                        ScanOutcome.AnchorOn -> stringResource(Res.string.scan_anchor_on)
                        ScanOutcome.ControlOn -> stringResource(Res.string.scan_control_on)
                        ScanOutcome.RevocationsApplied -> stringResource(Res.string.scan_revocations)
                        else -> stringResource(Res.string.scan_staff_on)
                    },
                    style = MaterialTheme.typography.body, color = colors.label, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp).padding(top = 16.dp),
                )
                Column(
                    Modifier.widthIn(max = QrCardWidth).fillMaxWidth().padding(top = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppButton(stringResource(Res.string.done), {
                        when (success) {
                            ScanOutcome.StaffOn -> vm.home(Tab.INCIDENTS)
                            ScanOutcome.AnchorOn, ScanOutcome.ControlOn -> vm.stack.clear()
                            else -> vm.back()
                        }
                    }, Modifier.fillMaxWidth())
                }
            } else if (tab == 0) {
                QrBadge(code, settings.nickname, { bigCode = true }, Modifier.padding(top = 24.dp))
                Text(
                    stringResource(Res.string.add_friend_mine_hint), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = QrCardWidth).padding(top = 16.dp),
                )
                // scanning has its own tab above; this is the fallback for when the camera doesn't work
                TextLink(stringResource(Res.string.add_friend_text_link), { sheet = true }, Modifier.padding(top = 8.dp))
            } else {
                if (camera.granted) {
                    Box(
                        Modifier.padding(top = 24.dp).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(26.dp)).background(Color.Black),
                    ) {
                        QrScanner(::handle, Modifier.fillMaxSize())
                        ScanFrame(Modifier.fillMaxSize())
                    }
                } else {
                    NoCamera(
                        camera.blocked, { if (camera.blocked) vm.phone.openAppSettings() else camera.request() },
                        Modifier.padding(top = 24.dp),
                    )
                }
                Text(
                    stringResource(Res.string.add_friend_scan_hint), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp).padding(top = 16.dp),
                )
                AnimatedVisibility(outcome != null, enter = fadeIn(tween(Motion.QUICK)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                    StatusLabel(
                        stringResource(
                            when (outcome) {
                                ScanOutcome.OwnCode -> Res.string.scan_own_code
                                ScanOutcome.WrongEvent -> Res.string.scan_wrong_event
                                ScanOutcome.Revoked -> Res.string.scan_revoked
                                ScanOutcome.NotNow -> Res.string.scan_not_now
                                ScanOutcome.EventInUse -> Res.string.scan_event_in_use
                                ScanOutcome.EventOver -> Res.string.event_expired
                                else -> Res.string.scan_unknown
                            }
                        ),
                        Modifier.padding(top = 12.dp), icon = Sym.Info,
                        color = if (outcome == ScanOutcome.EventInUse) colors.secondaryLabel else colors.redInk,
                    )
                }
                TextLink(stringResource(Res.string.add_friend_text_link), { sheet = true }, Modifier.padding(top = 8.dp))
            }
        }
    }
    if (bigCode) BigCodeDialog(code, settings.nickname) { bigCode = false }
    if (sheet) {
        CodeSheet(
            myCode = code,
            onCopy = { vm.phone.copyText(code) },
            onUse = {
                sheet = false
                handle(it)
            },
            onDismiss = { sheet = false },
        )
    }
}

private val ScanOutcome.success: Boolean
    get() = this is ScanOutcome.FriendAdded || this == ScanOutcome.StaffOn || this == ScanOutcome.AnchorOn ||
        this == ScanOutcome.ControlOn || this == ScanOutcome.RevocationsApplied

/** Shown instead of the camera when we lack access: same square, the explanation and a button to ask. */
@Composable
private fun NoCamera(blocked: Boolean, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(colors.cell).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Sym.QrScan, null, Modifier.size(44.dp), tint = colors.secondaryLabel)
        Text(
            stringResource(Res.string.add_friend_scan), style = MaterialTheme.typography.headline, color = colors.label,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(Res.string.add_friend_no_camera), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        AppButton(
            stringResource(if (blocked) Res.string.open_settings else Res.string.add_friend_allow_camera), onAllow,
            Modifier.padding(top = 16.dp), compact = true,
        )
    }
}

/** Code-as-text sheet: your code to copy at the top, a field for your friend's code below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeSheet(myCode: String, onCopy: () -> Unit, onUse: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    var copied by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = colors.grouped,
        contentColor = colors.label,
        dragHandle = {
            Box(Modifier.padding(vertical = 6.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(colors.tertiaryLabel))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).imePadding()) {
            Text(
                stringResource(Res.string.add_friend_text_link), style = MaterialTheme.typography.headline, color = colors.label,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
            )
            SectionTitle(stringResource(Res.string.code_sheet_mine))
            InsetGroup {
                Text(
                    myCode, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                )
                GroupDivider(start = Gutter)
                GroupRow(
                    stringResource(if (copied) Res.string.code_sheet_copied else Res.string.code_sheet_copy),
                    icon = if (copied) Sym.Check else Sym.Copy, tint = colors.accent,
                    onClick = {
                        onCopy()
                        copied = true
                    },
                )
            }
            SectionTitle(stringResource(Res.string.code_sheet_theirs))
            InputField(
                manual, { manual = it.trim() }, stringResource(Res.string.code_sheet_placeholder), Modifier.fillMaxWidth().padding(horizontal = Gutter),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (manual.isNotBlank()) onUse(manual) }),
                background = colors.cell,
            )
            AppButton(
                stringResource(Res.string.code_sheet_use), { onUse(manual) },
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 16.dp), enabled = manual.isNotBlank(),
            )
        }
    }
}

/**
 * New group: name at the top, chosen friends as avatars above the list, then all friends in a card with checkboxes.
 * The create button floats at the bottom, above the keyboard when it's open.
 */
@Composable
fun NewGroupScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val max = Limits.GROUP_MAX_MEMBERS - 1
    val sorted = remember(friends) { friends.sortedBy { it.nickname.lowercase() } }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    // the keyboard also covers the gesture bar; only the taller one matters
    val bottom = maxOf(WindowInsets.ime.asPaddingValues().calculateBottomPadding(), LocalBottomClearance.current)
    val fade = with(LocalDensity.current) { 24.dp.toPx() }

    NavScreen(
        title = stringResource(Res.string.group_new),
        background = colors.grouped,
        scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(Res.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottom + 8.dp + 50.dp + 24.dp),
            ) {
                item(key = "name") {
                    InputField(
                        name, { name = it.take(24) }, stringResource(Res.string.group_name),
                        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp), background = colors.cell,
                    )
                }
                item(key = "selected") {
                    AnimatedVisibility(
                        selected.isNotEmpty(),
                        enter = fadeIn(tween(Motion.QUICK)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Gutter, end = Gutter, top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (friend in sorted.filter { it.nodeId in selected }) {
                                SelectedMember(friend.nickname) { selected.remove(friend.nodeId) }
                            }
                        }
                    }
                }
                if (sorted.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(Res.string.group_no_friends), style = MaterialTheme.typography.subheadline,
                                color = colors.secondaryLabel, textAlign = TextAlign.Center,
                            )
                            TextLink(stringResource(Res.string.menu_add_friend), { vm.open(Dest.AddFriend) }, Modifier.padding(top = 4.dp))
                        }
                    }
                } else {
                    item(key = "header") { SectionTitle(stringResource(Res.string.group_pick, max)) }
                    item(key = "friends") {
                        InsetGroup {
                            sorted.forEachIndexed { index, friend ->
                                val checked = friend.nodeId in selected
                                GroupRow(
                                    friend.nickname,
                                    Modifier.toggleable(checked, role = Role.Checkbox) {
                                        when {
                                            checked -> selected.remove(friend.nodeId)
                                            selected.size < max -> selected.add(friend.nodeId)
                                            else -> haptics.reject()
                                        }
                                    },
                                    subtitle = friendStatus(friend, nearby, now, vm.venue),
                                    leading = { Avatar(friend.nickname, 36.dp, near = nearby.isInRange(friend.nodeId)) },
                                    trailing = { RoundCheck(checked) },
                                )
                                if (index < sorted.lastIndex) GroupDivider(start = 64.dp)
                            }
                        }
                    }
                }
            }
            // rows fade into the background under the button, like under the top bar
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.grouped.copy(alpha = 0f), colors.grouped), endY = fade))
                    .padding(start = Gutter, end = Gutter, top = 24.dp, bottom = bottom + 8.dp),
            ) {
                AppButton(
                    stringResource(Res.string.group_create),
                    {
                        vm.createGroup(name, selected.toList())
                        vm.home(Tab.MESSAGES)
                    },
                    Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && name.isNotBlank(),
                )
            }
        }
    }
}

/** A chosen friend above the list: their avatar with a small x; tap to remove them. */
@Composable
private fun SelectedMember(name: String, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(Res.string.remove), role = Role.Button, onClick = onRemove).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(name, 48.dp)
            // a ring in the background color separates the x from the avatar
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(22.dp).clip(CircleShape).background(colors.grouped)
                    .padding(2.dp).clip(CircleShape).background(colors.secondaryLabel),
                contentAlignment = Alignment.Center,
            ) { Icon(Sym.Close, null, Modifier.size(10.dp), tint = colors.cell) }
        }
        Text(
            name, style = MaterialTheme.typography.caption1, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Round checkbox: empty circle, or filled with a white check. */
@Composable
private fun RoundCheck(checked: Boolean) {
    val colors = AppTheme.colors
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .then(if (checked) Modifier.background(colors.accent) else Modifier.border(1.5.dp, colors.tertiaryLabel, CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Sym.Check, null, Modifier.size(14.dp), tint = colors.onAccent) }
}

/** Header of a person or group: large centered avatar, name and a subtitle line. */
@Composable
fun PersonHeader(name: String, note: String, modifier: Modifier = Modifier, near: Boolean = false, group: Boolean = false, size: Dp = 80.dp) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name, size, near = near, group = group)
        Text(
            name, style = MaterialTheme.typography.title1, color = colors.label, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
        )
        Text(
            note, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 2.dp),
        )
    }
}

/**
 * Profile of a friend or group: header, message button, details in cards, group members,
 * then, separately, what can be deleted.
 */
@Composable
fun ProfileScreen(vm: AppViewModel, conversation: String) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val density = LocalDensity.current
    val friend = friends.firstOrNull { Conversations.friend(it.nodeId) == conversation }
    val group = groups.firstOrNull { Conversations.group(it.id) == conversation }
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    // the name moves into the top bar only once the header name has scrolled under it
    val nameUnderBar by remember { derivedStateOf { scroll.value > with(density) { 150.dp.toPx() } } }
    if (friend == null && group == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val title = friend?.nickname ?: group!!.name

    NavScreen(
        title = if (nameUnderBar) title else "",
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            PersonHeader(
                title,
                if (friend != null) friendStatus(friend, nearby, now, vm.venue)
                else pluralStringResource(Res.plurals.group_members, group!!.members.size, group.members.size),
                near = friend != null && nearby.isInRange(friend.nodeId), group = group != null, size = 88.dp,
            )
            ActionTile(
                Sym.Chat, stringResource(Res.string.profile_message), { vm.back() },
                Modifier.align(Alignment.CenterHorizontally).width(80.dp), background = colors.cell,
            )
            InsetGroup(Modifier.padding(top = 24.dp)) {
                if (friend != null) {
                    GroupRow(stringResource(Res.string.profile_added), value = dateText(friend.addedAt), icon = Sym.PersonAdd)
                    GroupDivider()
                    // the route goes under the title so the value on the right stays short on narrow screens
                    GroupRow(
                        stringResource(Res.string.profile_last_seen),
                        subtitle = if (friend.lastSeenAt > 0) hopsText(friend.lastHops) else null,
                        value = if (friend.lastSeenAt > 0) agoText(friend.lastSeenAt, now) else stringResource(Res.string.presence_never),
                        icon = Sym.History,
                    )
                    GroupDivider()
                    GroupRow(
                        stringResource(Res.string.profile_last_zone),
                        subtitle = if (friend.lastZone.isNotEmpty()) agoText(friend.lastZoneAt, now) else null,
                        value = if (friend.lastZone.isNotEmpty()) vm.venue.zoneName(friend.lastZone) else stringResource(Res.string.profile_last_zone_none),
                        icon = Sym.Place,
                    )
                } else if (group != null) {
                    GroupRow(stringResource(Res.string.profile_created), value = dateText(group.createdAt), icon = Sym.Clock)
                }
            }
            if (friend != null) FriendLocation(vm, friend)
            InsetGroup(Modifier.padding(top = 24.dp)) {
                val mine = sharing?.shares?.firstOrNull { it.conversation == conversation }
                GroupRow(
                    stringResource(Res.string.profile_share_mine), icon = Sym.MyLocation, tint = colors.accent,
                    value = mine?.let { untilText(it.endsAtMs) }, chevron = true, onClick = { vm.openShare() },
                )
            }
            if (group != null) {
                SectionTitle(pluralStringResource(Res.plurals.group_members, group.members.size, group.members.size))
                InsetGroup {
                    group.members.forEachIndexed { index, member ->
                        val known = friends.firstOrNull { it.nodeId == member.nodeId }
                        val me = member.nodeId == vm.c.identity.nodeId
                        GroupRow(
                            if (me) stringResource(Res.string.msg_you) else known?.nickname ?: member.nickname,
                            subtitle = known?.let { friendStatus(it, nearby, now, vm.venue) },
                            leading = { Avatar(member.nickname, 36.dp, near = known != null && nearby.isInRange(known.nodeId)) },
                            onClick = if (me) null else ({ blocking = member.nodeId to (known?.nickname ?: member.nickname) }),
                            trailing = if (me) null else ({
                                Text(stringResource(Res.string.profile_block), style = MaterialTheme.typography.footnote, color = colors.redInk)
                            }),
                        )
                        if (index < group.members.lastIndex) GroupDivider(start = 64.dp)
                    }
                }
            }
            InsetGroup(Modifier.padding(top = 24.dp)) {
                // to the staff on site, who answer on the spot: harassment that comes through the app
                val reported = if (friend != null) stringResource(Res.string.report_contact_friend, friend.nickname)
                else stringResource(Res.string.report_contact_group, group!!.name)
                GroupRow(
                    stringResource(Res.string.profile_report), icon = Sym.Report, tint = colors.redInk,
                    onClick = { vm.reportHarassment(reported) },
                )
                GroupDivider()
                if (friend != null) {
                    GroupRow(
                        stringResource(Res.string.profile_block), icon = Sym.Hidden, tint = colors.redInk,
                        onClick = { blocking = friend.nodeId to friend.nickname },
                    )
                    GroupDivider()
                }
                GroupRow(
                    stringResource(if (friend != null) Res.string.profile_remove_friend else Res.string.profile_leave_group),
                    icon = if (friend != null) Sym.Delete else Sym.Logout, tint = colors.redInk, onClick = { confirm = true },
                )
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = if (friend != null) stringResource(Res.string.friend_remove_title, friend.nickname) else stringResource(Res.string.group_leave_title, group!!.name),
            text = stringResource(if (friend != null) Res.string.friend_remove_text else Res.string.group_leave_text),
            onDismiss = { confirm = false },
            confirmLabel = stringResource(if (friend != null) Res.string.remove else Res.string.profile_leave_group),
            destructive = true,
        ) {
            vm.home(Tab.MESSAGES)
            if (friend != null) vm.removeFriend(friend) else if (group != null) vm.leaveGroup(group.id)
        }
    }
    blocking?.let { (nodeId, name) ->
        ConfirmDialog(
            title = stringResource(Res.string.block_title, name),
            text = stringResource(Res.string.block_text),
            onDismiss = { blocking = null },
            confirmLabel = stringResource(Res.string.profile_block),
            destructive = true,
        ) {
            blocking = null
            if (friend?.nodeId == nodeId) vm.home(Tab.MESSAGES)
            vm.block(nodeId)
        }
    }
}

/**
 * Where a friend is, when they share it: which way and how far, how old and how sure. "Unde ești?" waits out the
 * chat's once-a-minute limit; "Fă-te văzut" asks them to flash their torch.
 */
@Composable
private fun FriendLocation(vm: AppViewModel, friend: Friend) {
    val records by vm.friendPositions.collectAsStateWithLifecycle()
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    val me by vm.position.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    // every second: the compass turns the arrow and the ask button counts down
    val now = rememberNow(1_000)
    val spot = FriendSpot.of(records, listOf(friend), now, vm.venue).firstOrNull()
    SectionTitle(stringResource(Res.string.pos_title))
    InsetGroup {
        if (spot != null) {
            val simulated = settings.simLat != null
            val my95 = if (simulated) 0.0 else fix?.let { it.accuracyM * LocationFix.RING_95 }?.toDouble() ?: 0.0
            val guide = me?.let { Guide.of(it, my95, spot.point, spot.ringM, vm.venue) }
            FriendSpotLines(spot, guide, vm.phone.heading())
            GroupDivider(start = Gutter)
            GroupRow(
                stringResource(Res.string.pos_on_map), icon = Sym.MapFill, tint = colors.accent, chevron = true,
                onClick = { vm.open(Dest.Map(friend = friend.nodeId)) },
            )
        } else {
            Text(
                stringResource(Res.string.pos_none), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
            )
        }
        GroupDivider(start = Gutter)
        val wait = AskLimit.waitMs(vm.askedAt[friend.nodeId], now)
        GroupRow(
            stringResource(Res.string.profile_where_are_you),
            subtitle = when {
                wait > 0 -> stringResource(Res.string.pos_ask_wait, ((wait + 999) / 1000).toInt())
                vm.askedAt.containsKey(friend.nodeId) -> stringResource(Res.string.profile_where_asked)
                else -> null
            },
            icon = Sym.PersonSearch, tint = if (wait > 0) colors.tertiaryLabel else colors.accent,
            onClick = if (wait > 0) null else ({ vm.requestPosition(friend) }),
        )
        GroupDivider(start = Gutter)
        GroupRow(
            stringResource(Res.string.seen_ask), icon = Sym.Torch, tint = colors.accent,
            onClick = { vm.askFriend(Res.string.seen_sent) { vm.c.chat.pingFriend(friend.nodeId) } },
        )
    }
}
