package ro.safetyplease.app.platform.ble

import ro.safetyplease.core.mesh.ScanDuty

/**
 * Pauses the scan for the off part of a [ScanDuty]. Times are monotonic. The window counts from the moment the scan
 * really started, since a start can wait for Android's spacing between starts.
 */
class ScanCycle {
    var duty: ScanDuty = ScanDuty.CONTINUOUS
        private set
    private var pausedAt: Long? = null

    /** The scan is off because of the cycle, not because something failed. */
    val paused: Boolean get() = pausedAt != null

    /** A new duty begins with scanning; false if it is the same one. */
    fun set(duty: ScanDuty): Boolean {
        if (duty == this.duty) return false
        this.duty = duty
        pausedAt = null
        return true
    }

    fun resume() {
        pausedAt = null
    }

    fun pause(now: Long) {
        pausedAt = now
    }

    /** When the next pause or restart is due; null while the scan simply keeps running or is down for another reason. */
    fun nextAt(scanning: Boolean, startedAt: Long): Long? {
        val pausedAt = pausedAt
        return when {
            duty.continuous -> null
            pausedAt != null -> pausedAt + duty.offMs
            scanning -> startedAt + duty.windowMs
            else -> null
        }
    }
}
