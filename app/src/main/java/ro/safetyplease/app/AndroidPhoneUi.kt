package ro.safetyplease.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.launch
import okio.Path
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.platform.power.BatteryExemption
import ro.safetyplease.app.platform.share.ShareSheet
import ro.safetyplease.app.platform.torch.CameraTorch
import ro.safetyplease.app.service.MeshService
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.common.Torch

/**
 * What the screens need from an Android phone: the mesh service, permissions, GPS, the system's own screens. It lives
 * as long as the screens' state, longer than any activity, so it opens other screens as a new task.
 */
class AndroidPhoneUi(private val app: Application, private val container: AppContainer) : PhoneUi {
    /**
     * Start only with permissions granted; otherwise Android 14+ won't let the service go foreground.
     * A network the user turned off stays off.
     */
    override fun startMesh() {
        if (!bluetoothAllowed() || !container.settings.value.meshEnabled) return
        if (MeshService.running) container.meshScope.launch { container.bleRadio.retry() } else MeshService.start(app)
        container.location.start()
    }

    override fun stopMesh() = MeshService.stop(app)

    override fun bluetoothAllowed(): Boolean = container.bleRadio.hasPermissions()

    override fun locationAllowed(): Boolean = container.location.hasPermission()

    override fun locationOn(): Boolean = container.location.isGpsOn()

    override fun startLocation() = container.location.start()

    override fun dial112() = open(Emergency.dialIntent())

    override fun openLocationSettings() = open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    override fun openAppSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))

    override fun toast(text: String, long: Boolean) {
        Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun copyText(text: String) {
        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("mesaj", text))
    }

    override fun shareFiles(files: List<Path>, onShared: (() -> Unit)?) = open(ShareSheet.intent(app, files.map(Path::toFile), onShared))

    override fun torch(): Torch = CameraTorch(app)

    override fun batteryHintNeeded(): Boolean = BatteryExemption.hintNeeded(app)

    override val batterySteps get() = BatteryExemption.vendor()?.steps

    override fun requestBatteryExemption() = BatteryExemption.request(app)

    override val appVersion: String get() = BuildConfig.VERSION_NAME

    override val appName: String get() = app.getString(R.string.app_name)

    override val demoAvailable get() = Demo.AVAILABLE

    // a tablet may have no dialer, a phone may lack a settings screen
    private fun open(intent: Intent) {
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
