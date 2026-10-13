package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.rememberNotificationsAllowed
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.notif_off_action
import ro.safetyplease.ui.resources.notif_off_title

/** Notifications turned off, with the way back to their settings; nothing while they're on. */
@Composable
fun NotificationsOffBanner(phone: PhoneUi, text: String, modifier: Modifier = Modifier) {
    if (rememberNotificationsAllowed()) return
    Banner(
        stringResource(Res.string.notif_off_title), text, modifier, warning = true,
        action = stringResource(Res.string.notif_off_action), onAction = phone::openNotificationSettings,
    )
}
