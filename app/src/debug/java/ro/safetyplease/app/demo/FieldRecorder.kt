package ro.safetyplease.app.demo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.BuildConfig
import ro.safetyplease.app.platform.ble.Advert
import ro.safetyplease.app.platform.ble.RadioProbe
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.toHex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Field measurements, one recording at a time, as CSV in the app's external files:
 *   adb pull /sdcard/Android/data/org.pingup.app/files/measure/
 * Field: every raw scan result of our service, link PHYs, TEST packets, and every 10 s the battery, the scan
 * response rate and the engine's counters (columns in [FieldRow]).
 * Census: an unfiltered scan, one row per second (columns in [CensusCounter]). Android pauses unfiltered scans
 * while the screen is off, so it has to stay on. The scan permission is neverForLocation, which hides some beacons.
 */
@SuppressLint("MissingPermission")
class FieldRecorder(private val context: Context, private val c: AppContainer, private val pinger: TestPinger) : RadioProbe {
    enum class Mode { FIELD, CENSUS }

    class Recording(val mode: Mode, val file: File, val rows: Int)

    private class Session(val mode: Mode, val file: File) {
        val startedAt = SystemClock.elapsedRealtime()
        val lines = Channel<String>(Channel.UNLIMITED)
        val rows = AtomicInteger()
        val scan = FieldScan()
        val census = CensusCounter()
        val jobs = ArrayList<Job>()
        var censusCallback: ScanCallback? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: Session? = null

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Where the recordings go; adb pull takes the whole folder. */
    val directory: File? get() = context.getExternalFilesDir(DIR)

    /** Starts a recording and returns its file. In field mode, [pings] TEST packets go out [everyMs] apart. */
    @Synchronized
    fun start(mode: Mode, pings: Int = 0, everyMs: Long = 1_000): File {
        check(session == null) { "already recording" }
        val dir = directory ?: error("external storage unavailable")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = if (mode == Mode.FIELD) "field" else "census"
        val s = Session(mode, File(dir, "$name-${c.identity.nodeId.nodePrefix().toHex()}-$stamp.csv"))
        if (mode == Mode.CENSUS) startCensus(s)
        scope.launch { write(s) }
        if (mode == Mode.FIELD) startField(s, pings, everyMs)
        session = s
        publish(s)
        return s.file
    }

    /** Stops the recording; the file is complete once the queued rows are written, a moment later. */
    @Synchronized
    fun stop(): Recording? {
        val s = session ?: return null
        session = null
        if (c.radio.probe === this) c.radio.probe = null
        s.censusCallback?.let { callback -> runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) } }
        s.jobs.forEach { it.cancel() }
        // a last round, so battery and counters cover the whole recording
        if (s.mode == Mode.FIELD) stats(s)
        s.lines.close()
        _recording.value = null
        return Recording(s.mode, s.file, s.rows.get())
    }

    /** Every recording, newest first. */
    fun files(): List<File> =
        directory?.listFiles { file -> file.extension == "csv" }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Opens the share sheet with every recording; false if there is none yet. */
    fun share(activity: Context): Boolean {
        val uris = files().map { FileProvider.getUriForFile(context, "${context.packageName}.measure", it) }
        if (uris.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/csv")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, null))
        return true
    }

    override fun heard(advert: Advert) {
        val s = session ?: return
        add(s, s.scan.row(advert))
    }

    override fun phy(address: String, txPhy: Int, rxPhy: Int) {
        val s = session ?: return
        // right after LinkUp the HELLO may not be in yet; the address still matches the adv rows
        val peer = c.engine.state.value.links.firstOrNull { it.address == address && it.peerId != 0L }?.peerId?.nodePrefix()?.toHex()
        add(s, FieldRow("phy", peer = peer.orEmpty(), address = address, phyTx = phyName(txPhy), phyRx = phyName(rxPhy)))
    }

    private fun startField(s: Session, pings: Int, everyMs: Long) {
        val radio = c.engine.state.value.radio
        listOf(
            FieldRow.info("device", "${Build.MANUFACTURER} ${Build.MODEL}"),
            FieldRow.info("android", Build.VERSION.SDK_INT),
            FieldRow.info("version", BuildConfig.VERSION_NAME),
            FieldRow.info("node", c.identity.nodeId.nodePrefix().toHex()),
            FieldRow.info("offloaded_batching", adapter?.isOffloadedScanBatchingSupported == true),
            FieldRow.info("coded_phy", radio.codedPhy),
            FieldRow.info("long_range", radio.longRangeActive),
        ).forEach { add(s, it) }
        // subscribed before the first ping goes out
        s.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { pinger.rows.collect { add(s, it) } }
        s.jobs += scope.launch {
            while (true) {
                stats(s)
                delay(STATS_MS)
            }
        }
        if (pings > 0) {
            s.jobs += scope.launch {
                repeat(pings) {
                    pinger.ping()
                    delay(everyMs)
                }
            }
        }
        c.radio.probe = this
    }

    private fun startCensus(s: Session) {
        check(c.radio.hasPermissions()) { "no Bluetooth permission" }
        val scanner = adapter?.bluetoothLeScanner ?: error("Bluetooth is off")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = s.census.heard(result.device.address, result.rssi)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { s.census.heard(it.device.address, it.rssi) }

            override fun onScanFailed(errorCode: Int) {
                Log.i(TestPinger.TAG, "census scan failed: $errorCode")
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        scanner.startScan(emptyList(), settings, callback)
        s.censusCallback = callback
        s.jobs += scope.launch {
            var next = s.startedAt
            while (true) {
                next += 1_000
                delay(next - SystemClock.elapsedRealtime())
                add(s, s.census.drain())
                publish(s)
            }
        }
    }

    private fun stats(s: Session) {
        (battery() + s.scan.drain() + meshStats(c.engine.state.value)).forEach { add(s, it) }
        publish(s)
    }

    private fun battery(): List<FieldRow> {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return emptyList()
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        // the charge counter is finer than the percentage over a 2-3 hour run; not every phone reports it
        val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }
        return listOfNotNull(
            FieldRow.stat("battery_pct", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)),
            charge?.let { FieldRow.stat("battery_uah", it) },
            temperature?.let { FieldRow.stat("battery_temp_c", it / 10.0) },
            FieldRow.stat("charging", if (manager.isCharging) 1 else 0),
        )
    }

    private fun add(s: Session, row: FieldRow) = enqueue(s, row.csv(System.currentTimeMillis(), SystemClock.elapsedRealtime() - s.startedAt))

    private fun add(s: Session, censusCells: String) =
        enqueue(s, "${System.currentTimeMillis()},${SystemClock.elapsedRealtime() - s.startedAt},$censusCells")

    private fun enqueue(s: Session, line: String) {
        if (s.lines.trySend(line).isSuccess) s.rows.incrementAndGet()
    }

    private suspend fun write(s: Session) {
        s.file.bufferedWriter().use { out ->
            out.appendLine(if (s.mode == Mode.FIELD) FieldRow.HEADER else CensusCounter.HEADER)
            for (line in s.lines) {
                out.appendLine(line)
                // write whatever else is queued, then flush, so a killed process loses little
                var next = s.lines.tryReceive().getOrNull()
                while (next != null) {
                    out.appendLine(next)
                    next = s.lines.tryReceive().getOrNull()
                }
                out.flush()
            }
        }
    }

    private fun publish(s: Session) {
        if (session === s) _recording.value = Recording(s.mode, s.file, s.rows.get())
    }

    companion object {
        private const val DIR = "measure"
        private const val STATS_MS = 10_000L
    }
}
