package ro.safetyplease.app.demo

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.venue.Venue
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DemoScheduleTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val first = LocalDate.of(2026, 7, 3)
    private val sets = DemoSchedule.sets(first, DemoChain.PACK_DAYS, zone)

    @Test
    fun bothStagesPlayEveryNightOfThePack() {
        val nights = sets.groupBy { item ->
            Instant.ofEpochSecond(item.start).atZone(zone).let { if (it.hour < 12) it.toLocalDate().minusDays(1) else it.toLocalDate() }
        }
        assertEquals((0 until DemoChain.PACK_DAYS).map { first.plusDays(it) }, nights.keys.sorted())
        for ((_, night) in nights) assertEquals(setOf(DemoSchedule.MAIN, DemoSchedule.SECOND), night.map { it.stage }.toSet())
    }

    @Test
    fun everySetFitsThePackRules() {
        val venue = Venue.parse(File("src/main/assets/venue.json").readText())
        assertEquals(sets.sortedBy { it.start }, sets)
        for (set in sets) {
            assertTrue(set.stage, venue.zone(set.stage) != null)
            assertTrue(set.title, set.title.isNotBlank() && set.title.length <= EventPacks.SCHEDULE_TITLE_CHARS)
            assertTrue(set.end > set.start && set.end - set.start <= EventPacks.SET_MAX_S)
        }
        assertTrue("one set each night runs past midnight", sets.any { Instant.ofEpochSecond(it.start).atZone(zone).hour == 0 })
    }

    @Test
    fun theJsonIsWhatThePackReads() {
        val json = DemoSchedule.json(sets)
        assertEquals(sets.size, json.size)
        val firstSet = json.first().jsonObject
        assertEquals(sets.first().stage, firstSet["stage"]!!.jsonPrimitive.content)
        assertEquals(sets.first().end, firstSet["end"]!!.jsonPrimitive.long)
    }
}
