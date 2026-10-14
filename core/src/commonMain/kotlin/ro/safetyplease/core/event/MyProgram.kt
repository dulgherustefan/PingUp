package ro.safetyplease.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The sets a person picked for "Planul meu", by [Program.key], those with "Amintește-mi" on, how many minutes ahead it
 * rings and the reminders already rung. Kept for one [event]; another event's picks don't carry over.
 */
@Serializable
data class MyProgram(
    val event: String = "",
    val picked: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    val leadMin: Int = Program.DEFAULT_LEAD_MIN,
    val fired: List<String> = emptyList(),
)

/**
 * "Planul meu" and its reminders, rung by the running session through the app's notifications, with the walk from
 * where the phone is now. On Android the session lives in the mesh's foreground service, so the intent is that a
 * reminder rings while the mesh runs, screen off included (a coroutine, no exact alarm; how Doze treats it is not
 * verified on devices); once the app is closed from the notification or killed, nothing rings until it runs again, and
 * a set that started meanwhile is skipped. The session also schedules each reminder with the phone ahead of time
 * ([ro.safetyplease.core.session.AlertSchedule.sets]), with the walk known then: an iPhone, which runs the session only
 * in front or woken by Bluetooth, rings it at its time all the same.
 */
class ProgramReminders(
    scope: CoroutineScope,
    private val store: JsonStore<MyProgram>,
    private val eventId: String,
    val schedule: List<ScheduleItem>,
    private val venue: Venue,
    private val clock: Clock,
    private val settings: () -> Settings,
    private val fix: () -> GeoFix?,
    private val walk: () -> WalkDistance,
    /** Where the walks to the stages are planned: the session's Dispatchers.Default keeps A* off the mesh thread. */
    private val planner: CoroutineContext = EmptyCoroutineContext,
    private val ring: (ScheduleItem, walkMin: Int) -> Unit,
) {
    val state: StateFlow<MyProgram> = store.state.map(::current).stateIn(scope, SharingStarted.Eagerly, current(store.value))

    init {
        if (schedule.isNotEmpty()) {
            scope.launch {
                while (true) {
                    ringDue()
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun current(saved: MyProgram): MyProgram = if (saved.event == eventId) saved else MyProgram(eventId)

    private fun change(transform: (MyProgram) -> MyProgram) = store.update { transform(current(it)) }

    fun togglePicked(item: ScheduleItem) = change { p ->
        val key = Program.key(item)
        if (key in p.picked) p.copy(picked = p.picked - key, reminders = p.reminders - key) else p.copy(picked = p.picked + key)
    }

    /** A reminder puts the set in "Planul meu" too. */
    fun toggleReminder(item: ScheduleItem) = change { p ->
        val key = Program.key(item)
        if (key in p.reminders) p.copy(reminders = p.reminders - key)
        else p.copy(reminders = p.reminders + key, picked = if (key in p.picked) p.picked else p.picked + key, fired = p.fired - key)
    }

    /** A new lead keeps what already rang: a set that rang doesn't ring again; toggling the bell re-arms one. */
    fun setLead(minutes: Int) = change { it.copy(leadMin = minutes) }

    fun picked(p: MyProgram): List<ScheduleItem> = schedule.filter { Program.key(it) in p.picked }

    /** Where the walk starts: the simulated or a fresh position, else the zone picked by hand, else the last fix. */
    fun origin(): GeoPoint? {
        val s = settings()
        val lat = s.simLat
        val lon = s.simLon
        if (lat != null && lon != null) return GeoPoint(lat, lon)
        val last = fix()
        if (last != null && last.isFresh(clock.wallMs())) return GeoPoint(last.lat, last.lon)
        return venue.zone(s.manualZone)?.center ?: last?.let { GeoPoint(it.lat, it.lon) }
    }

    /** Metres of walking from [from] to the stage of [item]. */
    fun walkMeters(from: GeoPoint?, item: ScheduleItem): Int? {
        val stage = venue.zone(item.stage)?.center ?: return null
        return from?.let { walk().meters(it, stage) }
    }

    private suspend fun ringDue() {
        val program = state.value
        if (program.reminders.isEmpty()) return
        // the walks are planned away from the caller's thread; the reminders ring back on it
        val due = withContext(planner) {
            val from = origin()
            Program.due(program, schedule, clock.wallMs() / 1000) { walkMeters(from, it) }.map { it to Program.walkMinutes(walkMeters(from, it)) }
        }
        if (due.isEmpty()) return
        due.forEach { (item, walkMin) -> ring(item, walkMin) }
        change { p -> p.copy(fired = p.fired + due.map { Program.key(it.first) }) }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
