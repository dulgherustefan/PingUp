package ro.safetyplease.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.chat.MeetingPlace
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.layers_no_conversations
import ro.safetyplease.ui.resources.layers_place_save
import ro.safetyplease.ui.resources.layers_place_saved
import ro.safetyplease.ui.resources.layers_place_send
import ro.safetyplease.ui.resources.layers_place_sent
import ro.safetyplease.ui.resources.layers_place_title
import ro.safetyplease.ui.resources.layers_send_to
import ro.safetyplease.ui.resources.send

/** A meeting point sent from the map stays up this long; it has no time of its own. */
private const val PLACE_EXPIRY_MIN = 60

/**
 * "Trimite locul": a long press on the map offers the point to a friend or a group, as a meeting point with no
 * landmark, or keeps it as the saved place.
 */
@Composable
fun PlaceDialog(vm: AppViewModel, point: GeoPoint, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val savedText = stringResource(Res.string.layers_place_saved)
    if (picking) {
        ConversationPicker(vm, onDismiss) { conversation ->
            vm.askFriend(Res.string.layers_place_sent) { vm.c.chat.proposeMeeting(conversation, MeetingPlace.point(point), null, PLACE_EXPIRY_MIN) }
        }
        return
    }
    AppDialog(
        onDismiss, stringResource(Res.string.layers_place_title), stringResource(Res.string.close), onDismiss, dismissLabel = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            AppButton(stringResource(Res.string.layers_place_send), { picking = true }, Modifier.fillMaxWidth())
            AppButton(
                stringResource(Res.string.layers_place_save),
                {
                    vm.c.settings.update { it.copy(savedPlace = point) }
                    vm.layers.picked = LayerPick.Saved
                    vm.phone.toast(savedText)
                    onDismiss()
                },
                Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Secondary,
            )
        }
    }
}

/** Picks one conversation, a group or a friend, and hands it to [onPick]. */
@Composable
fun ConversationPicker(vm: AppViewModel, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val choices = groups.map { Conversations.group(it.id) to it.name } + friends.map { Conversations.friend(it.nodeId) to it.nickname }
    var chosen by remember { mutableStateOf<String?>(null) }
    AppDialog(
        onDismiss, stringResource(Res.string.layers_send_to), stringResource(Res.string.send),
        onConfirm = {
            chosen?.let(onPick)
            onDismiss()
        },
        confirmEnabled = chosen != null,
    ) {
        if (choices.isEmpty()) {
            Text(
                stringResource(Res.string.layers_no_conversations), style = MaterialTheme.typography.footnote,
                color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            return@AppDialog
        }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            for ((conversation, name) in choices) {
                val selected = conversation == chosen
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget).clip(RoundedCornerShape(12.dp))
                        .selectable(selected, role = Role.RadioButton) { chosen = conversation }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.subheadline, color = AppTheme.colors.label, modifier = Modifier.weight(1f))
                    if (selected) Icon(Sym.Check, null, Modifier.size(20.dp), tint = AppTheme.colors.accent)
                }
            }
        }
    }
}
