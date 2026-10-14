package ro.safetyplease.core.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.Notices
import ro.safetyplease.core.announce.ZoneSet
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.Venue
import kotlin.concurrent.Volatile

/**
 * The console's map editor: the operator changes zones one by one, and the changes go out together as one map state
 * (PROTOCOL.md, section 6.8), [SETTLE_MS] after the last change but no later than [MAX_SETTLE_MS] after the first, and
 * never sooner than [MIN_GAP_MS] after the map state before, this console's or another's. Phones from before map states
 * show each one as a notice, so they stay rare. A map state that still closes something is signed again
 * [REFRESH_BEFORE_S] before it expires. Edits that couldn't be signed, the shift having lapsed, go out once it is renewed.
 * It runs only while [active] (the console's role), on [scope], the mesh's: the screen's calls are handed to it.
 */
class MapEditor(
    private val scope: CoroutineScope,
    private val venue: Venue,
    private val clock: Clock,
    private val notices: Notices,
    private val active: Flow<Boolean> = flowOf(true),
) {
    private val pending = MutableStateFlow<Map<String, ZoneState>>(emptyMap())

    // on the monotonic clock: the wall clock may jump; written on the scope only, read by the screen too
    @Volatile
    private var firstEditMono = 0L

    @Volatile
    private var lastEditMono = 0L

    @Volatile
    private var lastSentMono: Long? = null

    /** The zones changed here and not yet sent, by id. */
    val edits: StateFlow<Map<String, ZoneState>> = pending

    /** A map state carries one byte for each four zones, for at most [ZoneSet.MAX_ZONES] zones. */
    val supported: Boolean get() = venue.zones.size in 1..ZoneSet.MAX_ZONES

    init {
        scope.launch {
            active.distinctUntilChanged().collectLatest { on -> if (on) run() }
        }
    }

    private suspend fun run() = coroutineScope {
        launch {
            pending.collectLatest { edits ->
                if (edits.isEmpty()) return@collectLatest
                delay(dueInMs() ?: return@collectLatest)
                // a lapsed shift signs nothing: try again until a renewed one does
                while (!flush()) delay(RETRY_MS)
            }
        }
        launch {
            while (isActive) {
                delay(REFRESH_CHECK_MS)
                refresh()
            }
        }
    }

    /** The map state in force on this phone, from the notices. */
    fun inForce(): MapState = MapState.of(notices.board.value, venue, clock.wallMs() / 1000)

    /** What the next map state will say of every zone: the one in force, with the edits over it. */
    fun draft(): Map<String, ZoneState> {
        val base = inForce()
        val edits = pending.value
        return venue.zones.associate { it.id to (edits[it.id] ?: base.state(it.id)) }
    }

    fun set(zone: String, state: ZoneState) {
        if (!supported || venue.zone(zone) == null) return
        scope.launch {
            edited()
            val base = inForce().state(zone)
            pending.update { if (state == base) it - zone else it + (zone to state) }
        }
    }

    /** Opens every zone again: the closures, the crowds and the exit-only zones all end with the next map state. */
    fun openAll() {
        if (!supported) return
        scope.launch {
            edited()
            val base = inForce()
            pending.value = venue.zones.filter { base.state(it.id) != ZoneState.OPEN }.associate { it.id to ZoneState.OPEN }
        }
    }

    private fun edited() {
        val now = clock.monoMs()
        if (pending.value.isEmpty()) firstEditMono = now
        lastEditMono = now
    }

    /** In how many milliseconds the edits go out; null with nothing to send. */
    fun dueInMs(): Long? {
        if (pending.value.isEmpty()) return null
        val now = clock.monoMs()
        return (dueAt(firstEditMono, lastEditMono, lastMapStateMono(now)) - now).coerceAtLeast(0)
    }

    /**
     * Sends the edits now, without waiting: before an evacuation, so the phones pick the exits from the zones as they
     * are. False when they couldn't be signed.
     */
    suspend fun sendNow(): Boolean = scope.async { flush() }.await()

    /** The latest map state this phone took in, sent here or heard, on the monotonic clock; null for none. */
    private fun lastMapStateMono(nowMono: Long): Long? {
        val heard = notices.board.value.notices.firstOrNull { it.topic == NoticeTopic.MAP_STATE }?.receivedAt
            ?.let { nowMono - (clock.wallMs() - it).coerceAtLeast(0) }
        return listOfNotNull(lastSentMono, heard).maxOrNull()
    }

    /** True once nothing is left to send. */
    private suspend fun flush(): Boolean {
        val edits = pending.value
        if (edits.isEmpty() || !supported) return true
        val base = inForce()
        val draft = venue.zones.map { edits[it.id] ?: base.state(it.id) }
        val inForceStates = venue.zones.map { base.state(it.id) }
        if (draft != inForceStates) {
            notices.publishMapState(draft, VALID_S) ?: return false
            lastSentMono = clock.monoMs()
        }
        pending.update { now -> now.filter { (zone, state) -> edits[zone] != state } }
        return true
    }

    private suspend fun refresh() {
        if (!supported || !notices.canSign || pending.value.isNotEmpty()) return
        val nowSec = clock.wallMs() / 1000
        val held = notices.board.value.active(nowSec).firstOrNull { it.second.topic == NoticeTopic.MAP_STATE }?.second ?: return
        val states = held.extras.states ?: return
        if (states.size != venue.zones.size || states.all { it == ZoneState.OPEN } || held.validUntil - nowSec > REFRESH_BEFORE_S) return
        if (notices.publishMapState(states, VALID_S) != null) lastSentMono = clock.monoMs()
    }

    companion object {
        /** Changes made close together go out in one map state. */
        const val SETTLE_MS = 3_000L

        /** An operator still editing doesn't hold the first change back longer than this. */
        const val MAX_SETTLE_MS = 10_000L

        /** At most one map state in this long. */
        const val MIN_GAP_MS = 30_000L

        /** A map state holds for 12 hours: a closure left in force ends on its own by the next day. */
        const val VALID_S = 12 * 3600L
        const val REFRESH_BEFORE_S = 3600L
        const val REFRESH_CHECK_MS = 10 * 60_000L
        const val RETRY_MS = 10_000L

        fun dueAt(firstEditMs: Long, lastEditMs: Long, lastSentMs: Long?): Long =
            maxOf(minOf(lastEditMs + SETTLE_MS, firstEditMs + MAX_SETTLE_MS), lastSentMs?.plus(MIN_GAP_MS) ?: Long.MIN_VALUE)
    }
}
