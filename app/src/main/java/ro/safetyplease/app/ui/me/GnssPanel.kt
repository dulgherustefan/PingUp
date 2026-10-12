package ro.safetyplease.app.ui.me

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.safetyplease.app.R
import ro.safetyplease.app.service.MeshService
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.common.freshExportDir
import ro.safetyplease.app.ui.common.shareFiles
import ro.safetyplease.app.ui.designsystem.AppDialog
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.TextLink
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.core.location.DutyCycle
import java.time.ZoneId
import java.util.Locale

/** The measuring modes of the field campaign: off, 1 s, 2 s, and the automatic duty cycle. */
private val MODES = listOf(null, DutyCycle.URGENT_MS, DutyCycle.MOVING_MS, DutyCycle.STATIONARY_MS)

/**
 * The hidden GNSS diagnostic: what the chip did since the location source started, a measuring mode that keeps GPS on
 * with the screen off (a share session, so the service holds the location type), and the per-minute CSV.
 */
@Composable
internal fun GnssPanel(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val location = vm.c.location
    val s by location.diagnostics.summary.collectAsStateWithLifecycle()
    val l5 by location.diagnostics.l5Device.collectAsStateWithLifecycle()
    val mode by location.shareMaxMs.collectAsStateWithLifecycle()
    AppDialog(
        onDismiss, stringResource(R.string.gnss_title), stringResource(R.string.gnss_export),
        onConfirm = {
            vm.viewModelScope.launch {
                val file = withContext(Dispatchers.IO) {
                    location.diagnostics.writeCsv(freshExportDir(vm.c.exportDir), vm.c.clock.wallMs(), ZoneId.systemDefault())
                }
                shareFiles(context, listOf(file))
            }
        },
        dismissLabel = stringResource(R.string.close),
    ) {
        val none = "-"
        val l5Text = stringResource(
            when (l5) {
                true -> R.string.gnss_yes
                false -> R.string.gnss_no
                null -> R.string.gnss_unknown
            },
        )
        val lines = listOf(
            stringResource(R.string.gnss_l5, l5Text),
            stringResource(R.string.gnss_sats, s.satsInFix),
            stringResource(R.string.gnss_cn0, s.cn0Mean.one() ?: none),
            stringResource(R.string.gnss_carriers, s.carriersMhz.joinToString(", ") { String.format(Locale.ROOT, "%.2f", it) }.ifEmpty { none }),
            stringResource(R.string.gnss_ttff, s.ttffS.one() ?: none),
            stringResource(R.string.gnss_accuracy, s.accMedian.one() ?: none, s.accBest.one() ?: none, s.accWorst.one() ?: none),
            stringResource(R.string.gnss_fixes, s.fixes, s.l5Pct?.let { "$it%" } ?: none),
        )
        Text(
            lines.joinToString("\n"), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.fillMaxWidth(),
        )
        val modeText = when (mode) {
            null -> stringResource(R.string.gnss_mode_off)
            DutyCycle.STATIONARY_MS -> stringResource(R.string.gnss_mode_auto)
            else -> stringResource(R.string.gnss_mode_every, (mode ?: 0L) / 1000)
        }
        TextLink(
            stringResource(R.string.gnss_mode, modeText),
            {
                val next = MODES[(MODES.indexOf(mode) + 1) % MODES.size]
                location.share(next)
                MeshService.shareLocation(context, next != null)
            },
            Modifier.padding(top = 10.dp),
        )
        Text(
            stringResource(R.string.gnss_mode_text), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private fun Float?.one(): String? = this?.let { String.format(Locale.ROOT, "%.1f", it) }
