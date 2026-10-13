package ro.safetyplease.ui.staffcodes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.DensityVerdict
import ro.safetyplease.core.control.StaffDensity
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.DensityPicture
import ro.safetyplease.ui.common.densityColor
import ro.safetyplease.ui.common.densityHint
import ro.safetyplease.ui.common.densityWord
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.reportSpot
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.density_in_zone
import ro.safetyplease.ui.resources.density_known
import ro.safetyplease.ui.resources.density_latest
import ro.safetyplease.ui.resources.density_no_zone
import ro.safetyplease.ui.resources.density_phone_wait
import ro.safetyplease.ui.resources.density_rate_limited
import ro.safetyplease.ui.resources.density_sent
import ro.safetyplease.ui.resources.density_title
import ro.safetyplease.ui.resources.density_zone_recent
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow

/**
 * "Densitate": the crowd in the phone's zone as one of five pictures, one tap each. It goes out as a staff report
 * with the position; the phone says when it held one back and why.
 */
@Composable
fun DensityCodes(vm: AppViewModel) {
    val c = vm.c
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val log by c.staffChain.density.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val zone = position?.let { vm.venue.zoneAt(it.lat, it.lon)?.id } ?: settings.manualZone.ifEmpty { settings.staffZone }
    var result by remember { mutableStateOf<Pair<Int, DensityVerdict>?>(null) }
    LaunchedEffect(result) {
        if (result != null) {
            delay(RESULT_MS)
            result = null
        }
    }

    SectionTitle(stringResource(Res.string.density_title), top = 12.dp)
    Text(
        if (zone.isEmpty()) stringResource(Res.string.density_no_zone) else stringResource(Res.string.density_in_zone, vm.venue.zoneName(zone)),
        style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(horizontal = Gutter),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (level in 1..StaffDensity.LEVELS) {
            val word = stringResource(densityWord(level))
            val hint = stringResource(densityHint(level))
            val enabled = zone.isNotEmpty()
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(colors.cell)
                    .clickable(enabled = enabled, role = Role.Button) {
                        val spot = reportSpot(c.settings.value, c.locationFix.value, c.clock.wallMs(), vm.venue)
                        val verdict = c.staffChain.sendDensity(level, zone, spot?.point?.lat, spot?.point?.lon, spot?.fields)
                        result = level to verdict
                        if (verdict == DensityVerdict.SEND) haptics.confirm() else haptics.reject()
                    }
                    .semantics { contentDescription = "$level/5, $word, $hint" }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DensityPicture(level, if (enabled) densityColor(level, colors) else colors.secondaryLabel, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
                Text("$level", style = MaterialTheme.typography.footnote, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(word, style = MaterialTheme.typography.footnote, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
    val latest = remember(log, zone) { StaffDensity.latest(log.readings)[zone] }
    if (latest != null) {
        Text(
            stringResource(Res.string.density_latest, stringResource(densityWord(latest.level)), latest.level, agoText(StaffDensity.seenAt(latest), now)),
            style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(horizontal = Gutter),
        )
    }
    result?.let { (level, verdict) ->
        val text = when (verdict) {
            DensityVerdict.SEND -> stringResource(Res.string.density_sent, stringResource(densityWord(level)), level)
            DensityVerdict.PHONE_WAIT -> stringResource(Res.string.density_phone_wait)
            DensityVerdict.ZONE_RECENT -> stringResource(Res.string.density_zone_recent)
            DensityVerdict.KNOWN -> stringResource(Res.string.density_known)
            DensityVerdict.RATE_LIMITED -> stringResource(Res.string.density_rate_limited)
        }
        val sent = verdict == DensityVerdict.SEND
        StatusLabel(
            text, Modifier.padding(horizontal = Gutter), icon = if (sent) Sym.CheckCircle else Sym.Info,
            color = if (sent) colors.accent else colors.secondaryLabel,
        )
    }
}

private const val RESULT_MS = 10_000L
