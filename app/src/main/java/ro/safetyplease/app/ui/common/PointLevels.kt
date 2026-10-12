package ro.safetyplease.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.core.protocol.PointLevel

/** The words of a point's status level, as staff send it and the console shows it. */
fun pointLevelLabel(level: Int): Int = when (level) {
    PointLevel.FREE -> R.string.point_level_free
    PointLevel.BUSY -> R.string.point_level_busy
    else -> R.string.point_level_closed
}

/** The color of a point's status level; one nobody confirmed lately is grey, since it may no longer hold. */
@Composable
fun pointLevelColor(level: Int, stale: Boolean = false): Color {
    val colors = AppTheme.colors
    return when {
        stale -> colors.secondaryLabel
        level == PointLevel.FREE -> colors.green
        level == PointLevel.BUSY -> colors.orange
        else -> colors.red
    }
}
