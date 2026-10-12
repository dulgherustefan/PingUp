package ro.safetyplease.core.data

import kotlinx.serialization.Serializable
import ro.safetyplease.core.util.toHex

@Serializable
enum class Role {
    PARTICIPANT,
    STAFF,
    ANCHOR,

    /** Staff at the control-room console: the staff keys, plus the queue, the assignments and the shift log. */
    CONTROL,
    ;

    /** Opens reports and signs ACKs with the staff keys. */
    val hasStaffKeys: Boolean get() = this == STAFF || this == CONTROL
}

@Serializable
data class Settings(
    val nickname: String = "",
    val onboarded: Boolean = false,
    val role: Role = Role.PARTICIPANT,
    val teamName: String = "",
    val anchorZone: String = "",
    val ignoredPrefixes: List<Int> = emptyList(),
    val simLat: Double? = null,
    val simLon: Double? = null,
    val batteryHintDismissed: Boolean = false,
    /** Zone the user said they're in, used while there's no GPS fix. */
    val manualZone: String = "",
    /** The zone a staff or control QR names: urgencies there reach this phone on vibration. */
    val staffZone: String = "",
    /** "Șef de rol": the insistent alert rings here for the urgencies of the team's role, as on the console. */
    val roleLead: Boolean = false,
)

@Serializable
data class Friend(
    val nodeId: Long,
    val nickname: String,
    val boxKey: String,
    val signKey: String,
    val addedAt: Long,
    val lastSeenAt: Long = 0,
    val lastHops: Int = 0,
)

@Serializable
enum class MsgKind { TEXT, QUICK, ZONE, SYSTEM }

@Serializable
enum class MsgStatus { QUEUED, SENT, DELIVERED, FAILED, RECEIVED }

@Serializable
data class ChatMessage(
    val msgId: Long,
    val conversation: String,
    val senderId: Long,
    val fromMe: Boolean,
    val kind: MsgKind,
    val text: String = "",
    val quickCode: Int = 0,
    val zone: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val timeMs: Long,
    val status: MsgStatus,
    val hops: Int = 0,
    val recipients: Int = 1,
    val delivered: Int = 0,
    val read: Boolean = true,
)

@Serializable
data class OutboxItem(
    val msgId: Long,
    val recipient: Long,
    val innerHex: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
)

@Serializable
data class ChatData(
    val messages: List<ChatMessage> = emptyList(),
    val outbox: List<OutboxItem> = emptyList(),
)

@Serializable
data class GroupMember(val nodeId: Long, val nickname: String, val boxKey: String)

@Serializable
data class Group(val id: Long, val name: String, val members: List<GroupMember>, val createdAt: Long)

@Serializable
data class MyReport(
    val incidentId: String,
    val category: Int,
    val severity: Int,
    val zone: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val description: String = "",
    val anonymous: Boolean = true,
    val createdAt: Long,
    val status: Int = 0,
    /** How many times control reopened the incident: [status] belongs to this epoch. */
    val epoch: Int = 0,
    val teamName: String = "",
    /** The control console gave the incident to [teamName]; the team hasn't taken it itself. */
    val assigned: Boolean = false,
    val updatedAt: Long = 0,
    val sent: Boolean = false,
    /** Empty for reports made before cancelling existed; those can't be cancelled. */
    val cancelToken: String = "",
    /** Cancellation was requested; it's confirmed only when [status] becomes CANCELLED. */
    val cancelled: Boolean = false,
    /** The report as sealed for staff, hex; published again after a restart while it's pending, then cleared. */
    val sealed: String = "",
)

@Serializable
data class StaffIncident(
    val incidentId: String,
    val category: Int,
    val severity: Int,
    val zone: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val description: String = "",
    val nickname: String? = null,
    val reportedAt: Long,
    val receivedAt: Long,
    val hops: Int,
    val status: Int = 0,
    /** How many times control reopened the incident: [status] belongs to this epoch. */
    val epoch: Int = 0,
    val teamName: String = "",
    /** The control console gave the incident to [teamName]; the team hasn't taken it itself. */
    val assigned: Boolean = false,
    val statusAt: Long = 0,
    val cancelHash: String = "",
    /** The reporter cancelled, but the incident waits for an operator to confirm it or close it otherwise. */
    val cancelRequested: Boolean = false,
    /** Triage answers ([ro.safetyplease.core.protocol.Answer]); UNASKED outside medical reports. */
    val responds: Int = 0,
    val breathes: Int = 0,
    /** Written by staff: [nickname] is then the team, and [people] and [dangerContinues] come with it. */
    val fromStaff: Boolean = false,
    /** How many people are hurt or involved; 0 when not given. */
    val people: Int = 0,
    val dangerContinues: Boolean = false,
    /** How far off [lat] and [lon] may be, in metres; 0 when unknown. */
    val accuracyM: Int = 0,
    /** Number of the landmark nearest to the reporter, on the site plan; 0 for none. */
    val landmark: Int = 0,
)

@Serializable
data class IncidentData(
    val mine: List<MyReport> = emptyList(),
    val staff: List<StaffIncident> = emptyList(),
    /** Incidents staff removed from their list; they don't reopen if the report arrives again from the network. */
    val dismissed: List<String> = emptyList(),
    /** Creation times of our own deleted reports, kept while they still count toward the rate limit. */
    val deletedReportTimes: List<Long> = emptyList(),
)

object Conversations {
    fun friend(nodeId: Long) = "f${nodeId.toHex()}"
    fun group(groupId: Long) = "g${groupId.toHex()}"
    fun isGroup(conversation: String) = conversation.startsWith("g")
}
