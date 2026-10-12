package ro.safetyplease.ui.common

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** The screen in front, the one a sheet is shown from: the key window's root, then whatever it presents. */
fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .firstOrNull { it.isKeyWindow() }
    var top = window?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
