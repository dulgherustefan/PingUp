package ro.safetyplease.core.venue

/**
 * A position from the phone's GPS; [accuracyM] is the radius the system gives it, in metres, 0 when it gives none.
 * [courseDeg] is the direction of travel while walking, clockwise from north; null when standing or unknown.
 * [mock] says another app made it up, as a mock location.
 */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timeMs: Long,
    val courseDeg: Float? = null,
    val mock: Boolean = false,
) {
    fun isFresh(nowMs: Long) = nowMs - timeMs < FRESH_MS

    companion object {
        /** An older fix is no longer where the person is: a report would send a team to the wrong place. */
        const val FRESH_MS = 2 * 60_000L
    }
}
