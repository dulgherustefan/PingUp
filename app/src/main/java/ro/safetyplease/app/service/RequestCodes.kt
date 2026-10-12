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
}
