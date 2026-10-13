package ro.safetyplease.core.safety

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.LastFix
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.venue.GeoFix

@Serializable
enum class CheckinPhase {
    /** Before the deadline. */
    WAITING,

    /** The deadline passed and the person was asked; the friends are told if they don't answer in [CheckinManager.GRACE_MS]. */
    ASKED,

    /** The friends were told, once. */
    ALERTED,
}

/** "Ajung la [label] până la [deadlineAt]", with the friends who are told if the person goes silent. */
@Serializable
data class Checkin(
    val label: String,
    val startedAt: Long,
    val deadlineAt: Long,
    val friends: List<Long>,
    val phase: CheckinPhase = CheckinPhase.WAITING,
    /** How many friends the alert was queued for, once it went. */
    val told: Int = 0,
)

/** A friend's missed check-in as it reached this phone: [deadlineAt] and [fixedAt] are Unix seconds, [fixedAt] 0 without a fix. */
@Serializable
data class FriendAlert(
    val sender: Long,
    val msgId: Long,
    val label: String,
    val deadlineAt: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val fixedAt: Long = 0,
    val accuracy: Int = AccuracyClass.UNKNOWN,
    val receivedAt: Long,
    val hops: Int = 0,
)

@Serializable
data class CheckinData(
    val current: Checkin? = null,
    /** The destinations the person named, newest first, to pick again. */
    val places: List<String> = emptyList(),
    val alerts: List<FriendAlert> = emptyList(),
)

/**
 * The check-in on the way (car, lodging, station): the person says where they are going, by when and who to tell. At
 * the deadline the phone asks "Ai ajuns?"; with no answer [GRACE_MS] later, one missed check-in alert goes to the
 * chosen friends with the last position, through the outbox like a message. Until then nothing is sent. It never
 * starts from a sensor, and a friend's alert only ever shows them what to do.
 */
