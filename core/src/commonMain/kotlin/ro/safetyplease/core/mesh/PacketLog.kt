package ro.safetyplease.core.mesh

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.shortHex
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class LogKind { TX, RX, RELAY, DROP, LINK }

data class LogEntry(
    val timeMs: Long,
    val kind: LogKind,
    val type: Int,
    val id: Long,
    val ttl: Int,
    val link: String,
    val note: String,
) {
    fun format(): String {
        val head = "${formatTime(timeMs)} ${kind.name.padEnd(5)}"
        return if (kind == LogKind.LINK) "$head $link $note"
        else "$head ${PacketType.name(type).padEnd(8)} ${id.shortHex()} ttl=$ttl $link $note".trimEnd()
    }
}

/** Local time of day to the millisecond: 21:04:05.123. */
@OptIn(ExperimentalTime::class)
private fun formatTime(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault()).time
    fun two(v: Int) = v.toString().padStart(2, '0')
    return "${two(t.hour)}:${two(t.minute)}:${two(t.second)}.${(t.nanosecond / 1_000_000).toString().padStart(3, '0')}"
}

/** Live log for demo mode. Disabled in release, where [packet] and [link] do nothing. */
class PacketLog(private val enabled: Boolean, private val capacity: Int = 500) {
    private val buffer = ArrayDeque<LogEntry>()
    private val lock = SynchronizedObject()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    /** Mirror to logcat; set by the Android layer. */
    var sink: ((String) -> Unit)? = null

    fun packet(nowMs: Long, kind: LogKind, packet: Packet, link: String, note: String = "") =
        add(LogEntry(nowMs, kind, packet.type, packet.id, packet.ttl, link, note))

    fun link(nowMs: Long, link: String, note: String) = add(LogEntry(nowMs, LogKind.LINK, 0, 0, 0, link, note))

    private fun add(entry: LogEntry) {
        if (!enabled) return
        sink?.invoke(entry.format())
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun exportText(): String = _entries.value.joinToString("\n") { it.format() }
}
