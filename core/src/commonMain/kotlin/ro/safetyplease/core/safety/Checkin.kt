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

    /** "Ai ajuns?" was shown at [Checkin.askedAt]; the friends are told if nobody answers in [CheckinManager.GRACE_MS]. */
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
    /** When the question was shown, by the wall clock; 0 before. */
    val askedAt: Long = 0,
    /** The friends the alert was queued for. */
    val alerted: List<Long> = emptyList(),
    /** The alert is in the outbox; false while [phase] is ALERTED means the process died before it got there. */
    val queued: Boolean = false,
) {
    val told: Int get() = alerted.size
}

/**
 * A friend's missed check-in as it reached this phone: [deadlineAt] and [fixedAt] are Unix seconds, [fixedAt] 0 without
 * a fix; [receivedAt] is this phone's wall clock.
 */
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
 * the deadline the phone asks "Ai ajuns?"; with no answer [GRACE_MS] after the question, one missed check-in alert goes
 * to the chosen friends with the last position, through the outbox like a message. Until then nothing is sent. It never
 * starts from a sensor, and a friend's alert only ever shows them what to do.
 *
 * The answer time counts from when the question is shown, not from the deadline: a phone that was off, a process that
 * was killed or an iPhone that was suspended asks late, and the person still gets the whole [GRACE_MS]. While the
 * process lives, time is measured on the monotonic clock, so a wall clock that steps neither asks nor alerts early.
 */
