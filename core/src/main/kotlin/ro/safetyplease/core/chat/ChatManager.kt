package ro.safetyplease.core.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.FriendCard
import ro.safetyplease.core.crypto.Identity
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
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.mesh.WindowLimit
import ro.safetyplease.core.protocol.GroupMemberWire
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.InnerCodec
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.truncateUtf8
import kotlin.random.Random

/**
 * Private chat over the mesh: crypto_box envelopes, an outbox retried until DELIVERED, PING/PONG,
 * and groups by fan-out. Runs on the mesh context; public methods can be called from anywhere.
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

    fun start() {
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            while (isActive) {
                delay(RETRY_TICK_MS)
                retryOutbox(linkTrigger = false)
            }
        }
    }

    // --- friends ---

    fun myCard(): FriendCard = FriendCard(myNickname(), identity.box.publicKey, identity.sign.publicKey)

    /**
     * Returns false for our own code. An existing friend only gets their nickname updated. Scanning the code of
     * someone blocked is a decision to talk to them again.
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
        ping(nodeId)
        return true
    }

    fun removeFriend(nodeId: Long) {
        friends.update { list -> list.filterNot { it.nodeId == nodeId } }
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

    private fun enqueue(msgId: Long, recipient: Long, inner: ByteArray, boxKey: String = "") {
        val item = OutboxItem(msgId, recipient, inner.toHex(), clock.wallMs(), boxKey = boxKey)
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
        val payload = crypto.box(item.innerHex.hexToBytes(), key, identity.box.secretKey)
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
        val key = boxKeyOf(sender) ?: return
        val plain = crypto.boxOpen(packet.payload, key, identity.box.secretKey) ?: return
        val inner = InnerCodec.decode(plain) ?: return
        touch(sender, packet.hops)
        when (inner) {
            is Inner.Text -> onMessage(packet, inner.msgId, inner.groupId, MsgKind.TEXT, text = inner.text)
            is Inner.Quick -> onMessage(packet, inner.msgId, inner.groupId, MsgKind.QUICK, quickCode = inner.code)
            is Inner.Zone -> onMessage(packet, inner.msgId, inner.groupId, MsgKind.ZONE, zone = inner.zone, lat = inner.lat, lon = inner.lon)
            is Inner.Ping -> if (isFriend(sender)) sendDirect(sender, Inner.Pong(newId(), inner.msgId))
            is Inner.Pong -> Unit
            is Inner.Delivered -> onDelivered(sender, inner.deliveredId)
            is Inner.GroupInvite -> onInvite(sender, inner)
            is Inner.LeftGroup -> onLeft(sender, inner)
            is Inner.ZoneRequest -> if (isFriend(sender) && myZone.isNotEmpty() && shareZone()) {
                sendDirect(sender, Inner.ZoneUpdate(newId(), myZone))
            }
            is Inner.ZoneUpdate -> setLastZone(sender, inner.zone)
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
