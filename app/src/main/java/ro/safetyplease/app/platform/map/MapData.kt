package ro.safetyplease.app.platform.map

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface MapState {
    data object Loading : MapState

    /** No base map in the APK, or it failed its check: the drawn venue map stands in. */
    data object Missing : MapState

    class Ready(val pack: MapPack) : MapState
}

enum class MapKind { PENDING, REAL, FALLBACK }

object MapChoice {
    /** The real map needs its data; in text-only mode the drawn map shows its battery note instead. */
    fun of(state: MapState, textOnly: Boolean): MapKind = when {
        textOnly -> MapKind.FALLBACK
        state is MapState.Ready -> MapKind.REAL
        state is MapState.Loading -> MapKind.PENDING
        else -> MapKind.FALLBACK
    }
}

/**
 * The base map installed for this process. [start] seeds the state on the calling thread when the bundled pack is
 * already in place, so the first frame has its map, and otherwise copies and checks it off the main thread.
 */
object MapData {
    private val _state = MutableStateFlow<MapState>(MapState.Loading)
    val state: StateFlow<MapState> = _state
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val installer = MapInstaller(AssetSource(app.assets), File(app.filesDir, MapInstaller.ASSET_ROOT))
        val seeded = runCatching { installer.installed() }.getOrNull()
        if (seeded != null) _state.value = MapState.Ready(seeded)
        scope.launch {
            val pack = runCatching { installer.install() }.getOrNull()
            if (seeded == null || pack?.dir != seeded.dir) _state.value = pack?.let(MapState::Ready) ?: MapState.Missing
        }
    }
}
