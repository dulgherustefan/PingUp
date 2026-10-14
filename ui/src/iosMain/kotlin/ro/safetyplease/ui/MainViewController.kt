package ro.safetyplease.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.rememberFontScale
import ro.safetyplease.ui.ios.IosApp

private var started: IosApp? = null

/**
 * The process's app, made once: Swift's app delegate calls it at launch, before any screen, so that a launch by iOS in
 * the background for Bluetooth brings the session and the mesh up too. [restoreCentral] and [restoreServer]: iOS
 * launched the app to give back those Bluetooth managers (the launch options' Bluetooth keys).
 */
fun startApp(restoreCentral: Boolean, restoreServer: Boolean): IosApp = started ?: run {
    pinLanguage(appLanguage())
    IosApp(restoreCentral, restoreServer).also { started = it }
}

/** What the iPhone app shows: Swift hosts this view controller and nothing else. */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController(): UIViewController {
    val app = startApp(restoreCentral = false, restoreServer = false)
    return ComposeUIViewController {
        // the screen's density with the text size the phone asks for now, also after a change in Settings
        val density = LocalDensity.current.density
        val fontScale = rememberFontScale()
        CompositionLocalProvider(LocalDensity provides remember(density, fontScale) { Density(density, fontScale) }) {
            AppTheme {
                // a new session brings new screens, without the old ones' state
                key(app.model) { AppRoot(app.model, onStartMesh = app::startMesh) }
                app.toasts.Host()
            }
        }
    }
}
