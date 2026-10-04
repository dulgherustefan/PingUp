package ro.safetyplease.app.incidents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.app.core.Clock
import ro.safetyplease.app.core.hexToBytes
import ro.safetyplease.app.core.toHex
import ro.safetyplease.app.core.truncateUtf8
import ro.safetyplease.app.crypto.Crypto
import ro.safetyplease.app.crypto.StaffCrypto
import ro.safetyplease.app.crypto.StaffSecretKeys
import ro.safetyplease.app.data.IncidentData
import ro.safetyplease.app.data.JsonStore
import ro.safetyplease.app.data.MyReport
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.mesh.MeshEngine
import ro.safetyplease.app.mesh.MeshEvent
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.app.protocol.IncidentAck
import ro.safetyplease.app.protocol.IncidentBody
import ro.safetyplease.app.protocol.IncidentReportCodec
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.Packet
import ro.safetyplease.app.protocol.PacketType

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
    private val onReportUpdate: (MyReport) -> Unit = {},
    private val onReportSent: () -> Unit = {},
) {
    private val packetToIncident = HashMap<Long, String>()

    fun start() {
        scope.launch { engine.events.collect(::onEvent) }
        scope.launch {
            refreshPending()
            while (isActive) {
                delay(30_000)
                refreshPending()
            }
        }
    }

    // --- participant ---

    /** Cate milisecunde mai are de asteptat utilizatorul pana poate raporta din nou; 0 daca poate acum. */
    fun rateLimitWaitMs(): Long {
        val now = clock.wallMs()
        val recent = store.value.mine.map { it.createdAt }.filter { now - it < RATE_WINDOW_MS }.sorted()
        if (recent.size < RATE_MAX) return 0
        return recent[recent.size - RATE_MAX] + RATE_WINDOW_MS - now
    }

    /** [bypassRateLimit] e folosit doar de incidentele de test din modul demo. */
    fun report(draft: ReportDraft, bypassRateLimit: Boolean = false): Boolean {
        if (!bypassRateLimit && rateLimitWaitMs() > 0) return false
        val now = clock.wallMs()
        val incidentId = crypto.random(INCIDENT_ID_SIZE)
        val description = draft.description.trim().take(Limits.DESCRIPTION_CHARS).truncateUtf8(Limits.DESCRIPTION_BYTES)
        val zone = draft.zone.truncateUtf8(Limits.ZONE_BYTES)
        val nickname = draft.nickname?.truncateUtf8(Limits.NICK_BYTES)?.ifEmpty { null }
        val report = MyReport(
            incidentId = incidentId.toHex(), category = draft.category, severity = draft.severity, zone = zone,
            lat = draft.lat, lon = draft.lon, description = description, anonymous = nickname == null,
            createdAt = now, updatedAt = now,
        )
        store.update { it.copy(mine = (it.mine + report).takeLast(MAX_MINE)) }
        scope.launch {
            val body = IncidentBody(draft.category, draft.severity, now / 1000, zone, draft.lat, draft.lon, description, nickname)
            val packet = engine.publishReport(incidentId, staffCrypto.sealReport(body.encode()))
            packetToIncident[packet.id] = report.incidentId
            // un telefon de staff nu isi primeste propriul raport din retea, asa ca il deschide direct
            onReport(packet, hops = 0, alert = false)
            refreshPending()
            onReportSent()
        }
        return true
    }

    // --- staff ---

    fun acknowledge(incidentId: String) = sendAck(incidentId, AckStatus.ACKNOWLEDGED)

    fun resolve(incidentId: String) = sendAck(incidentId, AckStatus.RESOLVED)

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
            }
            is MeshEvent.Sent -> {
                val incidentId = packetToIncident.remove(event.packetId) ?: return
                store.update { data -> data.copy(mine = data.mine.map { if (it.incidentId == incidentId) it.copy(sent = true) else it }) }
            }
            else -> Unit
        }
    }

    private fun onReport(packet: Packet, hops: Int, alert: Boolean) {
        val secret = staffSecret() ?: return
        val incidentId = IncidentReportCodec.incidentId(packet.payload)
        val key = incidentId.toHex()
        if (store.value.staff.any { it.incidentId == key }) return
        val plain = staffCrypto.openReport(IncidentReportCodec.sealed(packet.payload), secret) ?: return
        val body = IncidentBody.decode(plain) ?: return
        val now = clock.wallMs()
        val incident = StaffIncident(
            incidentId = key, category = body.category, severity = body.severity, zone = body.zone,
            lat = body.lat, lon = body.lon, description = body.description, nickname = body.nickname,
            reportedAt = body.timestamp * 1000, receivedAt = now, hops = hops,
        )
        store.update { it.copy(staff = (it.staff + incident).takeLast(MAX_STAFF)) }
        // daca alta echipa a confirmat deja primirea, un al doilea RECEIVED ar fi doar zgomot in retea
        val known = engine.cachedAck(incidentId)
        if (known != null) {
            applyAck(known)
        } else if (engine.ackStatus(incidentId) == AckStatus.NONE) {
            val ack = staffCrypto.signAck(incidentId, AckStatus.RECEIVED, now / 1000, teamName(), secret)
            engine.publishAck(ack)
            applyAck(ack)
        }
        if (alert) onStaffAlert(incident)
    }

    private fun applyAck(ack: IncidentAck) {
        val key = ack.incidentId.toHex()
        val now = clock.wallMs()
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
        refreshPending()
    }

    /** Cat timp un raport propriu nu a ajuns la staff, advertising-ul il anunta ca vecinii sa ne prefere. */
    private fun refreshPending() {
        val now = clock.wallMs()
        engine.setPendingIncident(store.value.mine.any { it.status == AckStatus.NONE && now - it.createdAt < PENDING_MS })
    }

    companion object {
        const val RATE_MAX = 3
        const val RATE_WINDOW_MS = 10 * 60_000L
        const val PENDING_MS = 30 * 60_000L
        const val MAX_MINE = 50
        const val MAX_STAFF = 300
    }
}
