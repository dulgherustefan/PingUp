package ro.safetyplease.app.ui.common

import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.point_level_busy
import ro.safetyplease.ui.resources.point_level_closed
import ro.safetyplease.ui.resources.point_level_free
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.core.protocol.PointLevel

/** The words of a point's status level, as staff send it and the console shows it. */
fun pointLevelLabel(level: Int): StringResource = when (level) {
    PointLevel.FREE -> Res.string.point_level_free
    PointLevel.BUSY -> Res.string.point_level_busy
    else -> Res.string.point_level_closed
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
