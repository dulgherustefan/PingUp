package ro.safetyplease.ui.common

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use
import ro.safetyplease.ui.text.Labels
import kotlin.time.Instant

/** The export folder, emptied: each export replaces the files of the last one. */
fun freshExportDir(dir: Path): Path = dir.also {
    FileSystem.SYSTEM.deleteRecursively(it)
    FileSystem.SYSTEM.createDirectories(it)
}

/** [text] as the file [name] in [dir]. */
fun writeExport(dir: Path, name: String, text: String): Path = (dir / name).also { FileSystem.SYSTEM.write(it) { writeUtf8(text) } }

/** "2026-10-12-1430" on the phone's clock, for the names of exported files. */
fun exportStamp(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    return "${t.year}-${Labels.two(t.month.number)}-${Labels.two(t.day)}-${Labels.two(t.hour)}${Labels.two(t.minute)}"
}

/** The whole stream as text, or null past [max] bytes: a file that big isn't the one asked for. */
fun readBounded(source: Source, max: Long): String? = source.buffer().use { input ->
    val out = Buffer()
    while (input.read(out, 64 * 1024L) != -1L) {
        if (out.size > max) return null
    }
    out.readUtf8()
}
