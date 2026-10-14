package ro.safetyplease.ui.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.darwin.NSObject
import ro.safetyplease.core.location.CompassAccuracy
import ro.safetyplease.core.location.FixGate
import ro.safetyplease.core.location.FixIntake
import ro.safetyplease.core.location.FixMethod
import ro.safetyplease.core.location.Heading
import ro.safetyplease.core.location.LocationFix
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint

/**
 * The phone's position while the app is in front, precise or not at all: a report sends a team to it, and the zone is
 * told from it. Every fix iOS hands over is stamped with its own time, dropped when old ([FixIntake], at most one a
 * second) and goes through the same [FixGate] as on Android, with the venue's water. Updates stop in the background
 * and start again, fresh, in front. The compass runs only while a guiding screen keeps asking for [heading]. Made and
 * used on the main thread, as CoreLocation wants.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocation {
    private val manager = CLLocationManager()
    private val listener = Listener()
    private val position = MutableStateFlow<GeoFix?>(null)
    private val scope = MainScope()
    private val intake = FixIntake()
    private var gate = FixGate()
    private var gateWater: List<List<GeoPoint>> = emptyList()
    private var wanted = false
    private var running = false
    private var restartedMs: Long? = null
    private var askedPrecise = false
    private var last: LocationFix? = null
    private var compassDeg: Float? = null
    private var compassAccuracy = CompassAccuracy.UNRELIABLE
    private var compassUntilMs = 0L
    private var compass: Job? = null

    val fix: StateFlow<GeoFix?> = position

    /** The venue's water, from the session the app runs now: a fix there is kept, and the gate says so. */
    var water: () -> List<List<GeoPoint>> = { emptyList() }

    init {
        manager.delegate = listener
        manager.desiredAccuracy = kCLLocationAccuracyBest
        // every fix, paced to one a second here: behind a distance filter a person standing still gets none and goes stale
        manager.distanceFilter = kCLDistanceFilterNone
        // a pause would end the updates until the app starts them again. activityType stays at its default, which hands
        // over the fixes as measured: fitness would pull them onto the paths of the map, and at a festival people
        // stand in fields and on beaches
        manager.pausesLocationUpdatesAutomatically = false
        manager.headingFilter = HEADING_FILTER_DEG
        // an app allowed "while in use" gets no updates in the background, and a start made there (iOS woke the app for
        // Bluetooth) would wait for nothing: they stop there and start again on the way back
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            halt()
            stopCompass()
            // the temporary precise position ends with the app's use; it may be asked again next time
            askedPrecise = false
        }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            if (wanted && !running && allowed()) run()
        }
    }

    /** Allowed while in use or always, and with the precise position: an approximate one is kilometres off. */
    fun allowed(): Boolean = granted() && manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy

    /** Location services on for the whole phone. */
    fun on(): Boolean = CLLocationManager.locationServicesEnabled()

    /**
     * iOS asks the person the first time; afterwards only Settings changes the answer. [askPrecise]: a screen that needs
     * the exact position (a report, the 112 card) asks once while in front for it, where only an approximate one is allowed.
     */
    fun start(askPrecise: Boolean = false) {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            manager.requestWhenInUseAuthorization()
            return
        }
        if (askPrecise && granted() && !allowed() && !askedPrecise) {
            askedPrecise = true
            manager.requestTemporaryFullAccuracyAuthorizationWithPurposeKey(PRECISE_PURPOSE)
        }
        if (!allowed()) return
        wanted = true
        if (!running && inFront()) run()
    }

    fun stop() {
        wanted = false
        halt()
    }

    /**
     * Where the phone faces, clockwise from north: the course while walking fast enough, else the compass when it can be
     * trusted; null otherwise. Each call keeps the compass on a few seconds more: the guiding screens ask every second.
     */
    fun heading(): Float? {
        watchCompass()
        val moving = last?.takeIf { IosClock.monoMs() - it.elapsedMs <= COURSE_FRESH_MS }
        return Heading.pick(moving?.headingDeg, moving?.speedMps, compassDeg, compassAccuracy)
    }

    private fun granted(): Boolean =
        manager.authorizationStatus.let { it == kCLAuthorizationStatusAuthorizedWhenInUse || it == kCLAuthorizationStatusAuthorizedAlways }

    private fun inFront(): Boolean = UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground

    /** A new run: iOS hands over a first fix again, which may be one it kept from long ago. */
    private fun run() {
        running = true
        intake.restart()
        gate.reset()
        manager.startUpdatingLocation()
    }

    private fun halt() {
        running = false
        manager.stopUpdatingLocation()
    }

    private fun watchCompass() {
        compassUntilMs = IosClock.monoMs() + COMPASS_LEASE_MS
        if (compass != null || !CLLocationManager.headingAvailable()) return
        manager.startUpdatingHeading()
        compass = scope.launch {
            while (IosClock.monoMs() < compassUntilMs) delay(COMPASS_LEASE_MS)
            stopCompass()
        }
    }

    private fun stopCompass() {
        compass?.cancel()
        compass = null
        manager.stopUpdatingHeading()
        compassDeg = null
        compassAccuracy = CompassAccuracy.UNRELIABLE
    }

    private fun onLocation(location: CLLocation) {
        // a negative accuracy means iOS has no position to give
        if (location.horizontalAccuracy < 0) return
        val ageMs = ((NSDate().timeIntervalSinceReferenceDate - location.timestamp.timeIntervalSinceReferenceDate) * 1000).toLong()
        val elapsedMs = IosClock.monoMs() - ageMs
        if (!intake.take(ageMs, elapsedMs)) return
        val speed = location.speed.takeIf { it >= 0 }?.toFloat()
        val course = Heading.course(location.course.takeIf { it >= 0 }?.toFloat(), location.courseAccuracy.takeIf { it >= 0 }?.toFloat(), speed)
        val fix = location.coordinate.useContents {
            LocationFix(
                lat = latitude,
                lon = longitude,
                accuracyM = location.horizontalAccuracy.toFloat(),
                headingDeg = course,
                speedMps = speed,
                method = FixMethod.GNSS,
                timeMs = IosClock.wallMs() - ageMs,
                elapsedMs = elapsedMs,
                l5 = false,
                speedAccuracyMps = location.speedAccuracy.takeIf { it >= 0 }?.toFloat(),
            )
        }
        val water = water()
        if (water !== gateWater) {
            gateWater = water
            gate = FixGate(water)
        }
        if (!gate.check(fix).accepted) return
        last = fix
        position.value = GeoFix(fix.lat, fix.lon, fix.accuracyM, fix.timeMs, fix.headingDeg)
    }

    private inner class Listener : NSObject(), CLLocationManagerDelegateProtocol {
        // oldest first: the intake keeps one a second, the gate sees them in order
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            for (location in didUpdateLocations) (location as? CLLocation)?.let { onLocation(it) }
        }

        override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
            compassDeg = (if (didUpdateHeading.trueHeading >= 0) didUpdateHeading.trueHeading else didUpdateHeading.magneticHeading).toFloat()
            compassAccuracy = CompassAccuracy.ofErrorDeg(didUpdateHeading.headingAccuracy)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            when (didFailWithError.code) {
                // no position yet: iOS keeps trying by itself
                ERROR_LOCATION_UNKNOWN -> Unit
                ERROR_DENIED -> halt()
                ERROR_HEADING -> compassAccuracy = CompassAccuracy.UNRELIABLE
                // anything else may have ended the updates: start them again, at most every few seconds
                else -> {
                    val now = IosClock.monoMs()
                    if (running && restartedMs.let { it == null || now - it >= RESTART_GAP_MS }) {
                        restartedMs = now
                        halt()
                        run()
                    }
                }
            }
        }

        override fun locationManagerDidPauseLocationUpdates(manager: CLLocationManager) {
            if (running) manager.startUpdatingLocation()
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (allowed()) start() else halt()
        }
    }

    private companion object {
        /** The key of the purpose text in Info.plist, NSLocationTemporaryUsageDescriptionDictionary. */
        const val PRECISE_PURPOSE = "PreciseLocation"
        const val HEADING_FILTER_DEG = 5.0
        const val COMPASS_LEASE_MS = 3_000L
        const val COURSE_FRESH_MS = 5_000L
        const val RESTART_GAP_MS = 10_000L

        // CLError codes
        const val ERROR_LOCATION_UNKNOWN = 0L
        const val ERROR_DENIED = 1L
        const val ERROR_HEADING = 3L
    }
}
