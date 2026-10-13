package ro.safetyplease.ui.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import org.jetbrains.compose.resources.getString
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.emergency_call

/**
 * The iPhone app's process: a session and the screens' state on it, made again in place when an event is chosen or the
 * data wiped, since iOS doesn't let an app start itself again. The mesh runs while Bluetooth may, in front or not.
 */
class IosApp {
    private val scope = MainScope()
    private val location = IosLocation()
    private val notifications = UNUserNotificationCenter.currentNotificationCenter()

    // the notification center holds its delegate weakly
    private val taps = Taps()

    /** In front and in use: what the screen being on is on Android, for the power plan and the notifications. */
    private val active = MutableStateFlow(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)

    val toasts = Toasts()
    val phone = IosPhoneUi(this, location, toasts)
    private var store = ViewModelStore()
    private lateinit var platform: IosPlatform
    private var mesh: Job? = null

    /** The screens' state; a new session brings a new one. */
    var model: AppViewModel by mutableStateOf(create())
        private set

    init {
        // started again by iOS for Bluetooth, or by the person: the mesh comes back on if they left it on
        val settings = model.c.settings.value
        if (settings.onboarded && settings.meshEnabled && CBManager.authorization == CBManagerAuthorizationAllowedAlways) startMesh()
        for ((event, isActive) in listOf(UIApplicationDidBecomeActiveNotification to true, UIApplicationWillResignActiveNotification to false)) {
            NSNotificationCenter.defaultCenter.addObserverForName(event, null, NSOperationQueue.mainQueue) { _ ->
                active.value = isActive
                model.c.foreground.value = isActive
            }
        }
        notifications.delegate = taps
        scope.launch {
            // read before the call: an Objective-C call doesn't keep its arguments across a suspension
            val title = getString(Res.string.emergency_call)
            val call = UNNotificationAction.actionWithIdentifier(CALL_112, title, UNNotificationActionOptionForeground)
            notifications.setNotificationCategories(setOf(UNNotificationCategory.categoryWithIdentifier(CALL_112, listOf(call), emptyList<String>(), 0u)))
        }
    }

    private fun create(): AppViewModel {
        platform = IosPlatform(location, active, phone.appName, onRestart = { onMain(::restart) }, onWipe = { onMain(::wipe) })
        val session = PingUpSession(platform)
        session.foreground.value = active.value
        val factory = viewModelFactory { initializer { AppViewModel(session, phone) } }
        return ViewModelProvider.create(store, factory)[AppViewModel::class]
    }

    /**
     * Once the person allowed Bluetooth, or before iOS has asked: starting the radio is what makes it ask. A network
     * the person turned off stays off.
     */
    fun startMesh() {
        val c = model.c
        val refused = CBManager.authorization.let { it == CBManagerAuthorizationDenied || it == CBManagerAuthorizationRestricted }
        if (refused || !c.settings.value.meshEnabled) return
        if (mesh?.isActive == true) c.meshScope.launch { c.radio.retry() } else mesh = scope.launch { c.runMesh(active) }
        // asks for the position the first time, then follows it
        location.start()
    }

    fun stopMesh() {
        mesh?.cancel()
        mesh = null
    }

    /** A notification was tapped: its screen opens. */
    fun open(target: String) = model.openTarget(target)

    /** The event just chosen loads in a new session; its files were flushed before. */
    private fun restart() {
        val meshing = mesh?.isActive == true
        replace()
        if (meshing) startMesh()
    }

    /** Everything the app keeps goes, as deleting the app would do, and it starts over from the first screen. */
    private fun wipe() {
        val files = platform.filesDir
        val cache = platform.cacheDir
        replace {
            FileSystem.SYSTEM.deleteRecursively(files)
            FileSystem.SYSTEM.deleteRecursively(cache)
            KeychainVault(IosPlatform.VAULT_SERVICE).deleteAll()
            notifications.removeAllDeliveredNotifications()
            notifications.removeAllPendingNotificationRequests()
        }
    }

    /** Ends the session and its screens' state, does [between], then makes new ones. */
    private fun replace(between: () -> Unit = {}) {
        // the mesh's own stop would come after close() ended the session's runs: the radio stops here, before the next starts
        model.c.radio.stop()
        stopMesh()
        model.c.close()
        store.clear()
        between()
        store = ViewModelStore()
        model = create()
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private inner class Taps : NSObject(), UNUserNotificationCenterDelegateProtocol {
        // in front too: the Android app shows its notifications whatever is on screen
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) = withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound)

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            if (didReceiveNotificationResponse.actionIdentifier == CALL_112) {
                phone.dial112()
            } else {
                (didReceiveNotificationResponse.notification.request.content.userInfo[OPEN] as? String)?.let(::open)
            }
            withCompletionHandler()
        }
    }

    companion object {
        /** Where a notification leads, as Android's intent extra says it. */
        const val OPEN = "open"

        /** An urgent report nobody confirmed: its notification offers the call. */
        const val CALL_112 = "call-112"
    }
}
