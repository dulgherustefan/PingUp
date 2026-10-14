package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.rememberAlarmsAudible
import ro.safetyplease.ui.rememberNotificationsAllowed
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.iphone_alerts_quiet_text
import ro.safetyplease.ui.resources.iphone_alerts_quiet_title
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

/**
 * For staff: notifications are on, but an urgent alarm would come without sound or held back by Focus, as an iPhone
 * allows per app; nothing while they're off (the banner above says that) or heard.
 */
@Composable
fun AlarmsQuietBanner(phone: PhoneUi, modifier: Modifier = Modifier) {
    val allowed = rememberNotificationsAllowed()
    val audible = rememberAlarmsAudible()
    if (!allowed || audible) return
    Banner(
        stringResource(Res.string.iphone_alerts_quiet_title), stringResource(Res.string.iphone_alerts_quiet_text), modifier, warning = true,
        action = stringResource(Res.string.notif_off_action), onAction = phone::openNotificationSettings,
    )
}
