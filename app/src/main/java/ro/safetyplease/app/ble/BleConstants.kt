package ro.safetyplease.app.ble

import java.util.UUID

/** UUID-urile proprii, generate o singura data. Schimbarea lor rupe compatibilitatea cu versiunile vechi. */
object BleConstants {
    val SERVICE: UUID = UUID.fromString("f24a82e1-f0bc-471f-9cae-a9598a2a4dc4")
    val CHARACTERISTIC: UUID = UUID.fromString("c5d82591-cfb9-45dd-bea5-8447e1811ba6")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val ADVERT_VERSION = 1
    const val ADVERT_DATA_SIZE = 6

    const val REQUESTED_MTU = 517
    const val DEFAULT_MTU = 23
    const val ATT_OVERHEAD = 3

    const val SETUP_TIMEOUT_MS = 20_000L

    /** Doar faza de conectare; de reglat din logurile `conectat ... in N ms`. */
    const val CONNECT_TIMEOUT_MS = 20_000L
    const val OPERATION_TIMEOUT_MS = 5_000L
    const val WRITE_TIMEOUT_MS = 3_000L

    /** Dupa un 143 lasam stiva sa goleasca coada cam doua intervale de conexiune. */
    const val CONGESTION_PAUSE_MS = 100L

    /** Al doilea set de advertising pe Coded PHY, doar pe telefoanele care il suporta. False il opreste complet. */
    const val LONG_RANGE = true

    const val ADVERTISE_DEBOUNCE_MS = 300L
    const val ADVERTISE_RETRY_MS = 15_000L
    const val ADVERTISE_DEMOTE_AFTER = 3
    const val ADVERTISE_DEMOTED_RETRY_MS = 60_000L

    /** Un start de set fara niciun raspuns de la stiva e abandonat dupa atat. */
    const val ADVERTISE_START_GIVE_UP_MS = 30_000L
    const val CODED_RETRY_MS = 60_000L

    const val RSSI_ALPHA = 0.3
    const val REPORT_WINDOW_MS = 1_000L
    const val FULL_RESULT_HOLD_MS = 10_000L
    const val LEGACY_PREFERENCE_MS = 3_000L
    const val SIGHTING_FORGET_MS = 60_000L

    /** Android accepta cel mult 5 porniri de scanare in 30 s; cu 6,5 s intre ele nu ajungem la limita. */
    const val SCAN_START_SPACING_MS = 6_500L

    /** O scanare continua de peste 30 de minute devine oportunista, asa ca o repornim inainte. */
    const val SCAN_RESTART_MS = 25 * 60_000L
    const val SCAN_SILENCE_RESTART_MS = 3 * 60_000L
}
