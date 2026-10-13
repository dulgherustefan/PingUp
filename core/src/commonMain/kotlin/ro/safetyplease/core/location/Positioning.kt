package ro.safetyplease.core.location

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.math.sin

/** "Sunt lângă Reperul [number]", said at [setAtMs] (wall clock): the position is the landmark's [point] for a while. */
data class LandmarkFix(val number: Int, val point: GeoPoint, val setAtMs: Long) {
    /** Past its time, or the wall clock was set back past when it was said, so its time is unknown. */
    fun expired(nowMs: Long): Boolean = nowMs - setAtMs !in 0 until KEPT_MS

    /** A good GNSS fix far from the landmark: the person has walked off, or picked the wrong number. */
    fun overruledBy(fix: GeoFix): Boolean =
        fix.accuracyM > 0f && fix.accuracyM <= GOOD_FIX_M && distanceMeters(point, GeoPoint(fix.lat, fix.lon)) > DISAGREE_M

    fun fix(nowMs: Long): GeoFix = GeoFix(point.lat, point.lon, RADIUS_M, nowMs)

    companion object {
        const val KEPT_MS = 10 * 60_000L
        const val RADIUS_M = 15f
        const val GOOD_FIX_M = 15f
        const val DISAGREE_M = 30
    }
}

/** One step from the phone's own detector: [lengthM] walked toward [yawDeg], a yaw whose zero is arbitrary. */
data class Step(val timeMs: Long, val lengthM: Double, val yawDeg: Double)

/** The position the app shows and sends, and how it was made ([PositionMethod]). */
data class Fused(val fix: GeoFix, val method: Int)

/**
 * The yaw of the game rotation vector turned into a bearing: it has no north (no magnetometer, which the speakers by
 * a stage throw off), so the offset is learnt from the GNSS course while the person walks fast enough to have one.
 */
class StepHeading {
    private var offsetDeg: Double? = null

    fun onCourse(courseDeg: Double, yawDeg: Double) {
        val measured = norm(courseDeg - yawDeg)
        val old = offsetDeg
        offsetDeg = if (old == null) measured else norm(old + LEARN * signed(measured - old))
    }

    fun bearing(yawDeg: Double): Double? = offsetDeg?.let { norm(yawDeg + it) }

    private fun norm(deg: Double) = ((deg % 360) + 360) % 360

    private fun signed(deg: Double) = norm(deg + 180) - 180

    private companion object {
        const val LEARN = 0.3
    }
}

/**
 * Everything that makes the position shown and sent: GNSS fixes through [DisplayFilter], the landmark the person says
 * they stand by ([LandmarkFix]), and steps that bridge a GNSS gap of at most [PDR_GAP_MS]. Fixes, steps and the
 * screens' calls come from different threads.
 */
class Positioning(private val clock: Clock) {
    private val lock = SynchronizedObject()
    private val filter = DisplayFilter()
    private val heading = StepHeading()
    private var lastGnss: GeoFix? = null
    private var lastShown: GeoFix? = null
    private var lastYaw: Pair<Long, Double>? = null
    private var walked = 0.0

    private val _fused = MutableStateFlow<Fused?>(null)
    private val _position = MutableStateFlow<GeoFix?>(null)
    private val _landmark = MutableStateFlow<LandmarkFix?>(null)
    private val _stepsWanted = MutableStateFlow(false)
    private var needs = Needs()

    private data class Needs(val setting: Boolean = false, val exactShare: Boolean = false, val reports: Int = 0, val navigating: Boolean = false)

    /** What the app shows and sends; [position] is the same fix without the method. */
    val fused: StateFlow<Fused?> = _fused
    val position: StateFlow<GeoFix?> = _position

    /** The landmark the person said they stand by, while it holds. */
    val landmark: StateFlow<LandmarkFix?> = _landmark

    /** The steps are worth counting now: the setting is on and a report, navigation or an exact share needs the position. */
    val stepsWanted: StateFlow<Boolean> = _stepsWanted

