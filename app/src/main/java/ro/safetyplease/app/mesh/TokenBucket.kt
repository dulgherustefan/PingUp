package ro.safetyplease.app.mesh

import kotlin.math.ceil

class TokenBucket(
    private val capacity: Double,
    private val refillPerSecond: Double,
    nowMs: Long,
) {
    private var tokens = capacity
    private var lastMs = nowMs

    private fun refill(nowMs: Long) {
        if (nowMs > lastMs) {
            tokens = minOf(capacity, tokens + (nowMs - lastMs) * refillPerSecond / 1000.0)
            lastMs = nowMs
        }
    }

    fun tryTake(nowMs: Long): Boolean {
        refill(nowMs)
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    /** Cat mai e de asteptat pana la urmatorul jeton; 0 daca exista unul acum. */
    fun waitMs(nowMs: Long): Long {
        refill(nowMs)
        if (tokens >= 1.0) return 0
        return ceil((1.0 - tokens) * 1000.0 / refillPerSecond).toLong()
    }
}
