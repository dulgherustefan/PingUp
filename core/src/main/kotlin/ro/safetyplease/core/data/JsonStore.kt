package ro.safetyplease.core.data

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
import java.io.File

/**
 * Small state kept in memory and saved to a JSON file. Writes are briefly debounced and atomic
 * (temp file + rename), so a process killed mid-write can't corrupt the data.
 *
 * The file holds `{"schemaVersion": n, "data": ...}`; a file from before versioning holds the bare data and counts
 * as version 1. Every write first copies the previous file to `.bak`. A file that can't be read is never written
 * over: the store starts from [default], keeps changes in memory only and reports it through [unreadable].
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T,
    scope: CoroutineScope,
    private val schemaVersion: Int = 1,
    /** Brings data saved under an older [schemaVersion] up to date; gets the version it was saved with. */
    private val migrate: (fromVersion: Int, data: JsonElement) -> JsonElement = { _, data -> data },
) {
    private val backup = File(file.path + ".bak")

    /** The file exists but couldn't be read (damaged, or saved by a newer version); it stays as it is. */
    @Volatile
    var unreadable = false
        private set

    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state
    val value: T get() = _state.value
    private val dirty = Channel<Unit>(Channel.CONFLATED)

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
        dirty.trySend(Unit)
    }

    fun flush() = write(_state.value)

    /** Deletes the file and its backup, even an unreadable one, and starts over from [default]. */
    fun reset() {
        _state.value = default
        synchronized(file) {
            file.delete()
            backup.delete()
            unreadable = false
        }
    }

    private fun load(): T? {
        if (!file.exists()) return null
        return try {
            val root = json.parseToJsonElement(file.readText())
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
        synchronized(file) {
            if (unreadable) return
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            val stored = buildJsonObject {
                put(VERSION_KEY, schemaVersion)
                put(DATA_KEY, json.encodeToJsonElement(serializer, value))
            }
            tmp.writeText(stored.toString())
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val VERSION_KEY = "schemaVersion"
        const val DATA_KEY = "data"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
