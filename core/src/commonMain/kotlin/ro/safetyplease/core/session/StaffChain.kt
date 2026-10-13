package ro.safetyplease.core.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okio.Path
import ro.safetyplease.core.control.DensityBoard
import ro.safetyplease.core.control.DensityLog
import ro.safetyplease.core.control.DensityVerdict
import ro.safetyplease.core.control.HandOverBook
import ro.safetyplease.core.control.HandOverNotes
import ro.safetyplease.core.control.StaffDensity
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.AlertReason
import ro.safetyplease.core.incidents.ManDownDetector
import ro.safetyplease.core.incidents.ManDownWatch
import ro.safetyplease.core.incidents.MotionSample
import ro.safetyplease.core.incidents.PriorityRules
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.incidents.ResponderTarget
import ro.safetyplease.core.incidents.Responders
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.incidents.StaffCode
import ro.safetyplease.core.incidents.StaffCodes
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.LocationFields
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.StaffFields
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.distanceMeters

/** This phone's own choices for the staff chain. Man-down is on for staff unless they turn it off. */
@Serializable
data class StaffChainPrefs(val manDown: Boolean = true)

/**
 * The staff and medical chain on one phone (PL6): the priority table, the zones' crowd levels, the hand-over notes,
 * the responders' targets and man-down. Everything here stays on the phone but the reports it sends through
 * [report], on the existing sealed path.
 */
