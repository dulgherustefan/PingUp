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
import java.io.File

/**
 * Stare mica tinuta in memorie si salvata intr-un fisier JSON. Scrierea e amanata putin si atomica
 * (fisier temporar + redenumire), ca un proces omorat la mijloc sa nu lase date corupte.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T,
    scope: CoroutineScope,
) {
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

    fun reset() {
        _state.value = default
        synchronized(file) { file.delete() }
    }

    private fun load(): T? = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun write(value: T) = synchronized(file) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
