package ro.safetyplease.ui.ios

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import org.jetbrains.compose.resources.StringResource
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIPasteboard
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.common.IosTorch
import ro.safetyplease.ui.common.Torch
import ro.safetyplease.ui.common.topViewController

/**
 * What the screens need from an iPhone: the mesh as the app runs it, Bluetooth and the position, iOS's own screens and
 * sheets. iOS has no battery exemption to ask for: Bluetooth keeps running in the background by itself.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPhoneUi(private val app: IosApp, private val location: IosLocation, val toasts: Toasts) : PhoneUi {
    override fun startMesh() = app.startMesh()

    override fun stopMesh() = app.stopMesh()

    override fun bluetoothAllowed(): Boolean = CBManager.authorization == CBManagerAuthorizationAllowedAlways

    override fun locationAllowed(): Boolean = location.allowed()

    override fun locationOn(): Boolean = location.on()

    override fun startLocation() = location.start()

    override fun dial112() = open("tel:112")

    // an app can open only its own page in Settings; location is under Privacy there
    override fun openLocationSettings() = openAppSettings()

    override fun openAppSettings() = open(UIApplicationOpenSettingsURLString)

    // straight to the app's notifications page, from iOS 16 on
    override fun openNotificationSettings() = open(UIApplicationOpenNotificationSettingsURLString)

    override fun toast(text: String, long: Boolean) = toasts.show(text, long)

    override fun copyText(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun shareFiles(files: List<Path>, onShared: (() -> Unit)?) = onMain {
        val sheet = UIActivityViewController(activityItems = files.map { NSURL.fileURLWithPath(it.toString()) }, applicationActivities = null)
        sheet.completionWithItemsHandler = { _, completed, _, _ -> if (completed) onShared?.invoke() }
        val top = topViewController() ?: return@onMain
        // an iPad shows the sheet as a popover, which needs a place to point at
        sheet.popoverPresentationController?.sourceView = top.view
        top.presentViewController(sheet, animated = true, completion = null)
    }

    override fun torch(): Torch = IosTorch()

    override fun batteryHintNeeded() = false

    override val batterySteps: StringResource? = null

    override fun requestBatteryExemption() = Unit

    override val appVersion: String = info("CFBundleShortVersionString")

    override val appName: String = info("CFBundleDisplayName").ifEmpty { info("CFBundleName") }

    override val demoAvailable = false

    private fun open(link: String) = onMain {
        val url = NSURL.URLWithString(link) ?: return@onMain
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private fun info(key: String): String = NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: ""
}
