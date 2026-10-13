package ro.safetyplease.core.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import okio.IOException
import okio.Path
import platform.posix.EINTR
import platform.posix.F_BARRIERFSYNC
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.open
import platform.posix.strerror
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
actual fun writeSynced(path: Path, bytes: ByteArray) {
    val fd = open(path.toString(), O_WRONLY or O_CREAT or O_TRUNC, 0x1a4) // 0644
    if (fd < 0) throw IOException("open $path: ${strerror(errno)?.toKString()}")
    try {
        var done = 0
        while (done < bytes.size) {
            val n = bytes.usePinned { write(fd, it.addressOf(done), (bytes.size - done).convert()) }
            if (n < 0) {
                if (errno == EINTR) continue
                throw IOException("write $path: ${strerror(errno)?.toKString()}")
            }
            done += n.toInt()
        }
        // a barrier: nothing written after it, the rename included, reaches the disk before these bytes. F_FULLFSYNC
        // would also empty the drive's cache, ten times slower, and leave the rename no safer. Some file systems
        // refuse the barrier; then a plain fsync is the best there is
        if (fcntl(fd, F_BARRIERFSYNC) != 0 && fsync(fd) != 0) throw IOException("sync $path: ${strerror(errno)?.toKString()}")
    } finally {
        close(fd)
    }
}
