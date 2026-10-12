package ro.safetyplease.ui.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.ui.text.wallNowMs

/**
 * The phone's position while the app is in front, precise or not at all: a report sends a team to it, and the zone
 * is told from it. Made and used on the main thread, as CoreLocation wants.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocation {
    private val manager = CLLocationManager()
    private val listener = Listener()
    private val position = MutableStateFlow<GeoFix?>(null)
    private var listening = false

    val fix: StateFlow<GeoFix?> = position

    init {
        manager.delegate = listener
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5.0
    }

    /** Allowed while in use or always, and with the precise position: an approximate one is kilometres off. */
    fun allowed(): Boolean {
        val status = manager.authorizationStatus
        val granted = status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
        return granted && manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
    }

    /** Location services on for the whole phone. */
    fun on(): Boolean = CLLocationManager.locationServicesEnabled()

    /** iOS asks the person the first time; afterwards only Settings changes the answer. */
    fun start() {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            manager.requestWhenInUseAuthorization()
            return
        }
        if (!allowed() || listening) return
        listening = true
        manager.startUpdatingLocation()
    }

    fun stop() {
        listening = false
        manager.stopUpdatingLocation()
    }

    private inner class Listener : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            // a negative accuracy means iOS has no position to give
            if (location.horizontalAccuracy < 0) return
            location.coordinate.useContents { position.value = GeoFix(latitude, longitude, location.horizontalAccuracy.toFloat(), wallNowMs()) }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = Unit

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (allowed()) start() else stop()
        }
    }
}
