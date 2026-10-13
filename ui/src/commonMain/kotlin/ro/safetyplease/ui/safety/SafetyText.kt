package ro.safetyplease.ui.safety

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import ro.safetyplease.ui.text.Labels
import kotlin.time.Instant

/** "9:05" for a little more than nine minutes, "1:02:03" past the hour; "0:00" once the time is up. */
fun countdownText(remainingMs: Long): String {
    val seconds = (remainingMs.coerceAtLeast(0) + 999) / 1000
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    fun two(n: Long) = if (n < 10) "0$n" else n.toString()
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/** "22:30" for a time of [nowMs]'s day, "13.10 22:30" for another day, on the phone's clock. */
fun deadlineText(timeMs: Long, nowMs: Long): String {
    if (Labels.daysBetween(timeMs, nowMs) == 0) return Labels.clock(timeMs)
    val t = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return Labels.two(t.day) + "." + Labels.two(t.month.number) + " " + Labels.clock(timeMs)
}

/** "45 min", "2 h", "2 h 5 min": how long, the same in both languages, for a notification's text. */
fun shortDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000L
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

/** The friends chosen on the check-in screen, kept as text so that a turned phone doesn't lose them. */
fun chosenIds(saved: String): List<Long> = saved.split(',').mapNotNull { it.toLongOrNull() }

fun savedIds(ids: List<Long>): String = ids.joinToString(",")

/** [id] added, or taken out if it was there; adding stops at [max]. */
fun toggled(ids: List<Long>, id: Long, max: Int): List<Long> = when {
    id in ids -> ids - id
    ids.size < max -> ids + id
    else -> ids
}
