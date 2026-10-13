package ro.safetyplease.ui

import androidx.compose.runtime.key
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.ios.IosApp

private var started: IosApp? = null

/**
 * The process's app, made once: Swift's app delegate calls it at launch, before any screen, so that a launch by iOS in
 * the background for Bluetooth brings the session and the mesh up too.
 */
@Suppress("unused") // called from Swift
fun startApp(): IosApp = started ?: run {
    pinLanguage(appLanguage())
    IosApp().also { started = it }
}

/** What the iPhone app shows: Swift hosts this view controller and nothing else. */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController(): UIViewController {
    val app = startApp()
    return ComposeUIViewController {
        AppTheme {
            // a new session brings new screens, without the old ones' state
            key(app.model) { AppRoot(app.model, onStartMesh = app::startMesh) }
            app.toasts.Host()
        }
    }
}
