package ro.safetyplease.app.platform.map

import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** What tools/map_extract.py writes next to the tiles: the build they came from and the SHA-256 the app checks. */
@Serializable
data class MapManifest(
    val buildDate: String,
    val sha256: String,
    /** West, south, east, north. */
    val bbox: List<Double>,
    val maxzoom: Int,
    val file: String = MapPack.TILES,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): MapManifest = json.decodeFromString(serializer(), text)
    }
}

/** A verified base map in its own directory: the tiles, both styles, the fonts and sprites they point to. */
class MapPack(val dir: File, val manifest: MapManifest) {
    /** The style for the theme, with its placeholders pointing into [dir]. */
    fun style(dark: Boolean): String = MapStyle.resolve(File(dir, if (dark) STYLE_DARK else STYLE_LIGHT).readText(), dir.absolutePath)

    companion object {
        const val TILES = "costinesti.pmtiles"
        const val MANIFEST = "manifest.json"
        const val STYLE_LIGHT = "style-light.json"
        const val STYLE_DARK = "style-dark.json"

        /** A complete pack, or null when a file is missing or the manifest doesn't parse. */
        fun open(dir: File): MapPack? {
            val manifest = runCatching { MapManifest.parse(File(dir, MANIFEST).readText()) }.getOrNull() ?: return null
            if (listOf(manifest.file, STYLE_LIGHT, STYLE_DARK).any { !File(dir, it).isFile }) return null
            return MapPack(dir, manifest)
        }
    }
}

object MapStyle {
    const val PACK = "__PACK__"
    const val ASSETS = "__ASSETS__"

    /** The styles name their files as file://__PACK__/... and file://__ASSETS__/...; both live in the pack directory. */
    fun resolve(template: String, dir: String): String {
        val path = dir.replace('\\', '/').trimEnd('/')
        return template.replace(PACK, path).replace(ASSETS, path)
    }
}

object MapVersions {
    private val name = Regex("\\d{8}")

    /** Versions are build dates, yyyyMMdd, so the newest sorts last; anything else in the folder is ignored. */
    fun newest(names: Collection<String>): String? = names.filter { name.matches(it) }.maxOrNull()
}

fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Where the bundled packs come from: the APK assets on a phone, a folder in tests. */
interface PackSource {
    /** Names inside [path]; empty for a file or a missing path. */
    fun list(path: String): List<String>

    fun open(path: String): InputStream
}

class AssetSource(private val assets: AssetManager) : PackSource {
    override fun list(path: String): List<String> = assets.list(path)?.toList().orEmpty()

    override fun open(path: String): InputStream = assets.open(path)
}

/**
 * Copies the newest pack in assets/maps/<buildDate>/ to [root]/<buildDate>/. MapLibre crashes when a PMTiles file
 * changes under an open map (maplibre-native #3658), so a version directory is written once, through a temporary
 * directory renamed only after the SHA-256 matches, its files are made read-only, and a new build gets a new directory.
 * Runs once per process, before any map opens; that is also the only time older versions are removed.
 */
class MapInstaller(private val source: PackSource, private val root: File) {
    fun install(): MapPack? {
        root.mkdirs()
        val bundled = MapVersions.newest(source.list(ASSET_ROOT).filter { MapPack.MANIFEST in source.list("$ASSET_ROOT/$it") })
        if (bundled != null && MapPack.open(File(root, bundled)) == null) copy(bundled)
        val names = root.list()?.toList().orEmpty().filter { MapPack.open(File(root, it)) != null }
        val chosen = MapVersions.newest(names)
        root.listFiles()?.filter { it.name != chosen }?.forEach(::remove)
        return chosen?.let { MapPack.open(File(root, it)) }
    }

    private fun copy(version: String) {
        val temp = File(root, "$version.tmp")
        remove(temp)
        File(root, version).let { if (it.exists()) remove(it) }
        copyTree("$ASSET_ROOT/$version", temp)
        val manifest = runCatching { MapManifest.parse(File(temp, MapPack.MANIFEST).readText()) }.getOrNull()
        val tiles = manifest?.let { File(temp, it.file) }
        if (manifest == null || tiles == null || !tiles.isFile || !sha256Hex(tiles.inputStream()).equals(manifest.sha256, ignoreCase = true)) {
            remove(temp)
            return
        }
        temp.walkTopDown().filter { it.isFile }.forEach { it.setReadOnly() }
        if (!temp.renameTo(File(root, version))) remove(temp)
    }

    private fun copyTree(path: String, to: File) {
        val children = source.list(path)
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            source.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        children.forEach { copyTree("$path/$it", File(to, it)) }
    }

    // read-only files can't be deleted on every file system until they are writable again
    private fun remove(file: File) {
        file.walkTopDown().forEach { it.setWritable(true) }
        file.deleteRecursively()
    }

    companion object {
        const val ASSET_ROOT = "maps"
    }
}
