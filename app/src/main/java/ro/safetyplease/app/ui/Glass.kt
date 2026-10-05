package ro.safetyplease.app.ui

import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Estomparea a ce e in spatele unei bare merge doar de la Android 12; mai jos sticla devine aproape opaca. */
val CanBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Ce se vede prin sticla: continutul unui ecran, inregistrat la fiecare desen intr-un strat grafic.
 * Sticla trebuie sa stea langa continut, nu in el: altfel s-ar desena in propria inregistrare.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marcheaza continutul care se vede prin sticla. Fundalul lui trebuie desenat inauntru, altfel estomparea are goluri. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Sticla iOS 26: continutul din spate estompat, o nuanta peste el, un contur subtire si o umbra moale.
 * Fara [backdrop] (sau sub Android 12) ramane doar nuanta, aproape opaca.
 */
@Composable
fun Modifier.glass(backdrop: Backdrop?, shape: Shape, blur: Dp = 24.dp, shadow: Boolean = true): Modifier {
    val colors = AppTheme.colors
    val layer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val live = backdrop != null && CanBlur
    return this
        .then(if (shadow) Modifier.shadow(10.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x29000000)) else Modifier)
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            if (live && backdrop != null) {
                val radius = blur.toPx()
                layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                layer.record {
                    translate(backdrop.origin.x - position.x, backdrop.origin.y - position.y) { drawLayer(backdrop.layer) }
                }
                val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                clipPath(outline) { drawLayer(layer) }
            }
        }
        .background(if (live) colors.glass else colors.glassSolid, shape)
        .border(0.75.dp, colors.glassRim, shape)
        .clip(shape)
}

/** Apasarea pe iPhone: fara unda, doar un strat gri peste ce e apasat, cat timp degetul sta pe el. */
class PressHighlight(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color)

    override fun equals(other: Any?) = other is PressHighlight && other.color == color

    override fun hashCode() = color.hashCode()

    private class Node(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                var presses = 0
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses++
                        is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    }
                    if ((presses > 0) != pressed) {
                        pressed = presses > 0
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            if (pressed) drawRect(color)
            drawContent()
        }
    }
}
