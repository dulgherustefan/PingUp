package ro.safetyplease.ui.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionAuthenticationRequired
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.core.mesh.PeerSilence
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.checkin_arrived
import ro.safetyplease.ui.resources.checkin_extend
import ro.safetyplease.ui.resources.emergency_call
import ro.safetyplease.ui.resources.iphone_last_phone
import ro.safetyplease.ui.resources.iphone_no_phone

/**
 * The iPhone app's process: a session and the screens' state on it, made again in place when an event is chosen or the
 * data wiped, since iOS doesn't let an app start itself again. The mesh runs while Bluetooth may, in front or not.
 * [restoreCentral] and [restoreServer]: iOS started the app for Bluetooth, to give back those managers.
 */
class IosApp(restoreCentral: Boolean = false, restoreServer: Boolean = false) {
    init {
        // read here, on the main thread, before any other thread asks for a text
        IosTexts.capture()
        // Apple's rule for a Bluetooth relaunch: the managers come back first, before anything else is built
        restoreBluetooth(restoreCentral, restoreServer)
    }

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

    /** When the mesh last heard another phone, and when the app last left the front; only the main thread touches them. */
    private var silence = PeerSilence(IosClock.monoMs())
    private var leftAtMs = IosClock.monoMs()
    private var sweptAtMs = 0L

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
                if (isActive) cameBack() else leftAtMs = IosClock.monoMs()
            }
        }
        // a suspended app may be ended without a word: what waits to be written goes before iOS suspends it, off the main
        // thread so the lock doesn't wait for it. No queue, so that it starts before the notification returns
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ ->
            platform.notifier.sweep()
            flushInBackground()
        }
        // iOS ends the app as this returns: the stores are written now, and the person learns the alerts stop with it
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillTerminateNotification, null, null) { _ ->
            model.c.flush()
            if (model.c.settings.value.meshEnabled) platform.notifier.closed()
        }
        notifications.delegate = taps
        scope.launch { registerActions() }
        IosDiagnostics.start(platform.filesDir)
    }

    private fun create(): AppViewModel {
        platform = IosPlatform(location, active, phone.appName, onRestart = { onMain(::restart) }, onWipe = { onMain(::wipe) })
        // iOS keeps an app's Keychain items after the app is deleted, where Android's keystore goes with the app: keys
        // without the app's files are ones a deleted app left, and deleting the app deletes everything (PRIVACY.md).
        // The folder is made at once, so the keys made now stay even if nothing is written before the app quits
        val data = platform.filesDir / "data"
        if (!FileSystem.SYSTEM.exists(data)) {
            KeychainVault(IosPlatform.VAULT_SERVICE).deleteAll()
            FileSystem.SYSTEM.createDirectories(data)
        }
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
        if (mesh?.isActive == true) c.meshScope.launch { c.radio.retry() } else mesh = scope.launch { runMesh(c) }
        // follows the position once the person allowed it; iOS asks on the screen that needs it, never at launch,
        // as the onboarding promises
        if (location.allowed()) location.start()
    }

    fun stopMesh() {
        mesh?.cancel()
        mesh = null
    }

    /** A notification was tapped: its screen opens. */
    fun open(target: String) = model.openTarget(target)

    /** A quick action from the Home Screen icon: what Android's launcher shortcut and tiles open. False for one it doesn't know. */
    fun shortcut(type: String): Boolean {
        if (type != OpenTarget.REPORT && type != OpenTarget.DISCREET) return false
        open(type)
        return true
    }

    /**
     * The mesh, with what only an iPhone needs while it runs: iOS shows nothing when Bluetooth goes off or the app goes
     * quiet in the background, so the app says it.
     */
    private suspend fun runMesh(c: PingUpSession) = coroutineScope {
        silence = PeerSilence(IosClock.monoMs())
        launch { watchBluetooth(c) }
        launch {
            // a state flow keeps only the latest while this waits
            c.engine.state.collect { state ->
                val now = IosClock.monoMs()
                silence.update(state, now)
                // each time Bluetooth wakes the app, what ran out leaves the screen; at most once a minute
                if (now - sweptAtMs >= SWEEP_EVERY_MS) {
                    sweptAtMs = now
                    platform.notifier.sweep()
                }
                delay(TRACK_EVERY_MS)
            }
        }
        c.runMesh(active)
    }

    /**
     * Bluetooth off, or refused, with the app out of the front: the mesh hears nothing and nothing on the phone says so,
     * so a notification does, until Bluetooth is back or the mesh stops. In front, the screens say it.
     */
    private suspend fun watchBluetooth(c: PingUpSession) {
        val notifier = platform.notifier
        val startedAt = IosClock.monoMs()
        try {
            combine(c.engine.state.map { it.radio.bluetoothOn }.distinctUntilChanged(), active) { on, front -> on to front }
                .distinctUntilChanged()
                .collectLatest { (on, front) ->
                    if (on) return@collectLatest notifier.bluetoothOff(false)
                    if (front) return@collectLatest
                    // the radio says how Bluetooth is a moment after it starts: off until then
                    delay(BLUETOOTH_SETTLE_MS - (IosClock.monoMs() - startedAt))
                    notifier.bluetoothOff(true)
                }
        } finally {
            notifier.bluetoothOff(false)
        }
    }

    /**
     * Back in front: what ran out leaves the screen and, after a long while with no phone in reach, the person hears it;
     * not on a glance at another app.
     */
    private fun cameBack() {
        platform.notifier.sweep()
        val now = IosClock.monoMs()
        if (mesh?.isActive != true || now - leftAtMs < AWAY_MS) return
        val minutes = silence.silentMinutes(model.c.engine.state.value, now) ?: return
        val heard = silence.heardAny
        scope.launch {
            val text = IosTexts.plural(if (heard) Res.plurals.iphone_last_phone else Res.plurals.iphone_no_phone, minutes, minutes)
            toasts.show(text, long = true)
        }
    }

    /** The stores written off the main thread, under a background task iOS lets finish before it suspends the app. */
    private fun flushInBackground() {
        val session = model.c
        val app = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        fun end() {
            if (task == UIBackgroundTaskInvalid) return
            app.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
        task = app.beginBackgroundTaskWithName("flush") { end() }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            session.flush()
            onMain { end() }
        }
    }

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
        // what watched the phone for the old session goes with it
        platform.close()
        store.clear()
        between()
        store = ViewModelStore()
        model = create()
    }

    /** "Sună la 112" for an urgent report nobody confirmed, and the check-in's answers, which work without opening the app. */
    private suspend fun registerActions() {
        // read before the calls: an Objective-C call doesn't keep its arguments across a suspension
        val call = IosTexts.text(Res.string.emergency_call)
        val arrived = IosTexts.text(Res.string.checkin_arrived)
        val later15 = IosTexts.text(Res.string.checkin_extend, 15)
        val later30 = IosTexts.text(Res.string.checkin_extend, 30)
        val dial = UNNotificationAction.actionWithIdentifier(CALL_112, call, UNNotificationActionOptionForeground)
        // the check-in is for when its owner can't answer: whoever holds the locked phone can't call off the alert
        val here = UNNotificationAction.actionWithIdentifier(ARRIVED, arrived, UNNotificationActionOptionAuthenticationRequired)
        val in15 = UNNotificationAction.actionWithIdentifier(EXTEND_15, later15, UNNotificationActionOptionNone)
        val in30 = UNNotificationAction.actionWithIdentifier(EXTEND_30, later30, UNNotificationActionOptionNone)
        notifications.setNotificationCategories(
            setOf(
                UNNotificationCategory.categoryWithIdentifier(CALL_112, listOf(dial), emptyList<String>(), 0u),
                UNNotificationCategory.categoryWithIdentifier(CHECKIN, listOf(here, in15, in30), emptyList<String>(), 0u),
            ),
        )
    }

    /**
     * "Am ajuns", "+15" or "+30" from the check-in's notification. iOS runs the app in the background for it until [done]:
     * the answer is written and the question at the new time handed to iOS first, as Android's receiver does.
     */
    private fun answerCheckin(minutes: Int, done: () -> Unit) {
        val c = model.c
        val notifier = platform.notifier
        if (minutes == 0) c.checkin.arrived() else c.checkin.extend(minutes)
        c.alertSchedule.checkin(c.checkin.state.value.current)
        scope.launch {
            withContext(Dispatchers.Default) { c.checkin.flush() }
            notifier.idle()
            done()
        }
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
            val target = didReceiveNotificationResponse.notification.request.content.userInfo[OPEN] as? String
            when (didReceiveNotificationResponse.actionIdentifier) {
                CALL_112 -> phone.dial112()
                ARRIVED -> return onMain { answerCheckin(0) { onMain(withCompletionHandler) } }
                EXTEND_15 -> return onMain { answerCheckin(15) { onMain(withCompletionHandler) } }
                EXTEND_30 -> return onMain { answerCheckin(30) { onMain(withCompletionHandler) } }
                else -> target?.let { onMain { open(it) } }
            }
            withCompletionHandler()
        }
    }

    companion object {
        /** Where a notification leads, as Android's intent extra says it. */
        const val OPEN = "open"

        /** An urgent report nobody confirmed: its notification offers the call. */
        const val CALL_112 = "call-112"

        /** "Ai ajuns?", answered from the notification. */
        const val CHECKIN = "checkin"
        private const val ARRIVED = "checkin-arrived"
        private const val EXTEND_15 = "checkin-15"
        private const val EXTEND_30 = "checkin-30"

        private const val BLUETOOTH_SETTLE_MS = 3_000L
        private const val AWAY_MS = 60_000L
        private const val TRACK_EVERY_MS = 5_000L
        private const val SWEEP_EVERY_MS = 60_000L
    }
}
