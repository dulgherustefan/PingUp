package ro.safetyplease.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppLogo
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.FullScreen
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
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

/** The same in one line, for the console, whose panes need all the height a phone in landscape has. */
@Composable
fun FullScreenAlertsLine(phone: PhoneUi, modifier: Modifier = Modifier) {
    if (rememberFullScreenAlertsAllowed(phone)) return
    val colors = AppTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fill).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Report, null, Modifier.size(18.dp), tint = colors.orange)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(Res.string.fsi_off_title), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AppButton(stringResource(Res.string.fsi_off_action), phone::openFullScreenAlertSettings, compact = true)
    }
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
