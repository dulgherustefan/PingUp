package ro.safetyplease.ui.me

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.location.DutyCycle
import ro.safetyplease.core.util.formatDecimal
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.common.freshExportDir
import ro.safetyplease.ui.common.StepsSwitch
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.close
import ro.safetyplease.ui.resources.gnss_accuracy
import ro.safetyplease.ui.resources.gnss_carriers
import ro.safetyplease.ui.resources.gnss_cn0
import ro.safetyplease.ui.resources.gnss_export
import ro.safetyplease.ui.resources.gnss_fixes
import ro.safetyplease.ui.resources.gnss_l5
import ro.safetyplease.ui.resources.gnss_mode
import ro.safetyplease.ui.resources.gnss_mode_auto
import ro.safetyplease.ui.resources.gnss_mode_every
import ro.safetyplease.ui.resources.gnss_mode_off
import ro.safetyplease.ui.resources.gnss_mode_text
import ro.safetyplease.ui.resources.gnss_no
import ro.safetyplease.ui.resources.gnss_sats
import ro.safetyplease.ui.resources.gnss_title
import ro.safetyplease.ui.resources.gnss_ttff
import ro.safetyplease.ui.resources.gnss_unknown
import ro.safetyplease.ui.resources.gnss_yes

/** The measuring modes of the field campaign: off, 1 s, 2 s, and the automatic duty cycle. */
private val MODES = listOf(null, DutyCycle.URGENT_MS, DutyCycle.MOVING_MS, DutyCycle.STATIONARY_MS)

/**
 * The hidden GNSS diagnostic: what the chip did since the location source started, a measuring mode that keeps GPS on
 * with the screen off (a share session, so the service holds the location type), and the per-minute CSV.
 */
@Composable
internal fun GnssPanel(vm: AppViewModel, gnss: GnssView, onDismiss: () -> Unit) {
    val s by gnss.summary.collectAsStateWithLifecycle()
    val l5 by gnss.l5Device.collectAsStateWithLifecycle()
    val mode by gnss.mode.collectAsStateWithLifecycle()
    AppDialog(
        onDismiss, stringResource(Res.string.gnss_title), stringResource(Res.string.gnss_export),
        onConfirm = {
            vm.viewModelScope.launch {
                val file = withContext(Dispatchers.IO) { gnss.writeCsv(freshExportDir(vm.c.exportDir), vm.c.clock.wallMs()) }
                vm.phone.shareFiles(listOf(file))
            }
        },
        dismissLabel = stringResource(Res.string.close),
    ) {
        val none = "-"
        val l5Text = stringResource(
            when (l5) {
                true -> Res.string.gnss_yes
                false -> Res.string.gnss_no
                null -> Res.string.gnss_unknown
            },
        )
        val lines = listOf(
            stringResource(Res.string.gnss_l5, l5Text),
            stringResource(Res.string.gnss_sats, s.satsInFix),
            stringResource(Res.string.gnss_cn0, s.cn0Mean.one() ?: none),
            stringResource(Res.string.gnss_carriers, s.carriersMhz.joinToString(", ") { formatDecimal(it, 2) }.ifEmpty { none }),
            stringResource(Res.string.gnss_ttff, s.ttffS.one() ?: none),
            stringResource(Res.string.gnss_accuracy, s.accMedian.one() ?: none, s.accBest.one() ?: none, s.accWorst.one() ?: none),
            stringResource(Res.string.gnss_fixes, s.fixes, s.l5Pct?.let { "$it%" } ?: none),
        )
        Text(
            lines.joinToString("\n"), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.fillMaxWidth(),
        )
        val modeText = when (mode) {
            null -> stringResource(Res.string.gnss_mode_off)
            DutyCycle.STATIONARY_MS -> stringResource(Res.string.gnss_mode_auto)
            else -> stringResource(Res.string.gnss_mode_every, (mode ?: 0L) / 1000)
        }
        TextLink(
            stringResource(Res.string.gnss_mode, modeText),
            {
                gnss.setMode(MODES[(MODES.indexOf(mode) + 1) % MODES.size])
            },
            Modifier.padding(top = 10.dp),
        )
        Text(
            stringResource(Res.string.gnss_mode_text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        StepsSwitch(vm)
    }
}

private fun Float?.one(): String? = this?.let { formatDecimal(it.toDouble(), 1) }
