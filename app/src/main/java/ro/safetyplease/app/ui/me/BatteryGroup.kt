package ro.safetyplease.app.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.LowBatteryBanner
import ro.safetyplease.app.ui.common.chargerText
import ro.safetyplease.app.ui.common.helpOrigin
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.core.mesh.PowerProfile

private const val MINUTES_IN_HOUR = 60
private const val MAX_HOURS = 24

/**
 * The battery and how long it lasts at the pace of the power profile in force, with the nearest place to charge.
 * Under 20% a banner says the phone scans less and relays less, and under 5% that the map and the animations are off.
 */
@Composable
fun BatteryGroup(vm: AppViewModel) {
    val battery by vm.c.battery.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    // away from the venue the way to a charger would run to hundreds of km
    val origin = helpOrigin(position, null, settings.manualZone, vm.venue)
    val charger = chargerText(vm.venue, origin)
    val profile = stringResource(
        when (battery.profile) {
            PowerProfile.LEAF -> R.string.autonomy_profile_leaf
            PowerProfile.STAFF -> R.string.autonomy_profile_staff
            PowerProfile.ANCHOR -> R.string.autonomy_profile_anchor
            PowerProfile.ECONOMY -> R.string.autonomy_profile_economy
        },
    )
    Column {
        LowBatteryBanner(battery, vm.venue, origin, Modifier.padding(start = Gutter, end = Gutter, bottom = 20.dp)) { vm.open(Dest.Map(spot = "charge")) }
        InsetGroup {
            GroupRow(
                stringResource(R.string.autonomy_level), value = "${battery.percent}%", icon = Sym.Battery,
                subtitle = if (battery.charging) stringResource(R.string.autonomy_charging) else profile,
            )
            GroupDivider()
            val minutes = battery.minutesLeft
            GroupRow(
                stringResource(R.string.autonomy_left),
                value = if (battery.charging || minutes == null) null else timeLeft(minutes),
                subtitle = when {
                    battery.charging -> stringResource(R.string.autonomy_left_charging)
                    minutes == null -> stringResource(R.string.autonomy_left_measuring)
                    else -> stringResource(R.string.autonomy_left_text)
                },
            )
            if (charger != null) {
                GroupDivider()
                GroupRow(
                    stringResource(R.string.autonomy_charger), subtitle = charger, chevron = true,
                    onClick = { vm.open(Dest.Map(spot = "charge")) },
                )
            }
        }
    }
}

@Composable
private fun timeLeft(minutes: Int): String {
    val hours = minutes / MINUTES_IN_HOUR
    val rest = minutes % MINUTES_IN_HOUR
    return when {
        hours >= MAX_HOURS -> stringResource(R.string.autonomy_over_day, MAX_HOURS)
        hours == 0 -> stringResource(R.string.autonomy_minutes, rest)
        rest == 0 -> stringResource(R.string.autonomy_hours, hours)
        else -> stringResource(R.string.autonomy_hours_minutes, hours, rest)
    }
}
