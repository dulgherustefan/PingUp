package ro.safetyplease.core.announce

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A notice on the board, as it came ([payload], hex). [alerted]: the phone took the screen for it once; [dismissed]: the
 * user hid its band. [order] is the issue time it is ordered by here ([NoticeOrder.orderTime]) and [until] when it
 * stops holding, on this phone's clock, in seconds; both are 0 on a board saved before them. [uncertain]: its time and
 * this phone's clock disagree, so it is shown as of uncertain time.
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
    val order: Long = 0,
    val until: Long = 0,
    val uncertain: Boolean = false,
) {
    fun notice(): Notice? = Notice.decode(payload.hexToBytes())

    /** When [notice], this one decoded, stops holding on this phone's clock. */
    fun end(notice: Notice): Long = if (until > 0) until else notice.validUntil

    fun orderOf(notice: Notice): Long = if (order > 0) order else notice.issued

    /** Whether [notice], this one decoded, is in force at [nowSec]: not a withdrawal, and not ended. */
    fun holds(notice: Notice, nowSec: Long): Boolean = !notice.cancelled && end(notice) > nowSec
}

@Serializable
data class NoticeBoard(val notices: List<HeldNotice> = emptyList()) {
    /** The notices in force at [nowSec]: neither withdrawn nor ended, alerts first, the newest first within a level. */
    fun active(nowSec: Long): List<Pair<HeldNotice, Notice>> = notices
        .mapNotNull { held -> held.notice()?.takeIf { held.holds(it, nowSec) }?.let { held to it } }
        .sortedWith(
            compareBy<Pair<HeldNotice, Notice>> { it.second.topic != NoticeTopic.ALERT }.thenByDescending { it.second.level }
                .thenByDescending { it.first.orderOf(it.second) },
        )
}

/**
 * The console's notices on this phone (PROTOCOL.md, section 6.8), by the slots of [NoticeOrder], kept across restarts,
 * from the mesh or signed here. A console that holds a control shift signs them; where [resends], the alerts in force and
 * the withdrawals of evacuations go out again every [RESEND_MS], the others every [OTHERS_EVERY] times that, and the
 * phones that already hold one stop it at once. [onNotice] gets each new one, whether this phone signed it, and the
 * sequence numbers of those it only renews, which the phone already showed or followed. What touches the engine runs
 * on [scope], the mesh's.
 */
