package ro.safetyplease.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.designsystem.AppColors
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.density_hint_1
import ro.safetyplease.ui.resources.density_hint_2
import ro.safetyplease.ui.resources.density_hint_3
import ro.safetyplease.ui.resources.density_hint_4
import ro.safetyplease.ui.resources.density_hint_5
import ro.safetyplease.ui.resources.density_level_1
import ro.safetyplease.ui.resources.density_level_2
import ro.safetyplease.ui.resources.density_level_3
import ro.safetyplease.ui.resources.density_level_4
import ro.safetyplease.ui.resources.density_level_5

/** The word of a crowd level 1..5. */
fun densityWord(level: Int): StringResource = when (level) {
    1 -> Res.string.density_level_1
    2 -> Res.string.density_level_2
    3 -> Res.string.density_level_3
    4 -> Res.string.density_level_4
    else -> Res.string.density_level_5
}

/** People per square metre, as the picture means it; the crowd manager confirms the bands. */
fun densityHint(level: Int): StringResource = when (level) {
    1 -> Res.string.density_hint_1
    2 -> Res.string.density_hint_2
    3 -> Res.string.density_hint_3
    4 -> Res.string.density_hint_4
    else -> Res.string.density_hint_5
}

fun densityColor(level: Int, colors: AppColors): Color = when {
    level >= 5 -> colors.red
    level == 4 -> colors.orange
    else -> colors.green
}

/**
 * One of the five crowd pictures: heads seen from above in the same square, more of them at each step, rows offset as
 * people stand. The heads keep their size, so the gaps close as the level rises.
 */
@Composable
fun DensityPicture(level: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = level.coerceIn(1, 5) + 1
        val cell = size.minDimension / side
        val radius = size.minDimension / 15f
        for (row in 0 until side) {
            val shift = if (row % 2 == 1) cell / 4 else -cell / 4
            for (column in 0 until side) {
                val x = (column + 0.5f) * cell + if (side > 2) shift else 0f
                val y = (row + 0.5f) * cell
                drawCircle(color, radius, Offset(x.coerceIn(radius, size.width - radius), y))
            }
        }
    }
}
