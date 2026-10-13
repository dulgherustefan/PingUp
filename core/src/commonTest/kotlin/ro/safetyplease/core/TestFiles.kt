package ro.safetyplease.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random

private val fs = FileSystem.SYSTEM

/** A new directory under the system's temporary one, for what a test writes; [delete] removes it. */
class TempDir {
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pingup-${Random.nextLong().toULong().toString(16)}"
    private var folders = 0

    init {
        fs.createDirectories(root)
    }

    fun newFolder(): Path = (root / "folder${folders++}").also { fs.createDirectories(it) }

    fun delete() = fs.deleteRecursively(root)
}

/** A file of the repository by its path from the root; the iPhone simulator reads the Mac's disk too. */
fun repoFile(path: String): Path = REPO_ROOT.toPath() / path

fun Path.readText(): String = fs.read(this) { readUtf8() }

fun Path.writeText(text: String) = fs.write(this) { writeUtf8(text) }

fun Path.exists(): Boolean = fs.exists(this)

fun Path.delete() = fs.delete(this)

fun Path.length(): Long = fs.metadataOrNull(this)?.size ?: 0L

fun Path.mkdirs() = fs.createDirectories(this)

/** Replaces [target], as File.copyTo(target, overwrite = true) does. */
fun Path.copyTo(target: Path) = fs.copy(this, target)
