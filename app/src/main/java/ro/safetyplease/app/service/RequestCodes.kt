package ro.safetyplease.app.service

/**
 * Request codes of the fixed PendingIntents. Two with the same code and intents that differ only in extras are the
 * same PendingIntent, and FLAG_UPDATE_CURRENT gives both the extras of the last one, so each entry point has its own.
 * Per-item ones (an incident, a report, a conversation) use a hash of the item instead.
 */
object RequestCodes {
    const val OPEN_HOME = 1
    const val REPORT_ACTION = 2
    const val MESH_STOPPED = 3
    const val REPORT_TILE = 4
    const val SHIFT_ENDED = 5
    const val DISCREET_TILE = 6
    const val STOP_MESH = 7
    const val STOP_SHARING = 8
    const val SHARING_REMINDER = 9
    const val MAN_DOWN = 10
    const val CHECKIN_OPEN = 11
    const val CHECKIN_ARRIVED = 12
    const val CHECKIN_EXTEND_15 = 13
    const val CHECKIN_EXTEND_30 = 14
    const val CARD_112 = 15
}
