package ro.safetyplease.core.position

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.chat.MeetingPlace
import ro.safetyplease.core.chat.MeetingProposal
import ro.safetyplease.core.chat.PerFriendLimit
import ro.safetyplease.core.chat.PositionFrame
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.PositionRecord
import ro.safetyplease.core.data.PositionStore
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.LastFix
import ro.safetyplease.core.protocol.LastSeen
import ro.safetyplease.core.protocol.LostAction
import ro.safetyplease.core.protocol.NextUpdate
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance

/** Someone of [conversation] lost their group and asks to meet at [landmark] (0: the venue's meeting point) until [untilMs]. */
data class LostAlert(
    val sender: Long,
    val conversation: String,
    val msgId: Long,
    val seq: Int,
    val landmark: Int,
    val battery: Int,
    val atMs: Long,
    val untilMs: Long,
)

/**
 * Where a lost session sends this phone: [landmark] (0 for the venue's meeting point) at [point], until [untilMs]; [mine]
 * when this phone is the one that got lost. The walking route leads there.
 */
data class LostTarget(val conversation: String, val landmark: Int, val point: GeoPoint, val untilMs: Long, val mine: Boolean)

/** A friend's last position before their phone ran out, and the battery it had then. */
data class FriendGasp(val sender: Long, val lat: Double, val lon: Double, val accuracy: Int, val battery: Int, val fixAtMs: Long, val receivedAtMs: Long)

/** A lost report of ours about [friend], so their "Sunt bine" can close it. */
@Serializable
data class Search(val incidentId: String, val friend: Long, val atMs: Long)

/** A friend's phone says a report of ours looks for them, and they answered "Sunt bine". */
data class FoundReply(val incidentId: String, val friend: Long, val atMs: Long)

/** The report being written names [friend] as the one lost; [attach] is the reporter's yes to sending where they were. */
data class SearchDraft(val friend: Long, val attach: Boolean)

/** Where a lost report says the person was last seen, on the staff phone's clock ([LastSeen] on the wire). */
@Serializable
data class SeenAt(val lat: Double, val lon: Double, val atMs: Long, val accuracy: Int) {
    companion object {
        fun of(seen: LastSeen, reportedAtMs: Long) = SeenAt(seen.lat, seen.lon, reportedAtMs - seen.ageMin * 60_000L, seen.accuracy)
    }
}

/**
 * Finding each other again (PROTOCOL.md, section 6.6.2): "M-am pierdut de grup", the last gasp at 5% battery, and a
 * lost person's last position in a report, with their "Sunt bine". Runs on the mesh context, as [chat] does.
 *
 * A lost session to a group is the sender saying they lost it; sent to one friend, it is the sender looking for that
 * friend, which a lost report does. Its landmark is also the meeting point: phones pin it under the session's msgId,
 * so a "Vin acolo" is an ordinary yes to that meeting point.
 */
