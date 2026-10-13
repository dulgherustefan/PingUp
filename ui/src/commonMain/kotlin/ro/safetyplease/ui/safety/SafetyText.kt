package ro.safetyplease.ui.safety

/** "9:05" for a little more than nine minutes, "1:02:03" past the hour; "0:00" once the time is up. */
fun countdownText(remainingMs: Long): String {
    val seconds = (remainingMs.coerceAtLeast(0) + 999) / 1000
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    fun two(n: Long) = if (n < 10) "0$n" else n.toString()
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
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
