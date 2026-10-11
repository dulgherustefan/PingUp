package ro.safetyplease.app.platform.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

private val KILLING_VENDORS = setOf("samsung", "xiaomi", "redmi", "poco")

/** Vendors that kill background apps unless the app is excluded from battery optimization. */
fun killsBackgroundApps(manufacturer: String): Boolean = manufacturer.lowercase() in KILLING_VENDORS

/**
 * The exemption from battery optimization, asked for from one place. Besides keeping vendors from killing the mesh,
 * it is what lets Android 12+ restart the service after the process was killed.
 */
object BatteryExemption {
    fun vendorKillsBackgroundApps(): Boolean = killsBackgroundApps(Build.MANUFACTURER)

    fun granted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun hintNeeded(context: Context): Boolean = vendorKillsBackgroundApps() && !granted(context)

    /**
     * Asks directly while the exemption is missing: safety apps are among the cases Android accepts for that.
     * Once granted there is nothing left to ask, so the system list opens instead.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        if (!granted(context)) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
            if (runCatching { context.startActivity(direct) }.isSuccess) return
        }
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
