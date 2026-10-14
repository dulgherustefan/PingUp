package ro.safetyplease.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.designsystem.AppLogo
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.FullScreen
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.fsi_off_action
import ro.safetyplease.ui.resources.fsi_off_title

/** Whether urgent alerts may take the screen of a locked phone, asked again each time the app comes back to the front. */
@Composable
fun rememberFullScreenAlertsAllowed(phone: PhoneUi): Boolean {
    var allowed by remember { mutableStateOf(phone.fullScreenAlertsAllowed()) }
    LifecycleResumeEffect(Unit) {
        allowed = phone.fullScreenAlertsAllowed()
        onPauseOrDispose { }
    }
    return allowed
}

/** Urgent alerts can't take the screen of a locked phone, with the way to the setting that allows it; nothing otherwise. */
@Composable
fun FullScreenAlertsBanner(phone: PhoneUi, text: String, modifier: Modifier = Modifier) {
    if (rememberFullScreenAlertsAllowed(phone)) return
    Banner(
        stringResource(Res.string.fsi_off_title), text, modifier, warning = true,
        action = stringResource(Res.string.fsi_off_action), onAction = phone::openFullScreenAlertSettings,
    )
}

/**
 * Under an alert shown over the lock screen: the app's mark on its background, so nothing behind the alert can be read
 * or touched until the phone is unlocked.
 */
@Composable
fun LockScreenCover() {
    FullScreen(Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {}.clearAndSetSemantics {}) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppLogo(96.dp) }
    }
}
