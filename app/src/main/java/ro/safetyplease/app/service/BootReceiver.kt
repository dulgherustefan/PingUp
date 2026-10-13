package ro.safetyplease.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ro.safetyplease.app.App
import kotlin.concurrent.thread

/**
 * Brings the mesh back after a reboot or an app update, if it was running before. Both broadcasts are exemptions
 * from the Android 12+ ban on starting a foreground service from the background, and connectedDevice isn't among
 * the types Android 15 excludes. When the service still can't start, a notification asks the user to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!revives(intent.action) || !MeshService.wanted(context)) return
        // the container may still be loading from disk: wait for it off the main thread
        val pending = goAsync()
        thread(name = "boot") {
            try {
                val container = (context.applicationContext as App).container
                when (afterBoot(intent.action, wanted = true) { container.bleRadio.hasPermissions() }) {
                    BootAction.NOTHING -> Unit
                    BootAction.START -> if (runCatching { MeshService.start(context) }.isFailure) container.notifier.meshStopped()
                    BootAction.NOTIFY -> container.notifier.meshStopped()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

enum class BootAction { NOTHING, START, NOTIFY }

/**
 * After [action]: the mesh comes back only if it was running before. Without the Bluetooth permissions a
 * connectedDevice service can't go foreground on Android 14+, so the user is asked to open the app instead.
 */
fun afterBoot(action: String?, wanted: Boolean, hasPermissions: () -> Boolean): BootAction = when {
    !revives(action) || !wanted -> BootAction.NOTHING
    hasPermissions() -> BootAction.START
    else -> BootAction.NOTIFY
}

/** The broadcasts after which Android lets us start the mesh from the background. */
fun revives(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
