package ro.safetyplease.app.ui.staffcodes

import ro.safetyplease.ui.resources.staff_points_other
import ro.safetyplease.ui.resources.staff_points_team
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.staff_points_intro
import ro.safetyplease.ui.resources.staff_points_sent
import ro.safetyplease.ui.resources.staff_points_title
import ro.safetyplease.ui.resources.staff_points_wait
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.safetyplease.ui.text.rememberNow
import ro.safetyplease.app.ui.common.pointLevelColor
import ro.safetyplease.app.ui.common.pointLevelLabel
import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.SentPoint
import ro.safetyplease.core.protocol.PointLevel
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.StatusLabel
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.rememberHaptics
import ro.safetyplease.ui.designsystem.subheadline

/**
 * A staff phone's points of the site, its team's first, each with its three levels: one tap sends the status to the
 * console. Only the console sees it.
 */
@Composable
fun PointsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sent by vm.c.sentPoints.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val now = rememberNow()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val points = remember(settings.teamName) { PointBoard.forTeam(vm.venue, settings.teamName) }
    var refused by remember { mutableStateOf(false) }

    LaunchedEffect(refused) {
        if (refused) {
            delay(REFUSED_MS)
            refused = false
        }
    }

    NavScreen(
        title = stringResource(Res.string.staff_points_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.staff_points_intro), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
            )
            if (refused) {
                StatusLabel(stringResource(Res.string.staff_points_wait), Modifier.padding(horizontal = Gutter), icon = Sym.Info, color = colors.orangeInk)
            }
            val (own, other) = points.partition { settings.teamName.isNotEmpty() && it.team == settings.teamName }
            for ((title, group) in listOf(Res.string.staff_points_team to own, Res.string.staff_points_other to other)) {
                if (group.isEmpty()) continue
                if (own.isNotEmpty()) SectionTitle(stringResource(title), top = 8.dp)
                for (poi in group) {
                    PointCard(poi, sent[poi.number], now) { level ->
                        if (vm.sendPoint(poi.number, level)) haptics.confirm() else {
                            refused = true
                            haptics.reject()
                        }
                    }
                }
            }
        }
    }
}

private const val REFUSED_MS = 5_000L

/** A point's name, what this phone last said of it, and its three levels as wide buttons, the one it said ringed. */
@Composable
private fun PointCard(poi: Poi, sent: SentPoint?, now: Long, onLevel: (Int) -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.cell).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(poi.name.ifEmpty { "#${poi.number}" }, style = MaterialTheme.typography.headline)
        if (sent != null) {
            Text(
                stringResource(Res.string.staff_points_sent, stringResource(pointLevelLabel(sent.level)), ((now - sent.atMs) / 60_000).toInt()),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in PointLevel.ALL) {
                val color = pointLevelColor(level)
                val chosen = sent?.level == level
                Box(
                    Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.16f))
                        .then(if (chosen) Modifier.border(2.dp, color, RoundedCornerShape(16.dp)) else Modifier)
                        .clickable(role = Role.Button) { onLevel(level) }.padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(pointLevelLabel(level)), style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.SemiBold,
                        color = colors.label, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