    fun onRaw(fix: GeoFix) = synchronized(lock) {
        val previous = lastGnss
        // no newer than the last: a repeat; far older: the wall clock was set back, and the filter starts over
        if (previous != null && fix.timeMs <= previous.timeMs && previous.timeMs - fix.timeMs < CLOCK_STEP_MS) return@synchronized
        lastGnss = fix
        walked = 0.0
        val yaw = lastYaw
        if (fix.courseDeg != null && yaw != null && fix.timeMs - yaw.first <= YAW_FRESH_MS) heading.onCourse(fix.courseDeg.toDouble(), yaw.second)
        val shown = filter.onFix(fix)
        lastShown = shown
        val mark = _landmark.value
        if (mark != null && (mark.overruledBy(fix) || mark.expired(clock.wallMs()))) _landmark.value = null
        publish(shown)
    }

    fun onStep(step: Step) = synchronized(lock) {
        lastYaw = step.timeMs to step.yawDeg
        val gnss = lastGnss ?: return@synchronized
        if (step.timeMs - gnss.timeMs > PDR_GAP_MS || step.timeMs - gnss.timeMs < PDR_AFTER_MS) return@synchronized
        val bearing = heading.bearing(step.yawDeg) ?: return@synchronized
        walked += step.lengthM
        val r = radians(bearing)
        val accuracy = gnss.accuracyM + (PDR_DRIFT * walked).toFloat()
        val shown = filter.onStep(step.lengthM * sin(r), step.lengthM * cos(r), step.timeMs, accuracy) ?: return@synchronized
        lastShown = shown
        publish(shown)
    }

    /** "Sunt lângă Reperul [number]", standing at [point]: the position for the next minutes, unless GNSS disagrees. */
    fun nearLandmark(number: Int, point: GeoPoint) = synchronized(lock) {
        _landmark.value = LandmarkFix(number, point, clock.wallMs())
        publish(lastShown)
    }

    fun clearLandmark() = synchronized(lock) {
        if (_landmark.value == null) return@synchronized
        _landmark.value = null
        publish(lastShown)
    }

    /** Ends a landmark past its time, and keeps one that holds fresh. */
    fun tick() = synchronized(lock) {
        val mark = _landmark.value ?: return@synchronized
        if (mark.expired(clock.wallMs())) _landmark.value = null
        publish(lastShown)
    }

    /** A report screen opens ([open]) or closes; calls nest. */
    fun reportOpen(open: Boolean) = need { it.copy(reports = (it.reports + if (open) 1 else -1).coerceAtLeast(0)) }

    /**
     * For the walking route (`route/`): it calls this while it guides the person, so the steps can bridge a GNSS gap
     * on the way. Nothing else calls it.
     */
    fun navigating(on: Boolean) = need { it.copy(navigating = on) }

    /** The steps setting, and whether an exact share runs now. */
    fun follow(setting: Boolean, exactShare: Boolean) = need { it.copy(setting = setting, exactShare = exactShare) }

    /** Feeds [raw] in and keeps [stepsWanted] and the landmark's time up to date, until cancelled. */
    suspend fun run(raw: Flow<GeoFix?>, setting: Flow<Boolean>, exactShare: Flow<Boolean>) = coroutineScope {
        launch { raw.collect { fix -> if (fix != null) onRaw(fix) } }
        launch { combine(setting, exactShare) { s, e -> s to e }.collect { (s, e) -> follow(s, e) } }
        while (true) {
            delay(TICK_MS)
            tick()
        }
    }

    private fun need(change: (Needs) -> Needs) = synchronized(lock) {
        needs = change(needs)
        _stepsWanted.value = needs.setting && (needs.exactShare || needs.reports > 0 || needs.navigating)
    }

    /** The landmark while it holds, stamped now so it stays fresh; else [shown], the last fix through the filter. */
    private fun publish(shown: GeoFix?) {
        val mark = _landmark.value
        val next = when {
            mark != null -> Fused(mark.fix(clock.wallMs()), PositionMethod.MANUAL_PIN)
            shown != null -> Fused(shown, PositionMethod.GNSS)
            else -> null
        }
        _fused.value = next
        _position.value = next?.fix
    }

    companion object {
        /** Steps move the point only this long after the last fix: past it, the drift is larger than what it adds. */
        const val PDR_GAP_MS = 30_000L

        /** While fixes come every second or two, they lead and the steps wait. */
        const val PDR_AFTER_MS = 2_500L

        /** How much the radius grows per metre walked on steps alone. */
        const val PDR_DRIFT = 0.2
        const val TICK_MS = 15_000L
        private const val YAW_FRESH_MS = 3_000L

        /** A fix this much older than the last one means the wall clock was set back. */
        const val CLOCK_STEP_MS = 60_000L
    }
}
