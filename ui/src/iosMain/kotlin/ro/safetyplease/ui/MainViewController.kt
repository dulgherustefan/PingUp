package ro.safetyplease.ui

import androidx.compose.runtime.key
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.ios.IosApp

/** What the iPhone app shows: Swift hosts this view controller and nothing else. */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController(): UIViewController {
    pinLanguage(appLanguage())
    val app = IosApp()
    return ComposeUIViewController {
        AppTheme {
            // a new session brings new screens, without the old ones' state
            key(app.model) { AppRoot(app.model, onStartMesh = app::startMesh) }
            app.toasts.Host()
        }
    }
}
