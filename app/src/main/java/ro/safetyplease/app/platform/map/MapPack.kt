package ro.safetyplease.app.platform.map

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import ro.safetyplease.core.map.InstalledPack
import ro.safetyplease.core.map.PackManifest
import ro.safetyplease.ui.map.style.MapUrls
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

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
 * The map packs inside the APK: assets/maps/<folder>/manifest.json, a [PackManifest], next to its file; the world
 * overview in overview/, the festival's map in the newest yyyyMMdd/ folder, which also holds the styles' fonts and
 * sprites. The manifests are read from the APK at every start, so an update always brings its own. The tiles are copied
 * once to [root]/bundled/<folder>-<sha256 prefix>/: MapLibre 13.6.1 reads an asset whole, never a byte range, and a
 * PMTiles archive is read a range at a time. A copy goes through a temporary folder renamed once its SHA-256 matches,
 * then is made read-only (MapLibre crashes when a PMTiles file changes under an open map, maplibre-native #3658); other
 * content gets another folder. Fonts and sprites stay in the APK, read whole as asset:// URLs.
 */
class BundledPacks(private val source: PackSource, private val root: File) {
    /** The bundled packs whose copy is already in place, without copying anything: what the first frame can draw. */
    fun installed(): List<InstalledPack> = bundled().mapNotNull { (folder, manifest) -> placed(folder, manifest) }

    /**
     * Copies the packs not yet in place and checks them, then clears the copies no bundled pack uses any more and those
     * older versions made; downloaded areas elsewhere in [root] stay. Runs off the main thread.
     */
    fun install(): List<InstalledPack> {
        val packs = bundled().mapNotNull { (folder, manifest) -> placed(folder, manifest) ?: copy(folder, manifest) }
        val kept = packs.map { it.dir.name }.toSet()
        File(root, BUNDLED).listFiles()?.filter { it.name !in kept }?.forEach(::remove)
        root.listFiles()?.filter { OLD_COPY.matches(it.name) }?.forEach(::remove)
        return packs
    }

    /** The folder with the styles' fonts/ and sprites/: the newest dated pack's, in the APK. */
    fun styleAssets(): Path? = newest()?.takeIf { source.list("$ROOT/$it").containsAll(listOf(FONTS, SPRITES)) }?.let { ASSETS / ROOT / it }

    /** The overview and the newest dated pack, each with its manifest, when that reads and names a file that is there. */
    private fun bundled(): List<Pair<String, PackManifest>> = listOfNotNull(OVERVIEW, newest()).mapNotNull { folder ->
        val manifest = runCatching {
            source.open("$ROOT/$folder/$MANIFEST").use { json.decodeFromString(PackManifest.serializer(), it.readBytes().decodeToString()) }
        }.getOrNull()
        manifest?.takeIf { it.file in source.list("$ROOT/$folder") }?.let { folder to it }
    }

    private fun newest(): String? = source.list(ROOT).filter { DATED.matches(it) && MANIFEST in source.list("$ROOT/$it") }.maxOrNull()

    private fun target(folder: String, manifest: PackManifest) = File(File(root, BUNDLED), "$folder-" + manifest.sha256.take(SHA_PREFIX).lowercase())

    private fun placed(folder: String, manifest: PackManifest): InstalledPack? {
        val dir = target(folder, manifest)
        if (File(dir, manifest.file).length() != manifest.bytes) return null
        return InstalledPack(manifest, dir.toOkioPath())
    }

    private fun copy(folder: String, manifest: PackManifest): InstalledPack? {
        val dir = target(folder, manifest)
        val temp = File(dir.parentFile, dir.name + ".tmp")
        remove(temp)
        remove(dir)
        temp.mkdirs()
        val file = File(temp, manifest.file)
        val digest = MessageDigest.getInstance("SHA-256")
        runCatching {
            source.open("$ROOT/$folder/${manifest.file}").use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!file.isFile || file.length() != manifest.bytes || !sha.equals(manifest.sha256, ignoreCase = true)) {
            remove(temp)
            return null
        }
        file.setReadOnly()
        if (!temp.renameTo(dir)) {
            remove(temp)
            return null
        }
        return InstalledPack(manifest, dir.toOkioPath())
    }

    companion object {
        const val ROOT = "maps"
        const val OVERVIEW = "overview"
        const val MANIFEST = "manifest.json"
        private const val BUNDLED = "bundled"
        private const val FONTS = "fonts"
        private const val SPRITES = "sprites"
        private const val SHA_PREFIX = 12
        private const val BUFFER = 64 * 1024
        private val DATED = Regex("\\d{8}")

        /** The folders older versions copied the festival's whole pack to, and the half copies a crash left. */
        private val OLD_COPY = Regex("\\d{8}(\\.tmp)?")
        private val json = Json { ignoreUnknownKeys = true }

        /** Where [styleAssets] lies: the APK's assets, named as the WebView names them. */
        val ASSETS: Path = "/android_asset".toPath()

        /** The URLs MapLibre reads: asset:// for the fonts and sprites in the APK, file:// for the packs' copies. */
        val urls = MapUrls(file = { path -> inAssets(path)?.let { "asset://$it" } ?: ("file://" + path.toString().replace('\\', '/')) })

        private fun inAssets(path: Path): String? {
            val base = ASSETS.segments
            val segments = path.segments
            if (segments.size <= base.size || segments.take(base.size) != base) return null
            return segments.drop(base.size).joinToString("/")
        }

        // read-only files can't be deleted on every file system until they are writable again
        private fun remove(file: File) {
            if (!file.exists()) return
            file.walkTopDown().forEach { it.setWritable(true) }
            file.deleteRecursively()
        }
    }
}
