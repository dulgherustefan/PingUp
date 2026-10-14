package ro.safetyplease.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.common.Beacon
import ro.safetyplease.ui.common.IosTorch
import ro.safetyplease.ui.common.Torch
import ro.safetyplease.ui.common.topViewController
import ro.safetyplease.ui.map.OfflineMap
import ro.safetyplease.ui.me.GnssView
import ro.safetyplease.ui.text.wallNowMs

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

    // CoreLocation paces itself; the position comes as often as it moves
    override fun urgentLocation(on: Boolean) = Unit

    // the app shares only while it's in front, so there is nothing to keep running
    override fun sharingLocation(on: Boolean) = Unit

    // the arrow to a friend is drawn from north
    override fun heading(): Float? = null

    // the drawn venue map stands in
    override val offlineMap: OfflineMap? = null

    override val gnss: GnssView? = null

    override fun dial112() = open("tel:112")

    // as IosNotifications posts; iOS has no expiry for a delivered notification, so the app takes it away after 30 minutes
    override fun location112(title: String, text: String) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(text)
        val until = wallNowMs() + LOCATION_112_MS
        content.setUserInfo(mapOf<Any?, Any?>(IosApp.OPEN to OpenTarget.CARD_112, IosNotifications.UNTIL to until.toString()))
        content.setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive)
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(UNNotificationRequest.requestWithIdentifier(LOCATION_112, content, null), null)
    }

    // an app can open only its own page in Settings; location is under Privacy there
    override fun openLocationSettings() = openAppSettings()

    override fun openAppSettings() = open(UIApplicationOpenSettingsURLString)

    // straight to the app's notifications page, from iOS 16 on
    override fun openNotificationSettings() = open(UIApplicationOpenNotificationSettingsURLString)

    // iOS has no full-screen alerts for an app to be refused, and shows none over the lock screen
    override fun fullScreenAlertsAllowed() = true

    override fun openFullScreenAlertSettings() = openNotificationSettings()

    @Composable
    override fun ShowOverLockScreen(): Boolean = false

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

    override fun beacon(sound: Boolean): Beacon = IosBeacon(sound)

    @Composable
    override fun KeepScreenOn() {
        DisposableEffect(Unit) {
            UIApplication.sharedApplication.idleTimerDisabled = true
            onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
        }
    }

    override fun batteryHintNeeded() = false

    override val batterySteps: StringResource? = null

    override fun requestBatteryExemption() = Unit

    override val appVersion: String = info("CFBundleShortVersionString")

    override val appName: String = info("CFBundleDisplayName").ifEmpty { info("CFBundleName") }

    override val demoAvailable = false

    override fun diagnosticFiles(): List<Path> = IosDiagnostics.files()

    private fun open(link: String) = onMain {
        val url = NSURL.URLWithString(link) ?: return@onMain
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private fun info(key: String): String = NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: ""

    private companion object {
        const val LOCATION_112 = "location-112"

        /** As long as Android keeps it: a call to 112 is over by then. */
        const val LOCATION_112_MS = 30 * 60_000L
    }
}
