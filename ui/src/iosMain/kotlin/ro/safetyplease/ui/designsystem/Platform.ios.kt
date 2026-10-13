package ro.safetyplease.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNotificationName
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityMedium
import platform.UIKit.UIContentSizeCategoryDidChangeNotification
import platform.UIKit.UIContentSizeCategoryExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraSmall
import platform.UIKit.UIContentSizeCategoryLarge
import platform.UIKit.UIContentSizeCategoryMedium
import platform.UIKit.UIContentSizeCategorySmall

actual val CanBlur = true

@Composable
actual fun rememberReduceMotion(): Boolean =
    rememberSetting(UIAccessibilityReduceMotionStatusDidChangeNotification) { UIAccessibilityIsReduceMotionEnabled() }

/**
 * The text size the phone asks for, followed while the app runs. Compose reads it only when it makes the screen
 * (1.12.1), so a change in Settings waited for the next launch; this gives each size the scale Compose gives it.
 */
@Composable
fun rememberFontScale(): Float =
    rememberSetting(UIContentSizeCategoryDidChangeNotification) { FontScales[UIApplication.sharedApplication.preferredContentSizeCategory] ?: 1f }

/** A system setting, read again each time iOS posts [change]. */
@Composable
private fun <T> rememberSetting(change: NSNotificationName, read: () -> T): T {
    var value by remember { mutableStateOf(read()) }
    DisposableEffect(change) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(change, null, NSOperationQueue.mainQueue) { _ -> value = read() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return value
}

// Compose's own table (androidx.compose.ui.uikit.density): linear, where iOS scales its fonts unevenly
private val FontScales = mapOf(
    UIContentSizeCategoryExtraSmall to 0.8f,
    UIContentSizeCategorySmall to 0.85f,
    UIContentSizeCategoryMedium to 0.9f,
    UIContentSizeCategoryLarge to 1f,
    UIContentSizeCategoryExtraLarge to 1.1f,
    UIContentSizeCategoryExtraExtraLarge to 1.2f,
    UIContentSizeCategoryExtraExtraExtraLarge to 1.3f,
    UIContentSizeCategoryAccessibilityMedium to 1.4f,
    UIContentSizeCategoryAccessibilityLarge to 1.5f,
    UIContentSizeCategoryAccessibilityExtraLarge to 1.6f,
    UIContentSizeCategoryAccessibilityExtraExtraLarge to 1.7f,
    UIContentSizeCategoryAccessibilityExtraExtraExtraLarge to 1.8f,
)
