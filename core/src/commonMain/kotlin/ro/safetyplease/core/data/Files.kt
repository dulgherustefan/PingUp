package ro.safetyplease.core.data

import okio.Path
import okio.Path.Companion.toPath

/** The same path with [suffix] added to the file name: `chat.json` becomes `chat.json.bak`. */
fun Path.withSuffix(suffix: String): Path = parent?.div(name + suffix) ?: (name + suffix).toPath()

/**
 * Writes [bytes] to [path], replacing it, and returns only once they are on the disk: FileDescriptor.sync on the JVM
 * and Android, fcntl(F_FULLFSYNC) on Apple, where fsync alone leaves them in the drive's cache.
 */
expect fun writeDurably(path: Path, bytes: ByteArray)
