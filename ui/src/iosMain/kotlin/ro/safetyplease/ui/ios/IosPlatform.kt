package ro.safetyplease.ui.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLog
import platform.Foundation.NSMutableData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUserDomainMask
import platform.Foundation.appendData
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.SodiumCrypto
import ro.safetyplease.core.mesh.BatteryReading
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.session.AppPlatform
import ro.safetyplease.core.session.MeshRadio
import ro.safetyplease.core.session.Notifications
import ro.safetyplease.core.session.Vault
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.appLanguage
import ro.safetyplease.ui.text.ControlNoticeText
import ro.safetyplease.ui.text.NoticeText
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.roundToInt

/**
 * The iPhone under the shared session: the Keychain, the app's folders, what the app ships with, notifications, the
 * position and the battery. [restart] and [wipe] go back to [IosApp], which makes a new session in the same process.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalNativeApi::class)
class IosPlatform(
    positions: IosLocation,
    private val inFront: StateFlow<Boolean>,
    private val appName: String,
    private val onRestart: () -> Unit,
    private val onWipe: () -> Unit,
) : AppPlatform {
    private val fs = FileSystem.SYSTEM
    private val bundle = NSBundle.mainBundle.resourcePath!!.toPath()

    override val crypto: Crypto = SodiumCrypto()
    override val clock: Clock = IosClock
    override val filesDir: Path = folder(NSApplicationSupportDirectory, "pingup", private = true)
    override val cacheDir: Path = folder(NSCachesDirectory, "pingup", private = false)
    override val vault: Vault = KeychainVault(VAULT_SERVICE)

    // a debug framework comes with a debug build of the app, which also carries the demo assets
    override val debug: Boolean = Platform.isDebugBinary

    override val location: StateFlow<GeoFix?> = positions.fix

    // a share sends what IosLocation finds while the app is in front; nothing keeps it running in the background
    override fun shareLocation(maxIntervalMs: Long?) = Unit
    private val network = IosNetwork()

    override fun language(): String = appLanguage()

    /** The build's own copy first: a debug build trusts the demo staff keys. */
    override fun asset(name: String): String {
        val own = bundle / "assets-debug" / name
        return fs.read(if (debug && fs.exists(own)) own else bundle / "assets" / name) { readUtf8() }
    }

    override fun assets(folder: String): Map<String, String> =
        fs.listOrNull(bundle / "assets" / folder).orEmpty().associate { it.name to fs.read(it) { readUtf8() } }

    // the system log, as Android's logcat: Console.app on a Mac shows it also for a phone running on its own, which the
    // background test needs (under Xcode's debugger iOS doesn't suspend the app); filter on "Mesh:". The message is
    // the format itself, its % doubled: a Kotlin string passed to NSLog's %@ crashes the app
    override fun log(message: String) = NSLog("Mesh: " + message.replace("%", "%%"))

    override fun radio(scope: CoroutineScope, nodeId: Long): MeshRadio = IosRadio(scope, inFront, ::log)

    override fun notifications(venue: Venue, exercise: Boolean): Notifications = IosNotifications(venue, exercise, appName)

    /** UIKit gives the level in steps of 5% and no temperature; the simulator has no battery and says it charges. */
    override fun watchBattery(onReading: (BatteryReading) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            val device = UIDevice.currentDevice
            device.batteryMonitoringEnabled = true
            fun reading(): BatteryReading {
                val level = device.batteryLevel
                val state = device.batteryState
                val charging = level < 0 || state == UIDeviceBatteryState.UIDeviceBatteryStateCharging || state == UIDeviceBatteryState.UIDeviceBatteryStateFull
                return BatteryReading(if (level < 0) 100 else (level * 100).roundToInt().coerceIn(0, 100), charging, temperatureC = null)
            }
            onReading(reading())
            for (event in listOf(UIDeviceBatteryLevelDidChangeNotification, UIDeviceBatteryStateDidChangeNotification)) {
                NSNotificationCenter.defaultCenter.addObserverForName(event, null, NSOperationQueue.mainQueue) { _ -> onReading(reading()) }
            }
        }
    }

    override suspend fun sampleMobileNetwork(atMs: Long, cell: String): NetworkSample = network.sample(atMs, cell)

    // the session asks from its own thread, which may wait for the shared texts
    override fun noticeTitle(notice: Notice): String = runBlocking { getString(NoticeText.title(notice.topic, notice.level), notice.level) }

    override fun noticeBody(templates: NoticeTemplates, venue: Venue, notice: Notice): String = runBlocking {
        val text = NoticeText.text(templates, notice, language()) ?: getString(NoticeText.noText)
        val raw = ControlNoticeText.RESOURCES.associateWith { getString(it) }
        ControlNoticeText.body(notice, venue, text) { res, args -> ControlNoticeText.fill(raw.getValue(res), args) }
            ?: if (notice.zone.isEmpty()) text else getString(NoticeText.inZone, venue.zoneName(notice.zone), text)
    }

    /** At most [limit] bytes over https; what follows them isn't fetched. */
    override suspend fun download(url: String, limit: Long): ByteArray? {
        val link = NSURL.URLWithString(url)?.takeIf { it.scheme == "https" } ?: return null
        return suspendCancellableCoroutine { done ->
            val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
                timeoutIntervalForRequest = DOWNLOAD_TIMEOUT_S
                timeoutIntervalForResource = 3 * DOWNLOAD_TIMEOUT_S
            }
            val session = NSURLSession.sessionWithConfiguration(configuration, Download(limit) { done.resume(it) }, null)
            val task = session.dataTaskWithURL(link)
            done.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    override fun restart() = onRestart()

    override fun wipe() = onWipe()

    /** A folder of the app's own, made if missing; a [private] one isn't backed up, as the Android app allows no backup. */
    private fun folder(kind: ULong, name: String, private: Boolean): Path {
        val base = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String
        val path = "$base/$name"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        if (private) NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return path.toPath()
    }

    companion object {
        /** The Keychain's name for the app's secrets. */
        const val VAULT_SERVICE = "org.pingup.app.vault"
        private const val DOWNLOAD_TIMEOUT_S = 20.0
    }
}

/** Takes a response as it comes and stops past [limit] bytes, which is how the session sees a pack too large. */
@OptIn(ExperimentalForeignApi::class)
private class Download(private val limit: Long, private val done: (ByteArray?) -> Unit) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val received = NSMutableData()
    private var ok = false

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        ok = (didReceiveResponse as? NSHTTPURLResponse)?.statusCode == 200L
        completionHandler(if (ok) NSURLSessionResponseAllow else NSURLSessionResponseCancel)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        received.appendData(didReceiveData)
        if (received.length.toLong() >= limit) dataTask.cancel()
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        session.finishTasksAndInvalidate()
        val full = received.length.toLong() >= limit
        if (!ok || (didCompleteWithError != null && !full)) return done(null)
        val size = minOf(received.length.toLong(), limit).toInt()
        done(if (size == 0) ByteArray(0) else received.bytes!!.readBytes(size))
    }
}
