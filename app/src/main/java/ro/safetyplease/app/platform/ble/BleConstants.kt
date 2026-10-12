package ro.safetyplease.app.platform.ble

import ro.safetyplease.core.ble.BleLayout
import java.util.UUID

/** The shared layout as Android's types, and what only the Android radio needs. */
object BleConstants {
    val SERVICE: UUID = UUID.fromString(BleLayout.SERVICE)
    val CHARACTERISTIC: UUID = UUID.fromString(BleLayout.CHARACTERISTIC)
    val CCCD: UUID = UUID.fromString(BleLayout.CCCD)

    const val REQUESTED_MTU = 517
    const val DEFAULT_MTU = 23

    /** Connect phase only; tune it from the `connected ... in N ms` log lines. */
    const val CONNECT_TIMEOUT_MS = 20_000L
    const val OPERATION_TIMEOUT_MS = 5_000L
    const val WRITE_TIMEOUT_MS = 3_000L

    /** After a 143, give the stack about two connection intervals to drain its queue. */
    const val CONGESTION_PAUSE_MS = 100L

    /** Second advertising set on Coded PHY, only on phones that support it. False turns it off entirely. */
    const val LONG_RANGE = true

    const val ADVERTISE_DEBOUNCE_MS = 300L
    const val ADVERTISE_RETRY_MS = 15_000L
    const val ADVERTISE_DEMOTE_AFTER = 3
    const val ADVERTISE_DEMOTED_RETRY_MS = 60_000L

    /** A set start with no answer from the stack is abandoned after this long. */
    const val ADVERTISE_START_GIVE_UP_MS = 30_000L
    const val CODED_RETRY_MS = 60_000L

    /** One batch a second: the engine gets about one sighting per second per address anyway. */
    const val SCAN_BATCH_DELAY_MS = 1_000L

    /** Android allows at most 5 scan starts per 30 s; 6.5 s apart stays under the limit. */
    const val SCAN_START_SPACING_MS = 6_500L

    /** A continuous scan turns opportunistic after 30 minutes, so restart it before that. */
    const val SCAN_RESTART_MS = 25 * 60_000L
    const val SCAN_SILENCE_RESTART_MS = 3 * 60_000L
}
