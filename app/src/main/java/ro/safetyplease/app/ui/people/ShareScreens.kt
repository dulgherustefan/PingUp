package ro.safetyplease.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.precisionText
import ro.safetyplease.app.ui.common.untilText
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Avatar
import ro.safetyplease.app.ui.designsystem.ButtonKind
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.GroupDivider
import ro.safetyplease.app.ui.designsystem.GroupRow
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.InsetGroup
import ro.safetyplease.app.ui.designsystem.IosSwitch
import ro.safetyplease.app.ui.designsystem.NavScreen
import ro.safetyplease.app.ui.designsystem.PinMascot
import ro.safetyplease.app.ui.designsystem.SectionTitle
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.body
import ro.safetyplease.app.ui.designsystem.caption1
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.title1
import ro.safetyplease.app.ui.rememberLocationRequest
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.position.MyShare
import ro.safetyplease.core.position.ShareChoice
import ro.safetyplease.core.position.ShareLength
import ro.safetyplease.core.protocol.SharePrecision

/** The opt-in, before Android's own location prompt: what sharing does, and nothing ticked yet. */
@Composable
fun ShareIntroScreen(vm: AppViewModel) {
    val colors = AppTheme.colors
    val requestLocation = rememberLocationRequest(vm) {
        vm.back()
        vm.open(Dest.ShareChoose)
    }
    NavScreen(
        title = "",
        background = colors.grouped,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = Gutter)) {
            PinMascot(88.dp, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp))
            Text(stringResource(R.string.share_intro_title), style = MaterialTheme.typography.title1, color = colors.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.share_intro_text), style = MaterialTheme.typography.body, color = colors.secondaryLabel,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
            AppButton(
                stringResource(R.string.share_intro_choose),
                {
                    vm.acceptShareIntro()
                    requestLocation()
                },
                Modifier.fillMaxWidth(),
            )
            AppButton(stringResource(R.string.share_intro_later), { vm.back() }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Secondary)
        }
    }
}

/** Every friend and group with a switch, all off unless a share already runs; turning one on asks how long and how exact. */
@Composable
fun ShareChooseScreen(vm: AppViewModel) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    var choosing by remember { mutableStateOf<Pair<String, String>?>(null) }
    // the switch that asked for the permission, so its sheet opens once Android's prompt is answered
    var asking by remember { mutableStateOf<Pair<String, String>?>(null) }
    val requestLocation = rememberLocationRequest(vm) { granted ->
        if (granted) choosing = asking
        asking = null
    }
    val active = sharing?.shares.orEmpty().associateBy { it.conversation }

    NavScreen(
        title = stringResource(R.string.share_title),
        background = colors.grouped,
        scrolled = true,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            if (friends.isEmpty() && groups.isEmpty()) {
                Text(
                    stringResource(R.string.share_empty), style = MaterialTheme.typography.body, color = colors.secondaryLabel,
                    modifier = Modifier.padding(Gutter),
                )
            }
            val onChange: (String, String, Boolean) -> Unit = { conversation, name, on ->
                if (!on) {
                    vm.stopShare(conversation)
                } else if (!vm.c.location.hasPermission()) {
                    asking = conversation to name
                    requestLocation()
                } else {
                    choosing = conversation to name
                }
            }
            ShareSection(stringResource(R.string.share_friends), friends.map { Conversations.friend(it.nodeId) to it.nickname }, false, active, onChange)
            ShareSection(stringResource(R.string.share_groups), groups.map { Conversations.group(it.id) to it.name }, true, active, onChange)
        }
    }

    choosing?.let { (conversation, name) ->
        ShareChoiceSheet(name, onDismiss = { choosing = null }) { choice ->
            choosing = null
            vm.startShare(conversation, choice)
        }
    }
}

@Composable
private fun ShareSection(title: String, rows: List<Pair<String, String>>, group: Boolean, active: Map<String, MyShare>, onChange: (String, String, Boolean) -> Unit) {
    if (rows.isEmpty()) return
    SectionTitle(title)
    InsetGroup {
        rows.forEachIndexed { index, (conversation, name) ->
            val share = active[conversation]
            ShareSwitchRow(name, group, share?.let { "${precisionText(it.precision)} · ${untilText(it.endsAtMs)}" }, share != null) { on ->
                onChange(conversation, name, on)
            }
            if (index < rows.lastIndex) GroupDivider(start = 64.dp)
        }
    }
}

@Composable
private fun ShareSwitchRow(name: String, group: Boolean, subtitle: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    GroupRow(
        name,
        Modifier.semantics { toggleableState = ToggleableState(on) },
        subtitle = subtitle,
        leading = { Avatar(name, 36.dp, group = group) },
        onClick = { onChange(!on) },
        trailing = { IosSwitch(on, null) },
    )
}

