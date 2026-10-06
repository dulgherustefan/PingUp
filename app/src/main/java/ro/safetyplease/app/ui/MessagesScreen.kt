package ro.safetyplease.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

@Composable
fun presenceText(friend: Friend, nearby: Nearby, now: Long): String = when {
    nearby.isLinked(friend.nodeId) -> stringResource(R.string.presence_near) + " · " + stringResource(R.string.hop_direct)
    nearby.isInRange(friend.nodeId) -> stringResource(R.string.presence_near)
    friend.lastSeenAt > 0 -> stringResource(R.string.presence_seen, agoText(friend.lastSeenAt, now)) + " · " + hopsText(friend.lastHops)
    else -> stringResource(R.string.presence_never)
}

/** Starea retelei, scrisa sub titlul ecranului: cu cate telefoane esti legat acum. */
@Composable
fun networkText(links: Int, gate: RadioGate): String {
    return when {
        !gate.hasAccess -> stringResource(R.string.status_no_access)
        !gate.bluetoothOn -> stringResource(R.string.status_bt_off)
        links == 0 -> stringResource(R.string.net_searching)
        else -> pluralStringResource(R.plurals.connected_phones, links, links)
    }
}

/** Punctul din fata starii retelei: verde si cu ping cand esti legat, gri si cu ping cat cauta, rosu cand radioul nu merge. */
@Composable
private fun NetworkPing(links: Int, gate: RadioGate) {
    val colors = AppTheme.colors
    val working = gate.hasAccess && gate.bluetoothOn
    PingDot(
        color = when {
            !working -> colors.red
            links > 0 -> colors.brand
            else -> colors.secondaryLabel
        },
        active = working,
        size = 7.dp,
        modifier = Modifier.padding(end = 2.dp),
    )
}

/** Bula ta din stanga sus, pe fiecare tab: deschide setarile, direct, fara meniu intermediar. */
@Composable
fun MeButton(name: String, onClick: () -> Unit) {
    val label = stringResource(R.string.open_settings)
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(TouchTarget).clip(CircleShape)
            .clickable(press, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.pressScale(press)) { Avatar(name, GlassSize) } }
}

/**
 * Filtrul de deasupra listei, ca pastilele din Nixtio: „Toate” si „Necitite”, cea aleasa in culoarea de selectie.
 */
