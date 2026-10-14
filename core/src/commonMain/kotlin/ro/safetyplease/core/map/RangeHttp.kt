package ro.safetyplease.core.map

/** The network a download would use: none, metered (mobile data, a hotspot) or not. */
enum class NetState { OFFLINE, METERED, UNMETERED }

/** A file on the map host: its [size] in bytes, its [etag] when the host gives one, and whether it serves byte [ranges]. */
data class Remote(val size: Long, val etag: String?, val ranges: Boolean)

/** Byte-range reads of static files over HTTPS, for the map downloads; each phone gives its own. */
interface RangeHttp {
    fun network(): NetState

    /** Null when [url] can't be reached. */
    suspend fun probe(url: String): Remote?

    /**
     * [length] bytes of [url] from [offset], only while the file still has [etag] when one is given. Null on any failure,
     * an answer other than 206 with exactly [length] bytes among them.
     */
    suspend fun read(url: String, offset: Long, length: Int, etag: String?): ByteArray?
}
