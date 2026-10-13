package ro.safetyplease.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringArrayResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.day_names
import ro.safetyplease.ui.resources.day_short_names
import ro.safetyplease.ui.resources.month_names
import ro.safetyplease.ui.resources.month_short_names
import kotlin.time.Instant

/** Month and weekday names in the app's language, Monday first: MMMM, MMM, EEEE and EEE. */
@Immutable
class DateNames(val months: List<String>, val shortMonths: List<String>, val days: List<String>, val shortDays: List<String>)

@Composable
fun rememberDateNames(): DateNames {
    val months = stringArrayResource(Res.array.month_names)
    val shortMonths = stringArrayResource(Res.array.month_short_names)
    val days = stringArrayResource(Res.array.day_names)
    val shortDays = stringArrayResource(Res.array.day_short_names)
    return remember(months, shortMonths, days, shortDays) { DateNames(months, shortMonths, days, shortDays) }
}

/**
 * [timeMs] in [zone], as java.text.SimpleDateFormat would write [pattern], for the letters the texts use: d, M, y, E,
 * H and m, repeated as there; text in single quotes stays as it is.
 */
fun formatDate(pattern: String, timeMs: Long, names: DateNames, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(zone)
    val out = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\'') {
            // '' is a quote, inside quoted text too
            if (pattern.startsWith("''", i)) {
                out.append('\'')
                i += 2
                continue
            }
            i++
            while (i < pattern.length) {
                if (pattern.startsWith("''", i)) {
                    out.append('\'')
                    i += 2
                } else if (pattern[i] == '\'') {
                    i++
                    break
                } else {
                    out.append(pattern[i++])
                }
            }
            continue
        }
        var run = 1
        while (i + run < pattern.length && pattern[i + run] == c) run++
        out.append(
            when (c) {
                'd' -> t.day.padded(run)
                'M' -> when {
                    run >= 4 -> names.months[t.month.ordinal]
                    run == 3 -> names.shortMonths[t.month.ordinal]
                    else -> t.month.ordinal.plus(1).padded(run)
                }
                'y' -> if (run == 2) (t.year % 100).padded(2) else t.year.padded(run)
                'E' -> if (run >= 4) names.days[t.dayOfWeek.ordinal] else names.shortDays[t.dayOfWeek.ordinal]
                'H' -> t.hour.padded(run)
                'm' -> t.minute.padded(run)
                else -> c.toString().repeat(run)
            },
        )
        i += run
    }
    return out.toString()
}

private fun Int.padded(width: Int): String = toString().padStart(width, '0')
