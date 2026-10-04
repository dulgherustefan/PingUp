package ro.safetyplease.app.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.app.core.Clock
import ro.safetyplease.app.core.hexToBytes
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.core.truncateUtf8
import ro.safetyplease.app.crypto.Crypto
import ro.safetyplease.app.crypto.FriendCard
import ro.safetyplease.app.crypto.Identity
import ro.safetyplease.app.data.ChatData
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Conversations
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.GroupMember
import ro.safetyplease.app.data.JsonStore
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MsgStatus
import ro.safetyplease.app.data.OutboxItem
import ro.safetyplease.app.mesh.MeshEngine
import ro.safetyplease.app.mesh.MeshEvent
import ro.safetyplease.app.protocol.GroupMemberWire
import ro.safetyplease.app.protocol.Inner
import ro.safetyplease.app.protocol.InnerCodec
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.Packet
import ro.safetyplease.app.protocol.PacketType
import kotlin.random.Random

/**
 * Chat privat peste mesh: plicuri crypto_box, outbox cu retransmisie pana la DELIVERED, PING/PONG
 * si grupuri prin fan-out. Ruleaza in contextul mesh; metodele publice pot fi chemate de oriunde.
 */
class ChatManager(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val crypto: Crypto,
    private val identity: Identity,
    private val friends: JsonStore<List<Friend>>,
    private val groups: JsonStore<List<Group>>,
    private val chat: JsonStore<ChatData>,
    private val clock: Clock,
    private val random: Random,
    private val myNickname: () -> String,
    private val onIncoming: (message: ChatMessage, senderName: String) -> Unit = { _, _ -> },
) {
    private val attempts = LinkedHashMap<Long, Pair<Long, Long>>()
    private var linkRetry: Job? = null

    fun start() {
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            while (isActive) {
                delay(RETRY_TICK_MS)
                retryOutbox(linkTrigger = false)
            }
        }
    }

    // --- prieteni ---

    fun myCard(): FriendCard = FriendCard(myNickname(), identity.box.publicKey, identity.sign.publicKey)

    /** Intoarce false daca e propriul cod. Un prieten existent primeste doar nickname-ul actualizat. */
    fun addFriend(card: FriendCard): Boolean {
        val nodeId = card.nodeId
        if (nodeId == identity.nodeId) return false
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

    // --- trimitere ---

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

    /** Sterge mesajul doar de pe acest telefon; daca era al nostru si inca in drum, nu mai e retrimis. */
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

    /** Ce mai e in outbox pleaca acum, fara sa astepte pauza dintre incercari; ce a expirat dupa 24 h pleaca din nou, ca mesaj nou. */
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
                MsgKind.SYSTEM -> Unit
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

    fun leaveGroup(groupId: Long) {
        groups.update { list -> list.filterNot { it.id == groupId } }
        val conversation = Conversations.group(groupId)
        chat.update { it.copy(messages = it.messages.filterNot { m -> m.conversation == conversation }) }
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

    private fun enqueue(msgId: Long, recipient: Long, inner: ByteArray) {
        val item = OutboxItem(msgId, recipient, inner.toHex(), clock.wallMs())
        chat.update { it.copy(outbox = it.outbox + item) }
        attempt(item)
    }

    /** Fiecare incercare e un pachet nou (alt id, alt nonce), ca relay-urile sa nu il arunce ca duplicat. */
    private fun attempt(item: OutboxItem) {
        if (engine.state.value.readyLinks == 0) return
        val key = boxKeyOf(item.recipient) ?: return
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

    private fun sendDirect(recipient: Long, inner: Inner) {
        if (engine.state.value.readyLinks == 0) return
        val key = boxKeyOf(recipient) ?: return
        engine.unicast(PacketType.PRIVATE, recipient, crypto.box(InnerCodec.encode(inner), key, identity.box.secretKey))
    }

    private fun retryOutbox(linkTrigger: Boolean) {
        val now = clock.wallMs()
        val expired = chat.value.outbox.filter { now - it.createdAt > OUTBOX_TTL_MS }
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
            val backoff = backoffMs(item.attempts)
            val wait = if (linkTrigger) minOf(backoff, LINK_RETRY_MIN_MS) else backoff
            if (item.attempts == 0 || now - item.lastAttemptAt >= wait) attempt(item)
        }
    }

    // --- primire ---

    private fun onEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.Received -> if (event.packet.type == PacketType.PRIVATE) onPrivate(event.packet)
            is MeshEvent.Sent -> onSent(event.packetId)
            is MeshEvent.PeerLinked -> {
                // un vecin nou e o sansa noua pentru mesajele nelivrate; asteptam putin sa se aseze legaturile
                if (linkRetry?.isActive != true) {
                    linkRetry = scope.launch {
                        delay(LINK_SETTLE_MS)
                        retryOutbox(linkTrigger = true)
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
        // confirmarea pleaca si pentru duplicate: prima s-ar fi putut pierde pe drum
        sendDirect(sender, Inner.Delivered(newId(), msgId))
        if (chat.value.messages.any { it.msgId == msgId && it.senderId == sender && !it.fromMe }) return
        val message = ChatMessage(
            msgId = msgId, conversation = conversation, senderId = sender, fromMe = false, kind = kind,
            text = text, quickCode = quickCode, zone = zone, lat = lat, lon = lon,
            timeMs = clock.wallMs(), status = MsgStatus.RECEIVED, hops = packet.hops, read = false,
        )
        chat.update { it.copy(messages = (it.messages + message).takeLast(MAX_MESSAGES)) }
        onIncoming(message, nameOf(sender))
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
        sendDirect(sender, Inner.Delivered(newId(), invite.msgId))
        if (groups.value.any { it.id == invite.groupId }) return
        val me = GroupMember(identity.nodeId, myNickname(), identity.box.publicKey.toHex())
        val others = invite.members.filter { it.nodeId != identity.nodeId && it.nodeId != sender }
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

    // --- utilitare ---

    private fun isFriend(nodeId: Long) = friends.value.any { it.nodeId == nodeId }

    private fun boxKeyOf(nodeId: Long): ByteArray? {
        friends.value.firstOrNull { it.nodeId == nodeId }?.let { return it.boxKey.hexToBytes() }
        for (group in groups.value) {
            group.members.firstOrNull { it.nodeId == nodeId }?.let { return it.boxKey.hexToBytes() }
        }
        return null
    }

    fun nameOf(nodeId: Long): String {
        friends.value.firstOrNull { it.nodeId == nodeId }?.let { return it.nickname }
        for (group in groups.value) group.members.firstOrNull { it.nodeId == nodeId }?.let { return it.nickname }
        return nodeId.toHex().take(8)
    }

    /** Orice pachet autentic de la un prieten ii actualizeaza prezenta; nu exista prezenta difuzata periodic. */
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
        const val LINK_RETRY_MIN_MS = 5_000L

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
