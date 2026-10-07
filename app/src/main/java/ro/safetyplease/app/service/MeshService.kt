package ro.safetyplease.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.mesh.PowerPolicy

/**
 * Tine mesh-ul pornit cu ecranul stins. Android cere pentru asta un serviciu in prim-plan,
 * cu notificare permanenta, de tipul connectedDevice.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private var batteryPercent = 100
    private var charging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            applyPower()
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
            // repornirea din fundal dupa ce procesul a fost omorat nu e permisa pe Android 12+; asteptam sa fie deschisa aplicatia
            Log.w(AppContainer.TAG, "serviciul nu a putut porni in prim-plan: ${e.message}")
            stopSelf()
            return
        }
        running = true
        container.meshScope.launch { container.radio.start() }
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch {
            val links = container.engine.state.map { it.readyLinks }.distinctUntilChanged()
            val role = container.settings.state.map { it.role }.distinctUntilChanged()
            combine(links, role) { count, r -> count to (r == Role.ANCHOR) }.collectLatest { (count, isAnchor) ->
                // legaturile apar si dispar des; notificarea nu trebuie sa palpaie odata cu ele
                delay(1_000)
                container.notifier.updateForeground(count, isAnchor)
            }
        }
        scope.launch {
            val pending = container.incidentStore.state.map { data -> hasPending() }.distinctUntilChanged()
            combine(pending, container.boostUntil) { _, until -> until }.collectLatest { until ->
                applyPower()
                val remaining = until - container.clock.monoMs()
                if (remaining > 0) {
                    delay(remaining)
                    applyPower()
                }
            }
        }
    }

    private fun hasPending(): Boolean {
        val now = container.clock.wallMs()
        return container.incidentStore.value.mine.any { IncidentManager.isPending(it, now) }
    }

    private fun applyPower() {
        val boost = container.clock.monoMs() < container.boostUntil.value
        val (scan, advertise) = PowerPolicy.modes(batteryPercent, charging, hasPending(), boost)
        container.meshScope.launch { container.radio.setPowerMode(scan, advertise) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (running) {
            running = false
            runCatching { unregisterReceiver(batteryReceiver) }
            container.meshScope.launch { container.radio.stop() }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
