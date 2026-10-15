package ro.safetyplease.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * A looping phase, from 0 up to 1 over [periodMillis], stepped at most [stepsPerSecond] times a second instead of
 * every frame. For slow, small motion (the map's pulse, the floating mascot), which looks the same either way: at the
 * screen's own rate (up to 120 Hz) each frame redrew the whole screen, and a screen that only waits kept a quarter
 * of a core busy (measured on the simulator: about a third less with this).
 */
@Composable
fun rememberLoop(periodMillis: Int, stepsPerSecond: Int = 30): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMillis, stepsPerSecond) {
        val start = withFrameMillis { it }
        while (true) {
            delay(1000L / stepsPerSecond)
            withFrameMillis { now -> phase.floatValue = loopPhase(now - start, periodMillis) }
        }
    }
    return phase
}

internal fun loopPhase(elapsedMillis: Long, periodMillis: Int): Float = (elapsedMillis % periodMillis).toFloat() / periodMillis

/**
 * On press the element shrinks slightly and springs back. A quick tap in a scrolling list reports its press only with
 * its release; the shrink still gets [MIN_PRESS_MS] before it springs back, so every tap is felt.
 */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier {
    if (LocalReduceMotion.current) return this
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interaction, pressed) {
        var presses = 0
        var pressedAt = TimeSource.Monotonic.markNow()
        var release: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    presses++
                    pressedAt = TimeSource.Monotonic.markNow()
                    release?.cancel()
                    launch { scale.animateTo(pressed, spring(stiffness = 1500f)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    presses = (presses - 1).coerceAtLeast(0)
                    if (presses == 0) {
                        val held = pressedAt.elapsedNow().inWholeMilliseconds
                        release = launch {
                            delay(pressHoldLeft(held))
                            scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 700f))
                        }
                    }
                }
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** How long a press shows at the least, so a tap the list reported late is still seen. */
const val MIN_PRESS_MS = 90L

/** What is left of [MIN_PRESS_MS] after a press held for [heldMs]. */
internal fun pressHoldLeft(heldMs: Long): Long = (MIN_PRESS_MS - heldMs).coerceAtLeast(0L)

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
