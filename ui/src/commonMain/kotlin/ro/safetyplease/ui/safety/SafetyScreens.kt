package ro.safetyplease.ui.safety

import androidx.compose.runtime.Composable
import ro.safetyplease.ui.AppViewModel

/** The screens of personal safety that sit apart from the report: [Dest.Safety][ro.safetyplease.ui.Dest.Safety] opens one. */
enum class SafetyScreen {
    /** "Check-in pe drum", with the alerts of friends who didn't check in on top. */
    CHECKIN,

    /** Only the friends' alerts, as a notification opens them. */
    FRIEND_ALERTS,

    /** "Nu sunt bine". */
    UNWELL,

    /** "Dacă suni 112, spune:". */
    CARD_112,
}

@Composable
fun SafetyScreenHost(vm: AppViewModel, screen: SafetyScreen) {
    when (screen) {
        SafetyScreen.CHECKIN -> CheckinScreen(vm)
        SafetyScreen.FRIEND_ALERTS -> FriendAlertsScreen(vm)
        SafetyScreen.UNWELL -> UnwellScreen(vm)
        SafetyScreen.CARD_112 -> Card112Screen(vm)
    }
}