class Regasire(
    private val scope: CoroutineScope,
    private val chat: ChatManager,
    private val positions: PositionStore,
    private val clock: Clock,
    private val venue: Venue,
    private val myNodeId: Long,
    private val searches: JsonStore<List<Search>>,
    private val walk: () -> WalkDistance,
    private val mapState: () -> MapState,
    private val myPosition: () -> GeoFix?,
    private val battery: () -> Int,
    private val frame: () -> PositionFrame,
) {
    private val _alerts = MutableStateFlow<List<LostAlert>>(emptyList())
    private val _target = MutableStateFlow<LostTarget?>(null)
    private val _gasps = MutableStateFlow<List<FriendGasp>>(emptyList())
    private val _searchedBy = MutableStateFlow<List<LostAlert>>(emptyList())
    private val _found = MutableStateFlow<List<FoundReply>>(emptyList())
    private val _draft = MutableStateFlow<SearchDraft?>(null)

    /** Group members who say they are lost, waiting for "Vin acolo". */
    val alerts: StateFlow<List<LostAlert>> = _alerts

    /** The landmark this phone is headed to, its own lost session's or the one it said "Vin acolo" to. */
    val target: StateFlow<LostTarget?> = _target

    /** Friends whose phone sent its last position at low battery; kept as long as a position would be. */
    val gasps: StateFlow<List<FriendGasp>> = _gasps

    /** Friends looking for us through a lost report: "Te caută prietenii", waiting for "Sunt bine". */
    val searchedBy: StateFlow<List<LostAlert>> = _searchedBy

    /** "Sunt bine" from someone a report of ours looks for: the report can be closed. */
    val found: StateFlow<List<FoundReply>> = _found

    val draft: StateFlow<SearchDraft?> = _draft

    private val seen = LinkedHashSet<Pair<Long, Long>>()
    private val heard = PerFriendLimit(ACTIVATION_INTERVAL_MS)
    private var activatedAtMono: Long? = null
    private var seq = 0
    private var gaspArmed = true

    init {
        chat.onLost = ::onLost
        chat.onLastGasp = ::onLastGasp
    }

    // --- "M-am pierdut de grup" ---

    /** How long until this phone may say it is lost again; 0 when it may now. */
    fun activationWaitMs(): Long = activatedAtMono?.let { maxOf(0L, it + ACTIVATION_INTERVAL_MS - clock.monoMs()) } ?: 0L

    /** The meeting place a lost session with [conversation] would propose now: a landmark, else the venue's meeting point. */
    fun meetingFor(conversation: String): MeetingPlace? {
        val now = clock.wallMs()
        val members = chat.recipientsNow(conversation).toSet()
        val people = positions.value.records.filter { it.sender in members && now - it.fixAtMs <= LostMeeting.FRESH_MS }
            .map { GeoPoint(it.lat, it.lon) } +
            listOfNotNull(myPosition()?.takeIf { now - it.timeMs <= LostMeeting.FRESH_MS }?.let { GeoPoint(it.lat, it.lon) })
        LostMeeting.choose(venue, people, walk(), mapState())?.let { return MeetingPlace.landmark(it.number) }
        return venue.meetingPoint?.let(MeetingPlace::venue)
    }

    /**
     * "M-am pierdut de grup": an exact share of [SHARE_MIN] minutes with [conversation], the meeting place of [meetingFor]
     * pinned there, and the lost session to every member. Once every [ACTIVATION_INTERVAL_MS]; false when that holds
     * or nobody is there to tell.
     */
    suspend fun imLost(conversation: String): Boolean = scope.async {
        if (!Conversations.isGroup(conversation) || activationWaitMs() > 0) return@async false
        val place = meetingFor(conversation)
        seq = (seq + 1) and 0xFF
        val inner = chat.sendLost(conversation) { msgId, groupId ->
            Inner.LostSession(msgId, groupId, LostAction.START, seq, SHARE_MIN, battery(), place?.landmark ?: 0)
        } ?: return@async false
        activatedAtMono = clock.monoMs()
        chat.shareExactFor(conversation, SHARE_MIN)
        val until = clock.wallMs() + SHARE_MIN * 60_000L
        if (place != null) {
            chat.pinMeeting(MeetingProposal(conversation, myNodeId, inner.msgId, inner.seq, place, null, until))
            _target.value = targetOf(conversation, place.landmark, until, mine = true)
        }
        true
    }.await()

    /** We are together again: the members hear it, and nobody is sent anywhere any more. */
    fun foundGroup(conversation: String) {
        scope.launch {
            val target = _target.value?.takeIf { it.conversation == conversation && it.mine } ?: return@launch
            _target.value = null
            chat.sendLost(conversation) { msgId, groupId -> Inner.LostSession(msgId, groupId, LostAction.FOUND, seq, 1, battery(), target.landmark) }
        }
    }

    /** "Vin acolo": our exact share with the group for [SHARE_MIN] minutes, and a yes to the meeting place. */
    fun comeThere(alert: LostAlert) {
        scope.launch {
            _alerts.update { list -> list - alert }
            chat.shareExactFor(alert.conversation, SHARE_MIN)
            val place = placeOf(alert.landmark) ?: return@launch
            chat.pinMeeting(MeetingProposal(alert.conversation, alert.sender, alert.msgId, alert.seq, place, null, alert.untilMs))
            chat.acceptMeeting(alert.conversation)
            _target.value = targetOf(alert.conversation, alert.landmark, alert.untilMs, mine = false)
        }
    }

    fun dismiss(alert: LostAlert) {
        _alerts.update { list -> list - alert }
        _searchedBy.update { list -> list - alert }
    }

    // --- the last gasp ---

    /**
     * Each battery reading: the first one at [GASP_PERCENT] or less, off the charger, sends the last position to whoever
     * we share it with now, at the precision they have; nothing when we share with nobody or have no fresh fix.
     */
    fun onBattery(percent: Int, charging: Boolean) {
        if (charging || percent > GASP_PERCENT) {
            gaspArmed = true
            return
        }
        if (!gaspArmed) return
        gaspArmed = false
        scope.launch { sendGasps(percent) }
    }

    private fun sendGasps(percent: Int) {
        val fix = myPosition()?.takeIf { it.isFresh(clock.wallMs()) } ?: return
        val frame = frame()
        for ((recipient, precision) in chat.shareTargetsNow()) {
            if (precision == SharePrecision.ZONE) continue
            val approximate = precision == SharePrecision.APPROXIMATE
            val point = frame.latLon(frame.point(fix.lat, fix.lon, approximate)) ?: continue
            val radius = if (approximate) maxOf(fix.accuracyM.toDouble(), PositionFrame.APPROXIMATE_RADIUS_M) else fix.accuracyM.toDouble()
            val last = LastFix(point.lat, point.lon, fix.timeMs / 1000, AccuracyClass.of(radius))
            chat.sendLastGasp(recipient, Inner.LastGasp(last, percent.coerceIn(0, 100)))
        }
    }

    // --- the last position in a lost report ---

    /** Where [friend] was last, from what they themselves shared with us: their position, or their last gasp. */
    fun lastSeenOf(friend: Long): LastSeen? {
        val now = clock.wallMs()
        val record = positions.value.records.firstOrNull { it.sender == friend }
        val gasp = _gasps.value.firstOrNull { it.sender == friend }
        val (lat, lon, at, accuracy) = when {
            gasp != null && (record == null || gasp.fixAtMs > record.fixAtMs) -> Seen(gasp.lat, gasp.lon, gasp.fixAtMs, gasp.accuracy)
            record != null -> Seen(record.lat, record.lon, record.fixAtMs, record.accuracy)
            else -> return null
        }
        return LastSeen(lat, lon, ((now - at).coerceAtLeast(0) / 60_000).toInt().coerceAtMost(0xFFFF), accuracy)
    }

    private data class Seen(val lat: Double, val lon: Double, val atMs: Long, val accuracy: Int)

    /** The report screen picked [friend] as the person lost (null: none), and whether their last position goes with it. */
    fun setDraft(draft: SearchDraft?) {
        _draft.value = draft
    }

    /** What a lost report of [category] carries from the draft: the last position, only with the reporter's yes. */
    fun extrasFor(category: Int): ReportExtras {
        val draft = _draft.value?.takeIf { it.attach && isLost(category) } ?: return ReportExtras.NONE
        return lastSeenOf(draft.friend)?.let { ReportExtras(lastSeen = it) } ?: ReportExtras.NONE
    }

    /** The report went: the friend it looks for hears "Te caută prietenii", and their "Sunt bine" can close it. */
    fun reportSent(incidentId: String, category: Int) {
        val draft = _draft.value?.takeIf { isLost(category) } ?: return
        _draft.value = null
        val now = clock.wallMs()
        searches.update { list -> (list.filter { now - it.atMs < SEARCH_KEPT_MS } + Search(incidentId, draft.friend, now)).takeLast(MAX_SEARCHES) }
        scope.launch {
            chat.sendLost(Conversations.friend(draft.friend)) { msgId, _ ->
                Inner.LostSession(msgId, null, LostAction.START, 0, LostAction.MAX_MINUTES, battery(), 0)
            }
        }
    }

    /** "Sunt bine" to a friend looking for us: it ends the search without telling where we are. */
    fun imOk(alert: LostAlert) {
        _searchedBy.update { list -> list.filterNot { it.sender == alert.sender } }
        scope.launch {
            chat.sendLost(alert.conversation) { msgId, _ -> Inner.LostSession(msgId, null, LostAction.FOUND, 0, 1, battery(), 0) }
        }
    }

    /** The reporter closed the report, or chose not to. */
    fun doneWith(reply: FoundReply) {
        _found.update { list -> list - reply }
        searches.update { list -> list.filterNot { it.incidentId == reply.incidentId } }
    }

    // --- receiving ---

    private fun onLost(sender: Long, conversation: String, inner: Inner.LostSession) {
        if (!seen.add(sender to inner.msgId)) return
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        val now = clock.wallMs()
        val direct = inner.groupId == null
        when (inner.action) {
            LostAction.START -> {
                if (!heard.tryTake(sender, clock.monoMs())) return
                val alert = LostAlert(sender, conversation, inner.msgId, inner.seq, inner.landmark, inner.battery, now, now + inner.minutes * 60_000L)
                if (direct) _searchedBy.update { list -> list.filterNot { it.sender == sender } + alert }
                else _alerts.update { list -> list.filterNot { it.sender == sender && it.conversation == conversation } + alert }
            }
            LostAction.FOUND -> {
                _alerts.update { list -> list.filterNot { it.sender == sender && it.conversation == conversation } }
                if (_target.value?.let { it.conversation == conversation && !it.mine } == true) _target.value = null
                if (!direct) return
                val replies = searches.value.filter { it.friend == sender }.map { FoundReply(it.incidentId, sender, now) }
                _found.update { list -> list.filterNot { it.friend == sender } + replies }
            }
        }
    }

    /** Kept only from someone who shares their position with us now: the last gasp is a position like theirs. */
    private fun onLastGasp(sender: Long, gasp: Inner.LastGasp) {
        val now = clock.wallMs()
        val session = positions.value.sessionOf(sender, now) ?: return
        val fixAt = gasp.fix.fixedAt * 1000
        _gasps.update { list ->
            list.filter { it.sender != sender && now - it.receivedAtMs < GASP_KEPT_MS } +
                FriendGasp(sender, gasp.fix.lat, gasp.fix.lon, gasp.fix.accuracy, gasp.battery, fixAt, now)
        }
        val record = PositionRecord(
            sender, gasp.fix.lat, gasp.fix.lon, gasp.fix.accuracy, PositionMethod.UNKNOWN, null, fixAt, now,
            maxOf(session.expiresAtMs, now + GASP_KEPT_MS), 0, NextUpdate.SESSION_ENDED,
        )
        positions.update { it.withPosition(record) }
    }

    private fun placeOf(landmark: Int): MeetingPlace? =
        if (landmark != 0) MeetingPlace.landmark(landmark) else venue.meetingPoint?.let(MeetingPlace::venue)

    private fun targetOf(conversation: String, landmark: Int, untilMs: Long, mine: Boolean): LostTarget? {
        val point = if (landmark != 0) venue.landmark(landmark)?.point else venue.meetingPoint
        return point?.let { LostTarget(conversation, landmark, it, untilMs, mine) }
    }

    private fun isLost(category: Int) = category == IncidentCategory.LOST_FRIEND || category == IncidentCategory.LOST_CHILD

    companion object {
        /** One "M-am pierdut" per person in this long, sent and heard. */
        const val ACTIVATION_INTERVAL_MS = 10 * 60_000L

        /** The exact share a lost session and "Vin acolo" start. */
        const val SHARE_MIN = 15
        const val GASP_PERCENT = 5
        const val GASP_KEPT_MS = 2 * 60 * 60_000L
        const val SEARCH_KEPT_MS = 24 * 60 * 60_000L
        private const val MAX_SEARCHES = 20
        private const val MAX_SEEN = 256
    }
}
