package ro.safetyplease.app.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.Emergency
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.rememberNow
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.app.ui.isOpen
import ro.safetyplease.app.ui.rememberLocationRequest
import ro.safetyplease.core.incidents.Discreet

/** A discreet report still counts as the one in progress for this long. */
private const val ACTIVE_MS = 60 * 60_000L

/**
 * "Mă simt în nesiguranță": one button, calm words, no alarm. The report goes to the safeguarding team with the phone's
 * position; this phone neither sounds nor vibrates, now or when the team answers. The organiser has to have someone
 * to answer, so without that in the venue file the screen only says where to go instead.
 */
@Composable
fun DiscreetScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val spot by vm.spot.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val context = LocalContext.current
    val message = stringResource(R.string.discreet_message)
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    var limited by remember { mutableStateOf(false) }
    val requestLocation = rememberLocationRequest(vm)
    // found again by its mark, so it is still the one in progress after the screen or the app was left
    val report = incidents.mine.filter { Discreet.isDiscreet(it.description) && it.isOpen() && now - it.createdAt < ACTIVE_MS }.maxByOrNull { it.createdAt }

    LifecycleResumeEffect(Unit) {
        vm.c.location.start()
        onPauseOrDispose { }
    }

    NavScreen(
        title = stringResource(R.string.discreet_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        if (!vm.venue.safeguarding) {
            EmptyState(
                stringResource(R.string.discreet_off_title), stringResource(R.string.discreet_off_text), Modifier.padding(padding), icon = Sym.Info,
                action = { AppButton(stringResource(R.string.emergency_call), { Emergency.dial(context) }, kind = ButtonKind.Secondary, icon = Sym.Phone) },
            )
            return@NavScreen
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (report == null) {
                Text(stringResource(R.string.discreet_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                if (spot == null) {
                    StatusLabel(stringResource(R.string.discreet_no_location), icon = Sym.Info)
                    if (!vm.c.location.hasPermission()) TextLink(stringResource(R.string.report_use_location), { requestLocation() })
                }
                if (limited) {
                    StatusLabel(
                        pluralStringResource(
                            R.plurals.report_rate_limited, vm.rateLimitMax(), vm.rateLimitMax(), (vm.rateLimitWaitMs() / 60_000 + 1).toInt(),
                        ),
                        icon = Sym.Info, color = colors.redInk,
                    )
                }
                AppButton(stringResource(R.string.discreet_send), { limited = vm.reportDiscreet(message) == null }, Modifier.fillMaxWidth())
            } else {
                val status = Labels.myReportStatus(report.status, report.teamName.isNotEmpty())?.let {
                    if (it == R.string.status_acknowledged) stringResource(it, report.teamName) else stringResource(it)
                }
                StatusLabel(stringResource(R.string.discreet_sent_title), icon = Sym.CheckCircle, color = colors.accent)
                Text(stringResource(R.string.discreet_sent_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                if (status != null) Text(status, style = MaterialTheme.typography.subheadline, color = colors.label)
                if (!report.cancelled) TextLink(stringResource(R.string.discreet_withdraw), { vm.cancelReport(report.incidentId) })
            }
            AppButton(
                stringResource(R.string.emergency_call), { Emergency.dial(context) }, Modifier.fillMaxWidth(), ButtonKind.Secondary, icon = Sym.Phone,
            )
        }
    }
}
