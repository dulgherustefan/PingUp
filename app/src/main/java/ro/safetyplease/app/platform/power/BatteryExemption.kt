package ro.safetyplease.app.platform.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.battery_huawei
import ro.safetyplease.ui.resources.battery_oneplus
import ro.safetyplease.ui.resources.battery_oppo
import ro.safetyplease.ui.resources.battery_samsung
import ro.safetyplease.ui.resources.battery_vivo
import ro.safetyplease.ui.resources.battery_xiaomi

/**
 * Vendors that kill background apps unless the user exempts them, with the steps for each; sub-brands go under their
 * parent. The worst offenders ranked on dontkillmyapp.com; near-stock Android doesn't need the hint.
 */
enum class BatteryVendor(val steps: StringResource) {
    HUAWEI(Res.string.battery_huawei),
    XIAOMI(Res.string.battery_xiaomi),
    ONEPLUS(Res.string.battery_oneplus),
    SAMSUNG(Res.string.battery_samsung),
    OPPO(Res.string.battery_oppo),
    VIVO(Res.string.battery_vivo),
}

fun batteryVendor(manufacturer: String): BatteryVendor? = when (manufacturer.lowercase()) {
    "huawei", "honor" -> BatteryVendor.HUAWEI
    "xiaomi", "redmi", "poco" -> BatteryVendor.XIAOMI
    "oneplus" -> BatteryVendor.ONEPLUS
    "samsung" -> BatteryVendor.SAMSUNG
    "oppo", "realme" -> BatteryVendor.OPPO
    "vivo", "iqoo" -> BatteryVendor.VIVO
    else -> null
}

/** Vendors that kill background apps unless the app is excluded from battery optimization. */
fun killsBackgroundApps(manufacturer: String): Boolean = batteryVendor(manufacturer) != null

/**
 * Whether a phone running Android [sdk], made by [manufacturer], needs the exemption: every phone from Android 12 on,
 * which can't start the mesh again after it killed the process without it; before that, the vendors that kill
 * background apps.
 */
fun needsExemption(sdk: Int, manufacturer: String): Boolean = sdk >= Build.VERSION_CODES.S || killsBackgroundApps(manufacturer)

/**
 * The exemption from battery optimization, asked for from one place. Besides keeping vendors from killing the mesh,
 * it is what lets Android 12+ restart the service after the process was killed.
 */
object BatteryExemption {
    /** This phone's vendor, if it is one that needs the steps. */
    fun vendor(): BatteryVendor? = batteryVendor(Build.MANUFACTURER)

    fun granted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun hintNeeded(context: Context): Boolean = needsExemption(Build.VERSION.SDK_INT, Build.MANUFACTURER) && !granted(context)

    /**
     * Asks directly while the exemption is missing: safety apps are among the cases Android accepts for that.
     * Once granted there is nothing left to ask, so the system list opens instead.
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        if (!granted(context)) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
            if (runCatching { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
