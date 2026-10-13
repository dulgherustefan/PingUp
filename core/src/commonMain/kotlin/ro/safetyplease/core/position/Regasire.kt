package ro.safetyplease.core.position

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.chat.LostShare
import ro.safetyplease.core.chat.MeetingPlace
import ro.safetyplease.core.chat.MeetingProposal
import ro.safetyplease.core.chat.PerFriendLimit
import ro.safetyplease.core.chat.PositionFrame
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.PositionData
import ro.safetyplease.core.data.PositionRecord
import ro.safetyplease.core.data.PositionStore
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.BatteryLevel
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
data class FoundReply(val incidentId: String, val friend: Long)

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
 * friend, which a lost report does. Its landmark is also the meeting point: the lost phone pins it under the session's
 * msgId, and so does each phone that answers "Vin acolo", whose yes is an ordinary one to that meeting point.
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

    /** Friends whose phone sent its last position at low battery; each goes with the position it stands for. */
    val gasps: StateFlow<List<FriendGasp>> =
        combine(_gasps, positions.state) { list, data -> list.filter { holds(it, data) } }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Friends looking for us through a lost report: "Te caută prietenii", waiting for "Sunt bine". */
    val searchedBy: StateFlow<List<LostAlert>> = _searchedBy

    /** "Sunt bine" from someone a report of ours looks for: the report can be closed. */
    val found: StateFlow<List<FoundReply>> = _found

    val draft: StateFlow<SearchDraft?> = _draft

    /** This phone's own lost session while it runs. */
    private class Mine(val conversation: String, val lost: LostShare)

    /** The last group session heard from a (sender, conversation): its seq, its START's msgId, and whether it ended. */
    private class Heard(val seq: Int, val startId: Long, val ended: Boolean)

    private val seen = LinkedHashSet<Pair<Long, Long>>()
    private val heardSessions = HashMap<Pair<Long, String>, Heard>()
    private val heard = PerFriendLimit(ACTIVATION_INTERVAL_MS)
    private var activatedAtMono: Long? = null
    private var mine: Mine? = null
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
     * "M-am pierdut de grup": the lost session to every member of [conversation] with an exact share of [SHARE_MIN]
     * minutes, and the meeting place of [meetingFor] pinned there. Once every [ACTIVATION_INTERVAL_MS], counted from
     * the first tap; false when that holds or nobody is there to tell.
     */
    suspend fun imLost(conversation: String): Boolean = scope.async {
        if (!Conversations.isGroup(conversation) || activationWaitMs() > 0) return@async false
        val before = activatedAtMono
        activatedAtMono = clock.monoMs()
        val place = meetingFor(conversation)
        val seq = chat.nextMeetingSeq()
        val lost = chat.sendLostWithShare(conversation, SHARE_MIN) { msgId, groupId ->
            Inner.LostSession(msgId, groupId, LostAction.START, seq, SHARE_MIN, battery(), place?.landmark ?: 0)
        }
        if (lost == null) {
            activatedAtMono = before
            return@async false
        }
        mine = Mine(conversation, lost)
        val until = clock.wallMs() + SHARE_MIN * 60_000L
        if (place != null) {
            chat.pinMeeting(MeetingProposal(conversation, myNodeId, lost.session.msgId, seq, place, null, until))
            _target.value = targetOf(conversation, place.landmark, until, mine = true)
        }
        true
    }.await()

    /**
     * "Ne-am găsit": the members hear it, nobody is sent anywhere any more, and the exact share of the lost session
     * ends, giving back the share it replaced.
     */
    fun foundGroup(conversation: String) {
        scope.launch {
            val target = _target.value?.takeIf { it.conversation == conversation && it.mine }
            val session = mine?.takeIf { it.conversation == conversation }
            if (target == null && session == null) return@launch
            if (target != null) _target.value = null
            mine = null
            val seq = session?.lost?.session?.seq ?: 0
            chat.sendLost(conversation, ChatManager.LOST_TTL_MS) { msgId, groupId ->
                Inner.LostSession(msgId, groupId, LostAction.FOUND, seq, 1, battery(), target?.landmark ?: 0)
            }
            if (session != null) {
                chat.unpinMeeting(conversation, session.lost.session.msgId)
                chat.endLostShare(conversation, session.lost)
            }
        }
    }

    /** "Vin acolo": a yes to the meeting place, told only to the one who is lost. It shares nothing. */
    fun comeThere(alert: LostAlert) {
        scope.launch {
            _alerts.update { list -> list - alert }
            val place = placeOf(alert.landmark) ?: return@launch
            chat.pinMeeting(MeetingProposal(alert.conversation, alert.sender, alert.msgId, alert.seq, place, null, alert.untilMs))
            chat.acceptMeeting(alert.conversation, only = alert.sender)
            _target.value = targetOf(alert.conversation, alert.landmark, alert.untilMs, mine = false)
        }
    }

    fun dismiss(alert: LostAlert) {
        _alerts.update { list -> list - alert }
        _searchedBy.update { list -> list - alert }
    }

    // --- the last gasp ---

    /**
     * Each battery reading at [GASP_PERCENT] or less, off the charger, tries to send the last position to whoever we
     * share it with now, at the precision they have, until one goes; nothing when we share with nobody or have no fresh
     * fix. A charger or a level above it arms it again.
     */
    fun onBattery(percent: Int, charging: Boolean) {
        if (charging || percent > GASP_PERCENT) {
            gaspArmed = true
            return
        }
        if (!gaspArmed) return
        scope.launch { if (gaspArmed && sendGasps(percent)) gaspArmed = false }
    }

    /** True when at least one went. */
    private fun sendGasps(percent: Int): Boolean {
        val fix = myPosition()?.takeIf { it.isFresh(clock.wallMs()) } ?: return false
        val frame = frame()
        var sent = false
        for ((recipient, precision) in chat.shareTargetsNow()) {
            if (precision == SharePrecision.ZONE) continue
            val approximate = precision == SharePrecision.APPROXIMATE
            val point = frame.latLon(frame.point(fix.lat, fix.lon, approximate)) ?: continue
            val radius = if (approximate) maxOf(fix.accuracyM.toDouble(), PositionFrame.APPROXIMATE_RADIUS_M) else fix.accuracyM.toDouble()
            val last = LastFix(point.lat, point.lon, fix.timeMs / 1000, AccuracyClass.of(radius))
            if (chat.sendLastGasp(recipient, Inner.LastGasp(last, percent.coerceIn(0, 100)))) sent = true
        }
        return sent
    }

    // --- the last position in a lost report ---

    /**
     * Where [friend] was last, from what they share with us now: their position, which their last gasp also is. Nothing
     * once their share has ended.
     */
    fun lastSeenOf(friend: Long): LastSeen? {
        val now = clock.wallMs()
        val record = positions.value.records.firstOrNull { it.sender == friend && it.expiresAtMs > now } ?: return null
        return LastSeen(record.lat, record.lon, (record.ageMs(now) / 60_000).toInt().coerceAtMost(0xFFFF), record.accuracy)
    }

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

    /** "Sunt bine" to a friend looking for us: it ends the search without telling where we are or how much battery is left. */
    fun imOk(alert: LostAlert) {
        _searchedBy.update { list -> list.filterNot { it.sender == alert.sender } }
        scope.launch {
            chat.sendLost(alert.conversation, ChatManager.LOST_TTL_MS) { msgId, _ ->
                Inner.LostSession(msgId, null, LostAction.FOUND, alert.seq, 1, BatteryLevel.UNKNOWN, 0)
            }
        }
    }

    /** The reporter closed the report, or chose not to. */
    fun doneWith(reply: FoundReply) {
        _found.update { list -> list - reply }
        searches.update { list -> list.filterNot { it.incidentId == reply.incidentId } }
    }

    // --- receiving ---

    /**
     * A lost session as if [sender] had sent it in [conversation], for the demo's showcase command; the mesh hands
     * sessions to [onLost] through the chat.
     */
    fun heard(sender: Long, conversation: String, inner: Inner.LostSession) = onLost(sender, conversation, inner)

    private fun onLost(sender: Long, conversation: String, inner: Inner.LostSession) {
        if (!seen.add(sender to inner.msgId)) return
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        if (inner.groupId == null) return onSearch(sender, conversation, inner)
        val key = sender to conversation
        val last = heardSessions[key]
        when (inner.action) {
            LostAction.START -> {
                // a copy that comes after the FOUND of its session, or after a later session
                if (last != null && (if (last.ended) !after(inner.seq, last.seq) else after(last.seq, inner.seq))) return
                heardSessions[key] = Heard(inner.seq, inner.msgId, ended = false)
                holdShare(sender, inner.minutes)
                if (!heard.tryTake(sender, clock.monoMs())) return
                val alert = LostAlert(sender, conversation, inner.msgId, inner.seq, inner.landmark, inner.battery, clock.wallMs() + inner.minutes * 60_000L)
                _alerts.update { list -> list.filterNot { it.sender == sender && it.conversation == conversation } + alert }
            }
            LostAction.FOUND -> {
                if (last != null && after(last.seq, inner.seq)) return
                heardSessions[key] = Heard(inner.seq, last?.startId ?: 0L, ended = true)
                _alerts.update { list -> list.filterNot { it.sender == sender && it.conversation == conversation } }
                if (_target.value?.let { it.conversation == conversation && !it.mine } == true) _target.value = null
                if (last != null) chat.unpinMeeting(conversation, last.startId)
            }
        }
    }

    /** A lost session without a group: a friend's lost report looks for us, or the friend we look for is fine. */
    private fun onSearch(sender: Long, conversation: String, inner: Inner.LostSession) {
        val now = clock.wallMs()
        when (inner.action) {
            LostAction.START -> {
                if (!heard.tryTake(sender, clock.monoMs())) return
                val alert = LostAlert(sender, conversation, inner.msgId, inner.seq, inner.landmark, inner.battery, now + inner.minutes * 60_000L)
                _searchedBy.update { list -> list.filterNot { it.sender == sender } + alert }
            }
            LostAction.FOUND -> {
                val replies = searches.value.filter { it.friend == sender && now - it.atMs < SEARCH_KEPT_MS }.map { FoundReply(it.incidentId, sender) }
                if (replies.isNotEmpty()) _found.update { list -> list.filterNot { it.friend == sender } + replies }
            }
        }
    }

    /**
     * The sender's lost session implies their exact share with us for its minutes: a phone that reads kind 17 holds
     * it without waiting for the START, and its DELIVERED tells the sender so. A longer exact one we hold stays.
     */
    private fun holdShare(sender: Long, minutes: Int) {
        val now = clock.wallMs()
        val held = positions.value.sessionOf(sender, now)
        if (held != null && held.precision == SharePrecision.EXACT && held.expiresAtMs >= now + minutes * 60_000L) return
        positions.update { it.startedBy(sender, minutes, SharePrecision.EXACT, now) }
    }

    /**
     * Kept only from someone who shares their position with us now, as their position, until that share ends: when it
     * stops, ends or they are no longer a friend, the gasp goes with the position.
     */
    private fun onLastGasp(sender: Long, gasp: Inner.LastGasp) {
        val now = clock.wallMs()
        val session = positions.value.sessionOf(sender, now) ?: return
        val fixAt = minOf(gasp.fix.fixedAt * 1000, now)
        val record = PositionRecord(
            sender, gasp.fix.lat, gasp.fix.lon, gasp.fix.accuracy, PositionMethod.UNKNOWN, null, fixAt, now,
            session.expiresAtMs, 0, NextUpdate.SESSION_ENDED,
        )
        positions.update { it.withPosition(record) }
        val entry = FriendGasp(sender, gasp.fix.lat, gasp.fix.lon, gasp.fix.accuracy, gasp.battery, fixAt, now)
        val data = positions.value
        _gasps.update { list -> (list.filter { it.sender != sender } + entry).filter { holds(it, data) } }
    }

    /** A gasp counts while the sender's position is kept, inside their share, and none newer than the gasp came. */
    private fun holds(gasp: FriendGasp, data: PositionData): Boolean =
        data.records.any { it.sender == gasp.sender && it.fixAtMs <= gasp.fixAtMs }

    private fun placeOf(landmark: Int): MeetingPlace? =
        if (landmark != 0) MeetingPlace.landmark(landmark) else venue.meetingPoint?.let(MeetingPlace::venue)

    private fun targetOf(conversation: String, landmark: Int, untilMs: Long, mine: Boolean): LostTarget? {
        val point = if (landmark != 0) venue.landmark(landmark)?.point else venue.meetingPoint
        return point?.let { LostTarget(conversation, landmark, it, untilMs, mine) }
    }

    private fun isLost(category: Int) = category == IncidentCategory.LOST_FRIEND || category == IncidentCategory.LOST_CHILD

    /** [a] is later than [b] on the byte circle of seq. */
    private fun after(a: Int, b: Int) = ((a - b) and 0xFF) in 1..127

    companion object {
        /** One "M-am pierdut" per person in this long, sent and heard. */
        const val ACTIVATION_INTERVAL_MS = 10 * 60_000L

        /** The exact share a lost session starts. */
        const val SHARE_MIN = 15
        const val GASP_PERCENT = 5

        /** A last gasp is drawn on the map this long after its fix, at most. */
        const val GASP_SHOWN_MS = 2 * 60 * 60_000L
        const val SEARCH_KEPT_MS = 24 * 60 * 60_000L
        private const val MAX_SEARCHES = 20
        private const val MAX_SEEN = 256
    }
}
