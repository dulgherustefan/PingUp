package ro.safetyplease.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.mesh.AnchorHeartbeat
import ro.safetyplease.core.mesh.DeviceReadings
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.metrics.NetworkLog

/**
 * Keeps the mesh running with the screen off. Android requires a connectedDevice foreground service
 * with a persistent notification for that. While a location share session runs the service also holds the location
 * type, added only from a visible screen ([shareLocation]); there is no background location permission.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private val battery get() = container.battery.state
    private val screenOn = MutableStateFlow(true)
    private val boosting = MutableStateFlow(false)
    private val reportPending = MutableStateFlow(false)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as App).container
        try {
            goForeground(location = false)
        } catch (e: Exception) {
            // Android 12+ forbids starting it from the background after the process was killed, unless battery
            // optimization is off; the user brings it back by opening the app
            Log.w(AppContainer.TAG, "service could not start in the foreground: ${e.message}")
            container.notifier.meshStopped()
            stopSelf()
            return
        }
        running = true
        setWanted(this, true)
        container.meshScope.launch { container.radio.start() }
        screenOn.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val screenEvents = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(this, screenReceiver, screenEvents, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            val links = container.engine.state.map { it.readyLinks }.distinctUntilChanged()
            val role = container.settings.state.map { it.role }.distinctUntilChanged()
            combine(links, role) { count, r -> count to (r == Role.ANCHOR) }.collectLatest { (count, isAnchor) ->
                // links come and go often; the notification shouldn't flicker with them
                delay(1_000)
                container.notifier.updateForeground(count, isAnchor)
            }
        }
        scope.launch {
            container.boostUntil.collectLatest { until ->
                val remaining = until - container.clock.monoMs()
                boosting.value = remaining > 0
                if (remaining > 0) {
                    delay(remaining)
                    boosting.value = false
                }
            }
        }
        scope.launch {
            container.incidentStore.state.map { it.mine }.distinctUntilChanged().collectLatest { mine ->
                while (true) {
                    val until = IncidentManager.pendingUntil(mine, container.clock.wallMs())
                    reportPending.value = until != null
                    if (until == null) break
                    delay(until - container.clock.wallMs())
                }
            }
        }
        scope.launch {
            val role = container.settings.state.map { it.role }.distinctUntilChanged()
            combine(role, battery, screenOn, boosting, reportPending) { r, b, screen, boost, pending ->
                PowerPolicy.plan(r.hasStaffKeys, r == Role.ANCHOR, b.percent, b.charging, screen, boost, pending)
            }.distinctUntilChanged().collect { plan ->
                container.meshScope.launch { container.radio.setPower(plan) }
            }
        }
        scope.launch {
            container.settings.state.map { it.role == Role.ANCHOR }.distinctUntilChanged().collectLatest { anchor ->
                if (anchor) beatAsAnchor()
            }
        }
    }

    /**
     * Tells the console this anchor is alive, how its battery is and what it hears (PROTOCOL.md, section 6.9), with
     * its square of the site grid once it had a fix there and, with the mobile network log on, its last sample.
     */
    private suspend fun beatAsAnchor() {
        val heartbeat = AnchorHeartbeat()
        var square: Pair<Int, Int>? = null
        while (true) {
            delay(AnchorHeartbeat.CHECK_MS)
            val b = battery.value
            val device = DeviceReadings(b.percent, b.charging, b.temperatureC)
            val settings = container.settings.value
            val now = container.clock.wallMs()
            container.location.fix.value?.takeIf { it.isFresh(now) }?.let { fix -> container.grid.squareAt(fix.lat, fix.lon)?.let { square = it } }
            val sample = container.networkLog.value.samples.lastOrNull()
                ?.takeIf { settings.networkLog && now - it.at <= 2 * NetworkLog.INTERVAL_MS }
            val mobile = sample?.let(AnchorHeartbeat::mobileOf)
            heartbeat.next(container.engine.state.value, device, settings.anchorZone, container.clock.monoMs(), mobile, square)
                ?.let { container.sendHeartbeat(it) }
        }
    }

    private fun goForeground(location: Boolean) {
        val anchor = container.settings.value.role == Role.ANCHOR
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (location && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, container.notifier.foreground(container.engine.state.value.readyLinks, anchor), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running && intent?.hasExtra(EXTRA_LOCATION) == true) {
            val location = intent.getBooleanExtra(EXTRA_LOCATION, false) && container.location.hasPermission()
            // Android refuses the location type when the app is not visible; the mesh keeps its own type then
            runCatching { goForeground(location) }.onFailure { runCatching { goForeground(location = false) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            runCatching { unregisterReceiver(screenReceiver) }
            container.meshScope.launch { container.radio.stop() }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "mesh_service"
        private const val KEY_WANTED = "wanted"
        private const val EXTRA_LOCATION = "location"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        /** A share session started or ended; called from a visible screen, the only time Android allows the location type. */
        fun shareLocation(context: Context, on: Boolean) {
            if (!running) return
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java).putExtra(EXTRA_LOCATION, on))
        }

        fun stop(context: Context) {
            setWanted(context, false)
            context.stopService(Intent(context, MeshService::class.java))
        }

        /** The mesh ran and nobody stopped it, so it should come back after a reboot or an update. */
        fun wanted(context: Context): Boolean = prefs(context).getBoolean(KEY_WANTED, false)

        private fun setWanted(context: Context, wanted: Boolean) = prefs(context).edit { putBoolean(KEY_WANTED, wanted) }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
