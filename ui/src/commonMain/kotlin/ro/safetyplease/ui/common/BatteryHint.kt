package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import ro.safetyplease.ui.PhoneUi

/**
 * Whether the phone may stop the app in the background, asked once and again each time the app comes back to the front:
 * the person may have just exempted it in the phone's settings.
 */
@Composable
fun rememberBatteryHint(phone: PhoneUi): Boolean {
    var needed by remember { mutableStateOf(phone.batteryHintNeeded()) }
    LifecycleResumeEffect(Unit) {
        needed = phone.batteryHintNeeded()
        onPauseOrDispose { }
    }
    return needed
}
