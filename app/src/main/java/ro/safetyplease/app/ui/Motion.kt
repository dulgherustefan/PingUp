package ro.safetyplease.app.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset

/**
 * Miscarea aplicatiei. Trecerile intre ecrane merg pe arcuri, ca in iOS: o trecere intrerupta la jumatate
 * (inapoi apasat in timp ce ecranul intra) continua din viteza pe care o avea, fara salt.
 */
object Motion {
    const val QUICK = 150
    const val STANDARD = 250
    const val SLOW = 400
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** Un ecran care intra din dreapta sau o foaie care urca: se aseaza fara sa treaca de locul lui. */
    val Push = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 340f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** Cat dureaza, cu aproximatie, sa se aseze [Push]; il folosesc estomparile care il insotesc. */
    const val PUSH_SETTLE = 520
}

/** Apasat, elementul se strange putin si revine cu un arc moale, ca butoanele de sticla din iOS 26. */
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

/** True cand telefonul are „Elimina animatiile”: tranzitiile devin simple estompari. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
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
