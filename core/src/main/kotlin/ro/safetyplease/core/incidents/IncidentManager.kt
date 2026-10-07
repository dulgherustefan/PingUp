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
 * Incident flow. A participant seals the report to staff and tracks the ACKs;
 * a staff phone opens it, auto-confirms receipt, and can take or resolve it.
 * The author can withdraw a report with a token only they know; staff confirms with ACK CANCELLED.
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

    /** Our cancellations staff hasn't confirmed yet; [Cancel.atMs] is the request time, like [MyReport.updatedAt]. */
    private val cancelling = HashMap<String, Cancel>()

    /** Cancellations that reached staff before the report; [Cancel.atMs] is monotonic time. */
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

    /** Milliseconds until the user may report again; 0 if they can now. */
    fun rateLimitWaitMs(): Long {
        val now = clock.wallMs()
        val data = store.value
        val recent = (data.mine.map { it.createdAt } + data.deletedReportTimes).filter { now - it < RATE_WINDOW_MS }.sorted()
        if (recent.size < RATE_MAX) return 0
        return recent[recent.size - RATE_MAX] + RATE_WINDOW_MS - now
    }

    /** [bypassRateLimit] is only used by the demo's test incidents. */
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
            // a staff phone never receives its own report from the network, so it opens it directly
            onReport(packet, hops = 0, alert = false)
            refreshPending()
            onReportSent()
        }
        return true
    }

    /**
     * Withdraws one of our reports that staff hasn't resolved yet. The cancel is resent on every new neighbor
     * and every minute until the CANCELLED ACK arrives or [CANCEL_RETRY_MS] passes.
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
            // a staff phone never receives its own packet from the network, so it applies the cancel itself
            onCancel(IncidentCancel(id, token))
            refreshPending()
        }
    }

    /** Deletes the report from this phone only; an open one is cancelled first. */
    fun delete(incidentId: String) {
        val report = store.value.mine.firstOrNull { it.incidentId == incidentId } ?: return
        cancel(incidentId)
        val now = clock.wallMs()
        store.update { data ->
            data.copy(
                mine = data.mine.filterNot { it.incidentId == incidentId },
                // otherwise deleting would bypass the rate limit
                deletedReportTimes = (data.deletedReportTimes + report.createdAt).filter { now - it < RATE_WINDOW_MS },
            )
        }
    }

    // --- staff ---

    fun acknowledge(incidentId: String) = sendAck(incidentId, AckStatus.ACKNOWLEDGED)

    fun resolve(incidentId: String) = sendAck(incidentId, AckStatus.RESOLVED)

    /** Removes the incident from this phone's list only, without sending anything. */
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

    /** After staff mode is activated, also opens reports already in the store-and-forward cache. */
    fun reprocessCached() {
        scope.launch { engine.cachedReports().forEach { (packet, hops) -> onReport(packet, hops, alert = true) } }
    }

    // --- events ---

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
            // a new neighbor may be the path to staff the cancel hasn't found yet
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
        // if another team already confirmed receipt, a second RECEIVED would just be network noise
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
        // the report may arrive later by another route; the token can't be checked until then
        val now = clock.monoMs()
        earlyCancels.values.removeAll { now - it.atMs > EARLY_CANCEL_MS }
        earlyCancels.remove(key)
        earlyCancels[key] = Cancel(cancel.token, now)
        if (earlyCancels.size > MAX_EARLY_CANCELS) earlyCancels.remove(earlyCancels.keys.first())
    }

    /** Only the author's token cancels, and only while the incident isn't resolved. */
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

    /** Until the CANCELLED ACK arrives, a cancel can be lost like any packet; every resend is a new packet. */
    private fun retryCancels() {
        val now = clock.wallMs()
        cancelling.entries.removeAll { (id, c) ->
            now - c.atMs >= CANCEL_RETRY_MS || engine.ackStatus(id.hexToBytes()) >= AckStatus.RESOLVED
        }
        for ((id, c) in cancelling) engine.publishCancel(id.hexToBytes(), c.token)
    }

    /** The mesh cache doesn't survive a restart; the cancellation state does. */
    private fun resumeCancels() {
        for (r in store.value.mine.filter { it.cancelled }) {
            engine.forgetReport(r.incidentId.hexToBytes())
            if (r.status < AckStatus.RESOLVED) cancelling[r.incidentId] = Cancel(r.cancelToken.hexToBytes(), r.updatedAt)
        }
    }

    /** While one of our reports hasn't reached staff, advertising flags it so neighbors prefer us. */
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

        /** Our report that hasn't reached staff yet and wasn't withdrawn. */
        fun isPending(report: MyReport, nowMs: Long): Boolean =
            report.status == AckStatus.NONE && !report.cancelled && nowMs - report.createdAt < PENDING_MS
    }
}
