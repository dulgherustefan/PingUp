package ro.safetyplease.app.ui.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.AppLocale
import ro.safetyplease.app.R
import ro.safetyplease.app.text.NoticeText
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.designsystem.AppButton
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.ButtonKind
import ro.safetyplease.app.ui.designsystem.IconBtn
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.caption1
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.largeTitle
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title3
import ro.safetyplease.core.announce.HeldNotice
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.venue.Venue

/** A notice in force, with its words in the phone's language. */
class ShownNotice(val held: HeldNotice, val notice: Notice, val title: String, val text: String)

@Composable
fun shownNotices(vm: AppViewModel, active: List<Pair<HeldNotice, Notice>>): List<ShownNotice> {
    val res = LocalResources.current
    val language = AppLocale.language(LocalContext.current)
    return active.map { (held, notice) ->
        ShownNotice(held, notice, NoticeText.title(res, notice), NoticeText.body(res, vm.c.noticeTemplates, vm.venue, notice, language))
    }
}

/**
 * The bands of the notices in force, above every screen like the exercise band: one line each, the whole text on a tap.
 * The heat band leads to water and shade. Where [statusBar], the first band takes the status bar's space.
 */
@Composable
fun NoticeBands(vm: AppViewModel, notices: List<ShownNotice>, statusBar: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        notices.forEachIndexed { index, shown ->
            NoticeBand(vm, shown, Modifier.then(if (statusBar && index == 0) Modifier.statusBarsPadding() else Modifier))
        }
    }
}

/** Shade first, then a chill-out zone, which is usually in the shade; null if the venue has neither. */
fun shadeType(venue: Venue): String? = listOf(Venue.SHADE, "chill").firstOrNull { venue.spots(it).isNotEmpty() }

@Composable
private fun NoticeBand(vm: AppViewModel, shown: ShownNotice, modifier: Modifier) {
    val colors = AppTheme.colors
    val notice = shown.notice
    var open by rememberSaveable(notice.topic, notice.seq) { mutableStateOf(false) }
    val (fill, ink) = when {
        notice.urgent && notice.level == NoticeLevel.EVACUATION -> colors.red to Color.White
        notice.urgent -> colors.orange to Color.Black
        else -> colors.cell to colors.label
    }
    Column(
        modifier.fillMaxWidth().background(fill).clickable { open = !open }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(topicIcon(notice.topic), null, Modifier.size(18.dp), tint = if (notice.urgent) ink else topicTint(notice.topic))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(shown.title, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold, color = ink)
                Text(
                    shown.text, style = MaterialTheme.typography.subheadline, color = ink,
                    maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconBtn(Sym.Close, stringResource(R.string.notice_hide), { vm.c.notices.dismiss(notice.topic, notice.seq) }, tint = ink)
        }
        if (notice.topic == NoticeTopic.HEAT) {
            Row(Modifier.padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(stringResource(R.string.map_quick_water), { vm.open(Dest.Map(spot = "water")) }, kind = ButtonKind.Secondary, compact = true)
                shadeType(vm.venue)?.let { shade ->
                    AppButton(stringResource(R.string.map_quick_shade), { vm.open(Dest.Map(spot = shade)) }, kind = ButtonKind.Secondary, compact = true)
                }
            }
        }
    }
}

private fun topicIcon(topic: Int): ImageVector = when (topic) {
    NoticeTopic.ALERT -> Sym.Report
    NoticeTopic.HEAT -> Sym.Fire
    NoticeTopic.SCHEDULE -> Sym.Clock
    NoticeTopic.TRANSPORT -> Sym.Walk
    else -> Sym.Info
}

@Composable
private fun topicTint(topic: Int): Color = when (topic) {
    NoticeTopic.ALERT -> AppTheme.colors.accent
    NoticeTopic.HEAT -> AppTheme.colors.orange
    else -> AppTheme.colors.secondaryLabel
}

/**
 * A pause or an evacuation takes the whole screen once, until someone reads it; its band stays while it is in force.
 * In an exercise it says so at the top.
 */
@Composable
fun NoticeAlert(shown: ShownNotice, exercise: Boolean, onRead: () -> Unit) {
    val evacuation = shown.notice.level == NoticeLevel.EVACUATION
    val ink = if (evacuation) Color.White else Color.Black
    // it takes every touch, so nothing underneath is tapped by mistake
    Box(
        Modifier.fillMaxSize().background(if (evacuation) AppTheme.colors.red else AppTheme.colors.orange)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            if (exercise) {
                Text(stringResource(R.string.exercise_banner), style = MaterialTheme.typography.headline, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(16.dp))
            }
            Icon(Sym.Report, null, Modifier.size(64.dp), tint = ink)
            Spacer(Modifier.height(16.dp))
            Text(
                shown.title, style = MaterialTheme.typography.largeTitle, fontWeight = FontWeight.Bold, color = ink, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(shown.text, style = MaterialTheme.typography.title3, color = ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            AppButton(stringResource(R.string.notice_read), onRead, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}
