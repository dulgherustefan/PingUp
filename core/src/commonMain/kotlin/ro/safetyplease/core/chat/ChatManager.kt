package ro.safetyplease.core.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.ReservedNames
import ro.safetyplease.core.data.BlockedPerson
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.ChatStore
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.GroupMember
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.data.OutboxItem
import ro.safetyplease.core.data.OutgoingShare
import ro.safetyplease.core.data.PositionData
import ro.safetyplease.core.data.PositionRecord
import ro.safetyplease.core.data.PositionStore
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.mesh.WindowLimit
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.GroupMemberWire
import ro.safetyplease.core.protocol.Heading
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.InnerCodec
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.MeetingAction
import ro.safetyplease.core.protocol.MeetingLimits
import ro.safetyplease.core.protocol.NextUpdate
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.PingAction
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.ShareAction
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.venue.GeoPoint
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Private chat over the mesh: crypto_box envelopes, an outbox retried until DELIVERED, PING/PONG, sealed intros (one
 * scan makes both phones friends) and groups by fan-out. Runs on the mesh context; public methods can be called from anywhere.
 */
class ChatManager(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val crypto: Crypto,
    private val identity: Identity,
    private val friends: JsonStore<List<Friend>>,
    private val groups: JsonStore<List<Group>>,
    private val blocked: JsonStore<List<BlockedPerson>>,
    private val chat: ChatStore,
    private val clock: Clock,
    private val random: Random,
    private val myNickname: () -> String,
    private val shareZone: () -> Boolean = { true },
    private val positions: PositionStore = PositionStore.inMemory(),
    /** The event's grid box and day start, read again at every position sent or received. */
    private val positionFrame: () -> PositionFrame = { PositionFrame.NONE },
    private val onIncoming: (message: ChatMessage, senderName: String) -> Unit = { _, _ -> },
) {
    private val attempts = LinkedHashMap<Long, Pair<Long, Long>>()
    private var linkRetry: Job? = null

    /** Every chat packet this phone writes, retries and receipts included. */
    private val sendLimit = WindowLimit(PACKETS_PER_MINUTE, 60_000)

    /** When we last confirmed each (sender, msgId), so copies of one message don't each cost a DELIVERED flood. */
    private val confirmedAt = LinkedHashMap<Pair<Long, Long>, Long>()

    /** Where this phone says it is, as the app last told us, and the zone each friend has been sent. */
    private var myZone = ""
    private val zoneTold = HashMap<Long, String>()

    /** The last fix the app gave us, and when each share's START last went to a recipient that hasn't accepted it. */
    private var lastFix: Fix? = null
    private val policy = PositionPolicy()
    private var positionSeq = 0
    private val startSent = HashMap<Pair<String, Long>, Pair<Int, Long>>()
    private val asked = PerFriendLimit(REQUEST_INTERVAL_MS)
    private val answered = PerFriendLimit(REQUEST_INTERVAL_MS)

    /** When we last asked each friend where they are: their answer is accepted for a while without a session. */
    private val askedAt = HashMap<Long, Long>()

    /** The latest position each friend shares with us, until the session we hold for them ends. */
    val friendPositions: StateFlow<List<PositionRecord>> =
        positions.state.map { it.records }.stateIn(scope, SharingStarted.Eagerly, positions.value.records)

    /** What we share now, and with which conversation. */
    val activeShares: StateFlow<List<OutgoingShare>> =
        positions.state.map { it.shares }.stateIn(scope, SharingStarted.Eagerly, positions.value.shares)

    private val pingsSent = PerFriendLimit(PING_INTERVAL_MS)
    private val pingsHeard = PerFriendLimit(PING_INTERVAL_MS)
    private val comesSent = PerFriendLimit(PING_INTERVAL_MS)
    private val comesHeard = PerFriendLimit(PING_INTERVAL_MS)
    private val proposed = PerFriendLimit(MEETING_INTERVAL_MS)

    /** Our "Vin la tine" waiting for an answer, by friend: its seq, the precision we will share at, when it went. */
    private val comeOut = HashMap<Long, FriendAsk>()
    private var meetingSeq = 0

    /** The meeting points' msgIds already seen, so a retried copy of one that was replaced doesn't come back. */
    private val meetingsSeen = LinkedHashSet<Pair<Long, Long>>()

    private val _pingAsks = MutableStateFlow<List<FriendAsk>>(emptyList())
    private val _comeAsks = MutableStateFlow<List<FriendAsk>>(emptyList())
    private val _lighting = MutableStateFlow<List<Lighting>>(emptyList())
    private val _meetings = MutableStateFlow<List<MeetingProposal>>(emptyList())

    /** "Fă-te văzut" from friends, waiting for the user's answer; each is dropped after [PING_ASK_MS]. */
    val pingAsks: StateFlow<List<FriendAsk>> = _pingAsks

    /** "Vin la tine" from friends, waiting for the user's answer; each is dropped after [COME_ASK_MS]. */
    val comeAsks: StateFlow<List<FriendAsk>> = _comeAsks

    /** Friends who answered our "Fă-te văzut": their torch is lit now. */
    val lighting: StateFlow<List<Lighting>> = _lighting

    /** The meeting point of each conversation, the latest proposed, until it expires. Kept in memory only. */
    val meetings: StateFlow<List<MeetingProposal>> = _meetings

    fun start() {
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            while (isActive) {
                delay(RETRY_TICK_MS)
                retryOutbox(linkTrigger = false)
                positionTick()
                pruneAsks()
            }
        }
    }

    // --- friends ---

    fun myCard(): FriendCard = FriendCard(myNickname(), identity.box.publicKey, identity.sign.publicKey)

    /**
     * Returns false for our own code. An existing friend only gets their nickname updated. Scanning the code of
     * someone blocked is a decision to talk to them again. Either way our card goes to them, so they have us too.
     */
    fun addFriend(card: FriendCard): Boolean {
        val nodeId = card.nodeId
        if (nodeId == identity.nodeId) return false
        unblock(nodeId)
        val friend = Friend(nodeId, card.nickname, card.boxKey.toHex(), card.signKey.toHex(), clock.wallMs())
        friends.update { list ->
            if (list.any { it.nodeId == nodeId }) list.map { if (it.nodeId == nodeId) it.copy(nickname = card.nickname) else it }
            else list + friend
        }
        scope.launch { introduce(nodeId) }
        ping(nodeId)
        return true
    }

    /** Our card, signed for [recipient], through the outbox like a message; it replaces an intro still waiting for them. */
    private fun introduce(recipient: Long) {
        val msgId = newId()
        val nick = myNickname().truncateUtf8(Limits.NICK_BYTES)
        val box = identity.box.publicKey
        val sign = identity.sign.publicKey
        val signature = crypto.sign(InnerCodec.introSignedBytes(recipient, msgId, box, sign, nick), identity.sign.secretKey)
        chat.update { data -> data.copy(outbox = data.outbox.filterNot { it.recipient == recipient && it.sealed }) }
        enqueue(msgId, recipient, InnerCodec.encode(Inner.Intro(msgId, box, sign, nick, signature)), sealed = true)
    }

    fun removeFriend(nodeId: Long) {
        friends.update { list -> list.filterNot { it.nodeId == nodeId } }
        positions.update { it.forget(nodeId) }
        val conversation = Conversations.friend(nodeId)
        chat.update { data ->
            data.copy(
                messages = data.messages.filterNot { it.conversation == conversation },
                outbox = data.outbox.filterNot { it.recipient == nodeId },
            )
        }
    }

    /**
     * Nothing from [nodeId] is opened, shown or answered from now on, and nothing more is sent to them. They leave the
     * friends and every group, here only; they are not told.
     */
    fun block(nodeId: Long) {
        if (nodeId == identity.nodeId || isBlocked(nodeId)) return
        val person = BlockedPerson(nodeId, nameOf(nodeId), clock.wallMs())
        blocked.update { it + person }
        removeFriend(nodeId)
        groups.update { list -> list.map { group -> group.copy(members = group.members.filterNot { it.nodeId == nodeId }) } }
        chat.update { data -> data.copy(outbox = data.outbox.filterNot { it.recipient == nodeId }) }
        zoneTold.remove(nodeId)
    }

    fun unblock(nodeId: Long) {
        if (isBlocked(nodeId)) blocked.update { list -> list.filterNot { it.nodeId == nodeId } }
    }

    // --- sending ---

    fun sendText(conversation: String, text: String) {
        val clean = text.trim().truncateUtf8(Limits.TEXT_BYTES)
        if (clean.isEmpty()) return
        send(conversation, MsgKind.TEXT, text = clean) { id, group -> Inner.Text(id, group, clean) }
    }

    fun sendQuick(conversation: String, code: Int) =
        send(conversation, MsgKind.QUICK, quickCode = code) { id, group -> Inner.Quick(id, group, code) }

    fun sendZone(conversation: String, zone: String, lat: Double?, lon: Double?) =
        send(conversation, MsgKind.ZONE, zone = zone, lat = lat, lon = lon) { id, group ->
            Inner.Zone(id, group, zone.truncateUtf8(Limits.ZONE_BYTES), lat, lon)
        }

    fun ping(friendId: Long) {
        scope.launch { sendDirect(friendId, Inner.Ping(newId())) }
    }

    /** "Unde ești?": asks a friend for their zone. Nothing is kept for a retry; the answer lands in [Friend.lastZone]. */
    fun requestZone(friendId: Long) {
        scope.launch { if (isFriend(friendId)) sendDirect(friendId, Inner.ZoneRequest(newId())) }
    }

    /**
     * The app's word that this phone is now in [zone] (empty: nowhere known). Friends hear it once each, straight from
     * here, and not again until it changes or they ask: there is no timer.
     */
    fun zoneChanged(zone: String) {
        scope.launch {
            if (zone == myZone) return@launch
            myZone = zone
            announceZone()
        }
    }

    private fun announceZone() {
        if (myZone.isEmpty() || !shareZone()) return
        for (friend in friends.value) {
            if (zoneTold[friend.nodeId] == myZone) continue
            if (sendDirect(friend.nodeId, Inner.ZoneUpdate(newId(), myZone))) zoneTold[friend.nodeId] = myZone
        }
    }

    // --- positions (PROTOCOL.md, section 6.6) ---

    /**
     * Shares this phone's position with [conversation], a friend or a group, for [durationMin] minutes (1 to 120) at
     * [precision] ([SharePrecision]). Each recipient gets a START; those whose phone accepts it get positions, the
     * others only the zone. A new share with the same conversation replaces the old one.
     */
    fun startShare(conversation: String, durationMin: Int, precision: Int) {
        scope.launch { beginShare(conversation, durationMin, precision) }
    }

    private fun beginShare(conversation: String, durationMin: Int, precision: Int) {
        val recipients = recipientsOf(conversation)?.second ?: return
        if (recipients.isEmpty() || precision !in SharePrecision.EXACT..SharePrecision.ZONE) return
        val minutes = durationMin.coerceIn(1, PositionData.MAX_SHARE_MIN)
        val share = OutgoingShare(conversation, precision, clock.wallMs() + minutes * 60_000L, nextSeq())
        positions.update { data -> data.copy(shares = data.shares.filterNot { it.conversation == conversation } + share) }
        startSent.keys.removeAll { it.first == conversation }
        policy.reset()
        sharePosition()
    }

    /** Stops at once. Recipients that no other share still reaches get a STOP; the others' phones end it at expiry anyway. */
    fun stopShare(conversation: String) {
        scope.launch {
            val share = positions.value.shares.firstOrNull { it.conversation == conversation } ?: return@launch
            positions.update { data -> data.copy(shares = data.shares - share) }
            startSent.keys.removeAll { it.first == conversation }
            val still = shareTargets(clock.wallMs()).keys
            for (recipient in recipientsOf(conversation)?.second.orEmpty()) {
                if (recipient !in still) sendDirect(recipient, Inner.ShareSession(ShareAction.STOP, 0, share.precision, share.seq))
            }
        }
    }

    /** Another [durationMin] minutes (1 to 120) from now. */
    fun renewShare(conversation: String, durationMin: Int) {
        scope.launch {
            val share = positions.value.shares.firstOrNull { it.conversation == conversation } ?: return@launch
            val minutes = durationMin.coerceIn(1, PositionData.MAX_SHARE_MIN)
            val renewed = share.copy(expiresAtMs = clock.wallMs() + minutes * 60_000L)
            positions.update { data -> data.copy(shares = data.shares.map { if (it.conversation == conversation) renewed else it }) }
            for (recipient in share.accepted) {
                sendDirect(recipient, Inner.ShareSession(ShareAction.RENEW, minutes, share.precision, share.seq))
            }
        }
    }

    /**
     * A fix from the app: [accuracyM] its radius (null if unknown), [headingDeg] the course over ground, [method] a
     * [PositionMethod], [timeMs] when it was taken, on the wall clock. [PositionPolicy] decides whether it goes now.
     */
    fun onFix(lat: Double, lon: Double, accuracyM: Double?, headingDeg: Double?, method: Int, timeMs: Long) {
        scope.launch {
            lastFix = Fix(lat, lon, accuracyM, headingDeg, method.coerceIn(PositionMethod.GNSS, PositionMethod.UNKNOWN), timeMs)
            sharePosition()
        }
    }

    /**
     * "Unde ești?" for a friend's position: at most once a minute per friend. A phone that reads positions answers with
     * one, if it shares with us or answers anyone, and has a fix under 2 minutes old; otherwise with its zone, as before.
     */
    fun requestPosition(friendId: Long) {
        scope.launch {
            if (!isFriend(friendId) || !asked.tryTake(friendId, clock.monoMs())) return@launch
            if (sendDirect(friendId, Inner.ZoneRequest(newId()))) askedAt[friendId] = clock.wallMs()
        }
    }

    /** Whether a friend we don't share with gets our approximate position when they ask. */
    fun setAutoAnswer(enabled: Boolean) {
        positions.update { if (it.autoAnswer == enabled) it else it.copy(autoAnswer = enabled) }
    }

    private class Fix(val lat: Double, val lon: Double, val accuracyM: Double?, val headingDeg: Double?, val method: Int, val timeMs: Long)

    private fun positionTick() {
        val now = clock.wallMs()
        askedAt.entries.removeAll { now - it.value >= ANSWER_WINDOW_MS }
        sharePosition()
    }

    /** Each recipient of a share still running, with the most precise of the shares that reach them. */
    private fun shareTargets(now: Long): Map<Long, Int> {
        val targets = HashMap<Long, Int>()
        for (share in positions.value.shares) {
            if (share.expiresAtMs <= now) continue
            for (recipient in recipientsOf(share.conversation)?.second.orEmpty()) {
                targets[recipient] = minOf(targets[recipient] ?: share.precision, share.precision)
            }
        }
        return targets
    }

    /** What expired ends, START goes to who hasn't accepted yet, and the position goes if the policy says so. */
    private fun sharePosition() {
        val now = clock.wallMs()
        positions.update { it.pruned(now) }
        offerStarts(now)
        val targets = shareTargets(now)
        if (targets.isEmpty()) return policy.reset()
        val best = targets.values.min()
        if (best == SharePrecision.ZONE) return targets.keys.forEach(::tellZone)
        val fix = lastFix ?: return
        val frame = positionFrame()
        val approximate = best == SharePrecision.APPROXIMATE
        val at = frame.latLon(frame.point(fix.lat, fix.lon, approximate)) ?: return
        val accuracyM = radiusOf(fix, approximate)?.roundToInt()
        val keys = liveKeys()
        if (!policy.due(now, at, accuracyM, keys)) return
        val nextUpdate = NextUpdate.of(policy.nextUpdateMs(keys))
        val seq = nextSeq()
        val readers = positions.value.readers
        var sent = false
        for ((recipient, precision) in targets) {
            if (precision == SharePrecision.ZONE || recipient !in readers) tellZone(recipient)
            else if (sendPosition(recipient, positionOf(fix, frame, precision, seq, nextUpdate))) sent = true
        }
        // nothing went (no link, over budget): the next fix tries again instead of waiting for the next heartbeat
        if (sent) policy.sent(now, at, accuracyM)
    }

    /** START until the recipient accepts, a minute apart, [MAX_START_TRIES] times: an older phone never will. */
    private fun offerStarts(now: Long) {
        val mono = clock.monoMs()
        for (share in positions.value.shares) {
            if (share.expiresAtMs <= now) continue
            val minutes = ((share.expiresAtMs - now + 59_999) / 60_000).toInt()
            for (recipient in recipientsOf(share.conversation)?.second.orEmpty()) {
                if (recipient in share.accepted) continue
                val key = share.conversation to recipient
                val tries = startSent[key]
                if (tries != null && (tries.first >= MAX_START_TRIES || mono - tries.second < START_RETRY_MS)) continue
                val start = Inner.ShareSession(ShareAction.START, minutes, share.precision, share.seq)
                if (sendDirect(recipient, start)) startSent[key] = (tries?.first ?: 0) + 1 to mono
            }
        }
    }

    /** The zone instead of a position, for a phone that doesn't read them or a share of the zone only. */
    private fun tellZone(recipient: Long) {
        if (myZone.isEmpty() || zoneTold[recipient] == myZone) return
        if (sendDirect(recipient, Inner.ZoneUpdate(newId(), myZone))) zoneTold[recipient] = myZone
    }

    private fun radiusOf(fix: Fix, approximate: Boolean): Double? =
        if (approximate) maxOf(fix.accuracyM ?: 0.0, PositionFrame.APPROXIMATE_RADIUS_M) else fix.accuracyM

    /** Masked before it is sealed: an approximate position never leaves the phone more precise than its cell. */
    private fun positionOf(fix: Fix, frame: PositionFrame, precision: Int, seq: Int, nextUpdate: Int): Inner.Position {
        val approximate = precision == SharePrecision.APPROXIMATE
        return Inner.Position(
            frame.point(fix.lat, fix.lon, approximate), AccuracyClass.of(radiusOf(fix, approximate)), fix.method,
            frame.fixMinute(fix.timeMs), seq, nextUpdate, if (approximate) Heading.UNKNOWN else Heading.of(fix.headingDeg),
        )
    }

    /** Positions are P2: over budget the packet isn't written, and in the queue it goes after everything else. */
    private fun sendPosition(recipient: Long, position: Inner.Position): Boolean {
        if (engine.state.value.readyLinks == 0) return false
        val key = boxKeyOf(recipient) ?: return false
        return engine.unicastPosition(recipient, crypto.box(InnerCodec.encode(position), key, identity.box.secretKey)) != null
    }

    /** K in [PositionPolicy.intervalMs]: the positions this phone holds, its own included while it shares. */
    private fun liveKeys(): Int = positions.value.records.size + (if (positions.value.shares.isNotEmpty()) 1 else 0)

    private fun nextSeq(): Int {
        positionSeq = (positionSeq + 1) and 0xFF
        return positionSeq
    }

    private fun answerWhereAreYou(friend: Long) {
        if (answerWithPosition(friend)) return
        if (myZone.isNotEmpty() && shareZone()) sendDirect(friend, Inner.ZoneUpdate(newId(), myZone))
    }

    /** True when the question is dealt with: a position went, or one already went to this friend in the last minute. */
    private fun answerWithPosition(friend: Long): Boolean {
        val data = positions.value
        if (friend !in data.readers) return false
        val now = clock.wallMs()
        val precision = shareTargets(now)[friend] ?: SharePrecision.APPROXIMATE.takeIf { data.autoAnswer } ?: return false
        if (precision == SharePrecision.ZONE) return false
        val fix = lastFix?.takeIf { now - it.timeMs < ANSWER_FIX_MAX_AGE_MS } ?: return false
        if (!answered.tryTake(friend, clock.monoMs())) return true
        return sendPosition(friend, positionOf(fix, positionFrame(), precision, nextSeq(), NextUpdate.ON_REQUEST))
    }

    private fun onShareSession(sender: Long, session: Inner.ShareSession) {
        if (!knows(sender)) return
        when (session.action) {
            ShareAction.START, ShareAction.RENEW -> {
                positions.update { it.startedBy(sender, session.durationMin, session.precision, clock.wallMs()) }
                sendDirect(sender, Inner.ShareSession(ShareAction.ACCEPT, session.durationMin, session.precision, session.seq))
            }
            // silent: the friend's position just ends, as at expiry
            ShareAction.STOP -> positions.update { it.stoppedBy(sender) }
            ShareAction.ACCEPT -> onAccepted(sender, session.seq)
            ShareAction.COME_ASK -> onComeAsk(sender, session)
            ShareAction.COME_AGREE -> onComeAgree(sender, session.seq)
        }
    }

    // --- "Fă-te văzut", meeting points and "Vin la tine" (PROTOCOL.md, section 6.6.1) ---

    /**
     * "Fă-te văzut": asks a friend to light their torch, at most once every 2 minutes per friend. Their phone asks
     * them first unless they chose to accept it from friends; if they do, [lighting] says so. False when the limit
     * holds, the friend is unknown or the request could not go.
     */
    suspend fun pingFriend(friendId: Long): Boolean = scope.async {
        val now = clock.monoMs()
        if (!isFriend(friendId) || !pingsSent.ready(friendId, now)) return@async false
        val sent = sendDirect(friendId, Inner.PingRequest(PingAction.ASK, TorchPulse.SECONDS))
        if (sent) pingsSent.tryTake(friendId, now)
        sent
    }.await()

    /** The user's answer to a friend's "Fă-te văzut": yes tells them the torch is lit; no is silent. */
    fun answerPing(friendId: Long, accept: Boolean) {
        scope.launch {
            val ask = _pingAsks.value.firstOrNull { it.sender == friendId } ?: return@launch
            _pingAsks.update { asks -> asks - ask }
            if (accept) sendDirect(friendId, Inner.PingRequest(PingAction.LIGHTING, ask.seconds))
        }
    }

    private fun onPingRequest(sender: Long, request: Inner.PingRequest) {
        if (!isFriend(sender)) return
        val now = clock.wallMs()
        when (request.action) {
            PingAction.ASK -> {
                if (!pingsHeard.tryTake(sender, clock.monoMs())) return
                _pingAsks.update { asks -> asks.filterNot { it.sender == sender } + FriendAsk(sender, now, seconds = request.seconds) }
            }
            PingAction.LIGHTING -> {
                // only an answer to a question we asked lately
                if (pingsSent.ready(sender, clock.monoMs())) return
                _lighting.update { list -> list.filterNot { it.friend == sender } + Lighting(sender, now + request.seconds * 1000L) }
            }
        }
    }

    /**
     * "Vin la tine": asks a friend to share positions both ways for [COME_MIN] minutes. If they accept, their phone
     * starts sharing with us and ours starts with them, at [precision]. At most once every 2 minutes per friend. False
     * when the limit holds, the friend is unknown or the request could not go.
     */
    suspend fun requestComing(friendId: Long, precision: Int = SharePrecision.EXACT): Boolean = scope.async {
        val now = clock.monoMs()
        if (!isFriend(friendId) || !comesSent.ready(friendId, now) || precision !in SharePrecision.EXACT..SharePrecision.ZONE) {
            return@async false
        }
        val seq = nextSeq()
        if (!sendDirect(friendId, Inner.ShareSession(ShareAction.COME_ASK, COME_MIN, precision, seq))) return@async false
        comesSent.tryTake(friendId, now)
        comeOut[friendId] = FriendAsk(friendId, clock.wallMs(), seq, precision)
        true
    }.await()

    /** The user's answer to a friend's "Vin la tine": yes starts our share with them at [precision]; no is silent. */
    fun answerComing(friendId: Long, accept: Boolean, precision: Int = SharePrecision.EXACT) {
        scope.launch {
            val ask = _comeAsks.value.firstOrNull { it.sender == friendId } ?: return@launch
            _comeAsks.update { asks -> asks - ask }
            if (!accept || precision !in SharePrecision.EXACT..SharePrecision.ZONE) return@launch
            shareForComing(friendId, precision)
            sendDirect(friendId, Inner.ShareSession(ShareAction.COME_AGREE, COME_MIN, precision, ask.seq))
        }
    }

    private fun onComeAsk(sender: Long, session: Inner.ShareSession) {
        if (!isFriend(sender) || !comesHeard.tryTake(sender, clock.monoMs())) return
        val ask = FriendAsk(sender, clock.wallMs(), session.seq, session.precision)
        _comeAsks.update { asks -> asks.filterNot { it.sender == sender } + ask }
    }

    private fun onComeAgree(sender: Long, seq: Int) {
        val out = comeOut[sender]?.takeIf { it.seq == seq && clock.wallMs() - it.atMs < COME_ASK_MS } ?: return
        comeOut.remove(sender)
        shareForComing(sender, out.precision)
    }

    /** A longer share already running with that friend is left as it is. */
    private fun shareForComing(friendId: Long, precision: Int) {
        val conversation = Conversations.friend(friendId)
        val until = clock.wallMs() + COME_MIN * 60_000L
        val running = positions.value.shares.firstOrNull { it.conversation == conversation }
        if (running != null && running.expiresAtMs >= until && running.precision <= precision) return
        beginShare(conversation, COME_MIN, precision)
    }

    /**
     * Proposes [place] as the meeting point of [conversation], a friend or a group, for [meetAtSec] (Unix seconds, a
     * moment; null: no time), shown for [expiryMin] minutes (1 to 720). It replaces the conversation's meeting point. At
     * most one a minute per conversation; it goes with the retries of a message. False when the limit holds or nobody is
     * there to tell.
     */
    suspend fun proposeMeeting(conversation: String, place: MeetingPlace, meetAtSec: Long?, expiryMin: Int): Boolean = scope.async {
        val (groupId, recipients) = recipientsOf(conversation) ?: return@async false
        if (recipients.isEmpty()) return@async false
        if (!proposed.tryTake(groupId ?: recipients.single(), clock.monoMs())) return@async false
        val minutes = expiryMin.coerceIn(1, MeetingLimits.MAX_EXPIRY_MIN)
        val meetAt = meetAtSec?.takeIf { it in 1..MeetingLimits.MAX_MEET_AT }
        meetingSeq = (meetingSeq + 1) and 0xFF
        val inner = Inner.MeetingPoint(
            newId(), groupId, meetingSeq, place.landmark, place.point?.lat, place.point?.lon, place.venuePoint, meetAt, minutes,
        )
        val proposal = MeetingProposal(
            conversation, identity.nodeId, inner.msgId, inner.seq, place, meetAt, clock.wallMs() + minutes * 60_000L,
        )
        seenMeeting(identity.nodeId, inner.msgId)
        _meetings.update { list -> list.filterNot { it.conversation == conversation } + proposal }
        val bytes = InnerCodec.encode(inner)
        for (recipient in recipients) enqueue(inner.msgId, recipient, bytes)
        true
    }.await()

    /** Says yes to the meeting point of [conversation]: every member is told, with the retries of a message. */
    fun acceptMeeting(conversation: String) {
        scope.launch {
            val proposal = _meetings.value.firstOrNull { it.conversation == conversation } ?: return@launch
            if (proposal.proposer == identity.nodeId || identity.nodeId in proposal.accepted) return@launch
            val (groupId, recipients) = recipientsOf(conversation) ?: return@launch
            _meetings.update { list -> list.map { if (it == proposal) it.copy(accepted = it.accepted + identity.nodeId) else it } }
            val reply = Inner.MeetingReply(newId(), groupId, MeetingAction.ACCEPT, proposal.msgId)
            val bytes = InnerCodec.encode(reply)
            for (recipient in recipients) enqueue(reply.msgId, recipient, bytes)
        }
    }

    /** Takes back our own meeting point of [conversation]. */
    fun withdrawMeeting(conversation: String) {
        scope.launch {
            val proposal = _meetings.value.firstOrNull { it.conversation == conversation && it.proposer == identity.nodeId } ?: return@launch
            val (groupId, recipients) = recipientsOf(conversation) ?: return@launch
            _meetings.update { list -> list - proposal }
            val reply = Inner.MeetingReply(newId(), groupId, MeetingAction.WITHDRAW, proposal.msgId)
            val bytes = InnerCodec.encode(reply)
            for (recipient in recipients) enqueue(reply.msgId, recipient, bytes)
        }
    }

    /** The middle of where the members of [conversation] are known to be, us included; null under two points. */
    fun meetingCentroid(conversation: String): GeoPoint? {
        val members = recipientsOf(conversation)?.second?.toSet() ?: return null
        val points = friendPositions.value.filter { it.sender in members }.map { GeoPoint(it.lat, it.lon) }
        return centroid(points + listOfNotNull(lastFix?.let { GeoPoint(it.lat, it.lon) }))
    }

    /** Pins [proposal] as if its proposer had sent it; for the debug demo, whose friends are not on the mesh. */
    fun pinMeeting(proposal: MeetingProposal) {
        scope.launch { _meetings.update { list -> list.filterNot { it.conversation == proposal.conversation } + proposal } }
    }

    private fun onMeetingPoint(packet: Packet, inner: Inner.MeetingPoint) {
        val sender = packet.sender
        val conversation = conversationOf(sender, inner.groupId) ?: return
        confirm(sender, inner.msgId)
        if (!seenMeeting(sender, inner.msgId)) return
        // a retried copy can arrive long after its time
        if (MeetingTime.isLate(inner.meetAt, clock.wallMs() / 1000)) return
        val current = _meetings.value.firstOrNull { it.conversation == conversation }
        // of two from the same proposer the later seq wins, whichever arrives last
        if (current != null && current.proposer == sender && ((inner.seq - current.seq) and 0xFF) !in 1..127) return
        val place = if (inner.landmark != 0) MeetingPlace.landmark(inner.landmark)
        else MeetingPlace(point = GeoPoint(inner.lat!!, inner.lon!!), venuePoint = inner.venuePoint)
        val proposal = MeetingProposal(
            conversation, sender, inner.msgId, inner.seq, place, inner.meetAt, clock.wallMs() + inner.expiryMin * 60_000L,
        )
        _meetings.update { list -> list.filterNot { it.conversation == conversation } + proposal }
    }

    private fun onMeetingReply(packet: Packet, reply: Inner.MeetingReply) {
        val sender = packet.sender
        val conversation = conversationOf(sender, reply.groupId) ?: return
        confirm(sender, reply.msgId)
        val proposal = _meetings.value.firstOrNull { it.conversation == conversation && it.msgId == reply.proposalId } ?: return
        when (reply.action) {
            MeetingAction.ACCEPT -> if (sender != proposal.proposer && sender !in proposal.accepted) {
                _meetings.update { list -> list.map { if (it == proposal) it.copy(accepted = it.accepted + sender) else it } }
            }
            MeetingAction.WITHDRAW -> if (sender == proposal.proposer) _meetings.update { list -> list - proposal }
        }
    }

    /** False if this meeting point was seen before. */
    private fun seenMeeting(sender: Long, msgId: Long): Boolean {
        if (!meetingsSeen.add(sender to msgId)) return false
        while (meetingsSeen.size > 256) meetingsSeen.remove(meetingsSeen.first())
        return true
    }

    /** A group message from one of its members, or a direct one from a friend; null for anyone else. */
    private fun conversationOf(sender: Long, groupId: Long?): String? {
        if (groupId == null) return if (isFriend(sender)) Conversations.friend(sender) else null
        val group = groups.value.firstOrNull { it.id == groupId } ?: return null
        return if (group.members.any { it.nodeId == sender }) Conversations.group(groupId) else null
    }

    /** What was not answered in time is dropped, as are meeting points past their time and torches gone dark. */
    private fun pruneAsks() {
        val now = clock.wallMs()
        val friendIds = friends.value.map { it.nodeId }.toSet()
        _pingAsks.update { asks -> asks.filter { it.sender in friendIds && now - it.atMs < PING_ASK_MS } }
        _comeAsks.update { asks -> asks.filter { it.sender in friendIds && now - it.atMs < COME_ASK_MS } }
        _lighting.update { list -> list.filter { it.untilMs > now } }
        _meetings.update { list -> list.filter { it.expiresAtMs > now && recipientsOf(it.conversation) != null } }
        comeOut.entries.removeAll { now - it.value.atMs >= COME_ASK_MS }
    }

    /** The recipient reads positions: it gets ours now, without waiting for the next move. */
    private fun onAccepted(sender: Long, seq: Int) {
        val share = positions.value.shares.firstOrNull { share ->
            share.seq == seq && sender !in share.accepted && sender in recipientsOf(share.conversation)?.second.orEmpty()
        } ?: return
        positions.update { data -> data.copy(shares = data.shares.map { if (it == share) it.copy(accepted = it.accepted + sender) else it }) }
        val precision = shareTargets(clock.wallMs())[sender] ?: return
        val fix = lastFix ?: return
        if (precision == SharePrecision.ZONE) return
        val nextUpdate = NextUpdate.of(policy.nextUpdateMs(liveKeys()))
        sendPosition(sender, positionOf(fix, positionFrame(), precision, nextSeq(), nextUpdate))
    }

    /** Kept only inside a session the sender started, or for a while after we asked them. */
    private fun onPosition(packet: Packet, position: Inner.Position) {
        val sender = packet.sender
        if (!knows(sender)) return
        val now = clock.wallMs()
        val expires = positions.value.sessionOf(sender, now)?.expiresAtMs
            ?: askedAt[sender]?.takeIf { now - it < ANSWER_WINDOW_MS }?.let { now + ANSWER_KEPT_MS }
            ?: return
        val frame = positionFrame()
        val at = frame.latLon(position.point) ?: return
        val record = PositionRecord(
            sender, at.lat, at.lon, position.accuracy, position.method, Heading.degrees(position.heading),
            frame.fixTime(position.fixMinute, now), now, expires, position.seq, position.nextUpdate, packet.hops,
        )
        positions.update { it.withPosition(record) }
    }

    /** Deletes the message from this phone only; if it was ours and still in transit, it's no longer retried. */
    fun deleteMessage(message: ChatMessage) {
        chat.update { data ->
            data.copy(
                messages = data.messages.filterNot {
                    it.conversation == message.conversation && it.senderId == message.senderId && it.msgId == message.msgId
                },
                outbox = if (message.fromMe) data.outbox.filterNot { it.msgId == message.msgId } else data.outbox,
            )
        }
    }

    /**
     * Whatever is still in the outbox goes out now, skipping the retry delay; anything expired after 24 h is sent again as a new message.
     */
    fun resend(message: ChatMessage) {
        if (!message.fromMe || message.status == MsgStatus.DELIVERED) return
        scope.launch {
            val pending = chat.value.outbox.filter { it.msgId == message.msgId }
            if (pending.isNotEmpty()) {
                pending.forEach(::attempt)
                return@launch
            }
            deleteMessage(message)
            when (message.kind) {
                MsgKind.TEXT -> sendText(message.conversation, message.text)
                MsgKind.QUICK -> sendQuick(message.conversation, message.quickCode)
                MsgKind.ZONE -> sendZone(message.conversation, message.zone, message.lat, message.lon)
                MsgKind.SYSTEM, MsgKind.LEFT -> Unit
            }
        }
    }

    fun markRead(conversation: String) {
        if (chat.value.messages.none { it.conversation == conversation && !it.read }) return
        chat.update { data ->
            data.copy(messages = data.messages.map { if (it.conversation == conversation && !it.read) it.copy(read = true) else it })
        }
    }

    fun createGroup(name: String, memberIds: List<Long>) {
        scope.launch {
            val chosen = friends.value.filter { it.nodeId in memberIds }.take(Limits.GROUP_MAX_MEMBERS - 1)
            if (chosen.isEmpty()) return@launch
            val groupId = newId()
            val groupName = name.trim().truncateUtf8(Limits.GROUP_NAME_BYTES).ifEmpty { "Grup" }
            val me = GroupMember(identity.nodeId, myNickname(), identity.box.publicKey.toHex())
            val members = listOf(me) + chosen.map { GroupMember(it.nodeId, it.nickname, it.boxKey) }
            groups.update { it + Group(groupId, groupName, members, clock.wallMs()) }
            for (target in chosen) {
                val others = chosen.filter { it.nodeId != target.nodeId }.map {
                    GroupMemberWire(it.nodeId, it.boxKey.hexToBytes(), it.nickname.truncateUtf8(Limits.GROUP_NICK_BYTES))
                }
                val invite = Inner.GroupInvite(newId(), groupId, groupName, others)
                enqueue(invite.msgId, target.nodeId, InnerCodec.encode(invite))
            }
        }
    }

    /**
     * Leaves for real: the other members are told, with the same retries as a message, so they stop sending here. The
     * group, its history and what was still being sent to it are gone from this phone at once.
     */
    fun leaveGroup(groupId: Long) {
        scope.launch {
            val group = groups.value.firstOrNull { it.id == groupId } ?: return@launch
            val conversation = Conversations.group(groupId)
            groups.update { list -> list.filterNot { it.id == groupId } }
            chat.update { data ->
                data.copy(
                    messages = data.messages.filterNot { it.conversation == conversation },
                    outbox = data.outbox.filterNot { groupIdOf(it) == groupId },
                )
            }
            val left = Inner.LeftGroup(newId(), groupId)
            val inner = InnerCodec.encode(left)
            for (member in group.members) {
                if (member.nodeId != identity.nodeId) enqueue(left.msgId, member.nodeId, inner, member.boxKey)
            }
        }
    }

    private fun groupIdOf(item: OutboxItem): Long? = when (val inner = InnerCodec.decode(item.innerHex.hexToBytes())) {
        is Inner.Text -> inner.groupId
        is Inner.Quick -> inner.groupId
        is Inner.Zone -> inner.groupId
        is Inner.GroupInvite -> inner.groupId
        is Inner.LeftGroup -> inner.groupId
        is Inner.MeetingPoint -> inner.groupId
        is Inner.MeetingReply -> inner.groupId
        is Inner.LostSession -> inner.groupId
        is Inner.CheckinMissed -> inner.groupId
        else -> null
    }

    private fun send(
        conversation: String,
        kind: MsgKind,
        text: String = "",
        quickCode: Int = 0,
        zone: String = "",
        lat: Double? = null,
        lon: Double? = null,
        build: (msgId: Long, groupId: Long?) -> Inner,
    ) {
        scope.launch {
            val (groupId, recipients) = recipientsOf(conversation) ?: return@launch
            if (recipients.isEmpty()) return@launch
            val msgId = newId()
            val message = ChatMessage(
                msgId = msgId, conversation = conversation, senderId = identity.nodeId, fromMe = true, kind = kind,
                text = text, quickCode = quickCode, zone = zone, lat = lat, lon = lon,
                timeMs = clock.wallMs(), status = MsgStatus.QUEUED, recipients = recipients.size,
            )
            chat.update { it.copy(messages = (it.messages + message).takeLast(MAX_MESSAGES)) }
            val inner = InnerCodec.encode(build(msgId, groupId))
            for (recipient in recipients) enqueue(msgId, recipient, inner)
        }
    }

    private fun recipientsOf(conversation: String): Pair<Long?, List<Long>>? {
        if (Conversations.isGroup(conversation)) {
            val group = groups.value.firstOrNull { Conversations.group(it.id) == conversation } ?: return null
            return group.id to group.members.map { it.nodeId }.filter { it != identity.nodeId }
        }
        val friend = friends.value.firstOrNull { Conversations.friend(it.nodeId) == conversation } ?: return null
        return null to listOf(friend.nodeId)
    }

    private fun enqueue(msgId: Long, recipient: Long, inner: ByteArray, boxKey: String = "", sealed: Boolean = false) {
        val item = OutboxItem(msgId, recipient, inner.toHex(), clock.wallMs(), boxKey = boxKey, sealed = sealed)
        chat.update { it.copy(outbox = it.outbox + item) }
        attempt(item)
    }

    /**
     * Every attempt is a new packet (new id, new nonce) so relays don't drop it as a duplicate. A message waits its
     * turn while [MAX_IN_FLIGHT] others are being tried, and any attempt waits while the phone is over its chat budget.
     */
    private fun attempt(item: OutboxItem) {
        if (engine.state.value.readyLinks == 0) return
        if (item.attempts == 0 && chat.value.outbox.count { it.attempts > 0 } >= MAX_IN_FLIGHT) return
        val key = item.boxKey.takeIf { it.isNotEmpty() }?.hexToBytes() ?: boxKeyOf(item.recipient) ?: return
        if (!sendLimit.tryAdd(clock.monoMs())) return
        val inner = item.innerHex.hexToBytes()
        val payload = if (item.sealed) crypto.seal(inner, key) else crypto.box(inner, key, identity.box.secretKey)
        val packet = engine.unicast(PacketType.PRIVATE, item.recipient, payload)
        attempts[packet.id] = item.msgId to item.recipient
        while (attempts.size > 512) attempts.remove(attempts.keys.first())
        val now = clock.wallMs()
        chat.update { data ->
            data.copy(outbox = data.outbox.map {
                if (it.msgId == item.msgId && it.recipient == item.recipient) it.copy(attempts = it.attempts + 1, lastAttemptAt = now)
                else it
            })
        }
    }

    /** False if it didn't go: no link, no key, or over the chat budget. */
    private fun sendDirect(recipient: Long, inner: Inner): Boolean {
        if (engine.state.value.readyLinks == 0) return false
        val key = boxKeyOf(recipient) ?: return false
        if (!sendLimit.tryAdd(clock.monoMs())) return false
        engine.unicast(PacketType.PRIVATE, recipient, crypto.box(InnerCodec.encode(inner), key, identity.box.secretKey))
        return true
    }

    private fun retryOutbox(linkTrigger: Boolean) {
        val now = clock.wallMs()
        val expired = chat.value.outbox.filter { now - it.createdAt > OUTBOX_TTL_MS || gaveUp(it, now) }
        if (expired.isNotEmpty()) {
            val failed = expired.map { it.msgId }.toSet()
            chat.update { data ->
                data.copy(
                    outbox = data.outbox - expired.toSet(),
                    messages = data.messages.map {
                        if (it.fromMe && it.msgId in failed && it.status != MsgStatus.DELIVERED) it.copy(status = MsgStatus.FAILED) else it
                    },
                )
            }
        }
        if (engine.state.value.readyLinks == 0) return
        for (item in chat.value.outbox) {
            if (item.attempts >= MAX_ATTEMPTS) continue
            val backoff = backoffMs(item.attempts)
            // in a crowd links come and go every few seconds; a new one brings a retry forward at most once per window
            val wait = if (linkTrigger) minOf(backoff, LINK_RETRY_WINDOW_MS) else backoff
            if (item.attempts == 0 || now - item.lastAttemptAt >= wait) attempt(item)
        }
    }

    /** The last attempt had as long as the next one would have waited and no DELIVERED came back. */
    private fun gaveUp(item: OutboxItem, now: Long): Boolean =
        item.attempts >= MAX_ATTEMPTS && now - item.lastAttemptAt >= backoffMs(item.attempts)

    // --- lost from the group and the last gasp (PROTOCOL.md, section 6.6.2), read by position/Regasire ---

    internal var onLost: (sender: Long, conversation: String, inner: Inner.LostSession) -> Unit = { _, _, _ -> }
    internal var onLastGasp: (sender: Long, inner: Inner.LastGasp) -> Unit = { _, _ -> }

    /** A lost session to every member of [conversation], with the retries of a message; null when nobody is there. */
    internal suspend fun sendLost(conversation: String, build: (msgId: Long, groupId: Long?) -> Inner.LostSession): Inner.LostSession? =
        scope.async {
            val (groupId, recipients) = recipientsOf(conversation) ?: return@async null
            if (recipients.isEmpty()) return@async null
            val inner = build(newId(), groupId)
            val bytes = InnerCodec.encode(inner)
            for (recipient in recipients) enqueue(inner.msgId, recipient, bytes)
            inner
        }.await()

    /** Once, never retried; false if it didn't go. Call on the mesh context. */
    internal fun sendLastGasp(recipient: Long, gasp: Inner.LastGasp): Boolean = sendDirect(recipient, gasp)

    /** Who our shares reach now, each with the most precise of them. Call on the mesh context. */
    internal fun shareTargetsNow(): Map<Long, Int> = shareTargets(clock.wallMs())

    internal fun recipientsNow(conversation: String): List<Long> = recipientsOf(conversation)?.second.orEmpty()

    /** An exact share of [minutes] with [conversation], unless a longer exact one runs already. Call on the mesh context. */
    internal fun shareExactFor(conversation: String, minutes: Int) {
        val running = positions.value.shares.firstOrNull { it.conversation == conversation }
        if (running != null && running.precision == SharePrecision.EXACT && running.expiresAtMs >= clock.wallMs() + minutes * 60_000L) return
        beginShare(conversation, minutes, SharePrecision.EXACT)
    }

    // --- receiving ---

    private fun onEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.Received -> if (event.packet.type == PacketType.PRIVATE) onPrivate(event.packet)
            is MeshEvent.Sent -> onSent(event.packetId)
            is MeshEvent.PeerLinked -> {
                // a new neighbor is a new chance for undelivered messages; wait a moment for links to settle
                if (linkRetry?.isActive != true) {
                    linkRetry = scope.launch {
                        delay(LINK_SETTLE_MS)
                        retryOutbox(linkTrigger = true)
                        announceZone()
                    }
                }
            }
            is MeshEvent.PeerUnlinked -> Unit
        }
    }

    private fun onSent(packetId: Long) {
        val (msgId, _) = attempts.remove(packetId) ?: return
        chat.update { data ->
            data.copy(messages = data.messages.map {
                if (it.fromMe && it.msgId == msgId && it.status == MsgStatus.QUEUED) it.copy(status = MsgStatus.SENT) else it
            })
        }
    }

    private fun onPrivate(packet: Packet) {
        val sender = packet.sender
        if (isBlocked(sender)) return
        val plain = boxKeyOf(sender)?.let { crypto.boxOpen(packet.payload, it, identity.box.secretKey) }
        if (plain == null) {
            // not boxed by anyone we know: all a stranger may send is a sealed intro
            val intro = crypto.sealOpen(packet.payload, identity.box.publicKey, identity.box.secretKey)
                ?.let(InnerCodec::decode) as? Inner.Intro
            if (intro != null) onIntro(packet, intro)
            return
        }
        val inner = InnerCodec.decode(plain) ?: return
        touch(sender, packet.hops)
        if (isOneOne(inner) && knows(sender)) positions.update { it.withReader(sender) }
        when (inner) {
            is Inner.Text -> onMessage(packet, inner.msgId, inner.groupId, MsgKind.TEXT, text = inner.text)
            is Inner.Quick -> onMessage(packet, inner.msgId, inner.groupId, MsgKind.QUICK, quickCode = inner.code)
            is Inner.Zone -> onMessage(packet, inner.msgId, inner.groupId, MsgKind.ZONE, zone = inner.zone, lat = inner.lat, lon = inner.lon)
            is Inner.Ping -> if (isFriend(sender)) sendDirect(sender, Inner.Pong(newId(), inner.msgId))
            is Inner.Pong -> Unit
            is Inner.Delivered -> onDelivered(sender, inner.deliveredId)
            is Inner.GroupInvite -> onInvite(sender, inner)
            is Inner.LeftGroup -> onLeft(sender, inner)
            is Inner.ZoneRequest -> if (isFriend(sender)) answerWhereAreYou(sender)
            is Inner.ZoneUpdate -> setLastZone(sender, inner.zone)
            is Inner.Position -> onPosition(packet, inner)
            is Inner.ShareSession -> onShareSession(sender, inner)
            is Inner.PingRequest -> onPingRequest(sender, inner)
            is Inner.MeetingPoint -> onMeetingPoint(packet, inner)
            is Inner.MeetingReply -> onMeetingReply(packet, inner)
            is Inner.LostSession -> conversationOf(sender, inner.groupId)?.let { conversation ->
                confirm(sender, inner.msgId)
                onLost(sender, conversation, inner)
            }
            is Inner.LastGasp -> if (knows(sender)) onLastGasp(sender, inner)
            // decoded for the feature that will read it; until then dropped, unanswered, as an older phone does
            is Inner.CheckinMissed -> Unit
            // an intro only ever comes sealed
            is Inner.Intro -> Unit
        }
    }

    private fun onMessage(
        packet: Packet,
        msgId: Long,
        groupId: Long?,
        kind: MsgKind,
        text: String = "",
        quickCode: Int = 0,
        zone: String = "",
        lat: Double? = null,
        lon: Double? = null,
    ) {
        val sender = packet.sender
        val conversation: String
        if (groupId != null) {
            val group = groups.value.firstOrNull { it.id == groupId } ?: return
            if (group.members.none { it.nodeId == sender }) return
            conversation = Conversations.group(groupId)
        } else {
            if (!isFriend(sender)) return
            conversation = Conversations.friend(sender)
        }
        confirm(sender, msgId)
        if (chat.value.messages.any { it.msgId == msgId && it.senderId == sender && !it.fromMe }) return
        val message = ChatMessage(
            msgId = msgId, conversation = conversation, senderId = sender, fromMe = false, kind = kind,
            text = text, quickCode = quickCode, zone = zone, lat = lat, lon = lon,
            timeMs = clock.wallMs(), status = MsgStatus.RECEIVED, hops = packet.hops, read = false,
        )
        chat.update { it.copy(messages = (it.messages + message).takeLast(MAX_MESSAGES)) }
        if (kind == MsgKind.ZONE && groupId == null) setLastZone(sender, zone)
        onIncoming(message, nameOf(sender))
    }

    /**
     * Someone scanned our code. The keys must be the sender's and the signature made for us; then the sender is a
     * friend, under a neutral name if theirs passes for staff, since nothing in an intro proves it.
     */
    private fun onIntro(packet: Packet, intro: Inner.Intro) {
        val sender = packet.sender
        if (Identity.nodeIdOf(intro.signKey, intro.boxKey) != sender) return
        val signed = InnerCodec.introSignedBytes(identity.nodeId, intro.msgId, intro.boxKey, intro.signKey, intro.nickname)
        if (!crypto.verify(intro.signature, signed, intro.signKey)) return
        val known = isFriend(sender)
        val staffName = ReservedNames.imitatesStaff(intro.nickname)
        val nickname = if (staffName) sender.toHex().take(8) else intro.nickname
        val now = clock.wallMs()
        friends.update { list ->
            if (known) list.map { if (it.nodeId == sender && !staffName) it.copy(nickname = nickname) else it }
            else list + Friend(sender, nickname, intro.boxKey.toHex(), intro.signKey.toHex(), now)
        }
        touch(sender, packet.hops)
        confirm(sender, intro.msgId)
        if (known) return
        val notice = ChatMessage(
            msgId = intro.msgId, conversation = Conversations.friend(sender), senderId = sender, fromMe = false,
            kind = MsgKind.SYSTEM, timeMs = now, status = MsgStatus.RECEIVED, read = false,
        )
        chat.update { it.copy(messages = (it.messages + notice).takeLast(MAX_MESSAGES)) }
        onIncoming(notice, nickname)
    }

    private fun setLastZone(sender: Long, zone: String) {
        if (zone.isEmpty() || !isFriend(sender)) return
        val now = clock.wallMs()
        friends.update { list -> list.map { if (it.nodeId == sender) it.copy(lastZone = zone, lastZoneAt = now) else it } }
    }

    private fun onLeft(sender: Long, left: Inner.LeftGroup) {
        val group = groups.value.firstOrNull { it.id == left.groupId }
        // a copy that comes after the member was removed means our DELIVERED got lost
        if (group == null || group.members.none { it.nodeId == sender }) {
            if (isFriend(sender)) confirm(sender, left.msgId)
            return
        }
        confirm(sender, left.msgId)
        val name = nameOf(sender)
        groups.update { list ->
            list.map { if (it.id == left.groupId) it.copy(members = it.members.filterNot { m -> m.nodeId == sender }) else it }
        }
        val conversation = Conversations.group(left.groupId)
        val dropped = chat.value.outbox.filter { it.recipient == sender && groupIdOf(it) == left.groupId }
        val notice = ChatMessage(
            msgId = left.msgId, conversation = conversation, senderId = sender, fromMe = false,
            kind = MsgKind.LEFT, text = name, timeMs = clock.wallMs(), status = MsgStatus.RECEIVED,
        )
        chat.update { data ->
            val droppedIds = dropped.map { it.msgId }.toSet()
            data.copy(
                outbox = data.outbox - dropped.toSet(),
                // what was still on its way to them no longer counts them as a recipient
                messages = data.messages.map {
                    if (it.fromMe && it.conversation == conversation && it.msgId in droppedIds) {
                        val recipients = it.recipients - 1
                        it.copy(
                            recipients = recipients,
                            status = if (recipients > 0 && it.delivered >= recipients) MsgStatus.DELIVERED else it.status,
                        )
                    } else it
                } + notice,
            )
        }
    }

    private fun onDelivered(sender: Long, msgId: Long) {
        if (chat.value.outbox.none { it.msgId == msgId && it.recipient == sender }) return
        chat.update { data ->
            data.copy(
                outbox = data.outbox.filterNot { it.msgId == msgId && it.recipient == sender },
                messages = data.messages.map {
                    if (it.fromMe && it.msgId == msgId) {
                        val delivered = it.delivered + 1
                        it.copy(delivered = delivered, status = if (delivered >= it.recipients) MsgStatus.DELIVERED else it.status)
                    } else it
                },
            )
        }
    }

    private fun onInvite(sender: Long, invite: Inner.GroupInvite) {
        val inviter = friends.value.firstOrNull { it.nodeId == sender } ?: return
        confirm(sender, invite.msgId)
        if (groups.value.any { it.id == invite.groupId }) return
        val me = GroupMember(identity.nodeId, myNickname(), identity.box.publicKey.toHex())
        val others = invite.members.filter { it.nodeId != identity.nodeId && it.nodeId != sender && !isBlocked(it.nodeId) }
            .map { GroupMember(it.nodeId, it.nickname, it.boxKey.toHex()) }
        val members = listOf(GroupMember(sender, inviter.nickname, inviter.boxKey), me) + others
        groups.update { it + Group(invite.groupId, invite.name, members, clock.wallMs()) }
        val notice = ChatMessage(
            msgId = invite.msgId, conversation = Conversations.group(invite.groupId), senderId = sender, fromMe = false,
            kind = MsgKind.SYSTEM, timeMs = clock.wallMs(), status = MsgStatus.RECEIVED, read = false,
        )
        chat.update { it.copy(messages = (it.messages + notice).takeLast(MAX_MESSAGES)) }
        onIncoming(notice, inviter.nickname)
    }

    /**
     * A copy of a message we already confirmed is answered again only after [CONFIRM_REPEAT_MS]: a retry that
     * late means the first DELIVERED was lost, while closer copies (replays, retries crossing it) get nothing.
     */
    private fun confirm(sender: Long, msgId: Long) {
        val key = sender to msgId
        val now = clock.wallMs()
        val last = confirmedAt[key]
        if (last != null && now - last < CONFIRM_REPEAT_MS) return
        // a receipt that didn't go is sent when the sender retries
        if (!sendDirect(sender, Inner.Delivered(newId(), msgId))) return
        confirmedAt.remove(key)
        confirmedAt[key] = now
        while (confirmedAt.size > 512) confirmedAt.remove(confirmedAt.keys.first())
    }

    // --- helpers ---

    private fun isFriend(nodeId: Long) = friends.value.any { it.nodeId == nodeId }

    /** A friend, or someone in a group with us: who may share a position with us. */
    private fun knows(nodeId: Long) = isFriend(nodeId) || groups.value.any { group -> group.members.any { it.nodeId == nodeId } }

    /** A kind added in 1.1: whoever sends one gets positions from now on, instead of only the zone. */
    private fun isOneOne(inner: Inner): Boolean = inner is Inner.LeftGroup || inner is Inner.ZoneRequest ||
        inner is Inner.ZoneUpdate || inner is Inner.Position || inner is Inner.ShareSession || inner is Inner.PingRequest ||
        inner is Inner.MeetingPoint || inner is Inner.MeetingReply || inner is Inner.LostSession || inner is Inner.LastGasp ||
        inner is Inner.CheckinMissed

    fun isBlocked(nodeId: Long) = blocked.value.any { it.nodeId == nodeId }

    /** A group we left is gone, but a member still has to be able to answer the notice that we left. */
    private fun boxKeyOf(nodeId: Long): ByteArray? {
        friends.value.firstOrNull { it.nodeId == nodeId }?.let { return it.boxKey.hexToBytes() }
        for (group in groups.value) {
            group.members.firstOrNull { it.nodeId == nodeId }?.let { return it.boxKey.hexToBytes() }
        }
        return chat.value.outbox.firstOrNull { it.recipient == nodeId && it.boxKey.isNotEmpty() }?.boxKey?.hexToBytes()
    }

    fun nameOf(nodeId: Long): String {
        friends.value.firstOrNull { it.nodeId == nodeId }?.let { return it.nickname }
        for (group in groups.value) group.members.firstOrNull { it.nodeId == nodeId }?.let { return it.nickname }
        return nodeId.toHex().take(8)
    }

    /** Any authentic packet from a friend refreshes their presence; there's no periodic presence broadcast. */
    private fun touch(nodeId: Long, hops: Int) {
        if (!isFriend(nodeId)) return
        val now = clock.wallMs()
        friends.update { list -> list.map { if (it.nodeId == nodeId) it.copy(lastSeenAt = now, lastHops = hops) else it } }
    }

    private fun newId(): Long {
        while (true) {
            val id = random.nextLong()
            if (id != 0L) return id
        }
    }

    companion object {
        const val MAX_MESSAGES = 2000
        const val OUTBOX_TTL_MS = 24 * 60 * 60_000L
        const val RETRY_TICK_MS = 5_000L
        const val LINK_SETTLE_MS = 1_500L
        const val LINK_RETRY_WINDOW_MS = 60_000L

        /** Every attempt floods the whole region, so an undelivered message gets this many and then fails. */
        const val MAX_ATTEMPTS = 20

        /** Under the first retry delay, so a retry after a lost DELIVERED is still answered. */
        const val CONFIRM_REPEAT_MS = 10_000L

        /** Messages (per recipient) being tried at once; the others wait in the outbox. */
        const val MAX_IN_FLIGHT = 16

        /** Chat packets this phone may write in a minute: a message to a full group is 7, its receipts 1 each. */
        const val PACKETS_PER_MINUTE = 30

        /** "Unde ești?": one a minute per friend, asked and answered. */
        const val REQUEST_INTERVAL_MS = 60_000L

        /** An answer counts within this long of asking, and is kept this long; only a fix this fresh is given. */
        const val ANSWER_WINDOW_MS = 2 * 60_000L
        const val ANSWER_KEPT_MS = 15 * 60_000L
        const val ANSWER_FIX_MAX_AGE_MS = 2 * 60_000L

        const val START_RETRY_MS = 60_000L
        const val MAX_START_TRIES = 5

        /** "Fă-te văzut" and "Vin la tine": one every 2 minutes per friend, asked and heard. */
        const val PING_INTERVAL_MS = 2 * 60_000L

        /** A meeting point: one a minute per conversation. */
        const val MEETING_INTERVAL_MS = 60_000L

        /** A request not answered by then is stale: the friend has moved on. */
        const val PING_ASK_MS = 2 * 60_000L
        const val COME_ASK_MS = 5 * 60_000L

        /** "Vin la tine" shares both ways for this long. */
        const val COME_MIN = 15

        fun backoffMs(attempts: Int): Long = when {
            attempts <= 0 -> 0
            attempts == 1 -> 15_000
            attempts == 2 -> 30_000
            attempts == 3 -> 60_000
            attempts == 4 -> 120_000
            else -> 300_000
        }
    }
}
