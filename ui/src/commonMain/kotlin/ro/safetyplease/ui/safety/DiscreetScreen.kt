package ro.safetyplease.ui.safety

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.safety.Welfare
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
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
import ro.safetyplease.ui.isOpen
import ro.safetyplease.ui.rememberLocationRequest
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.card112_link
import ro.safetyplease.ui.resources.discreet_message
import ro.safetyplease.ui.resources.discreet_no_location
import ro.safetyplease.ui.resources.discreet_off_text
import ro.safetyplease.ui.resources.discreet_off_title
import ro.safetyplease.ui.resources.discreet_send
import ro.safetyplease.ui.resources.discreet_sent_text
import ro.safetyplease.ui.resources.discreet_sent_title
import ro.safetyplease.ui.resources.discreet_text
import ro.safetyplease.ui.resources.discreet_title
import ro.safetyplease.ui.resources.discreet_withdraw
import ro.safetyplease.ui.resources.emergency_call
import ro.safetyplease.ui.resources.report_rate_limited
import ro.safetyplease.ui.resources.report_use_location
import ro.safetyplease.ui.resources.status_acknowledged
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.rememberNow

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
    val message = stringResource(Res.string.discreet_message)
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    var limited by remember { mutableStateOf(false) }
    val safety = rememberReportOptions()
    val requestLocation = rememberLocationRequest(vm)
    // found again by its mark, so it is still the one in progress after the screen or the app was left
    val report = incidents.mine.filter { isUnsafeReport(it.description) && it.isOpen() && now - it.createdAt < ACTIVE_MS }.maxByOrNull { it.createdAt }

    LifecycleResumeEffect(Unit) {
        vm.phone.startLocation()
        vm.phone.urgentLocation(true)
        onPauseOrDispose { vm.phone.urgentLocation(false) }
    }

    NavScreen(
        title = stringResource(Res.string.discreet_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        if (!vm.venue.safeguarding) {
            EmptyState(
                stringResource(Res.string.discreet_off_title), stringResource(Res.string.discreet_off_text), Modifier.padding(padding), icon = Sym.Info,
                action = { AppButton(stringResource(Res.string.emergency_call), vm::call112, kind = ButtonKind.Secondary, icon = Sym.Phone) },
            )
            return@NavScreen
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (report == null) {
                Text(stringResource(Res.string.discreet_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                if (spot == null) {
                    StatusLabel(stringResource(Res.string.discreet_no_location), icon = Sym.Info)
                    if (!vm.phone.locationAllowed()) TextLink(stringResource(Res.string.report_use_location), { requestLocation() })
                }
                if (limited) {
                    StatusLabel(
                        pluralStringResource(
                            Res.plurals.report_rate_limited, vm.rateLimitMax(), vm.rateLimitMax(), (vm.rateLimitWaitMs() / 60_000 + 1).toInt(),
                        ),
                        icon = Sym.Info, color = colors.redInk,
                    )
                }
                ReportOptionsSection(safety, IncidentCategory.HARASSMENT, bleed = true)
                AppButton(
                    stringResource(Res.string.discreet_send),
                    {
                        limited = vm.reportDiscreet(message, safety.extras(IncidentCategory.HARASSMENT)) == null
                        if (!limited) safety.reset()
                    },
                    Modifier.fillMaxWidth(),
                )
            } else {
                val status = Labels.myReportStatus(report.status, report.teamName.isNotEmpty())?.let {
                    if (it == Res.string.status_acknowledged) stringResource(it, report.teamName) else stringResource(it)
                }
                StatusLabel(stringResource(Res.string.discreet_sent_title), icon = Sym.CheckCircle, color = colors.accent)
                Text(stringResource(Res.string.discreet_sent_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                if (status != null) Text(status, style = MaterialTheme.typography.subheadline, color = colors.label)
                if (!report.cancelled) TextLink(stringResource(Res.string.discreet_withdraw), { vm.cancelReport(report.incidentId) })
            }
            AppButton(
                stringResource(Res.string.emergency_call), vm::call112, Modifier.fillMaxWidth(), ButtonKind.Secondary, icon = Sym.Phone,
            )
            TextLink(stringResource(Res.string.card112_link), { vm.open(Dest.Safety(SafetyScreen.CARD_112)) }, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** A "mă simt în nesiguranță" report; "Nu sunt bine" carries the same first mark but has its own screen. */
fun isUnsafeReport(description: String): Boolean = Discreet.isDiscreet(description) && !Welfare.isWelfare(description)
