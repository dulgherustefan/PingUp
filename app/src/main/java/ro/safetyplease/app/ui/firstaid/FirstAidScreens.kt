package ro.safetyplease.app.ui.firstaid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.Emergency
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.Dest
import ro.safetyplease.app.ui.common.MapGlyph
import ro.safetyplease.app.ui.common.routeText
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Banner
import ro.safetyplease.app.ui.designsystem.GlassIconButton
import ro.safetyplease.app.ui.designsystem.GroupDivider
import ro.safetyplease.app.ui.designsystem.GroupRow
import ro.safetyplease.app.ui.designsystem.Gutter
import ro.safetyplease.app.ui.designsystem.InsetGroup
import ro.safetyplease.app.ui.designsystem.NavScreen
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.body
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title2
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/** Headings and loose text start where the text inside the cards starts. */
private val TextInset = Gutter * 2

/** Sheets that mention it send someone for a defibrillator, so they get a shortcut to the nearest one. */
private const val DEA_WORD = "DEA"

/**
 * First aid, offline: the sheets for whoever is next to the patient, then the nearest defibrillator and 112.
 * Opened from the report waiting screen and from Me.
 */
@Composable
fun FirstAidScreen(vm: AppViewModel) {
    val sheets = rememberSheets()
    FirstAidScaffold(vm) {
        Text(
            stringResource(R.string.first_aid_intro), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = TextInset),
        )
        if (sheets.any { it.draft }) DraftBanner()
        InsetGroup(Modifier.padding(top = 20.dp)) {
            sheets.forEachIndexed { i, sheet ->
                if (i > 0) GroupDivider(start = Gutter)
                GroupRow(sheet.title, subtitle = sheet.summary, chevron = true, onClick = { vm.open(Dest.FirstAidSheet(sheet.id)) })
            }
        }
        HelpGroup(vm, dea = true)
    }
}

/** One sheet: when it applies, the draft notice until it is approved, the numbered steps and the warnings. */
@Composable
fun FirstAidSheetScreen(vm: AppViewModel, id: String) {
    val sheet = rememberSheets().firstOrNull { it.id == id }
    if (sheet == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val colors = AppTheme.colors
    FirstAidScaffold(vm) {
        Text(
            sheet.title, style = MaterialTheme.typography.title2,
            modifier = Modifier.padding(horizontal = TextInset).semantics { heading() },
        )
        Text(
            sheet.summary, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
            modifier = Modifier.padding(start = TextInset, end = TextInset, top = 4.dp),
        )
        if (sheet.draft) DraftBanner()
        sheetParts(sheet).forEach { part ->
            when (val first = part.first()) {
                is SheetBlock.Step -> InsetGroup(Modifier.padding(top = 20.dp)) {
                    part.filterIsInstance<SheetBlock.Step>().forEachIndexed { i, step ->
                        if (i > 0) GroupDivider()
                        StepRow(step)
                    }
                }
                is SheetBlock.Warning -> Banner(
                    stringResource(R.string.first_aid_warning), first.text, Modifier.padding(start = Gutter, end = Gutter, top = 20.dp),
                    warning = true,
                )
                is SheetBlock.Text -> Text(
                    first.text, style = MaterialTheme.typography.body,
                    modifier = Modifier.padding(start = TextInset, end = TextInset, top = 20.dp),
                )
            }
        }
        sheet.approval?.let {
            Text(
                stringResource(R.string.first_aid_approved, it), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(start = TextInset, end = TextInset, top = 12.dp),
            )
        }
        HelpGroup(vm, dea = sheet.mentions(DEA_WORD))
    }
}

@Composable
private fun FirstAidScaffold(vm: AppViewModel, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(R.string.first_aid_title), background = AppTheme.colors.grouped, scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp, bottom = 24.dp)) { content() }
    }
}

@Composable
private fun rememberSheets(): List<FirstAidSheet> {
    val assets = LocalContext.current.assets
    return remember(assets) { loadSheets(assets) }
}

/** Shown on every sheet the event's medical lead has not approved yet. */
@Composable
private fun DraftBanner() {
    Banner(
        stringResource(R.string.first_aid_draft_title), stringResource(R.string.first_aid_draft_text),
        Modifier.padding(start = Gutter, end = Gutter, top = 16.dp), warning = true,
    )
}

/** A numbered step: the number in a circle, then the text; TalkBack reads them together. */
@Composable
private fun StepRow(step: SheetBlock.Step) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        // grows into a pill with large fonts instead of clipping the number
        Box(
            Modifier.defaultMinSize(28.dp, 28.dp).clip(CircleShape).background(colors.accent).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(step.number.toString(), style = MaterialTheme.typography.headline, color = colors.onAccent, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(12.dp))
        Text(step.text, style = MaterialTheme.typography.body, modifier = Modifier.weight(1f).padding(top = 3.dp))
    }
}

/** The nearest defibrillator, when [dea] and the venue has one, then the call to 112. */
@Composable
private fun HelpGroup(vm: AppViewModel, dea: Boolean) {
    val context = LocalContext.current
    val origin = rememberOrigin(vm)
    val nearest = if (dea) vm.venue.nearest(Venue.DEA, origin) else null
    InsetGroup(Modifier.padding(top = 20.dp)) {
        if (nearest != null) {
            GroupRow(
                stringResource(R.string.nearest_dea),
                subtitle = listOf(nearest.name, routeText(origin, nearest.point)).filter { it.isNotEmpty() }.joinToString(" · "),
                icon = MapGlyph.Dea, chevron = true, onClick = { vm.open(Dest.Map(spot = Venue.DEA)) },
            )
            GroupDivider()
        }
        GroupRow(
            stringResource(R.string.emergency_call), subtitle = stringResource(R.string.report_help_call_text), icon = Sym.Phone,
            onClick = { Emergency.dial(context) },
        )
    }
}

/** Where the way to the defibrillator is measured from: your position, else the zone you picked by hand. */
@Composable
private fun rememberOrigin(vm: AppViewModel): GeoPoint? {
    val position by vm.position.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    return position ?: vm.venue.zone(settings.manualZone)?.center
}

/** What goes under the title and the summary, with consecutive steps kept together in one card. */
internal fun sheetParts(sheet: FirstAidSheet): List<List<SheetBlock>> {
    val summary = sheet.blocks.indexOfFirst { it is SheetBlock.Text }
    val parts = mutableListOf<MutableList<SheetBlock>>()
    sheet.blocks.forEachIndexed { i, block ->
        val last = parts.lastOrNull()
        when {
            i == summary -> Unit
            block is SheetBlock.Step && last != null && last.last() is SheetBlock.Step -> last.add(block)
            else -> parts.add(mutableListOf(block))
        }
    }
    return parts
}
