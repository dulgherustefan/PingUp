package ro.safetyplease.core.data

import okio.Path
import java.io.FileOutputStream

actual fun writeSynced(path: Path, bytes: ByteArray) = synced(path, bytes, append = false)

actual fun appendSynced(path: Path, bytes: ByteArray) = synced(path, bytes, append = true)

private fun synced(path: Path, bytes: ByteArray, append: Boolean) {
    FileOutputStream(path.toFile(), append).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
}
