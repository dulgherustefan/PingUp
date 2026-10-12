package ro.safetyplease.app

import android.app.Application
import ro.safetyplease.app.platform.ble.BleRadio
import ro.safetyplease.app.platform.location.LocationSource
import ro.safetyplease.app.service.Notifier
import ro.safetyplease.core.session.PingUpSession

/**
 * Lives as long as the process: the shared session (identity, stores, the mesh engine, chat and incident managers), with
 * the Android parts the service and the screens use by their own types.
 */
class AppContainer private constructor(private val android: AndroidPlatform) : PingUpSession(android) {
    constructor(app: Application) : this(AndroidPlatform(app))

    val notifier: Notifier get() = android.notifier
    val location: LocationSource get() = android.locationSource
    val bleRadio: BleRadio get() = android.bleRadio

    companion object {
        const val TAG = "Mesh"
    }
}