class CheckinManager(
    private val scope: CoroutineScope,
    private val store: JsonStore<CheckinData>,
    private val clock: Clock,
    private val friends: () -> List<Friend>,
    private val lastFix: () -> GeoFix?,
    /** Queues alert [msgId] for the friends; returns those it was queued for. */
    private val alert: suspend (msgId: Long, friends: List<Long>, deadlineSec: Long, fix: LastFix?, label: String) -> List<Long>,
    /** Takes alert [msgId] out of the outbox; returns the friends it had not reached yet. */
    private val withdraw: (msgId: Long) -> List<Long>,
    /** A plain message to each of the friends. */
    private val tell: (friends: List<Long>, text: String) -> Unit,
    private val notify: (SafetyNotice) -> Unit,
) {
    val state: StateFlow<CheckinData> = store.state

    /** The wall and monotonic times at which this process started timing [key]. */
    private class Anchor(val key: Checkin, val wallMs: Long, val monoMs: Long) {
        fun now(clock: Clock): Long = wallMs + (clock.monoMs() - monoMs)
    }

    /** Only the watch reads and writes it. */
    private var anchor: Anchor? = null

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

    /**
     * Moves the deadline [minutes] later, counted from now if it has passed; the person is asked again at the new one.
     * False once the alert went, also when it went between reading the check-in and changing it.
     */
    fun extend(minutes: Int): Boolean {
        val current = store.value.current ?: return false
        if (current.phase == CheckinPhase.ALERTED || minutes <= 0) return false
        val now = clock.wallMs()
        val deadline = (maxOf(current.deadlineAt, now) + minutes * MINUTE_MS).coerceAtMost(now + MAX_MINUTES * MINUTE_MS)
        if (!swap(current, current.copy(deadlineAt = deadline, phase = CheckinPhase.WAITING, askedAt = 0))) return false
        notify(SafetyNotice.CheckinCleared)
        return true
    }

    /** Ends it without telling anyone; an alert that hasn't reached a friend yet is taken back. */
    fun cancel() = arrived()

    /**
     * The person arrived. An alert still in the outbox is taken back; the friends it already reached get [reassurance]
     * as a message, so they stop worrying. Before the alert, nobody hears anything.
     */
    fun arrived(reassurance: String? = null) {
        val current = store.value.current ?: return
        if (!swap(current, null)) return
        notify(SafetyNotice.CheckinCleared)
        if (current.phase != CheckinPhase.ALERTED) return
        val waiting = withdraw(alertId(current.startedAt)).toSet()
        val reached = current.alerted.filterNot { it in waiting }
        if (!reassurance.isNullOrBlank() && reached.isNotEmpty()) tell(reached, reassurance.trim())
    }

    /** A friend did not check in. A copy of one already here, which the sender's retries bring, changes nothing. */
    fun onFriendMissed(sender: Long, missed: Inner.CheckinMissed) {
        if (store.value.alerts.any { it.sender == sender && it.msgId == missed.msgId }) return
        val fix = missed.lastFix
        val entry = FriendAlert(
            sender, missed.msgId, missed.label, missed.deadline, fix?.lat, fix?.lon, fix?.fixedAt ?: 0, fix?.accuracy ?: AccuracyClass.UNKNOWN,
            receivedAt = clock.wallMs(),
        )
        store.update { it.copy(alerts = (listOf(entry) + it.alerts).take(MAX_ALERTS)) }
        val name = friends().firstOrNull { it.nodeId == sender }?.nickname.orEmpty()
        notify(SafetyNotice.FriendSilent(name, missed.label, missed.deadline * 1000, (fix?.fixedAt ?: 0) * 1000))
    }

    fun dismissAlert(sender: Long, msgId: Long) {
        store.update { it.copy(alerts = it.alerts.filterNot { a -> a.sender == sender && a.msgId == msgId }) }
    }

    /** Writes the state now, for a caller that may not live until the debounced write (a notification's action). */
    fun flush() = store.flush()

    /**
     * Waits for the next moment of [checkin] and acts on it; the state it writes starts the next watch. A watch that
     * starts after the state moved on (it waited for the one before, which wrote it) does nothing: the next one will.
     */
    private suspend fun watch(checkin: Checkin?) {
        if (checkin == null || store.value.current != checkin) return
        if (checkin.phase == CheckinPhase.ALERTED) {
            if (!checkin.queued) withContext(NonCancellable) { queue(checkin) }
            return
        }
        val known = anchor?.takeIf { it.key == checkin }
        val at = known ?: Anchor(checkin, clock.wallMs(), clock.monoMs()).also { anchor = it }
        // asked by a process that is gone: its window goes on only if the wall clock says it hasn't run out or gone back
        if (known == null && checkin.phase == CheckinPhase.ASKED && at.wallMs - checkin.askedAt !in 0 until GRACE_MS) return ask(checkin, at)
        while (true) {
            val now = at.now(clock)
            val due = if (checkin.phase == CheckinPhase.WAITING) checkin.deadlineAt else checkin.askedAt + GRACE_MS
            if (now >= due) return if (checkin.phase == CheckinPhase.WAITING) ask(checkin, at) else withContext(NonCancellable) { raise(checkin) }
            // in short steps, so a phone that slept is noticed
            delay((due - now).coerceIn(1, STEP_MS))
        }
    }

    /** Shows the question; its time to answer starts now, however late that is. */
    private fun ask(checkin: Checkin, at: Anchor) {
        val asked = checkin.copy(phase = CheckinPhase.ASKED, askedAt = at.now(clock))
        if (!swap(checkin, asked)) return
        anchor = Anchor(asked, at.wallMs, at.monoMs)
        notify(SafetyNotice.CheckinDue(checkin.label))
    }

    /** Marked as alerted before it is queued, so a second watch can't send it again. */
    private suspend fun raise(checkin: Checkin) {
        val alerted = checkin.copy(phase = CheckinPhase.ALERTED)
        if (swap(checkin, alerted)) queue(alerted)
    }

    /**
     * Hands the alert to the outbox; after a crash it runs again with the same msgId, which a friend keeps only once.
     * The alert is taken back only if the person answered while it was being queued, never because another run of the
     * same check-in queued it first.
     */
    private suspend fun queue(checkin: Checkin) {
        val msgId = alertId(checkin.startedAt)
        val fix = lastFix()?.let { LastFix(it.lat, it.lon, (it.timeMs / 1000).coerceIn(0, MAX_SECONDS), AccuracyClass.of(it.accuracyM.toDouble())) }
        val to = alert(msgId, checkin.friends, checkin.deadlineAt / 1000, fix, checkin.label)
        if (!swap(checkin, checkin.copy(alerted = to, queued = true))) {
            val now = store.value.current
            if (now == null || now.startedAt != checkin.startedAt || now.phase != CheckinPhase.ALERTED) withdraw(msgId)
            return
        }
        notify(SafetyNotice.CheckinAlerted(checkin.label, to.size))
    }

    /** Replaces [from] with [to] only if [from] is still the check-in. */
    private fun swap(from: Checkin, to: Checkin?): Boolean {
        var swapped = false
        store.update {
            swapped = it.current == from
            if (swapped) it.copy(current = to) else it
        }
        return swapped
    }

    companion object {
        const val MINUTE_MS = 60_000L

        /** The person has this long after the question to answer before the friends are told. */
        const val GRACE_MS = 10 * MINUTE_MS

        const val MAX_FRIENDS = 5

        /** The longest a check-in is set for: twelve hours. */
        const val MAX_MINUTES = 12 * 60
        const val MAX_PLACES = 5
        const val MAX_ALERTS = 10
        private const val STEP_MS = 15_000L
        private const val MAX_SECONDS = 0xFFFF_FFFFL

        /** The alert's msgId: the same for every run of one check-in, never 0. */
        fun alertId(startedAt: Long): Long = (startedAt * -7_046_029_254_386_353_131L) or 1L
    }
}
