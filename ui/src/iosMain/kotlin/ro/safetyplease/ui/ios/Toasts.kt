package ro.safetyplease.ui.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.glass
import ro.safetyplease.ui.designsystem.subheadline

/** Android's short notes at the bottom of the screen, which iOS doesn't have: a glass pill that fades by itself. */
class Toasts {
    private class Shown(val text: String, val long: Boolean)

    private var shown by mutableStateOf<Shown?>(null)

    fun show(text: String, long: Boolean) = dispatch_async(dispatch_get_main_queue()) { shown = Shown(text, long) }

    @Composable
    fun Host() {
        val current = shown
        LaunchedEffect(current) {
            if (current == null) return@LaunchedEffect
            delay(if (current.long) LONG_MS else SHORT_MS)
            if (shown === current) shown = null
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(current != null, enter = fadeIn(), exit = fadeOut()) {
                // VoiceOver reads it as it comes, as TalkBack reads a toast
                Text(
                    current?.text.orEmpty(), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.label, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp).glass(null, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    private companion object {
        // Android's own lengths for a short and a long toast
        const val SHORT_MS = 2_000L
        const val LONG_MS = 3_500L
    }
}