class CheckinManager(
    private val scope: CoroutineScope,
    private val store: JsonStore<CheckinData>,
    private val clock: Clock,
    private val friends: () -> List<Friend>,
    private val lastFix: () -> GeoFix?,
    /** Queues the alert for the friends; returns for how many. */
    private val alert: suspend (friends: List<Long>, deadlineSec: Long, fix: LastFix?, label: String) -> Int,
    /** A plain message to each of the friends. */
    private val tell: (friends: List<Long>, text: String) -> Unit,
    private val notify: (SafetyNotice) -> Unit,
) {
    val state: StateFlow<CheckinData> = store.state

    fun start() {
        scope.launch { store.state.map { it.current }.distinctUntilChanged().collectLatest(::watch) }
    }

    /** Starts the check-in, replacing one that runs. False if the place, the time or the friends are not usable. */
    fun begin(label: String, minutes: Int, chosen: List<Long>): Boolean {
        val place = label.trim().truncateUtf8(Limits.CHECKIN_LABEL_BYTES).trim()
        val known = friends().map { it.nodeId }.toSet()
        val who = chosen.distinct().filter { it in known }
        if (place.isEmpty() || minutes !in 1..MAX_MINUTES || who.isEmpty() || who.size > MAX_FRIENDS) return false
        val now = clock.wallMs()
        store.update {
            it.copy(
                current = Checkin(place, now, now + minutes * MINUTE_MS, who),
                places = (listOf(place) + it.places.filterNot { p -> p == place }).take(MAX_PLACES),
            )
        }
        notify(SafetyNotice.CheckinCleared)
        return true
    }

    /** Moves the deadline [minutes] later, counted from now if it has passed; the person is asked again at the new one. */
    fun extend(minutes: Int): Boolean {
        val current = store.value.current ?: return false
        if (current.phase == CheckinPhase.ALERTED || minutes <= 0) return false
        val now = clock.wallMs()
        val deadline = (maxOf(current.deadlineAt, now) + minutes * MINUTE_MS).coerceAtMost(now + MAX_MINUTES * MINUTE_MS)
        store.update { it.copy(current = current.copy(deadlineAt = deadline, phase = CheckinPhase.WAITING)) }
        notify(SafetyNotice.CheckinCleared)
        return true
    }

    /** Ends it without telling anyone. */
    fun cancel() {
        if (store.value.current == null) return
        store.update { it.copy(current = null) }
        notify(SafetyNotice.CheckinCleared)
    }

    /**
     * The person arrived. If the friends were already told, [reassurance] goes to them as a message, so they stop
     * worrying; before that, nobody hears anything.
     */
    fun arrived(reassurance: String? = null) {
        val current = store.value.current ?: return
        if (current.phase == CheckinPhase.ALERTED && !reassurance.isNullOrBlank()) tell(current.friends, reassurance.trim())
        cancel()
    }

    /** A friend did not check in. A copy of one already here, which the sender's retries bring, changes nothing. */
    fun onFriendMissed(sender: Long, missed: Inner.CheckinMissed, hops: Int) {
        if (store.value.alerts.any { it.sender == sender && it.msgId == missed.msgId }) return
        val fix = missed.lastFix
        val entry = FriendAlert(
            sender, missed.msgId, missed.label, missed.deadline, fix?.lat, fix?.lon, fix?.fixedAt ?: 0, fix?.accuracy ?: AccuracyClass.UNKNOWN,
            receivedAt = clock.wallMs(), hops = hops,
        )
        store.update { it.copy(alerts = (listOf(entry) + it.alerts).take(MAX_ALERTS)) }
        val name = friends().firstOrNull { it.nodeId == sender }?.nickname.orEmpty()
        notify(SafetyNotice.FriendSilent(name, missed.label))
    }

    fun dismissAlert(sender: Long, msgId: Long) {
        store.update { it.copy(alerts = it.alerts.filterNot { a -> a.sender == sender && a.msgId == msgId }) }
    }

    /** Waits for the next moment of [checkin] and acts on it; the state it writes starts the next watch. */
    private suspend fun watch(checkin: Checkin?) {
        if (checkin == null || checkin.phase == CheckinPhase.ALERTED) return
        while (true) {
            val now = clock.wallMs()
            val due = if (checkin.phase == CheckinPhase.WAITING) checkin.deadlineAt else checkin.deadlineAt + GRACE_MS
            if (now >= due) return if (checkin.phase == CheckinPhase.WAITING) ask(checkin) else withContext(NonCancellable) { raise(checkin) }
            // in short steps, so a clock that moved or a phone that slept is noticed
            delay((due - now).coerceIn(1, STEP_MS))
        }
    }

    private fun ask(checkin: Checkin) {
        val asked = checkin.copy(phase = CheckinPhase.ASKED)
        store.update { if (it.current == checkin) it.copy(current = asked) else it }
        if (store.value.current == asked) notify(SafetyNotice.CheckinDue(checkin.label))
    }

    /** Marked as sent before it is queued, so a restart or a second watch can't send it again. */
    private suspend fun raise(checkin: Checkin) {
        val sent = checkin.copy(phase = CheckinPhase.ALERTED)
        store.update { if (it.current == checkin) it.copy(current = sent) else it }
        if (store.value.current != sent) return
        val fix = lastFix()?.let { LastFix(it.lat, it.lon, (it.timeMs / 1000).coerceIn(0, MAX_SECONDS), AccuracyClass.of(it.accuracyM.toDouble())) }
        val told = alert(checkin.friends, checkin.deadlineAt / 1000, fix, checkin.label)
        store.update { data -> data.copy(current = data.current?.takeIf { it.startedAt == checkin.startedAt }?.copy(told = told) ?: data.current) }
        notify(SafetyNotice.CheckinAlerted(checkin.label, told))
    }

    companion object {
        const val MINUTE_MS = 60_000L

        /** The person has this long after the deadline to answer before the friends are told. */
        const val GRACE_MS = 10 * MINUTE_MS

        const val MAX_FRIENDS = 5

        /** The longest a check-in is set for: twelve hours. */
        const val MAX_MINUTES = 12 * 60
        const val MAX_PLACES = 5
        const val MAX_ALERTS = 10
        private const val STEP_MS = 15_000L
        private const val MAX_SECONDS = 0xFFFF_FFFFL
    }
}
