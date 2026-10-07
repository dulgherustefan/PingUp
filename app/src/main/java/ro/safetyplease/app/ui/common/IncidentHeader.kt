package ro.safetyplease.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.IconCircle
import ro.safetyplease.app.ui.designsystem.StatusLabel
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title2
import ro.safetyplease.core.protocol.IncidentCategory

fun categoryIcon(category: Int): ImageVector = when (category) {
    IncidentCategory.MEDICAL -> Sym.Medical
    IncidentCategory.VIOLENCE -> Sym.Fight
    IncidentCategory.LOST_PERSON -> Sym.PersonSearch
    IncidentCategory.HARASSMENT -> Sym.NoTouch
    IncidentCategory.CROWD -> Sym.Groups
    IncidentCategory.FIRE -> Sym.Fire
    else -> Sym.MoreHoriz
}

/** Category circle: pale red with a red icon when urgent, gray otherwise. */
@Composable
fun CategoryCircle(category: Int, urgent: Boolean, size: Dp) {
    val colors = AppTheme.colors
    IconCircle(
        categoryIcon(category), if (urgent) colors.red.copy(alpha = 0.15f) else colors.fill,
        if (urgent) colors.red else colors.label, size,
    )
}

/** Header of a report or incident: category circle, name and location. Red when urgent. */
@Composable
fun IncidentHeader(category: Int, urgent: Boolean, subtitle: String, modifier: Modifier = Modifier, status: @Composable () -> Unit = {}) {
    val colors = AppTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CategoryCircle(category, urgent, 88.dp)
        Text(
            stringResource(Labels.category(category)), style = MaterialTheme.typography.title2, color = colors.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 12.dp),
        )
        Text(
            subtitle, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 2.dp),
        )
        // status sits under the name, not lost between the buttons and the map
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (urgent) StatusLabel(stringResource(R.string.sev_urgent), icon = Sym.Priority, color = colors.redInk)
            status()
        }
    }
}
