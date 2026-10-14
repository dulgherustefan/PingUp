package ro.safetyplease.core.map.pmtiles

import okio.Closeable
import okio.EOFException
import okio.FileSystem
import okio.IOException
import okio.Path
import ro.safetyplease.core.map.RangeHttp
import ro.safetyplease.core.map.Remote

/** An archive read by byte ranges: a file on the phone, one on a map host, or the spans of a plan already fetched. */
interface RangeSource {
    /** The archive's length in bytes. */
    val size: Long

    /** Exactly [length] bytes from [offset]; an IOException when they can't be had. */
    suspend fun read(offset: Long, length: Int): ByteArray
}

internal fun RangeSource.checkRange(offset: Long, length: Int) {
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        throw EOFException("$length bytes at $offset of a $size-byte archive")
    }
}

/** A local file through one open handle, until [close]. Reads block the calling thread. */
class FileRangeSource(fs: FileSystem, path: Path) : RangeSource, Closeable {
    private val handle = fs.openReadOnly(path)
    override val size: Long = handle.size()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length)
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = handle.read(offset + done, out, done, length - done)
            if (n <= 0) throw EOFException("file ended at ${offset + done}")
            done += n
        }
        return out
    }

    override fun close() = handle.close()
}

/** A file on a map host, read through [http] only while it still has [etag], when the host gave one. */
class HttpRangeSource(
    private val http: RangeHttp,
    val url: String,
    override val size: Long,
    val etag: String?,
) : RangeSource {
    /** The file as the probe found it. */
    constructor(http: RangeHttp, url: String, remote: Remote) : this(http, url, remote.size, remote.etag)

    override suspend fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length)
        if (length == 0) return ByteArray(0)
        val bytes = http.read(url, offset, length, etag) ?: throw IOException("no bytes $offset+$length of $url")
        if (bytes.size != length) throw IOException("${bytes.size} bytes for $offset+$length of $url")
        return bytes
    }
}
