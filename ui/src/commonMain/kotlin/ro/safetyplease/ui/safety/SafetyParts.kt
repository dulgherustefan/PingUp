package ro.safetyplease.ui.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.safety_hours_minutes_short
import ro.safetyplease.ui.resources.safety_hours_short
import ro.safetyplease.ui.resources.time_minutes_short

/** "45 min", "2 h", "1 h 30 min". */
@Composable
fun durationText(minutes: Int): String = when {
    minutes < 60 -> stringResource(Res.string.time_minutes_short, minutes)
    minutes % 60 == 0 -> stringResource(Res.string.safety_hours_short, minutes / 60)
    else -> stringResource(Res.string.safety_hours_minutes_short, minutes / 60, minutes % 60)
}

/** One choice among a few, as a pill; the chosen one takes the selection color. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape).background(if (selected) colors.selection else colors.cell)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.subheadline, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onSelection else colors.label,
        )
    }
}

/** Pills that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Pills(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
