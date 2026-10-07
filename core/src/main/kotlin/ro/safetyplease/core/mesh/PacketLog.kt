package ro.safetyplease.core.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.shortHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

private fun formatTime(ms: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ms)) }

/** Live log for demo mode. Disabled in release, where [packet] and [link] do nothing. */
class PacketLog(private val enabled: Boolean, private val capacity: Int = 500) {
    private val buffer = ArrayDeque<LogEntry>()
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
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    fun clear() = synchronized(buffer) {
        buffer.clear()
        _entries.value = emptyList()
    }

    fun exportText(): String = _entries.value.joinToString("\n") { it.format() }
}
