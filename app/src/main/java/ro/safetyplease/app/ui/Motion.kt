package ro.safetyplease.app.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Miscarea aplicatiei: trei durate si o singura familie de curbe, fara salturi. */
object Motion {
    const val QUICK = 150
    const val STANDARD = 250
    const val SLOW = 400
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/** True cand telefonul are „Elimina animatiile”: miscarea in bucla si intrarile se opresc de tot. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Butoanele se micsoreaza putin la apasare si revin cu un arc scurt. */
fun Modifier.pressScale(source: InteractionSource, enabled: Boolean = true, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressed else 1f,
        animationSpec = if (isPressed) tween(90, easing = Motion.Standard) else spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Intrarea standard: apare usor de jos, o singura data. */
fun Modifier.enter(delayMs: Int = 0, rise: Dp = 12.dp): Modifier = composed {
    if (LocalReduceMotion.current) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, tween(Motion.STANDARD, easing = Motion.Enter))
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * risePx
    }
}

/** Balonul abia trimis creste din coltul de langa campul de scris. */
fun Modifier.bubbleEnter(fromEnd: Boolean): Modifier = composed {
    if (LocalReduceMotion.current) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(260, easing = Motion.Enter)) }
    val risePx = with(LocalDensity.current) { 14.dp.toPx() }
    graphicsLayer {
        alpha = progress.value
        val scale = 0.9f + 0.1f * progress.value
        scaleX = scale
        scaleY = scale
        translationY = (1f - progress.value) * risePx
        transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
    }
}

/** Respiratie lenta pentru „inca se cauta”; sta pe loc cand telefonul are animatiile oprite. */
fun Modifier.pulse(): Modifier = composed {
    if (LocalReduceMotion.current) return@composed this
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1100, easing = Motion.Standard), RepeatMode.Reverse), label = "pulseAlpha",
    )
    graphicsLayer { this.alpha = alpha }
}

class Haptics(private val view: View) {
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    fun reject() = perform(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
