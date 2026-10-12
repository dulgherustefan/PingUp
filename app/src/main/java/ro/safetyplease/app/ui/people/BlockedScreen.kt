package ro.safetyplease.app.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.TextLink

/** The people this phone ignores, each with a way back. Opened from Me, which lists it only once someone is blocked. */
@Composable
fun BlockedScreen(vm: AppViewModel) {
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val resources = LocalResources.current
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }

    NavScreen(
        title = stringResource(R.string.blocked_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        if (blocked.isEmpty()) {
            EmptyState(stringResource(R.string.blocked_empty), stringResource(R.string.blocked_empty_text), icon = Sym.Hidden, modifier = Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(top = 8.dp)) {
                InsetGroup {
                    blocked.forEachIndexed { index, person ->
                        GroupRow(
                            person.nickname,
                            subtitle = stringResource(R.string.blocked_since, Labels.date(resources, person.blockedAt)),
                            leading = { Avatar(person.nickname, 36.dp) },
                            trailing = { TextLink(stringResource(R.string.blocked_unblock), { vm.unblock(person.nodeId) }, color = colors.accent) },
                        )
                        if (index < blocked.lastIndex) GroupDivider(start = 64.dp)
                    }
                }
            }
        }
    }
}
