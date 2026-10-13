package ro.safetyplease.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Path.Companion.toOkioPath
import okio.source
import ro.safetyplease.app.platform.ble.BleRadio
import ro.safetyplease.app.platform.keystore.KeyVault
import ro.safetyplease.app.platform.location.GnssPrewarm
import ro.safetyplease.app.platform.location.LocationSource
import ro.safetyplease.app.platform.location.PedestrianSteps
import ro.safetyplease.app.platform.motion.MotionSensor
import ro.safetyplease.app.platform.network.MobileNetwork
import ro.safetyplease.app.platform.power.BatteryMonitor
import ro.safetyplease.app.service.Notifier
import ro.safetyplease.app.text.text
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.SodiumCrypto
import ro.safetyplease.core.incidents.MotionSample
import ro.safetyplease.core.mesh.BatteryReading
import ro.safetyplease.core.session.AppPlatform
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.text.ControlNoticeText
import ro.safetyplease.ui.text.NoticeText
import java.net.HttpURLConnection
import java.net.URL

object AndroidClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoMs(): Long = SystemClock.elapsedRealtime()
}

/** The Android phone under the shared session: Keystore, Bluetooth, notifications, GPS, the battery and the assets. */
class AndroidPlatform(private val app: Application) : AppPlatform {
    override val clock: Clock = AndroidClock
    override val crypto: Crypto = SodiumCrypto(LazySodiumAndroid(SodiumAndroid()))
    override val vault = KeyVault(app)
    override val filesDir = app.filesDir.toOkioPath()
    override val cacheDir = app.cacheDir.toOkioPath()
    override val debug = BuildConfig.DEBUG

    val locationSource = LocationSource(app)
    override val location = locationSource.fix
    val gnssPrewarm = GnssPrewarm(app)
    val steps = PedestrianSteps(app)

    /** [LocationSource] lives on the main thread. */
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mobileNetwork = MobileNetwork(app)
    private val motion = MotionSensor(app)

    /** Made by the session as it starts; the service and the screens reach them through [AppContainer]. */
    lateinit var bleRadio: BleRadio
        private set
    lateinit var notifier: Notifier
        private set

    override fun language(): String = AppLocale.current()

    override fun asset(name: String): String = app.assets.open(name).bufferedReader().use { it.readText() }

    override fun assets(folder: String): Map<String, String> =
        app.assets.list(folder).orEmpty().associateWith { name -> asset("$folder/$name") }

    override fun log(message: String) {
        Log.d(AppContainer.TAG, message)
    }

    override fun radio(scope: CoroutineScope, nodeId: Long) = BleRadio(app, scope, nodeId) { Log.i(AppContainer.TAG, it) }.also { bleRadio = it }

    override fun notifications(venue: Venue, exercise: Boolean) = Notifier(app, venue, exercise).also { notifier = it }

    override fun shareLocation(maxIntervalMs: Long?) {
        main.launch { locationSource.share(maxIntervalMs) }
    }

    override fun watchBattery(onReading: (BatteryReading) -> Unit) {
        BatteryMonitor(app, onReading)
    }

    override fun watchMotion(onSample: ((MotionSample) -> Unit)?) = motion.watch(onSample)

    override val watchesMotion: Boolean get() = motion.available

    override suspend fun sampleMobileNetwork(atMs: Long, cell: String) = withContext(Dispatchers.IO) { mobileNetwork.sample(atMs, cell) }

    override fun noticeTitle(notice: Notice): String = app.text(NoticeText.title(notice.topic, notice.level), notice.level)

    override fun noticeBody(templates: NoticeTemplates, venue: Venue, notice: Notice): String =
        (NoticeText.text(templates, notice, language()) ?: app.text(NoticeText.noText)).let { text ->
            ControlNoticeText.body(notice, venue, text) { res, args -> app.text(res, *args) }
                ?: if (notice.zone.isEmpty()) text else app.text(NoticeText.inZone, venue.zoneName(notice.zone), text)
        }

    override suspend fun download(url: String, limit: Long): ByteArray? = withContext(Dispatchers.IO) {
        val link = runCatching { URL(url) }.getOrNull()?.takeIf { it.protocol == "https" } ?: return@withContext null
        runCatching {
            val connection = link.openConnection() as HttpURLConnection
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.source().use { source ->
                    val buffer = Buffer()
                    while (buffer.size < limit && source.read(buffer, 64 * 1024L) >= 0) Unit
                    buffer.readByteArray(minOf(buffer.size, limit))
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override fun restart() {
        app.startActivity(Intent.makeRestartActivityTask(ComponentName(app, MainActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }

    override fun wipe() {
        app.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MS = 20_000
    }
}
