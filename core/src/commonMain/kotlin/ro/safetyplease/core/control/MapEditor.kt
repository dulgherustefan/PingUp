package ro.safetyplease.core.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.Notices
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.Venue

/**
 * The console's map editor: the operator changes zones one by one, and the changes go out together as one map state
 * (PROTOCOL.md, section 6.8), [SETTLE_MS] after the last change and never sooner than [MIN_GAP_MS] after the map state
 * before, this console's or another's. Phones from before map states show each one as a notice, so they stay rare. A
 * map state that still closes something is signed again [REFRESH_BEFORE_S] before it expires.
 */
class MapEditor(
    private val scope: CoroutineScope,
    private val venue: Venue,
    private val clock: Clock,
    private val notices: Notices,
) {
    private val pending = MutableStateFlow<Map<String, ZoneState>>(emptyMap())
    private var lastEditMs = 0L
    private var lastSentMs = 0L

    /** The zones changed here and not yet sent, by id. */
    val edits: StateFlow<Map<String, ZoneState>> = pending

    init {
        scope.launch {
            pending.collectLatest { edits ->
                if (edits.isEmpty()) return@collectLatest
                delay(dueAt(lastEditMs, lastMapStateMs()) - clock.wallMs())
                flush()
            }
        }
        scope.launch {
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
        if (venue.zone(zone) == null) return
        lastEditMs = clock.wallMs()
        val base = inForce().state(zone)
        pending.update { if (state == base) it - zone else it + (zone to state) }
    }

    /** Opens every zone again: the closures, the crowds and the exit-only zones all end with the next map state. */
    fun openAll() {
        lastEditMs = clock.wallMs()
        val base = inForce()
        pending.value = venue.zones.filter { base.state(it.id) != ZoneState.OPEN }.associate { it.id to ZoneState.OPEN }
    }

    /** When the edits go out, on the wall clock; null with nothing to send. */
    fun dueAtMs(): Long? = if (pending.value.isEmpty()) null else dueAt(lastEditMs, lastMapStateMs())

    /** The latest map state this phone took in, sent here or heard. */
    private fun lastMapStateMs(): Long =
        maxOf(lastSentMs, notices.board.value.notices.firstOrNull { it.topic == NoticeTopic.MAP_STATE }?.receivedAt ?: 0)

    private fun flush() {
        val edits = pending.value
        if (edits.isEmpty()) return
        val base = inForce()
        val draft = venue.zones.map { edits[it.id] ?: base.state(it.id) }
        val inForceStates = venue.zones.map { base.state(it.id) }
        if (draft != inForceStates) {
            notices.publishMapState(draft, VALID_S) ?: return
            lastSentMs = clock.wallMs()
        }
        pending.update { now -> now.filter { (zone, state) -> edits[zone] != state } }
    }

    private fun refresh() {
        if (!notices.canSign || pending.value.isNotEmpty()) return
        val nowSec = clock.wallMs() / 1000
        val held = notices.board.value.active(nowSec).firstOrNull { it.second.topic == NoticeTopic.MAP_STATE }?.second ?: return
        val states = held.extras.states ?: return
        if (states.size != venue.zones.size || states.all { it == ZoneState.OPEN } || held.validUntil - nowSec > REFRESH_BEFORE_S) return
        if (notices.publishMapState(states, VALID_S) != null) lastSentMs = clock.wallMs()
    }

    companion object {
        /** Changes made close together go out in one map state. */
        const val SETTLE_MS = 3_000L

        /** At most one map state in this long. */
        const val MIN_GAP_MS = 30_000L

        /** A map state holds for 12 hours: a closure left in force ends on its own by the next day. */
        const val VALID_S = 12 * 3600L
        const val REFRESH_BEFORE_S = 3600L
        private const val REFRESH_CHECK_MS = 10 * 60_000L

        fun dueAt(lastEditMs: Long, lastSentMs: Long): Long = maxOf(lastEditMs + SETTLE_MS, lastSentMs + MIN_GAP_MS)
    }
}
