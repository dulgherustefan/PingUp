package ro.safetyplease.core.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.Volatile

/**
 * Small state kept in memory and saved to a JSON file. Writes are briefly debounced and atomic
 * (synced temp file + rename), so a process killed mid-write can't corrupt the data. A write that fails, as on a full
 * disk, never stops the app: the change stays in memory and is written again later, and [health] says so meanwhile.
 *
 * The file holds `{"schemaVersion": n, "data": ...}`; a file from before versioning holds the bare data and counts
 * as version 1. Every write first copies the previous file to `.bak`, unless [keepsBackup] is false or the file is
 * over [BACKUP_MAX_BYTES]: then no `.bak` is kept, and one left from before goes at the next write. A file that can't be
 * read comes back from its `.bak` when that one holds this version's data, and is then written over; one from a newer
 * version, or without a usable backup, is never written over: the store starts from [default], keeps changes in memory
 * only and reports it through [unreadable].
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
    /**
     * False leaves out the fields at their default value, which makes a large store a fraction of the size. Only for
     * data whose defaults are constants that keep their meaning: a file read back takes the defaults of the reader.
     */
    encodeDefaults: Boolean = true,
) {
    private val backup = file.withSuffix(".bak")
    private val lock = SynchronizedObject()
    private val json = if (encodeDefaults) FULL else COMPACT

    /** The file exists but couldn't be read (damaged without a backup, or saved by a newer version); it stays as it is. */
    @Volatile
    var unreadable = false
        private set

    /** The file was damaged and the data came from the backup; the next write replaces the damaged file. */
    @Volatile
    var restored = false
        private set

    // the file on disk is the damaged one: the write that replaces it must not copy it over the good backup
    private var damaged = false

    private val _state = MutableStateFlow(load() ?: default)
    val state: StateFlow<T> = _state
    val value: T get() = _state.value

    private val _health = MutableStateFlow(StoreHealth(unreadable = unreadable, restored = restored))

    /** Whether the file keeps up: writes failing, unreadable, restored from the backup. */
    val health: StateFlow<StoreHealth> = _health

    /** Reset and not updated since: a write still pending from before must not bring the file back. */
    @Volatile
    private var cleared = false

    private val writer = StoreWriter(scope, _health) { write(_state.value) }

    fun update(transform: (T) -> T) {
        _state.update(transform)
        cleared = false
        writer.changed()
    }

    /** Writes now what waits; never throws: a write that fails is tried again later, as one after [update]. */
    fun flush() = writer.flush()

    /** Deletes the file and its backup, even an unreadable one, and starts over from [default]. */
    fun reset() {
        _state.value = default
        synchronized(lock) {
            try {
                fs.delete(file)
                fs.delete(backup)
            } catch (_: IOException) {
                // the next write replaces what stayed
            }
            unreadable = false
            restored = false
            damaged = false
            cleared = true
        }
        _health.update { StoreHealth(writeFailed = it.writeFailed) }
    }

    private fun load(): T? {
        if (!fs.exists(file)) return null
        return when (val main = read(file)) {
            is Read.Data -> main.value
            Read.Newer -> {
                unreadable = true
                null
            }
            Read.Damaged -> {
                // only a backup of this version: an older one would be migrated over newer files (the chat's)
                val saved = if (keepsBackup && fs.exists(backup)) read(backup) else null
                if (saved is Read.Data && saved.version == schemaVersion) {
                    restored = true
                    damaged = true
                    saved.value
                } else {
                    unreadable = true
                    null
                }
            }
        }
    }

    private sealed interface Read<out D> {
        class Data<D>(val value: D, val version: Int) : Read<D>

        /** Saved by a newer version: never written over. */
        data object Newer : Read<Nothing>

        /** Not JSON, not this data, or not readable at all. */
        data object Damaged : Read<Nothing>
    }

    private fun read(path: Path): Read<T> = try {
        val root = json.parseToJsonElement(fs.read(path) { readUtf8() })
        val wrapper = (root as? JsonObject)?.takeIf { VERSION_KEY in it && DATA_KEY in it }
        val version = wrapper?.getValue(VERSION_KEY)?.jsonPrimitive?.int ?: 1
        val data = wrapper?.getValue(DATA_KEY) ?: root
        when {
            version > schemaVersion -> Read.Newer
            version < 1 -> Read.Damaged
            else -> Read.Data(json.decodeFromJsonElement(serializer, if (version < schemaVersion) migrate(version, data) else data), version)
        }
    } catch (_: Exception) {
        Read.Damaged
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
            try {
                // on disk before the rename, like Android's AtomicFile; otherwise a power cut can leave an empty file
                writeSynced(tmp, stored.toString().encodeToByteArray())
                when {
                    damaged -> Unit
                    !keepsBackup || (fs.metadataOrNull(file)?.size ?: 0L) > BACKUP_MAX_BYTES -> fs.delete(backup)
                    fs.exists(file) -> fs.copy(file, backup)
                }
                fs.atomicMove(tmp, file)
                damaged = false
            } catch (e: IOException) {
                // a full disk: what was written of the temporary file would only hold on to the space
                runCatching { fs.delete(tmp) }
                throw e
            }
        }
    }

    companion object {
        /** Past this the previous file isn't copied to `.bak` at each write: the console's stores take megabytes. */
        const val BACKUP_MAX_BYTES = 256 * 1024L

        private val fs = FileSystem.SYSTEM
        private const val VERSION_KEY = "schemaVersion"
        private const val DATA_KEY = "data"

        private val FULL = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val COMPACT = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
