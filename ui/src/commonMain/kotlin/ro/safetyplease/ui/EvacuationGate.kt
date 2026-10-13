package ro.safetyplease.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * While an evacuation concerns this phone, nothing of friends opens over it: "Vin la tine", "M-am pierdut", "Te caută"
 * and the link of a friend's missed check-in wait until it ends. The organiser's own screens and the staff's alerts
 * are not held.
 */
class EvacuationGate {
    var evacuating by mutableStateOf(false)
        private set

    private var held: String? = null

    /** Whether friends' dialogs may open now. */
    val friendsMayAsk: Boolean get() = !evacuating

    /** False holds [target], a link from a notification, until the evacuation ends. */
    fun admits(target: String): Boolean {
        if (!evacuating || target !in FRIEND_LINKS) return true
        held = target
        return false
    }

    /** The evacuation began ([on]) or ended; on the end, the link it held, to open now. */
    fun evacuation(on: Boolean): String? {
        evacuating = on
        if (on) return null
        return held.also { held = null }
    }

    private companion object {
        val FRIEND_LINKS = setOf(OpenTarget.CHECKIN_ALERTS)
    }
}
