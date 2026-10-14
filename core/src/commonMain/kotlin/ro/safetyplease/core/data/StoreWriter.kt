package ro.safetyplease.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.IOException

/**
 * What became of a store's file: its writes fail (a full disk), it couldn't be read and lives in memory only, or it was
 * damaged and came back from its backup. The app can tell the person from it; nothing here stops the app.
 */
data class StoreHealth(val writeFailed: Boolean = false, val unreadable: Boolean = false, val restored: Boolean = false) {
    val ok: Boolean get() = !writeFailed && !unreadable && !restored

    /** Several stores as one: any of them is enough. */
    operator fun plus(other: StoreHealth) =
        StoreHealth(writeFailed || other.writeFailed, unreadable || other.unreadable, restored || other.restored)
}

/**
 * Writes a store's file [DEBOUNCE_MS] after a change, on [scope]. A write that fails with an I/O error, as on a full
 * disk, leaves the change pending: it is tried again after a pause that doubles from [FIRST_RETRY_MS] up to
 * [MAX_RETRY_MS], and [health] says the writes fail until one goes through.
 */
internal class StoreWriter(scope: CoroutineScope, private val health: MutableStateFlow<StoreHealth>, private val write: () -> Unit) {
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var pause = FIRST_RETRY_MS
            for (signal in dirty) {
                delay(DEBOUNCE_MS)
                if (attempt()) {
                    pause = FIRST_RETRY_MS
                } else {
                    delay(pause)
                    pause = minOf(pause * 2, MAX_RETRY_MS)
                    dirty.trySend(Unit)
                }
            }
        }
    }

    fun changed() {
        dirty.trySend(Unit)
    }

    /** Writes now, on the caller's thread; one that fails is left to the loop. */
    fun flush() {
        if (!attempt()) changed()
    }

    private fun attempt(): Boolean {
        val ok = try {
            write()
            true
        } catch (_: IOException) {
            false
        }
        health.update { it.copy(writeFailed = !ok) }
        return ok
    }

    companion object {
        const val DEBOUNCE_MS = 150L
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 60_000L
    }
}
