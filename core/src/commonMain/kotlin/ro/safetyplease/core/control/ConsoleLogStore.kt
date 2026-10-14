package ro.safetyplease.core.control

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import ro.safetyplease.core.data.StoreHealth
import ro.safetyplease.core.data.StoreWriter
import ro.safetyplease.core.data.appendSynced
import ro.safetyplease.core.data.withSuffix
import ro.safetyplease.core.data.writeSynced
import kotlin.concurrent.Volatile

/**
 * The console log on disk, which only grows: a header line, then one JSON line per entry, without the fields at their
 * default value. New entries are appended, a few hundred bytes each, where a busy evening used to rewrite megabytes at
 * every ACK. The file is written whole again (compacted, through a synced temporary file) only when it holds a line it
 * can't read or one cut short, after a change that isn't an append ([update]), and the first time after an older
 * version, whose single JSON object under the same name is read as it was. No `.bak` is kept: an append never touches
 * what is already on disk. A write that fails, as on a full disk, waits in memory and is tried again ([health]); a file
 * from a newer version, or one that can't be read at all, is never written over.
 */
class ConsoleLogStore(private val file: Path, scope: CoroutineScope) {
    private val lock = SynchronizedObject()

    /** The file couldn't be read; it stays as it is, and the log lives in memory only. */
    @Volatile
    var unreadable = false
        private set

    // under the lock: how many entries the file holds, whether it has to be written whole, and whether it is there at all
    private var written = 0
    private var compact = false
    private var onDisk = true

    private val _state: MutableStateFlow<ConsoleLog>
    val state: StateFlow<ConsoleLog> get() = _state
    val value: ConsoleLog get() = _state.value

    private val _health: MutableStateFlow<StoreHealth>

    /** Whether the file keeps up: writes failing, unreadable, or an older file read from its backup. */
    val health: StateFlow<StoreHealth> get() = _health

    init {
        val read = parse(file)
        unreadable = read.unreadable
        compact = read.compact
        onDisk = read.onDisk
        written = read.log.entries.size
        _state = MutableStateFlow(read.log)
        _health = MutableStateFlow(StoreHealth(unreadable = read.unreadable, restored = read.restored))
    }

    private val writer = StoreWriter(scope, _health) { write() }

    fun append(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        _state.update { it.copy(entries = it.entries + entries) }
        writer.changed()
    }

    /** A change that isn't an append, as the demo taking its entries back: the file is written whole. */
    fun update(transform: (ConsoleLog) -> ConsoleLog) {
        synchronized(lock) {
            compact = true
            _state.update(transform)
        }
        writer.changed()
    }

    /** Writes now what waits, and nothing when nothing does; never throws: a write that fails is tried again later. */
    fun flush() = writer.flush()

    private fun write() {
        synchronized(lock) {
            if (unreadable) return
            val entries = _state.value.entries
            if (!compact && entries.size == written) return
            try {
                if (compact || !onDisk || entries.size < written) {
                    rewrite(entries)
                } else {
                    appendSynced(file, lines(entries.subList(written, entries.size)))
                }
                written = entries.size
                compact = false
                onDisk = true
            } catch (e: IOException) {
                // an append may have stopped halfway: the next write starts the file over
                compact = true
                throw e
            }
        }
    }

    private fun rewrite(entries: List<LogEntry>) {
        file.parent?.let { fs.createDirectories(it) }
        val tmp = file.withSuffix(".tmp")
        try {
            writeSynced(tmp, (HEADER + "\n").encodeToByteArray() + lines(entries))
            fs.delete(file.withSuffix(".bak"))
            fs.atomicMove(tmp, file)
        } catch (e: IOException) {
            runCatching { fs.delete(tmp) }
            throw e
        }
    }

    private class Parsed(
        val log: ConsoleLog,
        val compact: Boolean = false,
        val unreadable: Boolean = false,
        val restored: Boolean = false,
        val onDisk: Boolean = true,
    )

    companion object {
        private val fs = FileSystem.SYSTEM
        private const val FORMAT = "lines"

        /** What the first line says: the format, so the single object of older versions is told apart, and its version. */
        private const val HEADER = """{"format":"$FORMAT","schemaVersion":${ConsoleLog.SCHEMA_VERSION}}"""

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** The log as the file holds it, read once and never written: the other mode's, for the edition report. */
        fun read(file: Path): ConsoleLog = parse(file).log

        private fun lines(entries: List<LogEntry>): ByteArray =
            buildString { entries.forEach { append(json.encodeToString(LogEntry.serializer(), it)).append('\n') } }.encodeToByteArray()

        private fun parse(file: Path): Parsed {
            // the first entry writes the file, header first; a phone that is no console never makes one
            if (!fs.exists(file)) return Parsed(ConsoleLog(), onDisk = false)
            val text = try {
                fs.read(file) { readUtf8() }
            } catch (_: IOException) {
                return Parsed(ConsoleLog(), unreadable = true)
            }
            if (text.isBlank()) return Parsed(ConsoleLog(), compact = true)
            val lines = text.split('\n')
            val header = runCatching { json.parseToJsonElement(lines.first()) as? JsonObject }.getOrNull()
            if (header?.get("format")?.jsonPrimitive?.contentOrNull != FORMAT) {
                legacy(text)?.let { return it }
                // damaged: the backup an older version kept, if it reads
                val backup = file.withSuffix(".bak")
                val saved = if (fs.exists(backup)) runCatching { legacy(fs.read(backup) { readUtf8() }) }.getOrNull() else null
                return saved?.takeIf { !it.unreadable }?.let { Parsed(it.log, compact = true, restored = true) } ?: Parsed(ConsoleLog(), unreadable = true)
            }
            val version = header["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0
            if (version != ConsoleLog.SCHEMA_VERSION) return Parsed(ConsoleLog(), unreadable = true)
            val entries = ArrayList<LogEntry>(lines.size)
            // a power cut can leave the last line cut short, even one that still reads
            var damaged = !text.endsWith('\n')
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val entry = runCatching { json.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
                if (entry == null) damaged = true else entries += entry
            }
            return Parsed(ConsoleLog(entries), compact = damaged)
        }

        /**
         * The single JSON object an older version wrote, `{"schemaVersion": 1, "data": {"entries": [...]}}`, or the bare
         * data from before versioning; unreadable for a newer version's, null for anything else.
         */
        private fun legacy(text: String): Parsed? = try {
            val root = json.parseToJsonElement(text)
            val wrapper = (root as? JsonObject)?.takeIf { "schemaVersion" in it && "data" in it }
            val version = wrapper?.getValue("schemaVersion")?.jsonPrimitive?.int ?: 1
            if (version > 1) {
                Parsed(ConsoleLog(), unreadable = true)
            } else {
                Parsed(json.decodeFromJsonElement(ConsoleLog.serializer(), wrapper?.getValue("data") ?: root), compact = true)
            }
        } catch (_: Exception) {
            null
        }
    }
}
