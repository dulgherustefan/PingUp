package ro.safetyplease.ui.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Role
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.SwitchRow
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.mandown_setting
import ro.safetyplease.ui.resources.mandown_setting_text

/** Man-down, for staff in the field and only where the phone can run it; on unless they turn it off. */
@Composable
fun ManDownSetting(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prefs by vm.c.staffChain.prefs.state.collectAsStateWithLifecycle()
    if (settings.role != Role.STAFF || !vm.c.staffChain.manDownAvailable) return
    InsetGroup {
        SwitchRow(
            stringResource(Res.string.mandown_setting), prefs.manDown, vm.c.staffChain::setManDown,
            subtitle = stringResource(Res.string.mandown_setting_text), icon = Sym.Person,
        )
    }
}
