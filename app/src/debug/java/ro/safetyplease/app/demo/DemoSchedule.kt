package ro.safetyplease.app.demo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ro.safetyplease.core.event.ScheduleItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The demo pack's schedule: the main stage and stage 2 every evening of the pack's days, in the phone's time zone, the
 * last set of each night running past midnight. The names are made up.
 */
object DemoSchedule {
    const val MAIN = "main-stage"
    const val SECOND = "second-stage"

    private class Slot(val stage: String, val start: LocalTime, val minutes: Long)

    private val NIGHT = listOf(
        Slot(SECOND, LocalTime.of(18, 0), 90),
        Slot(MAIN, LocalTime.of(19, 0), 60),
        Slot(SECOND, LocalTime.of(20, 0), 90),
        Slot(MAIN, LocalTime.of(20, 30), 75),
        Slot(SECOND, LocalTime.of(22, 0), 90),
        Slot(MAIN, LocalTime.of(22, 15), 90),
        Slot(SECOND, LocalTime.of(23, 45), 105),
        Slot(MAIN, LocalTime.of(0, 15), 105),
    )

    private val ACTS = listOf(
        "Valuri de Seară", "Nisip Electric", "Pescăruș Records", "Faleza", "Ora Albastră", "Briza", "Dune", "Far 44",
        "Corali", "Larg", "Zori pe Plajă", "Sare și Soare", "Golful Mic", "Epava", "Meridian", "Tuzla Sound",
    )

    /** [days] festival days from [firstDay]; a set after midnight belongs to the night before. */
    fun sets(firstDay: LocalDate, days: Long, zone: ZoneId): List<ScheduleItem> = (0 until days).flatMap { d ->
        val day = firstDay.plusDays(d)
        NIGHT.mapIndexed { i, slot ->
            val date = if (slot.start.hour < 12) day.plusDays(1) else day
            val start = date.atTime(slot.start).atZone(zone).toEpochSecond()
            ScheduleItem(slot.stage, ACTS[(d.toInt() * NIGHT.size + i) % ACTS.size], start, start + slot.minutes * 60)
        }
    }.sortedBy { it.start }

    fun json(items: List<ScheduleItem>): JsonArray = buildJsonArray {
        for (item in items) {
            add(
                buildJsonObject {
                    put("stage", item.stage)
                    put("title", item.title)
                    put("start", item.start)
                    put("end", item.end)
                },
            )
        }
    }
}
