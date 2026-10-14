package ro.safetyplease.core.map

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import okio.Path
import ro.safetyplease.core.venue.GeoPoint

/** What a map pack is for: the world overview the app ships, an event's own map, or an area the person downloaded. */
@Serializable
enum class PackKind { OVERVIEW, EVENT, AREA }

/**
 * The manifest.json next to a pack's PMTiles [file]: [sha256] (hex) and [bytes] are the file's, [bbox] is west, south,
 * east, north, [buildDate] is yyyyMMdd. A pack cut on the phone keeps the [source] archive's URL and its [etag] at the
 * time; [label] is the name the list shows.
 */
@Serializable
data class PackManifest(
    val id: String,
    val kind: PackKind,
    val buildDate: String,
    val file: String,
    val sha256: String,
    val bytes: Long,
    val bbox: List<Double>,
    val minzoom: Int = 0,
    val maxzoom: Int,
    val attribution: String = "",
    val source: String? = null,
    val etag: String? = null,
    val label: String = "",
)

/** A pack as it lies on the phone: its manifest and the folder holding the file. */
data class InstalledPack(val manifest: PackManifest, val dir: Path) {
    val file: Path get() = dir / manifest.file
}

/** The packs the maps draw from: the overview, the event's map and the downloaded areas. */
interface PackRegistry {
    val packs: StateFlow<List<InstalledPack>>

    /** The event or area pack with detail at [lat], [lon] for [zoom]; null where only the overview covers it. */
    fun detailAt(lat: Double, lon: Double, zoom: Double): InstalledPack?
}

/**
 * An area to download from [minzoom] to [maxzoom]: [bbox] is west, south, east, north (west past east crosses the
 * antimeridian), cut to [polygon] when there is one, such as a country's outline. [label] is the name the list shows.
 */
@Serializable
data class MapArea(
    val bbox: List<Double>,
    val minzoom: Int,
    val maxzoom: Int,
    val label: String,
    val polygon: List<GeoPoint> = emptyList(),
)

/** Downloading an area: [tiles] and [bytes] to fetch, and [localBytes] of its tiles already in installed packs, not fetched. */
data class MapEstimate(val tiles: Long, val bytes: Long, val localBytes: Long)

/** A download the person started; [id] is what [MapDownloads.start] gave. */
data class MapJob(val id: String, val area: MapArea, val wifiOnly: Boolean, val state: MapJobState)

sealed interface MapJobState {
    data object Queued : MapJobState

    data class Running(val doneBytes: Long, val totalBytes: Long) : MapJobState

    data class Paused(val reason: PauseReason) : MapJobState

    data class Failed(val reason: FailReason) : MapJobState

    /** Installed as the pack [packId]. */
    data class Done(val packId: String) : MapJobState
}

/** The person paused it, only Wi-Fi is allowed and the network is metered, there is no network, or the phone is full. */
enum class PauseReason { USER, METERED, OFFLINE, LOW_SPACE }

/**
 * The source changed under the download (its ETag), the host serves no byte ranges or keeps answering with an error,
 * or the finished pack failed its checks.
 */
enum class FailReason { SOURCE_CHANGED, NO_RANGES, SERVER, CORRUPT, OTHER }

/** Offline areas: their exact size before anything is fetched, the downloads, and removing installed packs. */
interface MapDownloads {
    val jobs: StateFlow<List<MapJob>>

    /** Read from the source archive's directories, so it needs the network. */
    suspend fun estimate(area: MapArea): MapEstimate

    /** Gives the new job's id; with [wifiOnly] the job pauses on a metered network. */
    fun start(area: MapArea, wifiOnly: Boolean): String

    fun pause(id: String)

    fun resume(id: String)

    /** Stops the job and drops what it fetched. */
    fun cancel(id: String)

    /** A pack a map still draws goes once no map uses it. */
    fun delete(packId: String)
}
