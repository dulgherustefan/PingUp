package ro.safetyplease.app

import android.app.Application
import kotlinx.coroutines.launch
import ro.safetyplease.app.service.MeshService
import ro.safetyplease.ui.PhoneUi

/** What the screens need from an Android phone: the mesh service, Bluetooth and location permissions, GPS. */
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
}
