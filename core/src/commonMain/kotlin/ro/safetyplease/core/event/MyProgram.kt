package ro.safetyplease.core.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance

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
 * where the phone is now. Nothing is scheduled with the system: on Android the session lives in the mesh's foreground
 * service, so a reminder rings while the mesh runs, screen off and in Doze too (a coroutine, no exact alarm); once the
 * app is closed from the notification or killed, nothing rings until it runs again, and a set that started meanwhile
 * is skipped. On an iPhone the session runs while the app is in front or woken by Bluetooth in the background, so a
 * reminder rings then, or at the next such moment if the set hasn't started yet.
 */
class ProgramReminders(
    scope: CoroutineScope,
    private val store: JsonStore<MyProgram>,
    event: EventPack?,
    private val venue: Venue,
    private val clock: Clock,
    private val settings: () -> Settings,
    private val fix: () -> GeoFix?,
    private val walk: () -> WalkDistance,
    private val ring: (ScheduleItem, walkMin: Int) -> Unit,
) {
    private val eventId = event?.id.orEmpty()
    val schedule: List<ScheduleItem> = event?.schedule.orEmpty()

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

    fun setLead(minutes: Int) = change { it.copy(leadMin = minutes, fired = emptyList()) }

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

    private fun ringDue() {
        val program = state.value
        if (program.reminders.isEmpty()) return
        val from = origin()
        val due = Program.due(program, schedule, clock.wallMs() / 1000) { walkMeters(from, it) }
        if (due.isEmpty()) return
        due.forEach { ring(it, Program.walkMinutes(walkMeters(from, it))) }
        change { p -> p.copy(fired = p.fired + due.map(Program::key)) }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
