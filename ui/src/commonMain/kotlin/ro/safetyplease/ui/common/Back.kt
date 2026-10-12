package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Takes the back gesture while [enabled]: Android's back, the swipe from the left edge on the iPhone. The last one
 * composed wins, as with Android's own.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = enabled, onBackCompleted = onBack)
}
