package ro.safetyplease.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
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
 * lets the person choose per app; nothing while they're off (the banner above says that) or heard.
 */
@Composable
fun AlarmsQuietBanner(phone: PhoneUi, modifier: Modifier = Modifier) {
    if (!rememberAlarmsQuiet()) return
    Banner(
        stringResource(Res.string.iphone_alerts_quiet_title), stringResource(Res.string.iphone_alerts_quiet_text), modifier, warning = true,
        action = stringResource(Res.string.notif_off_action), onAction = phone::openNotificationSettings,
    )
}

/** The same in one line, for the console, whose panes need all the height a phone in landscape has. */
@Composable
fun AlarmsQuietLine(phone: PhoneUi, modifier: Modifier = Modifier) {
    if (!rememberAlarmsQuiet()) return
    val colors = AppTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.fill).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Report, null, Modifier.size(18.dp), tint = colors.orange)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(Res.string.iphone_alerts_quiet_title), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AppButton(stringResource(Res.string.notif_off_action), phone::openNotificationSettings, compact = true)
    }
}

@Composable
private fun rememberAlarmsQuiet(): Boolean {
    val allowed = rememberNotificationsAllowed()
    val audible = rememberAlarmsAudible()
    return allowed && !audible
}
