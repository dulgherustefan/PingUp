package ro.safetyplease.ui.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Asks for the position when a screen needs it, and says how it went: iOS's own question the first time, and the
 * precise position for once where the person allowed only an approximate one. A manager of its own, so the answers come
 * back here; [IosLocation] follows the position once it's allowed. On the main thread, as CoreLocation wants.
 */
@OptIn(ExperimentalForeignApi::class)
class LocationAsk : NSObject(), CLLocationManagerDelegateProtocol {
    enum class Answer { NOT_ASKED, REFUSED, APPROXIMATE, PRECISE }

    private val manager = CLLocationManager()
    private var waiting: ((Answer) -> Unit)? = null

    init {
        manager.delegate = this
    }

    fun answer(): Answer = when (manager.authorizationStatus) {
        kCLAuthorizationStatusNotDetermined -> Answer.NOT_ASKED
        kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways ->
            if (manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy) Answer.PRECISE else Answer.APPROXIMATE
        // denied, or restricted by the phone's owner
        else -> Answer.REFUSED
    }

    /** iOS's question; [then] gets the answer once the person gives it. */
    fun ask(then: (Answer) -> Unit) {
        waiting = then
        manager.requestWhenInUseAuthorization()
    }

    /** The precise position while the app is in use, for [purpose], a key of NSLocationTemporaryUsageDescriptionDictionary. */
    fun askPrecise(purpose: String, then: (Boolean) -> Unit) {
        manager.requestTemporaryFullAccuracyAuthorizationWithPurposeKey(purpose) { _ ->
            dispatch_async(dispatch_get_main_queue()) { then(answer() == Answer.PRECISE) }
        }
    }

    // iOS calls it once the manager is made, before anything was asked: only a real answer is passed on
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val answer = answer()
        if (answer == Answer.NOT_ASKED) return
        val then = waiting ?: return
        waiting = null
        then(answer)
    }
}

/** The purpose in Info.plist's NSLocationTemporaryUsageDescriptionDictionary, worded in InfoPlist.strings. */
const val PRECISE_LOCATION_PURPOSE = "PingUpPrecise"
