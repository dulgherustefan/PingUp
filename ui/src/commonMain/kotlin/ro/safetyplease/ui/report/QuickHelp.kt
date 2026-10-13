package ro.safetyplease.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.StaffFields
import ro.safetyplease.core.protocol.Triage
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.reportSpot
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.quick_cannot_move_confirm
import ro.safetyplease.ui.resources.quick_cannot_move_confirm_text
import ro.safetyplease.ui.resources.quick_cannot_move_text
import ro.safetyplease.ui.resources.quick_cannot_move_title
import ro.safetyplease.ui.resources.quick_send
import ro.safetyplease.ui.resources.quick_unconscious_confirm
import ro.safetyplease.ui.resources.quick_unconscious_confirm_text
import ro.safetyplease.ui.resources.quick_unconscious_text
import ro.safetyplease.ui.resources.quick_unconscious_title
import ro.safetyplease.ui.resources.report_rate_limited

/** The two reports sent with one confirmation: someone who doesn't breathe, and being stuck in the crowd. */
enum class QuickHelp { UNCONSCIOUS, CANNOT_MOVE }

/** The first-aid sheet the reporter of an unconscious person sees at once. */
const val NOT_BREATHING_SHEET = "01-nu-respira"

/**
 * Sends [kind] with the position, like any report; the "inconștient" one also as a triage "doesn't respond". A staff
 * phone sends it in its team's name. The id of the report, or null if the rate limit held it.
 */
fun AppViewModel.sendQuickHelp(kind: QuickHelp): String? {
    val s = c.settings.value
    val spot = reportSpot(s, c.locationFix.value, c.clock.wallMs(), venue)
    val (category, triage, extras) = when (kind) {
        QuickHelp.UNCONSCIOUS -> Triple(IncidentCategory.MEDICAL, Triage(Answer.NO, Answer.UNKNOWN), ReportExtras(unconscious = true))
        QuickHelp.CANNOT_MOVE -> Triple(IncidentCategory.CROWD, null, ReportExtras(cannotMove = true))
    }
    val draft = ReportDraft(
        category, Priority.severity(category, urgent = false, triage), currentZoneId(), spot?.point?.lat, spot?.point?.lon, "", null,
        triage, StaffFields().takeIf { s.role.hasStaffKeys }, location = spot?.fields, extras = extras,
    )
    if (!c.incidents.report(draft)) return null
    return c.incidentStore.value.mine.lastOrNull()?.incidentId
}

@Composable
fun QuickHelpRows(vm: AppViewModel, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<QuickHelp?>(null) }
    InsetGroup(modifier) {
        GroupRow(
            stringResource(Res.string.quick_unconscious_title), subtitle = stringResource(Res.string.quick_unconscious_text),
            icon = Sym.Medical, tint = colors.redInk, chevron = true, onClick = { asking = QuickHelp.UNCONSCIOUS },
        )
        GroupDivider()
        GroupRow(
            stringResource(Res.string.quick_cannot_move_title), subtitle = stringResource(Res.string.quick_cannot_move_text),
            icon = Sym.Groups, chevron = true, onClick = { asking = QuickHelp.CANNOT_MOVE },
        )
    }
    val kind = asking ?: return
    val unconscious = kind == QuickHelp.UNCONSCIOUS
    ConfirmDialog(
        stringResource(if (unconscious) Res.string.quick_unconscious_confirm else Res.string.quick_cannot_move_confirm),
        stringResource(if (unconscious) Res.string.quick_unconscious_confirm_text else Res.string.quick_cannot_move_confirm_text),
        onDismiss = { asking = null },
        confirmLabel = stringResource(Res.string.quick_send),
        destructive = unconscious,
    ) {
        val id = vm.sendQuickHelp(kind)
        if (id == null) {
            haptics.reject()
            val max = vm.rateLimitMax()
            val minutes = (vm.rateLimitWaitMs() / 60_000 + 1).toInt()
            scope.launch { vm.phone.toast(getPluralString(Res.plurals.report_rate_limited, max, max, minutes), long = true) }
        } else {
            haptics.confirm()
            vm.open(Dest.ReportSent(id))
            // what to do while help comes, before anything else
            if (unconscious) vm.open(Dest.FirstAidSheet(NOT_BREATHING_SHEET))
        }
    }
}
