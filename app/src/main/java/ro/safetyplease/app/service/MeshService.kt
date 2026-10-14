package ro.safetyplease.app.service

import android.annotation.SuppressLint
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

/**
 * Keeps the mesh running with the screen off. Android requires a connectedDevice foreground service
 * with a persistent notification for that. While a location share session runs the service also holds the location
 * type, asked for from a visible screen ([shareLocation]); on Android 10 and 11 it holds it whenever location is allowed,
 * since scanning needs it there ([meshServiceTypes]). There is no background location permission.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private val screenOn = MutableStateFlow(true)

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
            goForeground(sharing = false)
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
        screenOn.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val screenEvents = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(this, screenReceiver, screenEvents, ContextCompat.RECEIVER_NOT_EXPORTED)

        // the radio, its power and an anchor's heartbeat, until the service ends and cancels the scope
        scope.launch { container.runMesh(screenOn) }
        scope.launch {
            val links = container.engine.state.map { it.readyLinks }.distinctUntilChanged()
            val role = container.settings.state.map { it.role }.distinctUntilChanged()
            combine(links, role, container.sharing) { count, r, sharing -> Triple(count, r, sharing) }.collectLatest { (count, r, sharing) ->
                // links come and go often; the notification shouldn't flicker with them
                delay(1_000)
                container.notifier.updateForeground(count, r, sharing)
            }
        }
    }

    /** In the foreground, sharing the location ([sharing]) or not; a refused location type leaves the mesh its own. */
    private fun goForeground(sharing: Boolean) {
        val notification = container.notifier.foreground(container.engine.state.value.readyLinks, container.settings.value.role, container.sharing.value)
        val types = meshServiceTypes(Build.VERSION.SDK_INT, sharing, container.location.hasPermission())
        try {
            ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, notification, types)
        } catch (e: Exception) {
            // Android refuses the location type to an app it doesn't see on screen, or once the permission went
            val plain = meshServiceTypes(Build.VERSION.SDK_INT, sharing = false, locationAllowed = false)
            if (types == plain) throw e
            ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, notification, plain)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.settings.update { it.copy(meshEnabled = false) }
            setWanted(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running && intent?.action == ACTION_STOP_SHARING) {
            container.stopAllShares()
            runCatching { goForeground(sharing = false) }
        } else if (running && intent?.hasExtra(EXTRA_LOCATION) == true) {
            // said from a visible screen: on Android 11 that also gives the scans the location a boot start couldn't
            runCatching { goForeground(intent.getBooleanExtra(EXTRA_LOCATION, false) && container.location.hasPermission()) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            runCatching { unregisterReceiver(screenReceiver) }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** From the notification's button: turns the network off until the user turns it back on. */
        const val ACTION_STOP = "ro.safetyplease.app.STOP_MESH"
        private const val PREFS = "mesh_service"
        private const val KEY_WANTED = "wanted"
        private const val EXTRA_LOCATION = "location"
        private const val ACTION_STOP_SHARING = "ro.safetyplease.app.STOP_SHARING"

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

        /** "Oprește" in the notification: every share ends at once, silently. */
        fun stopSharingIntent(context: Context): Intent = Intent(context, MeshService::class.java).setAction(ACTION_STOP_SHARING)

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

/**
 * The foreground service types of the mesh on Android [sdk]: connectedDevice always, location while a share runs
 * ([sharing]). On Android 10 and 11 also location whenever it is allowed ([locationAllowed]): there a Bluetooth scan
 * started with the screen off finds nothing without location access, which an app with no background location only
 * has as a location service. From Android 12 scanning needs no location.
 */
// the types are plain numbers, and below Android 10, where they don't exist, none is returned
@SuppressLint("InlinedApi")
fun meshServiceTypes(sdk: Int, sharing: Boolean, locationAllowed: Boolean): Int {
    if (sdk < Build.VERSION_CODES.Q) return 0
    val scanNeedsLocation = sdk < Build.VERSION_CODES.S && locationAllowed
    val location = if (sharing || scanNeedsLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or location
}
