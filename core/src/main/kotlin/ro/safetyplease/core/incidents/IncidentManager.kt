package ro.safetyplease.core.incidents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentBody
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.truncateUtf8

class ReportDraft(
    val category: Int,
    val severity: Int,
    val zone: String,
    val lat: Double?,
    val lon: Double?,
    val description: String,
    val nickname: String?,
)

/**
 * Fluxul de incidente. Participantul sigileaza raportul catre staff si urmareste ACK-urile;
 * telefonul de staff il deschide, confirma automat primirea si poate prelua sau rezolva.
 * Autorul isi poate retrage raportul cu un token pe care doar el il stie; staff-ul confirma cu ACK CANCELLED.
 */
class IncidentManager(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val crypto: Crypto,
    private val staffCrypto: StaffCrypto,
    private val store: JsonStore<IncidentData>,
    private val clock: Clock,
    private val staffSecret: () -> StaffSecretKeys?,
    private val teamName: () -> String,
    private val onStaffAlert: (StaffIncident) -> Unit = {},
    private val onStaffAlertCleared: (incidentId: String) -> Unit = {},
    private val onReportUpdate: (MyReport) -> Unit = {},
    private val onReportSent: () -> Unit = {},
) {
    private class Cancel(val token: ByteArray, val atMs: Long)

    private val packetToIncident = HashMap<Long, String>()

    /** Anularile proprii pe care staff-ul nu le-a confirmat inca; [Cancel.atMs] e ora cererii, ca [MyReport.updatedAt]. */
    private val cancelling = HashMap<String, Cancel>()

    /** Anulari ajunse la staff inaintea raportului; [Cancel.atMs] e timp monoton. */
    private val earlyCancels = LinkedHashMap<String, Cancel>()
    private var cancelRetry: Job? = null

    fun start() {
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            resumeCancels()
            refreshPending()
            while (isActive) {
                delay(30_000)
                refreshPending()
            }
        }
        scope.launch {
            while (isActive) {
                delay(CANCEL_RETRY_INTERVAL_MS)
                retryCancels()
            }
        }
    }

    // --- participant ---

    /** Cate milisecunde mai are de asteptat utilizatorul pana poate raporta din nou; 0 daca poate acum. */
    fun rateLimitWaitMs(): Long {
        val now = clock.wallMs()
        val data = store.value
        val recent = (data.mine.map { it.createdAt } + data.deletedReportTimes).filter { now - it < RATE_WINDOW_MS }.sorted()
        if (recent.size < RATE_MAX) return 0
        return recent[recent.size - RATE_MAX] + RATE_WINDOW_MS - now
    }

    /** [bypassRateLimit] e folosit doar de incidentele de test din modul demo. */
    fun report(draft: ReportDraft, bypassRateLimit: Boolean = false): Boolean {
        if (!bypassRateLimit && rateLimitWaitMs() > 0) return false
        val now = clock.wallMs()
        val incidentId = crypto.random(INCIDENT_ID_SIZE)
        val cancelToken = crypto.random(IncidentCancel.TOKEN_SIZE)
        val description = draft.description.trim().take(Limits.DESCRIPTION_CHARS).truncateUtf8(Limits.DESCRIPTION_BYTES)
        val zone = draft.zone.truncateUtf8(Limits.ZONE_BYTES)
        val nickname = draft.nickname?.truncateUtf8(Limits.NICK_BYTES)?.ifEmpty { null }
        val report = MyReport(
            incidentId = incidentId.toHex(), category = draft.category, severity = draft.severity, zone = zone,
            lat = draft.lat, lon = draft.lon, description = description, anonymous = nickname == null,
            createdAt = now, updatedAt = now, cancelToken = cancelToken.toHex(),
        )
        store.update { it.copy(mine = (it.mine + report).takeLast(MAX_MINE)) }
        scope.launch {
            val body = IncidentBody(
                draft.category, draft.severity, now / 1000, zone, draft.lat, draft.lon, description, nickname,
                IncidentCancel.hash(cancelToken),
            )
            val packet = engine.publishReport(incidentId, staffCrypto.sealReport(body.encode()))
            packetToIncident[packet.id] = report.incidentId
            // un telefon de staff nu isi primeste propriul raport din retea, asa ca il deschide direct
            onReport(packet, hops = 0, alert = false)
            refreshPending()
            onReportSent()
        }
        return true
    }

    /**
     * Retrage un raport propriu pe care staff-ul nu l-a rezolvat inca. Anularea pleaca din nou la fiecare
     * vecin nou si la fiecare minut, pana vine ACK-ul CANCELLED sau trec [CANCEL_RETRY_MS].
     */
    fun cancel(incidentId: String) {
        val report = store.value.mine.firstOrNull { it.incidentId == incidentId } ?: return
        if (report.cancelled || report.status >= AckStatus.RESOLVED || report.cancelToken.isEmpty()) return
        val now = clock.wallMs()
        store.update { data ->
            data.copy(mine = data.mine.map { if (it.incidentId == incidentId) it.copy(cancelled = true, updatedAt = now) else it })
        }
        val id = incidentId.hexToBytes()
        val token = report.cancelToken.hexToBytes()
        scope.launch {
            engine.forgetReport(id)
            cancelling[incidentId] = Cancel(token, now)
            engine.publishCancel(id, token)
            // un telefon de staff nu isi primeste propriul pachet din retea, asa ca isi aplica singur anularea
            onCancel(IncidentCancel(id, token))
            refreshPending()
        }
    }

    /** Sterge raportul doar de pe acest telefon; unul inca deschis e anulat intai. */
    fun delete(incidentId: String) {
        val report = store.value.mine.firstOrNull { it.incidentId == incidentId } ?: return
        cancel(incidentId)
        val now = clock.wallMs()
        store.update { data ->
            data.copy(
                mine = data.mine.filterNot { it.incidentId == incidentId },
                // altfel stergerea ar ocoli limita de rapoarte
                deletedReportTimes = (data.deletedReportTimes + report.createdAt).filter { now - it < RATE_WINDOW_MS },
            )
        }
    }

    // --- staff ---

    fun acknowledge(incidentId: String) = sendAck(incidentId, AckStatus.ACKNOWLEDGED)

    fun resolve(incidentId: String) = sendAck(incidentId, AckStatus.RESOLVED)

    /** Scoate incidentul doar din lista acestui telefon, fara niciun mesaj in retea. */
    fun dismiss(incidentId: String) {
        store.update { data ->
            data.copy(
                staff = data.staff.filterNot { it.incidentId == incidentId },
                dismissed = (data.dismissed - incidentId + incidentId).takeLast(MAX_STAFF),
            )
        }
        onStaffAlertCleared(incidentId)
    }

    private fun sendAck(incidentId: String, status: Int) {
        scope.launch {
            val secret = staffSecret() ?: return@launch
            val team = teamName()
            val now = clock.wallMs()
            val ack = staffCrypto.signAck(incidentId.hexToBytes(), status, now / 1000, team, secret)
            engine.publishAck(ack)
            applyAck(ack)
        }
    }

    /** Dupa activarea modului staff, deschide si rapoartele care erau deja in cache-ul de store-and-forward. */
    fun reprocessCached() {
        scope.launch { engine.cachedReports().forEach { (packet, hops) -> onReport(packet, hops, alert = true) } }
    }

    // --- evenimente ---

    private fun onEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.Received -> when (event.packet.type) {
                PacketType.INCIDENT_REPORT -> onReport(event.packet, event.packet.hops, alert = true)
                PacketType.INCIDENT_ACK -> IncidentAck.decode(event.packet.payload)?.let(::applyAck)
                PacketType.INCIDENT_CANCEL -> IncidentCancel.decode(event.packet.payload)?.let(::onCancel)
            }
            is MeshEvent.Sent -> {
                val incidentId = packetToIncident.remove(event.packetId) ?: return
                store.update { data -> data.copy(mine = data.mine.map { if (it.incidentId == incidentId) it.copy(sent = true) else it }) }
            }
            // un vecin nou poate fi drumul spre staff pe care anularea nu l-a gasit pana acum
            is MeshEvent.PeerLinked -> if (cancelling.isNotEmpty() && cancelRetry?.isActive != true) {
                cancelRetry = scope.launch {
                    delay(LINK_SETTLE_MS)
                    retryCancels()
                }
            }
            else -> Unit
        }
    }

    private fun onReport(packet: Packet, hops: Int, alert: Boolean) {
        val secret = staffSecret() ?: return
        val incidentId = IncidentReportCodec.incidentId(packet.payload)
        val key = incidentId.toHex()
        val data = store.value
        if (key in data.dismissed || data.staff.any { it.incidentId == key }) return
        val plain = staffCrypto.openReport(IncidentReportCodec.sealed(packet.payload), secret) ?: return
        val body = IncidentBody.decode(plain) ?: return
        val now = clock.wallMs()
        val incident = StaffIncident(
            incidentId = key, category = body.category, severity = body.severity, zone = body.zone,
            lat = body.lat, lon = body.lon, description = body.description, nickname = body.nickname,
            reportedAt = body.timestamp * 1000, receivedAt = now, hops = hops,
            cancelHash = body.cancelHash?.toHex().orEmpty(),
        )
        store.update { it.copy(staff = (it.staff + incident).takeLast(MAX_STAFF)) }
        engine.cachedAck(incidentId)?.let(::applyAck)
        earlyCancels.remove(key)?.takeIf { clock.monoMs() - it.atMs <= EARLY_CANCEL_MS }?.let { tryCancel(key, it.token, secret) }
        // daca alta echipa a confirmat deja primirea, un al doilea RECEIVED ar fi doar zgomot in retea
        if (engine.ackStatus(incidentId) == AckStatus.NONE) {
            val ack = staffCrypto.signAck(incidentId, AckStatus.RECEIVED, now / 1000, teamName(), secret)
            engine.publishAck(ack)
            applyAck(ack)
        }
        val cancelled = store.value.staff.any { it.incidentId == key && it.status == AckStatus.CANCELLED }
        if (alert && !cancelled) onStaffAlert(incident)
    }

    private fun onCancel(cancel: IncidentCancel) {
        val secret = staffSecret() ?: return
        val key = cancel.incidentId.toHex()
        if (store.value.staff.any { it.incidentId == key }) {
            tryCancel(key, cancel.token, secret)
            return
        }
        // raportul poate ajunge mai tarziu, pe alt drum; tokenul nu se poate verifica pana atunci
        val now = clock.monoMs()
        earlyCancels.values.removeAll { now - it.atMs > EARLY_CANCEL_MS }
        earlyCancels.remove(key)
        earlyCancels[key] = Cancel(cancel.token, now)
        if (earlyCancels.size > MAX_EARLY_CANCELS) earlyCancels.remove(earlyCancels.keys.first())
    }

    /** Doar tokenul autorului anuleaza, si doar cat timp incidentul nu e rezolvat. */
    private fun tryCancel(key: String, token: ByteArray, secret: StaffSecretKeys) {
        val incident = store.value.staff.firstOrNull { it.incidentId == key } ?: return
        if (incident.cancelHash.isEmpty() || incident.status >= AckStatus.RESOLVED) return
        if (!IncidentCancel.hash(token).contentEquals(incident.cancelHash.hexToBytes())) return
        val ack = staffCrypto.signAck(key.hexToBytes(), AckStatus.CANCELLED, clock.wallMs() / 1000, teamName(), secret)
        engine.publishAck(ack)
        applyAck(ack)
    }

    private fun applyAck(ack: IncidentAck) {
        val key = ack.incidentId.toHex()
        val now = clock.wallMs()
        val clearsAlert = ack.status == AckStatus.CANCELLED && store.value.staff.any { it.incidentId == key && it.status < ack.status }
        var updated: MyReport? = null
        store.update { data ->
            data.copy(
                mine = data.mine.map {
                    if (it.incidentId == key && ack.status > it.status) {
                        it.copy(status = ack.status, teamName = ack.teamName, updatedAt = now, sent = true).also { r -> updated = r }
                    } else it
                },
                staff = data.staff.map {
                    if (it.incidentId == key && ack.status > it.status) it.copy(status = ack.status, teamName = ack.teamName, statusAt = now)
                    else it
                },
            )
        }
        updated?.let(onReportUpdate)
        if (clearsAlert) onStaffAlertCleared(key)
        refreshPending()
    }

    /** Pana la ACK-ul CANCELLED, o anulare se poate pierde ca orice pachet; retrimiterea e mereu un pachet nou. */
    private fun retryCancels() {
        val now = clock.wallMs()
        cancelling.entries.removeAll { (id, c) ->
            now - c.atMs >= CANCEL_RETRY_MS || engine.ackStatus(id.hexToBytes()) >= AckStatus.RESOLVED
        }
        for ((id, c) in cancelling) engine.publishCancel(id.hexToBytes(), c.token)
    }

    /** Cache-ul mesh-ului nu supravietuieste unei reporniri, starea anularilor da. */
    private fun resumeCancels() {
        for (r in store.value.mine.filter { it.cancelled }) {
            engine.forgetReport(r.incidentId.hexToBytes())
            if (r.status < AckStatus.RESOLVED) cancelling[r.incidentId] = Cancel(r.cancelToken.hexToBytes(), r.updatedAt)
        }
    }

    /** Cat timp un raport propriu nu a ajuns la staff, advertising-ul il anunta ca vecinii sa ne prefere. */
    private fun refreshPending() {
        val now = clock.wallMs()
        engine.setPendingIncident(store.value.mine.any { isPending(it, now) })
    }

    companion object {
        const val RATE_MAX = 3
        const val RATE_WINDOW_MS = 10 * 60_000L
        const val PENDING_MS = 30 * 60_000L
        const val CANCEL_RETRY_MS = 30 * 60_000L
        const val CANCEL_RETRY_INTERVAL_MS = 60_000L
        const val MAX_MINE = 50
        const val MAX_STAFF = 300
        private const val EARLY_CANCEL_MS = 60 * 60_000L
        private const val MAX_EARLY_CANCELS = 64
        private const val LINK_SETTLE_MS = 1_500L

        /** Raport propriu care inca nu a ajuns la staff si nu a fost retras. */
        fun isPending(report: MyReport, nowMs: Long): Boolean =
            report.status == AckStatus.NONE && !report.cancelled && nowMs - report.createdAt < PENDING_MS
    }
}
