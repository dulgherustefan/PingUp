package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.VenueMap
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.map.MapOverlay
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_map_all_open
import ro.safetyplease.ui.resources.control_map_hint
import ro.safetyplease.ui.resources.control_map_open_all
import ro.safetyplease.ui.resources.control_map_pending
import ro.safetyplease.ui.resources.control_map_since
import ro.safetyplease.ui.resources.control_map_title
import ro.safetyplease.ui.resources.done
import ro.safetyplease.ui.resources.notice_no_shift
import ro.safetyplease.ui.resources.zone_state_closed
import ro.safetyplease.ui.resources.zone_state_crowded
import ro.safetyplease.ui.resources.zone_state_exit_only
import ro.safetyplease.ui.resources.zone_state_open
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.rememberNow

fun zoneStateLabel(state: ZoneState): StringResource = when (state) {
    ZoneState.OPEN -> Res.string.zone_state_open
    ZoneState.CROWDED -> Res.string.zone_state_crowded
    ZoneState.CLOSED -> Res.string.zone_state_closed
    ZoneState.EXIT_ONLY -> Res.string.zone_state_exit_only
}

/**
 * The console's "Zone": every zone's state, picked from the list or by a tap on the map, drawn as the phones will draw
 * it. The changes go out together through [ro.safetyplease.core.control.MapEditor], a few seconds after the last one and
 * at most one map state every 30 seconds; the line under the title says when.
 */
@Composable
fun ZoneStateEditor(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = vm.c
    val editor = c.mapEditor
    val colors = AppTheme.colors
    val edits by editor.edits.collectAsStateWithLifecycle()
    val board by c.notices.board.collectAsStateWithLifecycle()
    val now = rememberNow(1_000)
    val draft = remember(edits, board) { editor.draft() }
    val held = board.active(now / 1000).firstOrNull { it.second.topic == NoticeTopic.MAP_STATE }
    var selected by remember { mutableStateOf(vm.venue.zones.firstOrNull()?.id) }
    val due = editor.dueAtMs()
    val styles = remember(draft) { MapOverlay.zoneStyles(MapState(draft.filterValues { it != ZoneState.OPEN }), vm.venue) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(28.dp)).background(colors.grouped).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.control_map_title), style = MaterialTheme.typography.title2)
            Text(stringResource(Res.string.control_map_hint), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
            if (!c.notices.canSign) {
                Text(stringResource(Res.string.notice_no_shift), style = MaterialTheme.typography.subheadline, color = colors.red)
            }
            val closes = draft.values.any { it != ZoneState.OPEN }
            val status = when {
                due != null -> stringResource(Res.string.control_map_pending, ((due - now + 999) / 1000).coerceAtLeast(0).toInt())
                held != null && closes -> stringResource(Res.string.control_map_since, Labels.clock(held.first.receivedAt))
                else -> stringResource(Res.string.control_map_all_open)
            }
            Text(status, style = MaterialTheme.typography.headline, color = if (due != null) colors.orange else colors.label)
            VenueMap(
                vm.venue, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)),
                highlightZone = selected.orEmpty(), onTap = { _, zone -> zone?.let { selected = it.id } },
                mapOverlay = MapOverlay(zones = styles),
            )
            selected?.let(vm.venue::zone)?.let { zone ->
                Text(zone.name, style = MaterialTheme.typography.headline)
                SegmentedControl(
                    ZoneState.entries.map { it to stringResource(zoneStateLabel(it)) }, draft[zone.id] ?: ZoneState.OPEN,
                    { if (c.notices.canSign) editor.set(zone.id, it) },
                )
            }
            InsetGroup {
                vm.venue.zones.forEachIndexed { i, zone ->
                    if (i > 0) GroupDivider(start = 16.dp)
                    val state = draft[zone.id] ?: ZoneState.OPEN
                    GroupRow(
                        zone.name, value = stringResource(zoneStateLabel(state)),
                        icon = if (zone.id == selected) Sym.CheckCircle else null, tint = colors.accent,
                        onClick = { selected = zone.id },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(
                    stringResource(Res.string.control_map_open_all), editor::openAll, Modifier.weight(1f), kind = ButtonKind.Secondary,
                    enabled = c.notices.canSign && closes,
                )
                AppButton(stringResource(Res.string.done), onDismiss, Modifier.weight(1f))
            }
        }
    }
}