@Composable
private fun FilterPills(unreadOnly: Boolean, unreadCount: Int, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val unreadLabel = stringResource(R.string.filter_unread)
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterPill(stringResource(R.string.filter_all), !unreadOnly) { onSelect(false) }
        FilterPill(if (unreadCount > 0) "$unreadLabel · $unreadCount" else unreadLabel, unreadOnly) { onSelect(true) }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val fill = if (selected) colors.selection else colors.fill
    val ink = if (selected) colors.onSelection else colors.label
    // pastila se vede de 36, dar se atinge pe 48
    Box(
        Modifier.heightIn(min = TouchTarget).selectable(selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1,
            modifier = Modifier.clip(CircleShape).background(fill).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Lista de conversatii, ca in Signal pe iPhone: titlul centrat, bula ta in stanga, „mesaj nou” in dreapta,
 * cautarea dedesubt, apoi randurile fara linii intre ele.
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
    val focus = LocalFocusManager.current
    val colors = AppTheme.colors

    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
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
    val shown = rows.filter { (!unreadOnly || it.unread > 0) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }

    NavScreen(
        title = stringResource(R.string.tab_messages),
        subtitle = networkText(nearby.readyLinks, gate),
        subtitleLeading = { NetworkPing(nearby.readyLinks, gate) },
        scrolled = scrolled,
        leading = { MeButton(settings.nickname) { vm.open(Dest.Me) } },
        // un singur buton, ca in Mesajele de pe iPhone: „Mesaj nou” are si grupul nou si adaugarea unui prieten
        trailing = { backdrop -> GlassIconButton(Sym.Compose, stringResource(R.string.new_chat), { vm.open(Dest.NewChat) }, backdrop) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            if (rows.isNotEmpty()) {
                item(key = "search") {
                    Row(Modifier.padding(start = Gutter, end = if (searching) 4.dp else Gutter, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchField(
                            query, { query = it }, stringResource(R.string.search),
                            Modifier.weight(1f).onFocusChanged { searching = it.isFocused },
                        )
                        if (searching) {
                            TextLink(stringResource(R.string.cancel), {
                                query = ""
                                focus.clearFocus()
                            })
                        }
                    }
                }
            }
            item(key = "problem") { NetworkProblem(vm, nearby.radio, gate, settings.batteryHintDismissed, Modifier.padding(horizontal = Gutter, vertical = 6.dp)) }
            if (rows.isNotEmpty()) {
                item(key = "filter") {
                    val unreadRows = rows.filter { it.unread > 0 }
                    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter - 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterPills(unreadOnly, unreadRows.size, { unreadOnly = it }, Modifier.weight(1f))
                        if (unreadOnly && unreadRows.isNotEmpty()) {
                            TextLink(stringResource(R.string.mark_all_read), { unreadRows.forEach { vm.c.chat.markRead(it.id) } })
                        }
                    }
                }
            }
            when {
                rows.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty_text),
                        illustration = { PinMascot(120.dp) },
                    ) {
                        AppButton(stringResource(R.string.messages_empty_action), { vm.open(Dest.AddFriend) }, compact = true)
                    }
                }
                shown.isEmpty() -> item(key = "none") {
                    Text(
                        if (query.isNotBlank()) stringResource(R.string.messages_none_found, query.trim()) else stringResource(R.string.messages_none_unread),
                        style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                    )
                }
                else -> items(shown, key = { it.id }) { row ->
                    ConversationItem(vm, row, row.friend != null && nearby.isInRange(row.friend.nodeId), now, Modifier.animateItem())
                }
            }
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

/** Mesaj nou, ca foaia din Signal: grup nou, prieten nou, apoi prietenii pe care ii ai deja. */
@Composable
fun NewChatScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val now = rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(friends) { friends.sortedBy { it.nickname.lowercase() } }
    val shown = sorted.filter { query.isBlank() || it.nickname.contains(query.trim(), ignoreCase = true) }
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }

    NavScreen(
        title = stringResource(R.string.new_chat), background = colors.grouped, scrolled = scrolled,
        trailing = { backdrop -> GlassIconButton(Sym.Close, stringResource(R.string.close), { vm.back() }, backdrop) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            if (sorted.isNotEmpty()) {
                item(key = "search") {
                    SearchField(
                        query, { query = it }, stringResource(R.string.new_chat_search),
                        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                    )
                }
            }
            item(key = "actions") {
                InsetGroup(Modifier.padding(top = 8.dp)) {
                    GroupRow(
                        stringResource(R.string.menu_new_group), onClick = { vm.open(Dest.NewGroup) },
                        leading = { IconCircle(Sym.Group, colors.fill, colors.label, 36.dp) },
                    )
                    GroupDivider(start = 64.dp)
                    GroupRow(
                        stringResource(R.string.menu_add_friend), subtitle = stringResource(R.string.new_chat_add_label), onClick = { vm.open(Dest.AddFriend) },
                        leading = { IconCircle(Sym.QrCode, colors.fill, colors.label, 36.dp) },
                    )
                }
            }
            if (shown.isNotEmpty()) {
                item(key = "header") { SectionTitle(stringResource(R.string.section_friends)) }
                item(key = "friends") {
                    InsetGroup {
                        shown.forEachIndexed { index, friend ->
                            GroupRow(
                                friend.nickname, subtitle = presenceText(friend, nearby, now),
                                onClick = {
                                    vm.back()
                                    vm.open(Dest.Conversation(Conversations.friend(friend.nodeId)))
                                },
                                leading = { Avatar(friend.nickname, 36.dp, near = nearby.isInRange(friend.nodeId)) },
                            )
                            if (index < shown.lastIndex) GroupDivider(start = 64.dp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
