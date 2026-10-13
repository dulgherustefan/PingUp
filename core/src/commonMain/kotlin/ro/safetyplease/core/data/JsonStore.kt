package ro.safetyplease.core.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.Volatile

/**
 * Small state kept in memory and saved to a JSON file. Writes are briefly debounced and atomic
 * (synced temp file + rename), so a process killed mid-write can't corrupt the data.
 *
 * The file holds `{"schemaVersion": n, "data": ...}`; a file from before versioning holds the bare data and counts
 * as version 1. Every write first copies the previous file to `.bak`, unless [keepsBackup] is false: then no `.bak` is
 * kept, and one a past version left goes at the next write. A file that can't be read is never written
 * over: the store starts from [default], keeps changes in memory only and reports it through [unreadable].
 */
class JsonStore<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val default: T,
    scope: CoroutineScope,
    private val schemaVersion: Int = 1,
    /** Brings data saved under an older [schemaVersion] up to date; gets the version it was saved with. */
    private val migrate: (fromVersion: Int, data: JsonElement) -> JsonElement = { _, data -> data },
    /** False for data that must be gone once it is taken out, which a backup would keep. */
    private val keepsBackup: Boolean = true,
) {
    private val backup = file.withSuffix(".bak")
    private val lock = SynchronizedObject()

    /** The file exists but couldn't be read (damaged, or saved by a newer version); it stays as it is. */
    @Volatile
    var unreadable = false
        private set

    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state
    val value: T get() = _state.value
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    /** Reset and not updated since: a write still pending from before must not bring the file back. */
    @Volatile
    private var cleared = false

    init {
        scope.launch {
            for (signal in dirty) {
                delay(150)
                write(_state.value)
            }
        }
    }

    fun update(transform: (T) -> T) {
        _state.update(transform)
        cleared = false
        dirty.trySend(Unit)
    }

    fun flush() = write(_state.value)

    /** Deletes the file and its backup, even an unreadable one, and starts over from [default]. */
    fun reset() {
        _state.value = default
        synchronized(lock) {
            fs.delete(file)
            fs.delete(backup)
            unreadable = false
            cleared = true
        }
    }

    private fun load(): T? {
        if (!fs.exists(file)) return null
        return try {
            val root = json.parseToJsonElement(fs.read(file) { readUtf8() })
            val wrapper = (root as? JsonObject)?.takeIf { VERSION_KEY in it && DATA_KEY in it }
            val version = wrapper?.getValue(VERSION_KEY)?.jsonPrimitive?.int ?: 1
            val data = wrapper?.getValue(DATA_KEY) ?: root
            check(version in 1..schemaVersion) { "schema version $version" }
            json.decodeFromJsonElement(serializer, if (version < schemaVersion) migrate(version, data) else data)
        } catch (_: Exception) {
            unreadable = true
            null
        }
    }

    private fun write(value: T) {
        synchronized(lock) {
            if (unreadable || cleared) return
            file.parent?.let { fs.createDirectories(it) }
            val tmp = file.withSuffix(".tmp")
            val stored = buildJsonObject {
                put(VERSION_KEY, schemaVersion)
                put(DATA_KEY, json.encodeToJsonElement(serializer, value))
            }
            // on disk before the rename, like Android's AtomicFile; otherwise a power cut can leave an empty file
            writeSynced(tmp, stored.toString().encodeToByteArray())
            if (!keepsBackup) fs.delete(backup) else if (fs.exists(file)) fs.copy(file, backup)
            fs.atomicMove(tmp, file)
        }
    }

    private companion object {
        val fs = FileSystem.SYSTEM
        const val VERSION_KEY = "schemaVersion"
        const val DATA_KEY = "data"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
