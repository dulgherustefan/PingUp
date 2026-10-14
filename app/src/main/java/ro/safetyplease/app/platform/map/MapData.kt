package ro.safetyplease.app.platform.map

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Path
import ro.safetyplease.core.map.InstalledPack
import ro.safetyplease.core.map.PackKind
import ro.safetyplease.core.map.PackRegistry
import ro.safetyplease.ui.map.MapKind
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The packs the Android map draws: those the APK carries and the areas downloaded to the phone, which the downloads
 * hand over with [setAreas], most wanted first. The maps compose their style again whenever [packs] changes.
 */
class AndroidPackRegistry : PackRegistry {
    private data class Held(val bundled: List<InstalledPack> = emptyList(), val areas: List<InstalledPack> = emptyList())

    private val held = MutableStateFlow(Held())
    private val _packs = MutableStateFlow<List<InstalledPack>>(emptyList())
    override val packs: StateFlow<List<InstalledPack>> = _packs

    fun setBundled(packs: List<InstalledPack>) = set { it.copy(bundled = packs) }

    fun setAreas(packs: List<InstalledPack>) = set { it.copy(areas = packs.filter { pack -> pack.manifest.kind == PackKind.AREA }) }

    override fun detailAt(lat: Double, lon: Double, zoom: Double): InstalledPack? = PackCoverage.detailAt(_packs.value, lat, lon, zoom)

    @Synchronized
    private fun set(change: (Held) -> Held) {
        held.update(change)
        val now = held.value
        // the overview first, then the areas, then the event's map: the order the style stacks them in
        _packs.value = now.bundled.filter { it.manifest.kind == PackKind.OVERVIEW } + now.areas +
            now.bundled.filter { it.manifest.kind != PackKind.OVERVIEW }
    }
}

object PackCoverage {
    /**
     * The event or area pack with detail at [lat], [lon] for [zoom]: its box holds the point (west past east crosses the
     * antimeridian) and its tiles start at or below [zoom]; past its maxzoom MapLibre stretches its last tiles. The
     * event's map first.
     */
    fun detailAt(packs: List<InstalledPack>, lat: Double, lon: Double, zoom: Double): InstalledPack? =
        packs.filter { it.manifest.kind != PackKind.OVERVIEW }.sortedBy { it.manifest.kind != PackKind.EVENT }
            .firstOrNull { zoom >= it.manifest.minzoom && covers(it.manifest.bbox, lat, lon) }

    fun covers(bbox: List<Double>, lat: Double, lon: Double): Boolean {
        if (bbox.size != 4) return false
        val (west, south, east, north) = bbox
        if (lat < south || lat > north) return false
        return if (west <= east) lon in west..east else lon >= west || lon <= east
    }
}

object MapChoice {
    /** The real map needs a pack to draw and the fonts its names are written in; else the drawn venue map stands in. */
    fun of(packs: List<InstalledPack>, styleAssets: Path?): MapKind =
        if (packs.isNotEmpty() && styleAssets != null) MapKind.REAL else MapKind.FALLBACK
}

/**
 * The map packs of this process. [start] reads the bundled ones on the calling thread, two small manifests and the
 * copies already in place, so the first frame has its map; it copies what is missing off the main thread.
 */
object MapData {
    val registry = AndroidPackRegistry()

    /** The folder the styles take their fonts and sprites from; null without bundled fonts. */
    var styleAssets: Path? = null
        private set

    private val _kind = MutableStateFlow(MapKind.PENDING)
    val kind: StateFlow<MapKind> = _kind
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val bundled = BundledPacks(AssetSource(app.assets), File(app.filesDir, BundledPacks.ROOT))
        styleAssets = runCatching { bundled.styleAssets() }.getOrNull()
        val ready = runCatching { bundled.installed() }.getOrDefault(emptyList())
        if (ready.isNotEmpty()) publish(ready)
        scope.launch {
            val packs = runCatching { bundled.install() }.getOrDefault(ready)
            if (packs != ready || ready.isEmpty()) publish(packs)
        }
    }

    private fun publish(packs: List<InstalledPack>) {
        registry.setBundled(packs)
        _kind.value = MapChoice.of(packs, styleAssets)
    }
}