class StaffChain(
    private val scope: CoroutineScope,
    private val ioScope: CoroutineScope,
    private val platform: AppPlatform,
    private val notifications: Notifications,
    private val clock: Clock,
    private val settings: JsonStore<Settings>,
    incidents: StateFlow<IncidentData>,
    private val venue: Venue,
    private val walk: () -> WalkDistance,
    private val report: (draft: ReportDraft, bypassRateLimit: Boolean) -> Boolean,
    densityFile: Path,
    handOverFile: Path,
    prefsFile: Path,
    /** Where the hand-over PDFs are written, so they go with the notes. */
    exportDir: Path,
    /** The app is on screen: its own "Ești OK?" can be seen without a notification. */
    private val foreground: StateFlow<Boolean>,
) {
    val prefs = JsonStore(prefsFile, StaffChainPrefs.serializer(), StaffChainPrefs(), ioScope)
    val priority: PriorityRules = PriorityRules.parse(runCatching { platform.asset(PRIORITY_ASSET) }.getOrNull())
    val density = DensityBoard(JsonStore(densityFile, DensityLog.serializer(), DensityLog(), ioScope))
    /** Health data kept 72 hours at most: no backup file keeps it longer. */
    val handOver = HandOverBook(JsonStore(handOverFile, HandOverNotes.serializer(), HandOverNotes(), ioScope, keepsBackup = false), exportDir)

    /** Where this phone is, moving only by [MOVE_M] at a time, so a walk doesn't re-route every alert. */
    val position: StateFlow<GeoPoint?> = platform.location.scan(null as GeoPoint?) { last, fix ->
        val here = fix?.let { GeoPoint(it.lat, it.lon) }
        if (here == null || last == null || distanceMeters(last, here) >= MOVE_M) here else last
    }.distinctUntilChanged().stateIn(ioScope, SharingStarted.Eagerly, null)

    private val near = MutableStateFlow(emptySet<String>())
    private val dismissed = MutableStateFlow(emptySet<String>())

    /** The responders' incidents in range of this phone, for the alert planner ([ro.safetyplease.core.incidents.StaffProfile.near]). */
    val nearIncidents: StateFlow<Set<String>> = near

    /** Responders' incidents shown to this phone, until it dismisses them; only [responderTargets] touches it. */
    private val held = LinkedHashSet<String>()

    /**
     * The unconscious or not-breathing incidents this phone should run to, newest first, with the defibrillator on the
     * way. One stays after a team takes the incident, until the responder dismisses it ([dismissResponder]).
     */
    val responderTargets: StateFlow<List<ResponderTarget>> = combine(incidents, settings.state, position, dismissed) { data, s, here, gone ->
        if (!s.role.hasStaffKeys) {
            near.value = emptySet()
            held.clear()
            return@combine emptyList()
        }
        val me = Routing.profile(s).copy(position = here, near = near.value)
        val inRange = data.staff.filter { Responders.alerts(me, it) }.mapTo(HashSet()) { it.incidentId }
        near.value = inRange
        data.staff.filter { it.incidentId in inRange && it.incidentId !in gone && !Routing.handled(it) }.forEach { held += it.incidentId }
        val known = data.staff.mapTo(HashSet()) { it.incidentId }
        held.retainAll { it in known && it !in gone }
        data.staff.filter { it.incidentId in held }
            .sortedByDescending { it.receivedAt }
            .mapNotNull { Responders.target(it, here, venue, walk()) }
    }.stateIn(ioScope, SharingStarted.Eagerly, emptyList())

    fun dismissResponder(incidentId: String) = dismissed.update { it + incidentId }

    /** Man-down runs on this phone: Android, not the iPhone. */
    val manDownAvailable: Boolean get() = platform.watchesMotion

    /** Until when (wall time) "Ești OK?" stands; null while nothing is asked. */
    val manDownAsk = MutableStateFlow<Long?>(null)

    private val lock = SynchronizedObject()
    private val detector = ManDownDetector()
    private val watch = ManDownWatch()
    private var askJob: Job? = null

    fun start() {
        ioScope.launch {
            while (isActive) {
                handOver.prune(clock.wallMs())
                delay(PRUNE_EVERY_MS)
            }
        }
        scope.launch {
            combine(settings.state, prefs.state) { s, p -> s.role == Role.STAFF && p.manDown }.distinctUntilChanged().collect { on ->
                synchronized(lock) { detector.reset() }
                platform.watchMotion(if (on) ::onMotion else null)
                if (!on) {
                    watch.reset()
                    endQuestion()
                }
            }
        }
    }

    /** Writes the three stores now, with the session's own. */
    fun flush() {
        prefs.flush()
        density.flush()
        handOver.flush()
    }

    /** For IncidentManager: a routine crowd level is kept here instead of opening an incident; levels 4 and 5 do both. */
    fun divert(incident: StaffIncident): Boolean {
        if (incident.density > 0) density.offer(StaffDensity.reading(incident))
        return StaffDensity.isRoutine(incident)
    }

    /** The crowd level of [zone], as a staff report from here; anything but SEND means nothing went out. */
    fun sendDensity(level: Int, zone: String, lat: Double?, lon: Double?, location: LocationFields?): DensityVerdict {
        val now = clock.wallMs()
        val verdict = StaffDensity.check(density.value, zone, level, now, venue.zones.size)
        if (verdict != DensityVerdict.SEND) return verdict
        val draft = ReportDraft(
            IncidentCategory.CROWD, StaffDensity.severity(level), zone, lat, lon, "", null,
            staff = StaffFields(), location = location, extras = StaffDensity.extras(level),
        )
        if (!report(draft, false)) return DensityVerdict.RATE_LIMITED
        density.sent(now)
        return DensityVerdict.SEND
    }

    /** The alert as the planner decided it; a responder's names the defibrillator on the way. */
    fun post(post: AlertPost) {
        if (post.route.reason == AlertReason.RESPONDER) {
            notifications.responderAlert(post, Responders.target(post.incident, position.value, venue, walk()))
        } else {
            notifications.staffAlert(post)
        }
    }

    fun setManDown(on: Boolean) = prefs.update { it.copy(manDown = on) }

    /** "Sunt OK". */
    fun answerManDown() {
        scope.launch { if (watch.answered(clock.monoMs())) endQuestion() }
    }

    private fun onMotion(sample: MotionSample) {
        val fell = synchronized(lock) { detector.add(sample) }
        if (fell) scope.launch { ask() }
    }

    private fun ask() {
        if (!watch.suspected(clock.monoMs())) return
        val until = clock.wallMs() + ManDownWatch.ASK_MS
        // a question nobody can see must not end in a report: without the notification only the open app asks
        if (!notifications.manDownCheck(until) && !foreground.value) {
            watch.reset()
            return
        }
        manDownAsk.value = until
        askJob = scope.launch {
            delay(ManDownWatch.ASK_MS)
            if (watch.silent(clock.monoMs())) {
                sendManDown()
                endQuestion()
            }
        }
    }

    private fun endQuestion() {
        askJob?.cancel()
        askJob = null
        manDownAsk.value = null
        notifications.cancelManDownCheck()
    }

    /** "Staff în pericol" with the man-down entry and the last position this phone had, however old. */
    private fun sendManDown() {
        val fix = platform.location.value
        val s = settings.value
        val here = fix?.let { GeoPoint(it.lat, it.lon) }
        val zone = here?.let { venue.zoneAt(it.lat, it.lon)?.id } ?: s.staffZone.ifBlank { s.manualZone }
        val spec = StaffCodes.report(StaffCode.STAFF_IN_DANGER) ?: return
        val location = fix?.let { LocationFields(LocationFields.accuracyM(it.accuracyM.toDouble()), venue.nearestLandmark(GeoPoint(it.lat, it.lon))?.number ?: 0) }
        val draft = ReportDraft(
            spec.category, spec.severity, zone, fix?.lat, fix?.lon, "", null,
            staff = StaffFields(0, spec.dangerContinues), location = location, extras = ReportExtras(manDown = true),
        )
        report(draft, true)
    }

    companion object {
        const val PRIORITY_ASSET = "priority_rules.json"
        const val MOVE_M = 25
        const val PRUNE_EVERY_MS = 60_000L
    }
}
