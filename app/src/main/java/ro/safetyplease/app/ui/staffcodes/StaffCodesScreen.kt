package ro.safetyplease.app.ui.staffcodes

import ro.safetyplease.ui.resources.staff_code_handed_over
import ro.safetyplease.ui.resources.staff_code_on_scene
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.cancel
import ro.safetyplease.ui.resources.report_rate_limited
import ro.safetyplease.ui.resources.staff_code_danger
import ro.safetyplease.ui.resources.staff_code_medic
import ro.safetyplease.ui.resources.staff_code_reinforcements
import ro.safetyplease.ui.resources.staff_codes_intro
import ro.safetyplease.ui.resources.staff_codes_moved
import ro.safetyplease.ui.resources.staff_codes_none
import ro.safetyplease.ui.resources.staff_codes_nothing
import ro.safetyplease.ui.resources.staff_codes_sent
import ro.safetyplease.ui.resources.staff_codes_state_title
import ro.safetyplease.ui.resources.staff_codes_title
import ro.safetyplease.ui.resources.staff_points_open
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.rememberNow
import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.incidents.StaffCode
import ro.safetyplease.core.incidents.StaffCodes
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.StaffCodeResult
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline

/**
 * The field phone's quick codes: big buttons, one tap each, nothing typed. The first three go out as staff reports with
 * the phone's position, the last two move the incident the team has taken. A tap by mistake can be taken back for a
 * few seconds.
 */
@Composable
fun StaffCodesScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val now = rememberNow()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    var result by remember { mutableStateOf<StaffCodeResult?>(null) }

    // the position is attached to a report whenever there is a fix, so GPS starts with the screen
    LifecycleResumeEffect(Unit) {
        vm.phone.startLocation()
        onPauseOrDispose { }
    }
    LaunchedEffect(result) {
        if (result != null) {
            delay(RESULT_MS)
            result = null
        }
    }

    fun send(code: StaffCode, label: String) {
        result = vm.sendStaffCode(code, label)
        if (result !is StaffCodeResult.RateLimited && result !is StaffCodeResult.NothingToMove) haptics.confirm()
        else haptics.reject()
    }

    val medic = stringResource(Res.string.staff_code_medic)
    val reinforcements = stringResource(Res.string.staff_code_reinforcements)
    val danger = stringResource(Res.string.staff_code_danger)

    NavScreen(
        title = stringResource(Res.string.staff_codes_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.staff_codes_intro), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
            )
            CodeButton(medic, Sym.Medical, colors.red, Color.White) { send(StaffCode.MEDIC, medic) }
            CodeButton(reinforcements, Sym.Groups, colors.orange, Color.Black) { send(StaffCode.REINFORCEMENTS, reinforcements) }
            CodeButton(danger, Sym.Priority, colors.red, Color.White) { send(StaffCode.STAFF_IN_DANGER, danger) }

            Feedback(result, vm)

            SectionTitle(stringResource(Res.string.staff_codes_state_title), top = 12.dp)
            for ((code, label, icon) in FIELD_CODES) {
                val target = remember(incidents.staff, settings.teamName, code) { StaffCodes.target(code, incidents.staff, settings.teamName) }
                val text = stringResource(label)
                CodeButton(
                    text, icon, colors.accent, colors.onAccent, enabled = target != null,
                    subtitle = target?.let { listOf(stringResource(Labels.category(it.category)), vm.venue.zoneName(it.zone)).filter { part -> part.isNotEmpty() }.joinToString(" · ") }
                        ?: stringResource(Res.string.staff_codes_none),
                ) { send(code, text) }
            }

            if (PointBoard.points(vm.venue).isNotEmpty()) {
                val points = stringResource(Res.string.staff_points_open)
                CodeButton(points, Sym.Flag, colors.fill, colors.label) { vm.open(Dest.Points) }
            }
        }
    }
}

private val FIELD_CODES = listOf(
    Triple(StaffCode.ON_SCENE, Res.string.staff_code_on_scene, Sym.MyLocation),
    Triple(StaffCode.HANDED_OVER, Res.string.staff_code_handed_over, Sym.CheckCircle),
)

private const val RESULT_MS = 10_000L

@Composable
private fun Feedback(result: StaffCodeResult?, vm: AppViewModel) {
    val colors = AppTheme.colors
    when (result) {
        null -> Unit
        is StaffCodeResult.Reported -> Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
            StatusLabel(stringResource(Res.string.staff_codes_sent), Modifier.weight(1f), icon = Sym.CheckCircle, color = colors.accent)
            TextLink(stringResource(Res.string.cancel), { vm.cancelReport(result.incidentId) })
        }
        StaffCodeResult.Moved -> StatusLabel(stringResource(Res.string.staff_codes_moved), Modifier.padding(horizontal = Gutter), icon = Sym.CheckCircle, color = colors.accent)
        StaffCodeResult.NothingToMove ->
            StatusLabel(stringResource(Res.string.staff_codes_nothing), Modifier.padding(horizontal = Gutter), icon = Sym.Info, color = colors.redInk)
        StaffCodeResult.RateLimited -> {
            val waitMs = vm.rateLimitWaitMs()
            StatusLabel(
                pluralStringResource(Res.plurals.report_rate_limited, vm.rateLimitMax(), vm.rateLimitMax(), (waitMs / 60_000 + 1).toInt()),
                Modifier.padding(horizontal = Gutter), icon = Sym.Info, color = colors.redInk,
            )
        }
    }
}

/** A wide target for a gloved or hurried thumb: icon and words on a solid color, the second line saying what a tap will move. */
@Composable
private fun CodeButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onColor: Color,
    enabled: Boolean = true,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val fill = if (enabled) color else colors.fill
    val ink = if (enabled) onColor else colors.secondaryLabel
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(26.dp)).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = ink)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.headline, fontWeight = FontWeight.Bold, color = ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.footnote, color = ink.copy(alpha = 0.85f))
        }
    }
}
