package ro.safetyplease.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.SodiumCrypto
import ro.safetyplease.core.util.toHex

/** What the iPhone app shows: Swift hosts this view controller and nothing else. */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController(): UIViewController {
    pinLanguage(appLanguage())
    val identity = Identity.generate(SodiumCrypto())
    return ComposeUIViewController { App(identity.nodeId.toHex()) }
}
