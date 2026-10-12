package ro.safetyplease.ui.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset

/**
 * App motion. Screen transitions use springs, so a transition interrupted halfway
 * (back pressed while a screen enters) continues from its current velocity without a jump.
 */
object Motion {
    const val QUICK = 150
    const val STANDARD = 250
    const val SLOW = 400
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** A screen pushing in or a sheet sliding up: settles without overshooting. */
    val Push = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 340f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** Roughly how long [Push] takes to settle; used by the fades that accompany it. */
    const val PUSH_SETTLE = 520
}

/** On press the element shrinks slightly and springs back. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.92f): Modifier {
    if (LocalReduceMotion.current) return this
    val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (down) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** True when the phone has "Remove animations" on: transitions become plain fades. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** True when the battery is nearly empty: no map and no animations, only text. */
val LocalTextOnly = staticCompositionLocalOf { false }

/** Confirm and reject as the system plays them: Android's CONFIRM and REJECT (or the older ones), iOS's feedback generators. */
class Haptics(private val feedback: HapticFeedback) {
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
