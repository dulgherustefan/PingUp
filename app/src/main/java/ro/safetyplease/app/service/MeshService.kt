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

/**
 * Keeps the mesh running with the screen off. Android requires a connectedDevice foreground service
 * with a persistent notification for that.
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
        val anchor = container.settings.value.role == Role.ANCHOR
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, container.notifier.foreground(0, anchor), type)
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
            combine(links, role) { count, r -> count to (r == Role.ANCHOR) }.collectLatest { (count, isAnchor) ->
                // links come and go often; the notification shouldn't flicker with them
                delay(1_000)
                container.notifier.updateForeground(count, isAnchor)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.settings.update { it.copy(meshEnabled = false) }
            setWanted(this, false)
            stopSelf()
            return START_NOT_STICKY
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

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
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
