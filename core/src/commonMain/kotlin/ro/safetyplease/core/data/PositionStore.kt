package ro.safetyplease.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * The latest position a friend shared with this phone. [accuracy], [method] and [nextUpdate] keep the wire codes
 * (`AccuracyClass`, `PositionMethod`, `NextUpdate`); [fixAtMs] is the sender's fix on our wall clock, to the minute.
 * It is gone at [expiresAtMs], set here from the session, whatever the sender does.
 */
@Serializable
data class PositionRecord(
    val sender: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Int,
    val method: Int,
    val headingDeg: Int? = null,
    val fixAtMs: Long,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
    val seq: Int,
    val nextUpdate: Int,
    val hops: Int = 0,
) {
    /** A fix "from the future", from a clock running ahead, counts as new. */
    fun ageMs(nowMs: Long): Long = maxOf(0L, nowMs - fixAtMs)
}

/** A friend shares their position with us until [expiresAtMs], which we computed when the session arrived. */
@Serializable
data class IncomingShare(val sender: Long, val precision: Int, val expiresAtMs: Long)

/**
 * We share our position with a conversation (a friend or a group) until [expiresAtMs]. [accepted] are the
 * recipients whose phones answered this session's START: they read positions.
 */
@Serializable
data class OutgoingShare(
    val conversation: String,
    val precision: Int,
    val expiresAtMs: Long,
    val seq: Int,
    val accepted: List<Long> = emptyList(),
)

/** When a friend's shared position was last fixed; what is left once the record is gone. */
@Serializable
data class LastSeen(val sender: Long, val atMs: Long)

/**
 * Everything about shared positions on this phone: what friends share with us, what we share, and the phones that
 * showed they read positions ([readers]). Every function returns the same instance when nothing changes.
 */
@Serializable
data class PositionData(
    val records: List<PositionRecord> = emptyList(),
    val incoming: List<IncomingShare> = emptyList(),
    val shares: List<OutgoingShare> = emptyList(),
    val lastSeen: List<LastSeen> = emptyList(),
    val readers: List<Long> = emptyList(),
    /** "Unde ești?" from a friend we don't share with is answered with our position; off unless the user turns it on. */
    val autoAnswer: Boolean = false,
    /** The seq of this phone's last lost session or meeting point, kept so that a restart doesn't number from 1 again. */
    val meetingSeq: Int = MEETING_SEQ_START,
) {
    /** START or RENEW from [sender]: the session ends [durationMin] after now, at most [MAX_SHARE_MIN]. */
    fun startedBy(sender: Long, durationMin: Int, precision: Int, nowMs: Long): PositionData {
        val expires = nowMs + durationMin.coerceIn(0, MAX_SHARE_MIN) * 60_000L
        if (expires <= nowMs) return stoppedBy(sender)
        return copy(
            incoming = incoming.filterNot { it.sender == sender } + IncomingShare(sender, precision, expires),
            records = records.map { if (it.sender == sender) it.copy(expiresAtMs = minOf(expires, it.receivedAtMs + MAX_RECORD_MS)) else it },
        )
    }

    fun sessionOf(sender: Long, nowMs: Long): IncomingShare? = incoming.firstOrNull { it.sender == sender && it.expiresAtMs > nowMs }

    /** The session and its position are gone; only when it was last seen stays. */
    fun stoppedBy(sender: Long): PositionData {
        val record = records.firstOrNull { it.sender == sender }
        if (record == null && incoming.none { it.sender == sender }) return this
        return copy(
            records = records.filterNot { it.sender == sender },
            incoming = incoming.filterNot { it.sender == sender },
            lastSeen = if (record != null) seen(record) else lastSeen,
        )
    }

    /** Keeps [record] unless the one we have is newer: a later fix, or the same minute with a later [PositionRecord.seq]. */
    fun withPosition(record: PositionRecord): PositionData {
        val old = records.firstOrNull { it.sender == record.sender }
        if (old != null && !isNewer(record, old)) return this
        val capped = record.copy(expiresAtMs = minOf(record.expiresAtMs, record.receivedAtMs + MAX_RECORD_MS))
        return copy(records = records.filterNot { it.sender == record.sender } + capped)
    }

    /** Ends what expired by [nowMs], sessions and positions alike. */
    fun pruned(nowMs: Long): PositionData {
        val expired = records.filter { it.expiresAtMs <= nowMs }
        val endedIn = incoming.filter { it.expiresAtMs <= nowMs }
        val endedOut = shares.filter { it.expiresAtMs <= nowMs }
        if (expired.isEmpty() && endedIn.isEmpty() && endedOut.isEmpty()) return this
        var seen = lastSeen
        for (record in expired) seen = seen.filterNot { it.sender == record.sender } + LastSeen(record.sender, record.fixAtMs)
        return copy(records = records - expired.toSet(), incoming = incoming - endedIn.toSet(), shares = shares - endedOut.toSet(), lastSeen = seen)
    }

    fun withReader(nodeId: Long): PositionData = if (nodeId in readers) this else copy(readers = readers + nodeId)

    /** Someone blocked or no longer a friend: nothing of theirs is kept. */
    fun forget(nodeId: Long): PositionData {
        if (records.none { it.sender == nodeId } && incoming.none { it.sender == nodeId } && lastSeen.none { it.sender == nodeId } && nodeId !in readers) {
            return this
        }
        return copy(
            records = records.filterNot { it.sender == nodeId },
            incoming = incoming.filterNot { it.sender == nodeId },
            lastSeen = lastSeen.filterNot { it.sender == nodeId },
            readers = readers - nodeId,
        )
    }

    private fun seen(record: PositionRecord): List<LastSeen> = lastSeen.filterNot { it.sender == record.sender } + LastSeen(record.sender, record.fixAtMs)

    private fun isNewer(new: PositionRecord, old: PositionRecord): Boolean = when {
        new.fixAtMs != old.fixAtMs -> new.fixAtMs > old.fixAtMs
        // seq is a byte that wraps: the later of two is at most half the circle ahead
        else -> ((new.seq - old.seq) and 0xFF) in 1..127
    }

    companion object {
        /** The longest a session may last, and the longest a position is kept. */
        const val MAX_SHARE_MIN = 120
        const val MAX_RECORD_MS = MAX_SHARE_MIN * 60_000L

        /**
         * Where the meeting seq starts on a phone that kept none: an older version numbered from 1 after each start, so
         * this is later than what it can have sent since (fewer than 64 lost sessions and meeting points).
         */
        const val MEETING_SEQ_START = 64
    }
}

/**
 * [PositionData] in memory and, when opened on a file, saved to it through [JsonStore] (`positions.json`, with its
 * schemaVersion). Positions are short-lived; a damaged file only costs what friends shared until now.
 */
class PositionStore private constructor(private val file: JsonStore<PositionData>?) {
    private val lock = SynchronizedObject()
    private val memory = MutableStateFlow(file?.value ?: PositionData())

    val state: StateFlow<PositionData> get() = memory
    val value: PositionData get() = memory.value

    fun update(transform: (PositionData) -> PositionData) {
        synchronized(lock) {
            val old = memory.value
            val new = transform(old)
            if (new === old) return
            memory.value = new
            file?.update { new }
        }
    }

    /** Writes now what the file store still keeps back. */
    fun flush() {
        file?.flush()
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun open(file: Path, scope: CoroutineScope): PositionStore =
            PositionStore(JsonStore(file, PositionData.serializer(), PositionData(), scope, SCHEMA_VERSION))

        /** Kept for the life of the process only. */
        fun inMemory(): PositionStore = PositionStore(null)
    }
}
