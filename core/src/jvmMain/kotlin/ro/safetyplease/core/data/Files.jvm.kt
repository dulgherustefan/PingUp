package ro.safetyplease.core.data

import okio.Path
import java.io.FileOutputStream

actual fun writeDurably(path: Path, bytes: ByteArray) {
    FileOutputStream(path.toFile()).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
}
