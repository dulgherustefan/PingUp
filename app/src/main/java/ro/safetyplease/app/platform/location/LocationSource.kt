package ro.safetyplease.app.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.safetyplease.core.location.DutyCycle
import ro.safetyplease.core.location.FixGate
import ro.safetyplease.core.location.FixMethod
import ro.safetyplease.core.location.GnssStats
import ro.safetyplease.core.location.Heading
import ro.safetyplease.core.location.LocationFix
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint

/**
 * Position from GPS_PROVIDER, without Play Services or network: offline the chipset decides the accuracy, not the
 * provider. Fixes heard passively from other apps come for free. How often the chip is asked follows [DutyCycle]; every
 * fix passes [FixGate] before anyone sees it. Runs while a screen is visible ([start]/[stop]) or a share session is on.
 */
class LocationSource(private val context: Context) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gate = FixGate(emptyList())
    private var batteryPct: () -> Int = { 100 }
    private val motion = Motion(context)
    val diagnostics = GnssDiagnostics(manager)
    private val _fix = MutableStateFlow<GeoFix?>(null)
    val fix: StateFlow<GeoFix?> = _fix
    private val _located = MutableStateFlow<LocationFix?>(null)

    /** The last accepted fix with its heading, speed and how it was measured; [fix] is the same, for older callers. */
    val located: StateFlow<LocationFix?> = _located
    private val _searching = MutableStateFlow(false)

    /**
     * Running while the newest known fix (ours or the one Android kept) is missing or more than an hour old, checked every
     * few seconds: the satellite data may be stale, so the next fix can take up to 30 s. False whenever the source is off.
     */
    val searching: StateFlow<Boolean> = _searching
    private val _shareMaxMs = MutableStateFlow<Long?>(null)

    /** The slowest pace an active share session allows; null with no session. */
    val shareMaxMs: StateFlow<Long?> = _shareMaxMs

    private var visible = false
    private var urgent = 0
    private var job: Job? = null
    private var cycle: DutyCycle? = null
    private var requested: DutyCycle.Plan? = null
    private var lastAskMs = 0L
    private var lastFixMs: Long? = null
    private var lastSampleMs = 0L
    private var oneShot: CancellationSignal? = null

    private val listener = LocationListener { onLocation(it, FixMethod.GNSS) }
    private val passive = LocationListener { location ->
        // network and fused fixes are coarser than the chip; with our own request open the same GPS fix arrives here too
        if (location.provider == LocationManager.GPS_PROVIDER && requested == null) onLocation(location, FixMethod.PASSIVE)
    }

    /**
     * Where the phone faces now, for an arrow on screen: the GNSS course of the last fix while walking, else the compass
     * read at this moment ([Heading.pick]); null when neither can be trusted.
     */
    fun heading(): Float? {
        val last = _located.value
        return Heading.pick(last?.headingDeg?.takeIf { (last.speedMps ?: 0f) > Heading.COURSE_MIN_SPEED_MPS }, last?.speedMps, motion.azimuth, motion.compassAccuracy)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The phone's location setting: with it off there is no fix, whatever the permission. */
    fun isGpsOn(): Boolean = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)

    /** The event's water, where no fix can be, and the battery level for the diagnostic log; the session knows them once it runs. */
    fun configure(water: List<List<GeoPoint>>, batteryPct: () -> Int) {
        gate = FixGate(water)
        this.batteryPct = batteryPct
    }

    /** A screen that shows or uses the position is visible. */
    fun start() {
        visible = true
        refresh()
    }

    fun stop() {
        visible = false
        refresh()
    }

    /** A report or SOS is being written: 1 Hz until [on] goes false again. Calls nest. */
    fun urgent(on: Boolean) {
        urgent = (urgent + if (on) 1 else -1).coerceAtLeast(0)
        refresh()
    }

    /** Starts or ends a share session that needs a fix at least every [maxIntervalMs]; it keeps running off-screen. */
    fun share(maxIntervalMs: Long?) {
        _shareMaxMs.value = maxIntervalMs
        refresh()
    }

    private fun refresh() {
        val run = (visible || _shareMaxMs.value != null) && hasPermission() && isGpsOn()
        when {
            run && job == null -> begin()
            !run && job != null -> end()
            run -> plan()
        }
        motion.compass(visible && job != null)
    }

    @SuppressLint("MissingPermission")
    private fun begin() {
        val manager = manager ?: return
        val now = SystemClock.elapsedRealtime()
        val sensors = motion.start(
            onMotion = {
                cycle?.onMotion(SystemClock.elapsedRealtime())
                plan()
            },
            onStill = { cycle?.onStill(SystemClock.elapsedRealtime()) },
        )
        cycle = DutyCycle(now, sensors.steps, sensors.canWake)
        runCatching {
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, passive, Looper.getMainLooper())
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last ->
                val at = last.elapsedRealtimeNanos / 1_000_000
                lastFixMs = at
                if (now - at < GeoFix.FRESH_MS) onLocation(last, FixMethod.CACHED)
            }
        }
        diagnostics.start(executor)
        job = scope.launch {
            while (true) {
                plan()
                val mono = SystemClock.elapsedRealtime()
                _searching.value = lastFixMs.let { it == null || mono - it > SEARCHING_AFTER_MS }
                if (mono - lastSampleMs >= GnssStats.SAMPLE_MS) {
                    if (lastSampleMs != 0L) diagnostics.sample(System.currentTimeMillis(), batteryPct())
                    lastSampleMs = mono
                }
                delay(TICK_MS)
            }
        }
    }

    private fun end() {
        job?.cancel()
        job = null
        cycle = null
        requested = null
        lastSampleMs = 0L
        _searching.value = false
        oneShot?.cancel()
        oneShot = null
        motion.stop()
        diagnostics.stop()
        runCatching {
            manager?.removeUpdates(listener)
            manager?.removeUpdates(passive)
        }
    }

    @SuppressLint("MissingPermission")
    private fun plan() {
        val manager = manager ?: return
        val cycle = cycle ?: return
        val now = SystemClock.elapsedRealtime()
        val plan = cycle.plan(now, urgent > 0, _shareMaxMs.value)
        runCatching {
            if (plan.continuous) {
                if (plan != requested) {
                    request(manager, plan)
                    requested = plan
                }
            } else {
                if (requested != null) {
                    manager.removeUpdates(listener)
                    requested = null
                }
                if (oneShot == null && now - lastAskMs >= plan.intervalMs) {
                    lastAskMs = now
                    val signal = CancellationSignal()
                    oneShot = signal
                    LocationManagerCompat.getCurrentLocation(manager, LocationManager.GPS_PROVIDER, signal, executor) { location ->
                        if (oneShot === signal) oneShot = null
                        // getCurrentLocation may hand back a cached fix; only a recent one counts as "now"
                        if (location != null && ageMs(location) <= ONE_SHOT_MAX_AGE_MS) onLocation(location, FixMethod.GNSS)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun request(manager: LocationManager, plan: DutyCycle.Plan) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(plan.intervalMs)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(plan.minIntervalMs)
                .build()
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, executor, listener)
        } else {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, plan.intervalMs, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun onLocation(location: Location, method: FixMethod) {
        val age = ageMs(location)
        if (age > GeoFix.FRESH_MS) return
        val fix = LocationFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
            headingDeg = Heading.pick(
                location.bearing.takeIf { location.hasBearing() }, location.speed.takeIf { location.hasSpeed() },
                motion.azimuth, motion.compassAccuracy,
            ),
            speedMps = location.speed.takeIf { location.hasSpeed() },
            method = method,
            timeMs = System.currentTimeMillis() - age,
            elapsedMs = location.elapsedRealtimeNanos / 1_000_000,
            l5 = diagnostics.l5InFix,
        )
        if (gate.check(fix) != FixGate.Verdict.ACCEPTED) return
        lastFixMs = fix.elapsedMs
        _searching.value = false
        cycle?.onFix(SystemClock.elapsedRealtime(), fix)
        if (method != FixMethod.CACHED) diagnostics.onFix(fix)
        _located.value = fix
        val course = fix.headingDeg?.takeIf { (fix.speedMps ?: 0f) > Heading.COURSE_MIN_SPEED_MPS }
        _fix.value = GeoFix(fix.lat, fix.lon, fix.accuracyM, fix.timeMs, course)
    }

    private fun ageMs(location: Location) = SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000

    private companion object {
        const val TICK_MS = 5_000L
        const val ONE_SHOT_MAX_AGE_MS = 10_000L
        const val SEARCHING_AFTER_MS = 60 * 60_000L
    }
}
