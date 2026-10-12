package ro.safetyplease.core.data

import okio.Path
import okio.Path.Companion.toPath

/** The same path with [suffix] added to the file name: `chat.json` becomes `chat.json.bak`. */
fun Path.withSuffix(suffix: String): Path = parent?.div(name + suffix) ?: (name + suffix).toPath()

/**
 * Writes [bytes] to [path], replacing it, so that they reach the disk before anything written after this returns:
 * a power cut can't keep the rename that publishes them and lose them. FileDescriptor.sync on the JVM and Android,
 * fcntl(F_BARRIERFSYNC) on Apple, where fsync alone lets the drive reorder writes.
 */
expect fun writeSynced(path: Path, bytes: ByteArray)
