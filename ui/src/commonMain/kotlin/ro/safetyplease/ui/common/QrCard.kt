package ro.safetyplease.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.Radius
import ro.safetyplease.ui.designsystem.pressScale
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.designsystem.title3
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.add_friend_enlarge
import ro.safetyplease.ui.resources.add_friend_mine
import ro.safetyplease.ui.resources.close

// The code card ignores the theme so the code reads the same day and night. White name on forest green
// is 7.5:1 and the dark green code on white is over 11:1, enough for any camera.
private val QrBorder = Color(0xFF2B5E45)
private val QrInk = Color(0xFF17402E)
private val QrFrame = Color(0xFFE9E9E9)

/** Width of the code card, and of the camera and the texts that take its place, so switching tabs moves nothing. */
val QrCardWidth = 328.dp

/** Your code on a forest green card, your name below it. Tap to open it full screen. */
@Composable
fun QrBadge(code: String, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(press, 0.98f).widthIn(max = QrCardWidth).fillMaxWidth().clip(RoundedCornerShape(Radius.Card)).background(QrBorder)
            .clickable(press, LocalIndication.current, onClickLabel = stringResource(Res.string.add_friend_enlarge), role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // white in every theme so any camera can read it
        QrCode(
            code, QrInk, stringResource(Res.string.add_friend_mine),
            Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .background(Color.White).border(2.dp, QrFrame, RoundedCornerShape(12.dp)).padding(16.dp),
        )
        Text(
            name, style = MaterialTheme.typography.title3, color = Color.White, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
        )
    }
}

/** Full-screen code on white, easier to scan up close or in low light. Tap to close. */
@Composable
fun BigCodeDialog(code: String, name: String, onDismiss: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            shown,
            enter = if (reduce) fadeIn(tween(Motion.QUICK)) else fadeIn(tween(Motion.QUICK)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = 500f), initialScale = 0.85f),
            exit = fadeOut(tween(90)),
        ) {
            Column(
                Modifier.fillMaxSize().clickable(onClickLabel = stringResource(Res.string.close), onClick = onDismiss).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                QrCode(
                    code, Color.Black, stringResource(Res.string.add_friend_mine),
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(name, style = MaterialTheme.typography.title1, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}