class Notices(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val chain: KeyChain,
    private val crypto: Crypto,
    private val clock: Clock,
    private val store: JsonStore<NoticeBoard>,
    private val resends: () -> Boolean,
    private val onNotice: (notice: Notice, own: Boolean, renews: Set<Long>) -> Unit = { _, _, _ -> },
) {
    val board: StateFlow<NoticeBoard> get() = store.state

    private val offset = MutableStateFlow(0L)

    /** The neighbours' clock minus this phone's, in seconds, while they disagree past the skew; else 0. */
    val clockOffsetS: StateFlow<Long> = offset

    private val holders = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** How many neighbours hold each notice this console signed that still holds, by [NoticeOrder.extraId]. */
    val heldAround: StateFlow<Map<Long, Int>> = holders

    private fun nowSec(): Long = clock.wallMs() / 1000

    fun start() {
        scope.launch {
            forget(chain::isRevoked)
            engine.events.collect { e ->
                if (e !is MeshEvent.Received || e.packet.type != PacketType.ANNOUNCEMENT) return@collect
                // the engine verified it and its slot took it
                Notice.decode(e.packet.payload)?.let { accept(it, own = false) }
            }
        }
        scope.launch {
            var round = 0
            while (isActive) {
                delay(RESEND_MS)
                round++
                if (resends()) resend(round)
            }
        }
        scope.launch {
            while (isActive) {
                look()
                delay(LOOK_MS)
            }
        }
    }

    private fun resend(round: Int) {
        val now = nowSec()
        for (held in store.value.notices) {
            val notice = held.notice() ?: continue
            // a phone that missed the withdrawal of an evacuation would follow it until it ends
            val withdrawal = notice.cancelled && NoticeOrder.slot(notice) == NoticeOrder.EVACUATIONS && held.end(notice) > now
            if (!held.holds(notice, now) && !withdrawal) continue
            if (notice.topic == NoticeTopic.ALERT || round % OTHERS_EVERY == 0) engine.broadcast(PacketType.ANNOUNCEMENT, held.payload.hexToBytes())
        }
    }

    private fun look() {
        offset.value = engine.noticeClockOffsetMs() / 1000
        val now = nowSec()
        holders.value = store.value.notices.filter { it.own }
            .mapNotNull { held -> held.notice()?.takeIf { held.end(it) > now } }
            .associate { NoticeOrder.extraId(it) to engine.noticeHolders(it) }
    }

    /** This phone's control shift, if it holds one now: only a console signs notices. */
    private fun signer(): Credential? =
        chain.credential?.takeIf { it.certificate.role == CertRole.CONTROL && chain.status(it.certificate) == CertStatus.VALID }

    val canSign: Boolean get() = signer() != null

    private suspend fun <T> onMesh(block: () -> T): T = scope.async { block() }.await()

    /** The notices of [slot] that hold now, on this phone's board. */
    private fun inForce(slot: Int): List<Notice> {
        val now = nowSec()
        return store.value.notices.mapNotNull { held -> held.notice()?.takeIf { NoticeOrder.slot(it) == slot && held.holds(it, now) } }
    }

    /**
     * The issue time and sequence number of a notice of [slot] signed now, on this phone's clock, or its neighbours'
     * while they disagree with it past the skew: a console whose clock is wrong would send notices already over. It is
     * issued no earlier than the newest of its slot here, so another console's clock running a little ahead doesn't
     * hold it back, and no later than the clock plus the skew. Its number is its issue time, or one above every number
     * known in the topic if that is higher, so phones of earlier versions, which order a topic by number alone, take it
     * after any console's: a replacement console's first notice included. A number past the issue time and the skew,
     * which only a wrong clock or a stolen key would set, is left out. [under]: numbered below it if there is room, for
     * [belowEvacuations].
     */
    private fun numbering(topic: Int, slot: Int, under: Long?): Pair<Long, Long> {
        val now = nowSec() + engine.noticeClockOffsetMs() / 1000
        val newest = maxOf(engine.noticeOrder(slot), store.value.notices.mapNotNull { h -> h.notice()?.takeIf { NoticeOrder.slot(it) == slot }?.let(h::orderOf) }.maxOrNull() ?: 0)
        val issued = maxOf(now, minOf(newest + 1, now + KeyChain.SKEW_S))
        val known = knownSeqs(topic).filter { it <= issued + KeyChain.SKEW_S }
        if (under != null) {
            val seq = (known.filter { it < under }.maxOrNull() ?: 0) + 1
            if (seq < under) return issued to seq
        }
        return issued to minOf(maxOf(issued, (known.maxOrNull() ?: 0) + 1), MAX_SEQ)
    }

    private fun knownSeqs(topic: Int): List<Long> =
        store.value.notices.filter { it.topic == topic }.map { it.seq } + engine.noticeSeq(topic)

    /**
     * While evacuations are in force, the number an alert of another level goes under: phones of earlier versions keep
     * one alert, the highest numbered, so they keep the evacuation and skip the other. Null when nothing known in the
     * topic but the evacuations is numbered at or above them, as they might not be the alert those phones hold.
     */
    private fun belowEvacuations(): Long? {
        val evacuations = inForce(NoticeOrder.EVACUATIONS).map { it.seq }
        val lowest = evacuations.minOrNull() ?: return null
        val plausible = knownSeqs(NoticeTopic.ALERT).filter { it <= nowSec() + 2 * KeyChain.SKEW_S }
        return lowest.takeIf { plausible.none { it >= lowest && it !in evacuations } }
    }

    /**
     * Signs and sends a notice of [template] for [validForS] seconds, with [extras]; it replaces its slot's last one, or,
     * for an evacuation, joins those in force: its zones take in theirs and it holds at least as long. Null without a
     * control shift, with a template that doesn't fit the wire, or when the engine didn't take it.
     */
    suspend fun publish(
        template: NoticeTemplate, params: List<String>, zone: String, validForS: Long, extras: NoticeExtras = NoticeExtras.NONE,
    ): Notice? = onMesh {
        val credential = signer() ?: return@onMesh null
        if (validForS !in 1..Notice.MAX_VALID_S || params.size != template.params.size || !params.all(NoticeTemplates::fits)) return@onMesh null
        val evacuation = template.topic == NoticeTopic.ALERT && template.level == NoticeLevel.EVACUATION
        val slot = if (evacuation) NoticeOrder.EVACUATIONS else template.topic
        val under = if (template.topic == NoticeTopic.ALERT && !evacuation) belowEvacuations() else null
        val (issued, seq) = numbering(template.topic, slot, under)
        val joining = if (evacuation) inForce(NoticeOrder.EVACUATIONS) else emptyList()
        val (zones, field) = Evacuations.joined(extras, zone, joining)
        val until = maxOf(issued + validForS, joining.maxOfOrNull { it.validUntil } ?: 0).coerceAtMost(issued + Notice.MAX_VALID_S)
        send(Notice.sign(crypto, credential, template.topic, template.level, seq, issued, until, template.id, field, params, extras = zones))
    }

    /** Signs and sends the map state [states], one per zone in the venue's order, for [validForS] seconds. */
    suspend fun publishMapState(states: List<ZoneState>, validForS: Long): Notice? = onMesh {
        val credential = signer() ?: return@onMesh null
        if (validForS !in 1..Notice.MAX_VALID_S) return@onMesh null
        val topic = NoticeTopic.MAP_STATE
        val (issued, seq) = numbering(topic, topic, null)
        send(Notice.sign(crypto, credential, topic, 1, seq, issued, issued + validForS, 0, extras = NoticeExtras(states = states)))
    }

    /** Withdraws what [topic] has in force, an alert's evacuations included, a withdrawal for each slot; the last, or null. */
    suspend fun cancel(topic: Int): Notice? = onMesh {
        val slots = if (topic == NoticeTopic.ALERT) listOf(NoticeTopic.ALERT, NoticeOrder.EVACUATIONS) else listOf(topic)
        slots.mapNotNull(::withdrawSlot).lastOrNull()
    }

    /** Withdraws the slot of [notice]: for an evacuation, every evacuation in force. Null with nothing to withdraw. */
    suspend fun withdraw(notice: Notice): Notice? = onMesh { withdrawSlot(NoticeOrder.slot(notice)) }

    /**
     * A newer notice of [slot] that shows nothing, for as long as what it withdraws would have held. After the
     * evacuations, the other alert in force goes again, numbered above the withdrawal: phones of earlier versions,
     * which held the evacuation as their one alert, show it then.
     */
    private fun withdrawSlot(slot: Int): Notice? {
        val credential = signer() ?: return null
        val current = inForce(slot).takeIf { it.isNotEmpty() } ?: return null
        val topic = NoticeOrder.topicOf(slot)
        val evacuations = slot == NoticeOrder.EVACUATIONS
        val under = if (topic == NoticeTopic.ALERT && !evacuations) belowEvacuations() else null
        val (issued, seq) = numbering(topic, slot, under)
        val until = maxOf(current.maxOf { it.validUntil }, issued + 1).coerceAtMost(issued + Notice.MAX_VALID_S)
        val level = if (evacuations) NoticeLevel.EVACUATION else current.first().level
        val withdrawal = send(Notice.sign(crypto, credential, topic, level, seq, issued, until, 0, flags = Notice.CANCEL)) ?: return null
        if (evacuations) inForce(NoticeTopic.ALERT).firstOrNull()?.let(::signAgain)
        return withdrawal
    }

    private fun signAgain(notice: Notice) {
        val credential = signer() ?: return
        val (issued, seq) = numbering(notice.topic, NoticeOrder.slot(notice), null)
        if (notice.validUntil - issued !in 1..Notice.MAX_VALID_S) return
        send(
            Notice.sign(
                crypto, credential, notice.topic, notice.level, seq, issued, notice.validUntil, notice.template, notice.zone, notice.params,
                notice.flags and Notice.EXTRAS.inv(), notice.extras,
            ),
        )
    }

    /** Hands [notice] to the engine, and to the board once the engine took it. */
    private fun send(notice: Notice): Notice? {
        if (engine.publishNotice(notice) == null) return null
        accept(notice, own = true)
        return notice
    }

    /**
     * Takes [notice] onto the board if its slot takes it here ([NoticeOrder.take]), read on the neighbours' clock when
     * this phone's disagrees with theirs. A notice signed again with the same words, or an evacuation that takes in one
     * the phone already followed, keeps what the person did with the one it replaces and is passed on as renewing it.
     */
    private fun accept(notice: Notice, own: Boolean) {
        if (chain.isRevoked(notice.certificate.id)) return
        val shift = engine.noticeClockOffsetMs() / 1000
        val wall = nowSec()
        val seen = wall + shift
        val until = NoticeOrder.until(notice, seen)
        if (until <= seen) return
        val order = NoticeOrder.orderTime(notice, seen)
        val slot = NoticeOrder.slot(notice)
        var renews: Set<Long>? = null
        store.update { board ->
            renews = null
            val kept = board.notices.filter { held -> held.notice()?.let { keeps(held, it, seen, wall) } == true }
            val inSlot = kept.mapNotNull { held -> held.notice()?.takeIf { NoticeOrder.slot(it) == slot }?.let { held to it } }
            val take = NoticeOrder.take(notice, order, inSlot.map { (held, n) -> NoticeOrder.Held(n, held.orderOf(n)) })
            if (!take.taken) return@update board
            val ended = take.ends.map { inSlot[it] }
            val followed = ended.filter { (held, n) ->
                held.holds(n, wall) && !notice.cancelled && (NoticeOrder.sameContent(n, notice) || (slot == NoticeOrder.EVACUATIONS && held.alerted))
            }
            renews = followed.map { it.second.seq }.toSet()
            val uncertain = NoticeOrder.uncertain(notice, seen) || shift != 0L
            val held = HeldNotice(
                notice.topic, notice.seq, notice.encode().toHex(), clock.wallMs(), own,
                alerted = followed.any { it.first.alerted }, dismissed = followed.any { it.first.dismissed },
                order = order, until = until - shift, uncertain = uncertain,
            )
            board.copy(notices = kept - ended.map { it.first }.toSet() + held)
        }
        renews?.let { onNotice(notice, own, it) }
    }

    /** A topic's latest stays while its order counts; an evacuation goes once it ends. */
    private fun keeps(held: HeldNotice, notice: Notice, seenSec: Long, wallSec: Long): Boolean {
        if (NoticeOrder.markUntil(held.orderOf(notice)) <= seenSec) return false
        return !(NoticeOrder.slot(notice) == NoticeOrder.EVACUATIONS && !notice.cancelled && held.end(notice) <= wallSec)
    }

    /** Certificates revoked since: what they signed leaves the board and the mesh cache, and their slots take notices again. */
    fun revoked(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val revoked = ids.toSet()
        scope.launch { forget { it in revoked } }
    }

    private fun forget(revoked: (Long) -> Boolean) {
        val signedBy = { held: HeldNotice -> held.notice()?.let { revoked(it.certificate.id) } != false }
        if (store.value.notices.any(signedBy)) store.update { board -> board.copy(notices = board.notices.filterNot(signedBy)) }
        engine.forgetNotices(revoked)
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

        /** How often the board reads the neighbours' clock and, on a console, who holds its notices. */
        const val LOOK_MS = 15_000L

        private const val MAX_SEQ = 0xFFFFFFFFL
    }
}
