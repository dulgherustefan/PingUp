package ro.safetyplease.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Banner
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.core.mesh.BatteryState
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/** The nearest place to charge the phone, as the line a banner or a row shows; null if the venue has none. */
@Composable
fun chargerText(venue: Venue, origin: GeoPoint?): String? {
    val spot = venue.nearest("charge", origin) ?: return null
    val name = spot.name.ifEmpty { stringResource(R.string.map_quick_charge) }
    return name + " · " + routeText(origin, spot.point)
}

/**
 * Said once the battery is under 20% and the phone is not charging: it scans less and helps less to relay, so the
 * battery lasts; under 5% the map and the animations are off as well. Nothing otherwise.
 */
@Composable
fun LowBatteryBanner(
    battery: BatteryState,
    venue: Venue,
    origin: GeoPoint?,
    modifier: Modifier = Modifier,
    onShowCharger: (() -> Unit)? = null,
) {
    if (!PowerPolicy.lowBattery(battery.percent, battery.charging)) return
    val textOnly = PowerPolicy.textOnly(battery.percent, battery.charging)
    val charger = chargerText(venue, origin)
    val text = buildString {
        append(stringResource(R.string.low_battery_text))
        if (textOnly) append(" ").append(stringResource(R.string.low_battery_text_only))
        if (charger != null) append("\n").append(stringResource(R.string.low_battery_charger, charger))
    }
    Banner(
        stringResource(R.string.low_battery_title, if (textOnly) PowerPolicy.TEXT_ONLY_PERCENT else PowerPolicy.LOW_BATTERY_PERCENT),
        text, modifier, warning = true,
        action = stringResource(R.string.low_battery_show).takeIf { charger != null && onShowCharger != null }, onAction = { onShowCharger?.invoke() },
    )
}

/** Stands in for the map while the battery is nearly empty: the places below it still work. */
@Composable
fun TextOnlyMapNote(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(20.dp)).background(AppTheme.colors.fill).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.map_text_only), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}