/** How long and how exact: neither is picked, the suggested pair ("zonă aproximativă, 1 h") is marked, not chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareChoiceSheet(name: String, onDismiss: () -> Unit, onStart: (ShareChoice) -> Unit) {
    val colors = AppTheme.colors
    var choice by remember { mutableStateOf(ShareChoice()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = colors.grouped,
        contentColor = colors.label,
        dragHandle = {
            Box(Modifier.padding(vertical = 6.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(colors.tertiaryLabel))
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.share_with, name), style = MaterialTheme.typography.headline, color = colors.label,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
            )
            SectionTitle(stringResource(R.string.share_length))
            InsetGroup(Modifier.selectableGroup()) {
                val lengths = listOf(
                    ShareLength.MIN_15 to R.string.share_len_15,
                    ShareLength.HOUR_1 to R.string.share_len_hour,
                    ShareLength.FESTIVAL_DAY to R.string.share_len_day,
                    ShareLength.UNTIL_STOPPED to R.string.share_len_open,
                )
                lengths.forEachIndexed { index, (length, label) ->
                    OptionRow(
                        stringResource(label), choice.length == length, length == ShareChoice.SUGGESTED_LENGTH,
                        note = if (length == ShareLength.UNTIL_STOPPED) stringResource(R.string.share_len_open_note) else null,
                    ) { choice = choice.copy(length = length) }
                    if (index < lengths.lastIndex) GroupDivider(start = Gutter)
                }
            }
            SectionTitle(stringResource(R.string.share_precision))
            InsetGroup(Modifier.selectableGroup()) {
                ShareChoice.PRECISIONS.forEachIndexed { index, precision ->
                    val label = when (precision) {
                        SharePrecision.EXACT -> R.string.share_prec_exact
                        SharePrecision.APPROXIMATE -> R.string.share_prec_approx
                        else -> R.string.share_prec_zone
                    }
                    OptionRow(stringResource(label), choice.precision == precision, precision == ShareChoice.SUGGESTED_PRECISION) {
                        choice = choice.copy(precision = precision)
                    }
                    if (index < ShareChoice.PRECISIONS.lastIndex) GroupDivider(start = Gutter)
                }
            }
            AppButton(
                stringResource(R.string.share_start), { onStart(choice) },
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp), enabled = choice.ready,
            )
        }
    }
}

/** A radio row; the suggested one carries a "Sugerat" tag and an accent outline, but starts unselected like the rest. */
@Composable
private fun OptionRow(label: String, selected: Boolean, suggested: Boolean, note: String? = null, onSelect: () -> Unit) {
    val colors = AppTheme.colors
    val suggestedText = stringResource(R.string.share_suggested)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            // the tag is read with the label, so TalkBack still says whether the row is selected
            .clearAndSetSemantics { contentDescription = listOfNotNull(label, note, suggestedText.takeIf { suggested }).joinToString(", ") }
            .padding(horizontal = Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .then(if (selected) Modifier.background(colors.accent) else Modifier.border(1.5.dp, colors.tertiaryLabel, CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Sym.Check, null, Modifier.size(13.dp), tint = colors.onAccent) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.body, color = colors.label)
            if (note != null) Text(note, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
        if (suggested) {
            Text(
                suggestedText, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.SemiBold, color = colors.accent,
                modifier = Modifier.padding(start = 8.dp).border(1.dp, colors.accent, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** "Cine mă vede acum": each share, how exact, how long it has left, a stop for each and one for all. */
@Composable
fun SharingScreen(vm: AppViewModel) {
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    NavScreen(
        title = stringResource(R.string.watchers_title),
        background = colors.grouped,
        scrolled = true,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            val shares = sharing?.shares.orEmpty()
            if (shares.isEmpty()) {
                Text(stringResource(R.string.watchers_empty), style = MaterialTheme.typography.body, color = colors.secondaryLabel, modifier = Modifier.padding(Gutter))
            } else {
                InsetGroup(Modifier.padding(top = 8.dp)) {
                    shares.forEachIndexed { index, share ->
                        val group = Conversations.isGroup(share.conversation)
                        val name = if (group) groups.firstOrNull { Conversations.group(it.id) == share.conversation }?.name
                        else friends.firstOrNull { Conversations.friend(it.nodeId) == share.conversation }?.nickname
                        val end = share.endsAtMs
                        val left = if (end != null && end - now < 60 * 60_000L) stringResource(R.string.watchers_left, ((end - now + 59_999) / 60_000).toInt()) else untilText(end)
                        GroupRow(
                            name.orEmpty(),
                            subtitle = "${precisionText(share.precision)} · $left",
                            leading = { Avatar(name.orEmpty(), 36.dp, group = group) },
                            trailing = {
                                AppButton(stringResource(R.string.sharing_stop), { vm.stopShare(share.conversation) }, kind = ButtonKind.Secondary, compact = true)
                            },
                        )
                        if (index < shares.lastIndex) GroupDivider(start = 64.dp)
                    }
                }
                AppButton(
                    stringResource(R.string.watchers_stop_all), { vm.stopAllShares() },
                    Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 20.dp), kind = ButtonKind.Danger,
                )
            }
            AppButton(
                stringResource(R.string.watchers_add), { vm.openShare() },
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 12.dp), kind = ButtonKind.Secondary,
            )
            Text(
                stringResource(R.string.share_intro_text), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(Gutter),
            )
        }
    }
}
