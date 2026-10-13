package ro.safetyplease.ui.safety

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.safety.Welfare
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.isOpen
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.emergency_call
import ro.safetyplease.ui.resources.map_title
import ro.safetyplease.ui.resources.report_rate_limited
import ro.safetyplease.ui.resources.status_acknowledged
import ro.safetyplease.ui.resources.unwell_breathe_1
import ro.safetyplease.ui.resources.unwell_breathe_2
import ro.safetyplease.ui.resources.unwell_breathe_3
import ro.safetyplease.ui.resources.unwell_breathe_4
import ro.safetyplease.ui.resources.unwell_breathe_5
import ro.safetyplease.ui.resources.unwell_breathe_physical
import ro.safetyplease.ui.resources.unwell_breathe_title
import ro.safetyplease.ui.resources.unwell_exhale
import ro.safetyplease.ui.resources.unwell_inhale
import ro.safetyplease.ui.resources.unwell_kind_anxiety
import ro.safetyplease.ui.resources.unwell_kind_other
import ro.safetyplease.ui.resources.unwell_kind_overwhelmed
import ro.safetyplease.ui.resources.unwell_kind_panic
import ro.safetyplease.ui.resources.unwell_message
import ro.safetyplease.ui.resources.unwell_off_text
import ro.safetyplease.ui.resources.unwell_send
import ro.safetyplease.ui.resources.unwell_sent_text
import ro.safetyplease.ui.resources.unwell_sent_title
import ro.safetyplease.ui.resources.unwell_text
import ro.safetyplease.ui.resources.unwell_title
import ro.safetyplease.ui.resources.unwell_withdraw
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.rememberNow

/** A request for company still counts as the one in progress for this long. */
private const val ACTIVE_MS = 60 * 60_000L

private class Kind(val label: StringResource)

private val Kinds = listOf(
    Kind(Res.string.unwell_kind_anxiety), Kind(Res.string.unwell_kind_panic), Kind(Res.string.unwell_kind_overwhelmed), Kind(Res.string.unwell_kind_other),
)

/**
 * "Nu sunt bine": anxiety, panic or feeling overwhelmed. One button asks the welfare team (the safeguarding role) to
 * come, as quietly as a discreet report; until someone does, a breathing guide keeps the person company. Without a
 * safeguarding team in the venue file the screen only says where to go.
 */
@Composable
fun UnwellScreen(vm: AppViewModel) {
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val now = rememberNow()
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var limited by remember { mutableStateOf(false) }
    val labels = Kinds.map { stringResource(it.label) }
    val message = stringResource(Res.string.unwell_message, labels[kind].lowercase())
    val report = incidents.mine.filter { Welfare.isWelfare(it.description) && it.isOpen() && now - it.createdAt < ACTIVE_MS }.maxByOrNull { it.createdAt }

    LifecycleResumeEffect(Unit) {
        vm.phone.startLocation()
        vm.phone.urgentLocation(true)
        onPauseOrDispose { vm.phone.urgentLocation(false) }
    }

    NavScreen(
        title = stringResource(Res.string.unwell_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!vm.venue.safeguarding) {
                Text(stringResource(Res.string.unwell_off_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                AppButton(
                    stringResource(Res.string.map_title), { vm.open(Dest.Map(spot = "medical")) }, Modifier.fillMaxWidth(), ButtonKind.Secondary,
                    icon = Sym.Place,
                )
            } else if (report == null) {
                Text(stringResource(Res.string.unwell_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                Pills { labels.forEachIndexed { i, label -> Pill(label, kind == i, { kind = i }) } }
                if (limited) {
                    StatusLabel(
                        pluralStringResource(
                            Res.plurals.report_rate_limited, vm.rateLimitMax(), vm.rateLimitMax(), (vm.rateLimitWaitMs() / 60_000 + 1).toInt(),
                        ),
                        icon = Sym.Info, color = colors.redInk,
                    )
                }
                AppButton(stringResource(Res.string.unwell_send), { limited = vm.reportWelfare(message) == null }, Modifier.fillMaxWidth())
            } else {
                val status = Labels.myReportStatus(report.status, report.teamName.isNotEmpty())?.let {
                    if (it == Res.string.status_acknowledged) stringResource(it, report.teamName) else stringResource(it)
                }
                StatusLabel(stringResource(Res.string.unwell_sent_title), icon = Sym.CheckCircle, color = colors.accent)
                Text(stringResource(Res.string.unwell_sent_text), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
                if (status != null) Text(status, style = MaterialTheme.typography.subheadline, color = colors.label)
                if (!report.cancelled) TextLink(stringResource(Res.string.unwell_withdraw), { vm.cancelReport(report.incidentId) })
            }
            BreathingGuide()
            AppButton(
                stringResource(Res.string.emergency_call), { vm.phone.dial112() }, Modifier.fillMaxWidth(), ButtonKind.Secondary, icon = Sym.Phone,
            )
        }
    }
}

/** The report of someone who isn't well: a discreet one with its own mark, so the welfare team reads it as what it is. */
private fun AppViewModel.reportWelfare(text: String): String? = report(
    IncidentCategory.HARASSMENT, Priority.severity(IncidentCategory.HARASSMENT, urgent = false), currentZoneId(), Welfare.describe(text),
    anonymous = true,
)

private const val INHALE_MS = 4_000
private const val EXHALE_MS = 6_000

/**
 * A slow circle to breathe with, four seconds in and six out, and the steps in words. The texts are a draft until the
 * medical lead approves them (strings_safety.xml).
 */
@Composable
private fun BreathingGuide() {
    val colors = AppTheme.colors
    val still = LocalReduceMotion.current
    val phase = if (still) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "breath")
        val animated by transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(INHALE_MS + EXHALE_MS, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        )
        animated
    }
    val inhaling = phase < INHALE_MS.toFloat() / (INHALE_MS + EXHALE_MS)
    val turn = INHALE_MS.toFloat() / (INHALE_MS + EXHALE_MS)
    val scale = if (inhaling) 0.6f + 0.4f * phase / turn else 1f - 0.4f * (phase - turn) / (1f - turn)
    Text(stringResource(Res.string.unwell_breathe_title), style = MaterialTheme.typography.headline)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(140.dp).graphicsLayer {
                    scaleX = if (still) 0.8f else scale
                    scaleY = if (still) 0.8f else scale
                }.clip(CircleShape).background(colors.accent.copy(alpha = 0.25f)),
            )
            if (!still) {
                Text(stringResource(if (inhaling) Res.string.unwell_inhale else Res.string.unwell_exhale), style = MaterialTheme.typography.title2)
            }
        }
    }
    for (step in listOf(
        Res.string.unwell_breathe_1, Res.string.unwell_breathe_2, Res.string.unwell_breathe_3, Res.string.unwell_breathe_4, Res.string.unwell_breathe_5,
    )) {
        Text(stringResource(step), style = MaterialTheme.typography.subheadline, textAlign = TextAlign.Start)
    }
    Text(stringResource(Res.string.unwell_breathe_physical), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
}
