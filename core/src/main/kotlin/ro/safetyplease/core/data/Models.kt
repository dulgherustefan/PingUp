package ro.safetyplease.core.data

import kotlinx.serialization.Serializable
import ro.safetyplease.core.util.toHex

@Serializable
enum class Role { PARTICIPANT, STAFF, ANCHOR }

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
    val teamName: String = "",
    val updatedAt: Long = 0,
    val sent: Boolean = false,
    /** Empty for reports made before cancelling existed; those can't be cancelled. */
    val cancelToken: String = "",
    /** Cancellation was requested; it's confirmed only when [status] becomes CANCELLED. */
    val cancelled: Boolean = false,
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
    val teamName: String = "",
    val statusAt: Long = 0,
    val cancelHash: String = "",
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
