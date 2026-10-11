package ro.safetyplease.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ro.safetyplease.app.App

/**
 * Brings the mesh back after a reboot or an app update, if it was running before. Both broadcasts are exemptions
 * from the Android 12+ ban on starting a foreground service from the background, and connectedDevice isn't among
 * the types Android 15 excludes. When the service still can't start, a notification asks the user to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!revives(intent.action) || !MeshService.wanted(context)) return
        val container = (context.applicationContext as App).container
        // without the Bluetooth permissions a connectedDevice service can't go foreground on Android 14+
        val started = container.radio.hasPermissions() && runCatching { MeshService.start(context) }.isSuccess
        if (!started) container.notifier.meshStopped()
    }
}

/** The broadcasts after which Android lets us start the mesh from the background. */
fun revives(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
