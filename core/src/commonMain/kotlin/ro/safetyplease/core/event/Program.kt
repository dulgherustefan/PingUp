package ro.safetyplease.core.event

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.util.utf8
import kotlin.math.ceil
import kotlin.time.Instant

/** What plays on a stage now and what comes after it. */
data class NowNext(val now: ScheduleItem?, val next: ScheduleItem?)

/** The stages' schedule from the event pack, read the way a festival reads it: by night, by stage, by now and next. */
object Program {
    /** A set that starts before this hour belongs to the night before: a festival day ends at dawn, not at midnight. */
    const val DAY_CUTOFF_H = 6

    /** "Urmează" looks this far ahead; a stage with nothing sooner is done for the night. */
    const val NEXT_WITHIN_S = 6 * 3600L

    /** A crowd walks at about a metre a second, slower than a street. */
    const val WALK_M_PER_MIN = 60

    /** A walk longer than this isn't on the site: the walk isn't looked up for a set further away than lead plus this. */
    const val MAX_WALK_MIN = 60

    /** How many minutes before a set "Amintește-mi" rings, on top of the walk. */
    val LEAD_OPTIONS = listOf(5, 10, 15, 30)
    const val DEFAULT_LEAD_MIN = 10

    /** A set is named by its stage and start: the pack gives sets no ids, and one stage plays one set at a time. */
    fun key(item: ScheduleItem): String = "${item.stage}@${item.start}"

    /** The festival day of a set: the calendar day in [zone], with the small hours counted to the night before. */
    fun day(item: ScheduleItem, zone: TimeZone): LocalDate =
        Instant.fromEpochSeconds(item.start - DAY_CUTOFF_H * 3600L).toLocalDateTime(zone).date

    fun days(schedule: List<ScheduleItem>, zone: TimeZone): List<LocalDate> = schedule.map { day(it, zone) }.distinct().sorted()

    fun onDay(schedule: List<ScheduleItem>, day: LocalDate, zone: TimeZone): List<ScheduleItem> = schedule.filter { day(it, zone) == day }

    /** The stages in the order their first set appears. */
    fun stages(schedule: List<ScheduleItem>): List<String> = schedule.map { it.stage }.distinct()

    /** The day the Program screen opens on: tonight's while it lasts, else the next one, else the last. */
    fun currentDay(schedule: List<ScheduleItem>, nowSec: Long, zone: TimeZone): LocalDate? {
        val days = days(schedule, zone)
        val today = Instant.fromEpochSeconds(nowSec - DAY_CUTOFF_H * 3600L).toLocalDateTime(zone).date
        return days.firstOrNull { it >= today } ?: days.lastOrNull()
    }

    /**
     * Seconds since the epoch make midnight nothing special: a set from 23:30 to 01:00 is on at 00:15. Relies on
     * [schedule] being sorted by start, as [EventPack] gives it: "next" is the first later set of the stage.
     */
    fun nowNext(schedule: List<ScheduleItem>, stage: String, nowSec: Long): NowNext {
        val sets = schedule.filter { it.stage == stage }
        val now = sets.lastOrNull { it.start <= nowSec && nowSec < it.end }
        val next = sets.firstOrNull { it.start > nowSec && it.start - nowSec <= NEXT_WITHIN_S }
        return NowNext(now, next)
    }

    /** Minutes of walking for [meters], rounded up; none when the distance isn't known. */
    fun walkMinutes(meters: Int?): Int = if (meters == null || meters <= 0) 0 else ceil(meters / WALK_M_PER_MIN.toDouble()).toInt()

    /** When "Amintește-mi" rings for [item]: [leadMin] minutes before it starts, and the walk there earlier still. */
    fun remindAt(item: ScheduleItem, leadMin: Int, walkMeters: Int?): Long = item.start - (leadMin + walkMinutes(walkMeters)) * 60L

    /**
     * The reminders of [program] due at [nowSec], with the walk from where the phone is ([walkMeters]); a set that has
     * already started is never due, so a phone that was off doesn't ring for what it missed. [walkMeters] is asked only
     * for a set within the lead plus [MAX_WALK_MIN], so a long schedule costs no walk until a set is close.
     */
    fun due(program: MyProgram, schedule: List<ScheduleItem>, nowSec: Long, walkMeters: (ScheduleItem) -> Int?): List<ScheduleItem> =
        schedule.filter { item ->
            val key = key(item)
            key in program.reminders && key !in program.fired && nowSec < item.start &&
                item.start - nowSec <= (program.leadMin + MAX_WALK_MIN) * 60L &&
                nowSec >= remindAt(item, program.leadMin, walkMeters(item))
        }

    /**
     * "Planul meu" as one chat message: [header], then a line per set in order, as many as fit in a chat text; [line]
     * writes one set.
     */
    fun planText(items: List<ScheduleItem>, header: String, maxBytes: Int = Limits.TEXT_BYTES, line: (ScheduleItem) -> String): String {
        val text = StringBuilder(header)
        for (item in items.sortedBy { it.start }) {
            val next = "\n" + line(item)
            if ((text.toString() + next).utf8().size > maxBytes) break
            text.append(next)
        }
        return text.toString()
    }
}
