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
import ro.safetyplease.core.data.IncidentUpdate
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.ReportResponse
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.metrics.timedAck
import ro.safetyplease.core.protocol.AckFlags
import ro.safetyplease.core.protocol.AckOrder
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentBody
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.protocol.IncidentResponse
import ro.safetyplease.core.protocol.IncidentUpdateCodec
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.LocationFields
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.ResponseBody
import ro.safetyplease.core.protocol.StaffFields
import ro.safetyplease.core.protocol.Triage
import ro.safetyplease.core.protocol.UpdateBody
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
    val triage: Triage? = null,
    /** Only a staff phone sends these; its report then carries the team's name instead of [nickname]. */
    val staff: StaffFields? = null,
    /** "Cum te recunoaștem?", sent on the first line of the description ([Recognition]). */
    val recognition: String = "",
    val location: LocationFields? = null,
    /** Reported later, anonymously or for someone else ([ReportExtras]); an anonymous report has no reply key. */
    val extras: ReportExtras = ReportExtras.NONE,
)

/** What the reporter says after its report ([ro.safetyplease.core.protocol.UpdateKind]); a move says where to. */
class UpdateDraft(
    val kind: Int,
    val zone: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val location: LocationFields? = null,
)

/**
 * Incident flow. A participant seals the report to staff and tracks the ACKs;
 * a staff phone opens it, auto-confirms receipt, and moves it through the field states; the control console closes,
 * reopens or confirms a cancel. The author can withdraw a report with a token only they know: staff confirms with
 * ACK CANCELLED, by itself where a wrong cancel costs little and after an operator checks everywhere else.
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
    /** A new response from the console to one of our reports, already stored with it. */
    private val onReportResponse: (MyReport) -> Unit = {},
    /** Every ACK that becomes an incident's state on the network: [own] if signed here, with the hops it traveled. */
    private val onAck: (ack: IncidentAck, own: Boolean, hops: Int) -> Unit = { _, _, _ -> },
    /** How many incidents staff keeps; the control console keeps the whole event. */
    private val staffCap: () -> Int = { MAX_STAFF },
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
            republishPending()
        }
        scope.launch {
            while (isActive) {
                delay(CANCEL_RETRY_INTERVAL_MS)
                retryCancels()
            }
        }
    }

    // --- participant ---

    /** Reports allowed in [RATE_WINDOW_MS]: staff, who report as their job, get more room. */
    fun rateLimitMax(): Int = if (staffSecret() != null) STAFF_RATE_MAX else RATE_MAX

    /** Milliseconds until the user may report again; 0 if they can now. */
    fun rateLimitWaitMs(): Long {
        val now = clock.wallMs()
        val data = store.value
        val max = rateLimitMax()
        val recent = (data.mine.map { it.createdAt } + data.deletedReportTimes).filter { now - it < RATE_WINDOW_MS }.sorted()
        if (recent.size < max) return 0
        return recent[recent.size - max] + RATE_WINDOW_MS - now
    }

    /** [bypassRateLimit] is only used by the demo's test incidents. */
    fun report(draft: ReportDraft, bypassRateLimit: Boolean = false): Boolean {
        if (!bypassRateLimit && rateLimitWaitMs() > 0) return false
        val now = clock.wallMs()
        val incidentId = crypto.random(INCIDENT_ID_SIZE)
        val cancelToken = crypto.random(IncidentCancel.TOKEN_SIZE)
        // an anonymous report can't be answered, so it carries nothing to answer to
        val anonymous = draft.extras.anonymous
        val reply = if (anonymous) null else crypto.boxKeyPair()
        val zone = draft.zone.truncateUtf8(Limits.ZONE_BYTES)
        // until reports are signed (2.18), the team named in the staff QR stands for who wrote it
        val staff = draft.staff?.takeIf { staffSecret() != null }
        val nickname = (if (staff != null) teamName() else draft.nickname.takeUnless { anonymous })?.truncateUtf8(Limits.NICK_BYTES)?.ifEmpty { null }
        fun body(description: String) = IncidentBody(
            draft.category, draft.severity, now / 1000, zone, draft.lat, draft.lon, description, nickname,
            IncidentCancel.hash(cancelToken), draft.triage, staff, draft.location, reply?.publicKey, draft.extras,
        )
        // with every other field full, the reply key leaves the longest descriptions a few bytes short
        val room = minOf(Limits.DESCRIPTION_BYTES, IncidentBody.MAX_SIZE - body("").encode().size)
        val description = Recognition.join(draft.recognition, draft.description.trim().take(Limits.DESCRIPTION_CHARS)).truncateUtf8(room)
        val sealed = staffCrypto.sealReport(body(description).encode())
        val report = MyReport(
            incidentId = incidentId.toHex(), category = draft.category, severity = draft.severity, zone = zone,
            lat = draft.lat, lon = draft.lon, description = description, anonymous = nickname == null,
            createdAt = now, updatedAt = now, cancelToken = cancelToken.toHex(), sealed = sealed.toHex(),
            replyKey = reply?.publicKey?.toHex().orEmpty(), replySecret = reply?.secretKey?.toHex().orEmpty(),
        )
        store.update { it.copy(mine = (it.mine.withoutStaleSeals(now) + report).takeLast(MAX_MINE)) }
        scope.launch {
            val packet = engine.publishReport(incidentId, sealed)
            packetToIncident[packet.id] = report.incidentId
            // a staff phone never receives its own report from the network, so it opens it directly
            onReport(packet, hops = 0, alert = false)
            onReportSent()
        }
        return true
    }

    /**
     * Withdraws one of our reports that staff hasn't closed yet. The cancel is resent on every new neighbor
     * and every minute until the incident is closed or [CANCEL_RETRY_MS] passes. The report keeps spreading, so every
     * staff phone sees both, unless [forgetsWithCancel].
     */
    fun cancel(incidentId: String) {
        val report = store.value.mine.firstOrNull { it.incidentId == incidentId } ?: return
        if (report.cancelled || AckStatus.isClosed(report.status) || report.cancelToken.isEmpty()) return
        val now = clock.wallMs()
        store.update { data ->
            val mine = data.mine.map { if (it.incidentId == incidentId) it.copy(cancelled = true, updatedAt = now) else it }
            data.copy(mine = mine.withoutStaleSeals(now))
        }
        val id = incidentId.hexToBytes()
        val token = report.cancelToken.hexToBytes()
        scope.launch {
            if (forgetsWithCancel(report)) engine.forgetReport(id)
            cancelling[incidentId] = Cancel(token, now)
            engine.publishCancel(id, token)
            // a staff phone never receives its own packet from the network, so it applies the cancel itself
            onCancel(IncidentCancel(id, token))
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

    /** Milliseconds until the report can take another update; null if it never can again (closed, cancelled or full). */
    fun updateWaitMs(incidentId: String): Long? {
        val report = store.value.mine.firstOrNull { it.incidentId == incidentId } ?: return null
        if (!canUpdate(report)) return null
        val last = report.updates.lastOrNull()?.at ?: return 0
        return maxOf(0, last + UPDATE_INTERVAL_MS - clock.wallMs())
    }

    /**
     * Tells staff what changed since the report: sealed to them like it, with the cancel token to show it comes from
     * the author. Kept and served like the report, the latest of them; at most one every [UPDATE_INTERVAL_MS].
     */
    fun update(incidentId: String, draft: UpdateDraft): Boolean {
        if (updateWaitMs(incidentId) != 0L) return false
        val report = store.value.mine.first { it.incidentId == incidentId }
        val now = clock.wallMs()
        val zone = draft.zone?.truncateUtf8(Limits.ZONE_BYTES)
        val seq = report.updates.size + 1
        val body = UpdateBody(draft.kind, now / 1000, report.cancelToken.hexToBytes(), zone, draft.lat, draft.lon, draft.location)
        val sealed = staffCrypto.sealReport(body.encode())
        val entry = IncidentUpdate(
            seq, draft.kind, now, zone, draft.lat, draft.lon, draft.location?.accuracyM ?: 0, draft.location?.landmark ?: 0,
        )
        store.update { data -> data.copy(mine = data.mine.map { if (it.incidentId == incidentId) it.copy(updates = it.updates + entry) else it }) }
        scope.launch {
            val packet = engine.publishUpdate(incidentId.hexToBytes(), seq, sealed)
            // as with the report, a staff phone never receives its own update from the network
            onUpdate(packet.payload)
        }
        return true
    }

    // --- staff ---

    fun acknowledge(incidentId: String) = sendAck(incidentId, AckStatus.ACKNOWLEDGED)

    fun onScene(incidentId: String) = sendAck(incidentId, AckStatus.ON_SCENE)

    fun handOver(incidentId: String) = sendAck(incidentId, AckStatus.HANDED_OVER)

    fun resolve(incidentId: String) = sendAck(incidentId, AckStatus.RESOLVED)

    // --- control console; a team's certificate can't sign these (the build's shared key still can) ---

    fun close(incidentId: String) = sendAck(incidentId, AckStatus.CLOSED_BY_CONTROL)

    fun unfounded(incidentId: String) = sendAck(incidentId, AckStatus.UNFOUNDED)

    /** The reporter's cancel, checked by the operator. */
    fun confirmCancel(incidentId: String) = sendAck(incidentId, AckStatus.CANCELLED)

    /** Starts the next epoch, so the incident opens again everywhere, whatever closed it. */
    fun reopen(incidentId: String) = sendAck(incidentId, AckStatus.REOPENED)

    /**
     * Gives the incident to [team]: an ACKNOWLEDGED signed in that team's name and flagged as an assignment, which
     * its phones show as theirs. Goes out only while nobody has taken the incident; the protocol can't reassign yet.
     */
    fun assign(incidentId: String, team: String) = sendAck(incidentId, AckStatus.ACKNOWLEDGED, team, AckFlags.ASSIGNED)

    /**
     * Answers the reporter with a predefined [ResponseCode][ro.safetyplease.core.protocol.ResponseCode], sealed to the
     * report's reply key and signed like an ACK. Only a control shift's certificate signs one that relays accept
     * (the build's shared key still can). Nothing goes out for a report without a reply key.
     */
    fun respond(incidentId: String, code: Int, landmark: Int = 0) {
        scope.launch {
            val secret = staffSecret() ?: return@launch
            val incident = store.value.staff.firstOrNull { it.incidentId == incidentId }?.takeIf { it.replyKey.isNotEmpty() } ?: return@launch
            // relays keep the latest response by its second, so two never share one
            val time = maxOf(clock.wallMs() / 1000, (incident.responses.lastOrNull()?.at ?: 0) / 1000 + 1)
            val response = staffCrypto.signResponse(incidentId.hexToBytes(), time, ResponseBody(code, landmark), incident.replyKey.hexToBytes(), secret)
            if (engine.publishResponse(response) == null) return@launch
            val sent = ReportResponse(code, landmark, time * 1000, clock.wallMs())
            store.update { data ->
                data.copy(staff = data.staff.map { if (it.incidentId == incidentId) it.copy(responses = it.responses + sent) else it })
            }
            // a console that wrote the report itself never receives the response from the network
            onResponse(response)
        }
    }

    /** Removes the incident from this phone's list only, without sending anything. */
    fun dismiss(incidentId: String) {
        store.update { data ->
            data.copy(
                staff = data.staff.filterNot { it.incidentId == incidentId },
                dismissed = (data.dismissed - incidentId + incidentId).takeLast(staffCap()),
            )
        }
        onStaffAlertCleared(incidentId)
    }

    private fun sendAck(incidentId: String, status: Int, team: String? = null, flags: Int = 0) {
        scope.launch {
            val secret = staffSecret() ?: return@launch
            val name = (team ?: teamName()).truncateUtf8(Limits.TEAM_BYTES)
            val id = incidentId.hexToBytes()
            val time = clock.wallMs() / 1000
            val known = epochOf(incidentId)
            val epoch = if (status == AckStatus.REOPENED) known + 1 else known
            if (epoch > IncidentAck.MAX_EPOCH) return@launch
            val ack = staffCrypto.signAck(id, status, time, name, secret, flags, epoch)
            // an ACK under a certificate never reaches a 1.0 phone, so there is nothing to lead with
            if (epoch == 0 && secret.certificate == null) publishForOneZero(id, status, time, name, secret)?.let { lead ->
                // the phone shows what the network has, also if the scope ends before the new status goes out
                applyAck(lead)
                delay(ONE_ZERO_LEAD_MS)
            }
            publishOwn(ack)
            applyAck(ack)
        }
    }

    /**
     * 1.0 phones get neither a status they don't know nor an ACK with an epoch. In epoch 0 such a status first goes
     * out as the closest one they know, so they show that; it leads by [ONE_ZERO_LEAD_MS], so relays keep and pass it
     * on before the new status replaces it. Null if nothing went out.
     */
    private fun publishForOneZero(id: ByteArray, status: Int, time: Long, team: String, secret: StaffSecretKeys): IncidentAck? {
        val legacy = AckStatus.legacy(status)
        if (legacy == status || legacy == AckStatus.NONE) return null
        return staffCrypto.signAck(id, legacy, time, team, secret).takeIf { engine.publishAck(it) != null }
    }

    /** The incident's epoch as far as this phone knows. */
    private fun epochOf(incidentId: String): Int {
        val data = store.value
        val stored = data.staff.firstOrNull { it.incidentId == incidentId }?.epoch ?: data.mine.firstOrNull { it.incidentId == incidentId }?.epoch ?: 0
        return maxOf(stored, engine.cachedAck(incidentId.hexToBytes())?.epoch ?: 0)
    }

    /** Nothing goes out if a better ACK is already known. */
    private fun publishOwn(ack: IncidentAck) {
        if (engine.publishAck(ack) != null) onAck(ack, true, 0)
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
                PacketType.INCIDENT_ACK -> IncidentAck.decode(event.packet.payload)?.let {
                    onAck(it, false, event.packet.hops)
                    applyAck(it, hops = event.packet.hops)
                }
                PacketType.INCIDENT_CANCEL -> IncidentCancel.decode(event.packet.payload)?.let(::onCancel)
                PacketType.INCIDENT_RESPONSE -> IncidentResponse.decode(event.packet.payload)?.let(::onResponse)
                PacketType.INCIDENT_UPDATE -> onUpdate(event.packet.payload)
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
            responds = body.triage?.responds ?: Answer.UNASKED, breathes = body.triage?.breathes ?: Answer.UNASKED,
            fromStaff = body.staff != null, people = body.staff?.people ?: 0, dangerContinues = body.staff?.dangerContinues == true,
            accuracyM = body.location?.accuracyM ?: 0, landmark = body.location?.landmark ?: 0,
            replyKey = body.replyKey?.toHex().orEmpty(),
            happenedMinAgo = body.extras.happenedMinAgo, witness = body.extras.witness, noReply = body.extras.anonymous,
        )
        store.update { it.copy(staff = (it.staff + incident).takeLast(staffCap())) }
        // an update that came before the report: relays keep the latest one, and so did this phone's cache
        engine.cachedUpdate(incidentId)?.let { onUpdate(it.payload) }
        // a reopen that came before the report raises no alert of its own: the one below does, once, if [alert]
        engine.cachedAck(incidentId)?.let { applyAck(it, alertOnReopen = false) }
        earlyCancels.remove(key)?.takeIf { clock.monoMs() - it.atMs <= EARLY_CANCEL_MS }?.let { tryCancel(key, it.token, secret) }
        // if another team already confirmed receipt, a second RECEIVED would just be network noise
        if (engine.ackStatus(incidentId) == AckStatus.NONE) {
            val ack = staffCrypto.signAck(incidentId, AckStatus.RECEIVED, now / 1000, teamName(), secret)
            publishOwn(ack)
            applyAck(ack)
        }
        val closed = store.value.staff.any { it.incidentId == key && AckStatus.isClosed(it.status) }
        if (alert && !closed && !Priority.isInformational(incident.category)) onStaffAlert(incident)
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

    /**
     * Only the author's token cancels, and only while the incident is open. Where a wrong cancel could leave someone
     * without help, it only marks the incident for an operator to check.
     */
    private fun tryCancel(key: String, token: ByteArray, secret: StaffSecretKeys) {
        val incident = store.value.staff.firstOrNull { it.incidentId == key } ?: return
        if (incident.cancelHash.isEmpty() || AckStatus.isClosed(incident.status)) return
        if (!IncidentCancel.hash(token).contentEquals(incident.cancelHash.hexToBytes())) return
        if (Priority.cancelNeedsReview(incident.category, incident.severity) || incident.epoch > 0) {
            if (!incident.cancelRequested) {
                store.update { data -> data.copy(staff = data.staff.map { if (it.incidentId == key) it.copy(cancelRequested = true) else it }) }
            }
            return
        }
        val ack = staffCrypto.signAck(key.hexToBytes(), AckStatus.CANCELLED, clock.wallMs() / 1000, teamName(), secret)
        publishOwn(ack)
        applyAck(ack)
    }

    /** A response to one of our reports, opened with its reply key; a copy or one for someone else is ignored. */
    private fun onResponse(response: IncidentResponse) {
        val key = response.incidentId.toHex()
        val at = response.timestamp * 1000
        val report = store.value.mine.firstOrNull { it.incidentId == key && it.replySecret.isNotEmpty() } ?: return
        if (report.responses.any { it.at == at }) return
        val plain = crypto.sealOpen(response.sealed, report.replyKey.hexToBytes(), report.replySecret.hexToBytes()) ?: return
        val body = ResponseBody.decode(plain) ?: return
        val entry = ReportResponse(body.code, body.landmark, at, clock.wallMs())
        var updated: MyReport? = null
        store.update { data ->
            data.copy(
                mine = data.mine.map {
                    if (it.incidentId == key) it.copy(responses = (it.responses + entry).sortedBy { r -> r.at }).also { r -> updated = r } else it
                },
            )
        }
        updated?.let(onReportResponse)
    }

    /**
     * The reporter's update of an incident this staff phone holds, if it carries the author's cancel token. One that
     * comes before the report waits in the mesh cache (see [onReport]).
     */
    private fun onUpdate(payload: ByteArray) {
        val secret = staffSecret() ?: return
        if (!IncidentUpdateCodec.isWellFormed(payload)) return
        val key = IncidentUpdateCodec.incidentId(payload).toHex()
        val seq = IncidentUpdateCodec.seq(payload)
        val incident = store.value.staff.firstOrNull { it.incidentId == key } ?: return
        if (incident.cancelHash.isEmpty() || incident.updates.any { it.seq == seq }) return
        val plain = staffCrypto.openReport(IncidentUpdateCodec.sealed(payload), secret) ?: return
        val body = UpdateBody.decode(plain) ?: return
        if (!IncidentCancel.hash(body.token).contentEquals(incident.cancelHash.hexToBytes())) return
        val entry = IncidentUpdate(
            seq, body.kind, body.timestamp * 1000, body.zone, body.lat, body.lon, body.location?.accuracyM ?: 0, body.location?.landmark ?: 0,
        )
        store.update { data ->
            data.copy(
                staff = data.staff.map {
                    if (it.incidentId == key) it.copy(updates = (it.updates + entry).sortedBy { u -> u.seq }) else it
                },
            )
        }
    }

    /** [hops] is how far an ACK from the network traveled; null for one signed here, which times nothing. */
    private fun applyAck(ack: IncidentAck, alertOnReopen: Boolean = true, hops: Int? = null) {
        val key = ack.incidentId.toHex()
        val now = clock.wallMs()
        val before = store.value.staff.firstOrNull { it.incidentId == key }?.takeIf { supersedes(ack, it.status, it.epoch, it.assigned) }
        var updated: MyReport? = null
        store.update { data ->
            data.copy(
                mine = data.mine.map {
                    if (it.incidentId != key) return@map it
                    val timed = if (hops != null) it.timedAck(ack.status, hops, now) else it
                    if (supersedes(ack, it.status, it.epoch, it.assigned)) {
                        timed.copy(
                            status = ack.status, epoch = ack.epoch, teamName = ack.teamName, assigned = ack.assigned,
                            updatedAt = now, sent = true,
                        ).also { r -> updated = r }
                    } else timed
                }.withoutStaleSeals(now),
                staff = data.staff.map {
                    if (it.incidentId == key && supersedes(ack, it.status, it.epoch, it.assigned)) {
                        it.copy(
                            status = ack.status, epoch = ack.epoch, teamName = ack.teamName, assigned = ack.assigned, statusAt = now,
                            // a reopen is a new decision; a cancel from before it is checked again only if it comes again
                            cancelRequested = it.cancelRequested && ack.epoch == it.epoch,
                        )
                    } else it
                },
            )
        }
        updated?.let(onReportUpdate)
        if (before == null) return
        if (ack.status in CLEARS_ALERT && before.status !in CLEARS_ALERT) onStaffAlertCleared(key)
        if (alertOnReopen && ack.status == AckStatus.REOPENED && !Priority.isInformational(before.category)) {
            store.value.staff.firstOrNull { it.incidentId == key }?.let(onStaffAlert)
        }
    }

    /** Until the incident is closed, a cancel can be lost like any packet; every resend is a new packet. */
    private fun retryCancels() {
        val now = clock.wallMs()
        cancelling.entries.removeAll { (id, c) ->
            now - c.atMs >= CANCEL_RETRY_MS || AckStatus.isClosed(engine.ackStatus(id.hexToBytes()))
        }
        for ((id, c) in cancelling) engine.publishCancel(id.hexToBytes(), c.token)
    }

    /** The mesh cache doesn't survive a restart; the cancellation state does. */
    private fun resumeCancels() {
        for (r in store.value.mine.filter { it.cancelled }) {
            if (forgetsWithCancel(r)) engine.forgetReport(r.incidentId.hexToBytes())
            if (!AckStatus.isClosed(r.status)) cancelling[r.incidentId] = Cancel(r.cancelToken.hexToBytes(), r.updatedAt)
        }
    }

    /** The cache was lost with the process; a report still waiting for staff must not be lost with it. */
    private fun republishPending() {
        val now = clock.wallMs()
        if (store.value.mine.any { it.sealed.isNotEmpty() && !isPending(it, now) }) {
            store.update { it.copy(mine = it.mine.withoutStaleSeals(now)) }
        }
        val again = store.value.mine.filter { isPending(it, now) && it.sealed.isNotEmpty() }.map { it.incidentId }.toSet()
        if (again.isEmpty()) return
        store.update { data -> data.copy(mine = data.mine.map { if (it.incidentId in again) it.copy(republished = it.republished + 1) else it }) }
        for (r in store.value.mine.filter { it.incidentId in again }) {
            val packet = engine.publishReport(r.incidentId.hexToBytes(), r.sealed.hexToBytes())
            packetToIncident[packet.id] = r.incidentId
        }
    }

    companion object {
        const val RATE_MAX = 3

        /** A supervisor in a busy zone writes more than three in ten minutes; the relays' per-link limit still applies. */
        const val STAFF_RATE_MAX = 20
        const val RATE_WINDOW_MS = 10 * 60_000L

        /** How long the status a 1.0 phone can read goes out before the new one (see [publishForOneZero]). */
        const val ONE_ZERO_LEAD_MS = 2_000L
        const val PENDING_MS = 30 * 60_000L
        const val CANCEL_RETRY_MS = 30 * 60_000L
        const val CANCEL_RETRY_INTERVAL_MS = 60_000L
        const val MAX_MINE = 50
        const val MAX_STAFF = 300

        /** Updates of one report; relays rate-limit them per link as well. */
        const val MAX_UPDATES = 10
        const val UPDATE_INTERVAL_MS = 60_000L

        /** Far more than one event brings, so the console never drops an incident from its queue. */
        const val MAX_STAFF_CONSOLE = 5_000
        private const val EARLY_CANCEL_MS = 60 * 60_000L
        private const val MAX_EARLY_CANCELS = 64
        private const val LINK_SETTLE_MS = 1_500L

        /** Closed by a decision that nobody needs to go any more; a team's RESOLVED leaves the alert to them. */
        private val CLEARS_ALERT = setOf(AckStatus.CANCELLED, AckStatus.UNFOUNDED, AckStatus.CLOSED_BY_CONTROL)

        /** Later in the supersede order, or, at the same place, a team's own take where the phone showed an assignment. */
        private fun supersedes(ack: IncidentAck, status: Int, epoch: Int, assigned: Boolean): Boolean {
            val order = AckOrder.compare(ack.epoch, ack.status, epoch, status)
            return order > 0 || (order == 0 && assigned && !ack.assigned)
        }

        /**
         * The reporter's cancel waits for an operator: see [Priority.cancelNeedsReview]; after a reopen, always. The
         * reporter knows only the epochs that reached it: after a reopen it missed, staff checks a cancel this calls
         * unchecked, and the reporter learns so from the next summary.
         */
        fun cancelIsChecked(report: MyReport): Boolean = Priority.cancelNeedsReview(report.category, report.severity) || report.epoch > 0

        /**
         * A withdrawn report leaves our cache only if no staff phone confirmed it and its cancel closes it by itself.
         * A report staff has may have been reopened without our knowing, and then staff checks the cancel: it stays.
         */
        private fun forgetsWithCancel(report: MyReport): Boolean = report.status == AckStatus.NONE && !cancelIsChecked(report)

        /** Only the author updates, with the cancel token; a closed or withdrawn report has nothing left to say. */
        fun canUpdate(report: MyReport): Boolean = report.cancelToken.isNotEmpty() && !report.cancelled &&
            !AckStatus.isClosed(report.status) && report.updates.size < MAX_UPDATES

        /** Our report that hasn't reached staff yet and wasn't withdrawn. */
        fun isPending(report: MyReport, nowMs: Long): Boolean =
            report.status == AckStatus.NONE && !report.cancelled && nowMs - report.createdAt < PENDING_MS

        /** When the last of our pending reports stops being pending, unless staff answers first; null if none is. */
        fun pendingUntil(mine: List<MyReport>, nowMs: Long): Long? =
            mine.filter { isPending(it, nowMs) }.maxOfOrNull { it.createdAt + PENDING_MS }

        /** The sealed copy is only for publishing a pending report again; once it stops being pending, it goes. */
        private fun List<MyReport>.withoutStaleSeals(nowMs: Long): List<MyReport> =
            map { if (it.sealed.isNotEmpty() && !isPending(it, nowMs)) it.copy(sealed = "") else it }
    }
}
