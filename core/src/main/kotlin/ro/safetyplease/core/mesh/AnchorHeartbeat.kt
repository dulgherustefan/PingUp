package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.util.truncateUtf8

/** What the platform reads of the device. Unknown values are null. */
data class DeviceReadings(val batteryPercent: Int?, val charging: Boolean, val temperatureC: Int?)

/**
 * When an anchor tells the console it is alive (PROTOCOL.md, section 6.9): every [INTERVAL_MS], and as soon as
 * something the console acts on changes, though not within [MIN_GAP_MS] of the last one. 30 anchors cost the mesh
 * 0.1 packets a second.
 */
class AnchorHeartbeat {
    private var lastHeardMs: Long? = null
    private var sentAtMs: Long? = null
    private var sentState: State? = null

    /** What changes the console's view of an anchor, unlike the crowd, which changes all the time. */
    private data class State(val charging: Boolean, val battery: Int?, val hot: Boolean, val alone: Boolean)

    /** The heartbeat due now, or null. [nowMs] is on the engine's monotonic clock, as [state] is. */
    fun next(state: MeshState, device: DeviceReadings, zone: String, nowMs: Long): Heartbeat? {
        val heard = if (state.readyLinks > 0) nowMs else state.seen.maxOfOrNull { it.lastSeenMs }
        if (heard != null && heard > (lastHeardMs ?: Long.MIN_VALUE)) lastHeardMs = heard
        val beat = Heartbeat(
            batteryPercent = device.batteryPercent?.coerceIn(0, 100),
            charging = device.charging,
            temperatureC = device.temperatureC?.coerceIn(-127, 127),
            quietS = lastHeardMs?.let { ((nowMs - it) / 1000).coerceIn(0, Heartbeat.MAX_QUIET_S.toLong()).toInt() },
            links = state.readyLinks.coerceAtMost(0xFF),
            crowd = Heartbeat.crowdOf(state.visiblePeers),
            zone = zone.truncateUtf8(Limits.ZONE_BYTES),
        )
        val current = State(
            charging = beat.charging,
            battery = beat.batteryPercent?.let { BATTERY_BANDS.count { band -> it < band } },
            hot = (beat.temperatureC ?: 0) >= HOT_C,
            alone = beat.links == 0,
        )
        val sent = sentAtMs
        val due = sent == null || nowMs - sent >= INTERVAL_MS || (current != sentState && nowMs - sent >= MIN_GAP_MS)
        if (!due) return null
        sentAtMs = nowMs
        sentState = current
        return beat
    }

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val MIN_GAP_MS = 60_000L

        /** How often the platform asks; a change waits at most this long. */
        const val CHECK_MS = 10_000L

        /** Phones shut down near 60 °C, and a box in the sun gets there; the console is told from here up. */
        const val HOT_C = 45
        private val BATTERY_BANDS = intArrayOf(50, 20, 10)
    }
}
