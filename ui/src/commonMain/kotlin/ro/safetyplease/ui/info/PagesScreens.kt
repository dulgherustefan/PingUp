package ro.safetyplease.ui.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.event.InfoPage
import ro.safetyplease.core.event.InfoPages
import ro.safetyplease.core.event.PageBlock
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.first_aid_warning
import ro.safetyplease.ui.resources.pages_approved
import ro.safetyplease.ui.resources.pages_draft_text
import ro.safetyplease.ui.resources.pages_draft_title
import ro.safetyplease.ui.resources.pages_intro
import ro.safetyplease.ui.resources.pages_title

/** Headings and loose text start where the text inside the cards starts. */
private val TextInset = Gutter * 2

/** English has its own copy of the pages, with the same file names; every other language reads the Romanian originals. */
fun pagesDirectory(language: String): String = if (language == "en") "pages-en" else "pages"

/** The pages of [language] the app ships with. */
fun loadPages(session: PingUpSession, language: String): List<InfoPage> = InfoPages.read(session.assets(pagesDirectory(language)))

/**
 * Information pages, offline: the beach, transport, the wristband and money, harm reduction and the Safe Zone, safe
 * spaces. Opened from the map tab and from Me.
 */
@Composable
fun PagesScreen(vm: AppViewModel) {
    val pages = rememberPages(vm)
    PagesScaffold(vm) {
        Text(
            stringResource(Res.string.pages_intro), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = TextInset),
        )
        if (pages.any { it.draft }) DraftBanner()
        InsetGroup(Modifier.padding(top = 20.dp)) {
            pages.forEachIndexed { i, page ->
                if (i > 0) GroupDivider(start = Gutter)
                GroupRow(page.title, subtitle = page.summary, chevron = true, onClick = { vm.open(Dest.Page(page.id)) })
            }
        }
    }
}

/** One page: what it is for, the draft notice until the organiser approves it, then its sections. */
@Composable
fun PageScreen(vm: AppViewModel, id: String) {
    val page = rememberPages(vm).firstOrNull { it.id == id }
    if (page == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val colors = AppTheme.colors
    PagesScaffold(vm) {
        Text(
            page.title, style = MaterialTheme.typography.title2,
            modifier = Modifier.padding(horizontal = TextInset).semantics { heading() },
        )
        Text(
            page.summary, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
            modifier = Modifier.padding(start = TextInset, end = TextInset, top = 4.dp),
        )
        if (page.draft) DraftBanner()
        pageParts(page).forEach { part ->
            when (val first = part.first()) {
                is PageBlock.Heading -> Text(
                    first.text, style = MaterialTheme.typography.headline,
                    modifier = Modifier.padding(start = TextInset, end = TextInset, top = 24.dp).semantics { heading() },
                )
                is PageBlock.Bullet -> InsetGroup(Modifier.padding(top = 12.dp)) {
                    part.forEachIndexed { i, bullet ->
                        if (i > 0) GroupDivider()
                        Text(
                            bullet.text, style = MaterialTheme.typography.body,
                            modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
                        )
                    }
                }
                is PageBlock.Warning -> Banner(
                    stringResource(Res.string.first_aid_warning), first.text, Modifier.padding(start = Gutter, end = Gutter, top = 16.dp),
                    warning = true,
                )
                is PageBlock.Paragraph -> Text(
                    first.text, style = MaterialTheme.typography.body,
                    modifier = Modifier.padding(start = TextInset, end = TextInset, top = 12.dp),
                )
            }
        }
        page.approval?.let {
            Text(
                stringResource(Res.string.pages_approved, it), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(start = TextInset, end = TextInset, top = 24.dp),
            )
        }
    }
}

@Composable
private fun PagesScaffold(vm: AppViewModel, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(Res.string.pages_title), background = AppTheme.colors.grouped, scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp, bottom = 24.dp)) { content() }
    }
}

@Composable
private fun rememberPages(vm: AppViewModel): List<InfoPage> {
    // the event pack's pages, approved by the organiser, replace the ones the app ships
    val pack = vm.c.event?.pages.orEmpty()
    val language = vm.c.language()
    return remember(language) {
        val files = InfoPages.forLanguage(pack, language)
        if (files.isEmpty()) loadPages(vm.c, language) else InfoPages.read(files)
    }
}

/** Shown on every page the organiser has not approved yet. */
@Composable
private fun DraftBanner() {
    Banner(
        stringResource(Res.string.pages_draft_title), stringResource(Res.string.pages_draft_text),
        Modifier.padding(start = Gutter, end = Gutter, top = 16.dp), warning = true,
    )
}

/** What goes under the title and the summary, with consecutive bullets kept together in one card. */
fun pageParts(page: InfoPage): List<List<PageBlock>> {
    val summary = page.blocks.indexOfFirst { it is PageBlock.Paragraph }
    val parts = mutableListOf<MutableList<PageBlock>>()
    page.blocks.forEachIndexed { i, block ->
        val last = parts.lastOrNull()
        when {
            i == summary -> Unit
            block is PageBlock.Bullet && last != null && last.last() is PageBlock.Bullet -> last.add(block)
            else -> parts.add(mutableListOf(block))
        }
    }
    return parts
}
