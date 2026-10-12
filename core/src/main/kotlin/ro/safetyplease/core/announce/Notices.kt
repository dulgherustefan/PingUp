package ro.safetyplease.core.announce

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex

/**
 * The latest notice of one topic, as it came ([payload], hex). [alerted]: the phone took the screen for it once;
 * [dismissed]: the user hid its band.
 */
@Serializable
data class HeldNotice(
    val topic: Int,
    val seq: Long,
    val payload: String,
    val receivedAt: Long,
    val own: Boolean = false,
    val alerted: Boolean = false,
    val dismissed: Boolean = false,
) {
    fun notice(): Notice? = Notice.decode(payload.hexToBytes())
}

@Serializable
data class NoticeBoard(val notices: List<HeldNotice> = emptyList()) {
    /** The notices in force at [nowSec]: neither withdrawn nor expired, alerts first. */
    fun active(nowSec: Long): List<Pair<HeldNotice, Notice>> = notices
        .mapNotNull { held -> held.notice()?.takeIf { !it.cancelled && it.validUntil > nowSec }?.let { held to it } }
        .sortedWith(compareBy<Pair<HeldNotice, Notice>> { it.second.topic != NoticeTopic.ALERT }.thenByDescending { it.second.level })
}

/**
 * The console's notices on this phone (PROTOCOL.md, section 6.8): the latest of each topic, kept across restarts, from
 * the mesh or signed here. A console that holds a control shift signs them; where [resends], the alerts in force go out
 * again every [RESEND_MS] and the others every [OTHERS_EVERY] times that, and the phones that already hold one stop it
 * at once. [onNotice] gets each new one, and whether this phone signed it.
 */
class Notices(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val chain: KeyChain,
    private val crypto: Crypto,
    private val clock: Clock,
    private val store: JsonStore<NoticeBoard>,
    private val resends: () -> Boolean,
    private val onNotice: (notice: Notice, own: Boolean) -> Unit = { _, _ -> },
) {
    val board: StateFlow<NoticeBoard> get() = store.state

    private fun nowSec(): Long = clock.wallMs() / 1000

    fun start() {
        scope.launch {
            engine.events.collect { e ->
                if (e !is MeshEvent.Received || e.packet.type != PacketType.ANNOUNCEMENT) return@collect
                // the engine verified it and found it its topic's latest
                Notice.decode(e.packet.payload)?.let { accept(it, own = false) }
            }
        }
        scope.launch {
            var round = 0
            while (isActive) {
                delay(RESEND_MS)
                round++
                if (!resends()) continue
                for ((held, notice) in store.value.active(nowSec())) {
                    if (notice.topic == NoticeTopic.ALERT || round % OTHERS_EVERY == 0) engine.broadcast(PacketType.ANNOUNCEMENT, held.payload.hexToBytes())
                }
            }
        }
    }

    /** This phone's control shift, if it holds one now: only a console signs notices. */
    private fun signer(): Credential? =
        chain.credential?.takeIf { it.certificate.role == CertRole.CONTROL && chain.status(it.certificate) == CertStatus.VALID }

    val canSign: Boolean get() = signer() != null

    private fun nextSeq(topic: Int): Long = (store.value.notices.firstOrNull { it.topic == topic }?.seq ?: 0) + 1

    /**
     * Signs and sends a notice of [template] for [validForS] seconds; it replaces the topic's last one. Null without a
     * control shift or with a template that doesn't fit the wire.
     */
    fun publish(template: NoticeTemplate, params: List<String>, zone: String, validForS: Long): Notice? {
        val credential = signer() ?: return null
        if (validForS !in 1..Notice.MAX_VALID_S || params.size != template.params.size || !params.all(NoticeTemplates::fits)) return null
        val now = nowSec()
        val notice = Notice.sign(
            crypto, credential, template.topic, template.level, nextSeq(template.topic), now, now + validForS, template.id, zone, params,
        )
        send(notice)
        return notice
    }

    /** Withdraws the topic's notice in force: a newer one that shows nothing, for as long as the old one would have held. */
    fun cancel(topic: Int): Notice? {
        val credential = signer() ?: return null
        val current = store.value.active(nowSec()).firstOrNull { it.second.topic == topic }?.second ?: return null
        val notice = Notice.sign(
            crypto, credential, topic, current.level, nextSeq(topic), nowSec(), current.validUntil, 0, flags = Notice.CANCEL,
        )
        send(notice)
        return notice
    }

    private fun send(notice: Notice) {
        scope.launch { if (engine.publishNotice(notice) != null) accept(notice, own = true) }
    }

    private fun accept(notice: Notice, own: Boolean) {
        var fresh = false
        store.update { board ->
            val old = board.notices.firstOrNull { it.topic == notice.topic }
            if (old != null && old.seq >= notice.seq) return@update board
            fresh = true
            val held = HeldNotice(notice.topic, notice.seq, notice.encode().toHex(), clock.wallMs(), own)
            board.copy(notices = board.notices.filter { it.topic != notice.topic } + held)
        }
        if (fresh) onNotice(notice, own)
    }

    /** The phone took the screen for this notice; it won't again. */
    fun alerted(topic: Int, seq: Long) = mark(topic, seq) { it.copy(alerted = true) }

    /** The user hid the notice's band; a newer one of the topic shows again. */
    fun dismiss(topic: Int, seq: Long) = mark(topic, seq) { it.copy(dismissed = true, alerted = true) }

    private fun mark(topic: Int, seq: Long, change: (HeldNotice) -> HeldNotice) = store.update { board ->
        board.copy(notices = board.notices.map { if (it.topic == topic && it.seq == seq) change(it) else it })
    }

    companion object {
        /** As for revocations; a phone that holds the notice drops the copy, so only the phones without it pay. */
        const val RESEND_MS = 10 * 60_000L

        /** Heat and changes go again every 30 minutes: store-and-forward already reaches the phones that come later. */
        const val OTHERS_EVERY = 3
    }
}
