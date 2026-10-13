package ro.safetyplease.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import ro.safetyplease.core.chat.MeetingTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The time of a meeting point is a moment in Unix seconds, so whether it is late takes no time zone; a zone comes in only
 * to turn a time of day a person picks into a moment. Every zone here is fixed, so no machine setting matters.
 */
class MeetingTimeTest {
    private val utc = TimeZone.UTC
    private val bucharest = TimeZone.of("Europe/Bucharest")

    /** 13 October 2026, 00:00 UTC; Bucharest is on summer time that day, three hours ahead of UTC. */
    private val midnight = 1_791_849_600L

    /** [hour]:[minute] on the clock of [zone], on [day] October 2026. */
    private fun at(zone: TimeZone, hour: Int, minute: Int, day: Int = 13): Long =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(zone).epochSeconds

    /** The moment [hour]:[minute] means at [nowHour]:[nowMinute] on the 13th, on the clock of [zone]. */
    private fun next(zone: TimeZone, hour: Int, minute: Int, nowHour: Int, nowMinute: Int): Long =
        MeetingTime.nextOccurrence(hour * 60 + minute, at(zone, nowHour, nowMinute), zone)

    /** The time of day [moment] falls on in [zone], in minutes after midnight. */
    private fun minuteOfDay(moment: Long, zone: TimeZone): Int =
        Instant.fromEpochSeconds(moment).toLocalDateTime(zone).let { it.hour * 60 + it.minute }

    @Test
    fun theHelpersAgreeWithTheCalendar() {
        assertEquals(midnight, at(utc, 0, 0))
        assertEquals(midnight + 22 * 3600 + 30 * 60, at(utc, 22, 30))
        assertEquals(midnight + 19 * 3600 + 30 * 60, at(bucharest, 22, 30), "three hours ahead of UTC")
        assertEquals(at(utc, 9, 0), at(bucharest, 12, 0))
    }

    // --- late ---

    @Test
    fun aMeetingIsLateOnlyWhenNowIsMoreThanAnHourPastIt() {
        assertFalse(MeetingTime.isLate(null, at(utc, 9, 0)), "no time")
        assertFalse(MeetingTime.isLate(at(utc, 22, 30), at(utc, 9, 0)), "22:30 tonight, seen at 09:00")
        assertTrue(MeetingTime.isLate(at(utc, 8, 30), at(utc, 10, 0)), "08:30, seen at 10:00")
        assertFalse(MeetingTime.isLate(at(utc, 9, 30), at(utc, 10, 0)), "09:30, seen at 10:00")
        assertFalse(MeetingTime.isLate(at(utc, 9, 0), at(utc, 10, 0)), "an hour exactly")
        assertTrue(MeetingTime.isLate(at(utc, 9, 0), at(utc, 10, 0) + 1), "a second more")
        assertFalse(MeetingTime.isLate(at(utc, 10, 0), at(utc, 10, 0)), "just now")
    }

    @Test
    fun aMeetingIsNotLateForBeingOnAnotherDay() {
        // the first version read the clock face, and took a meeting 13.5 hours ahead for one 10.5 hours past
        assertFalse(MeetingTime.isLate(at(utc, 22, 30), at(utc, 9, 0)), "13.5 hours ahead")
        assertFalse(MeetingTime.isLate(at(utc, 9, 0, day = 14), at(utc, 22, 0)), "tomorrow morning, seen tonight")
        assertFalse(MeetingTime.isLate(at(utc, 22, 30, day = 15), at(utc, 9, 0)), "two days ahead")
        assertTrue(MeetingTime.isLate(at(utc, 22, 30, day = 12), at(utc, 22, 30)), "the same hour a day ago")
    }

    @Test
    fun theLateLimitHoldsAcrossMidnight() {
        assertFalse(MeetingTime.isLate(at(utc, 23, 30), at(utc, 0, 20, day = 14)), "50 minutes ago")
        assertFalse(MeetingTime.isLate(at(utc, 23, 30), at(utc, 0, 30, day = 14)), "an hour exactly")
        assertTrue(MeetingTime.isLate(at(utc, 23, 30), at(utc, 0, 31, day = 14)), "an hour and a minute")
        assertTrue(MeetingTime.isLate(at(utc, 23, 30), at(utc, 1, 0, day = 14)), "90 minutes ago")
        assertFalse(MeetingTime.isLate(at(utc, 0, 15, day = 14), at(utc, 23, 50)), "25 minutes ahead")
    }

    // --- a time of day to a moment ---

    @Test
    fun aTimeOfDayIsTodaysUntilAnHourPastAndThenTomorrows() {
        for (zone in listOf(utc, bucharest)) {
            assertEquals(at(zone, 22, 30), next(zone, 22, 30, 9, 0), "later today")
            assertEquals(at(zone, 10, 0), next(zone, 10, 0, 10, 0), "right now")
            assertEquals(at(zone, 9, 30), next(zone, 9, 30, 10, 0), "30 minutes past is still today's")
            assertEquals(at(zone, 9, 0), next(zone, 9, 0, 10, 0), "an hour past exactly")
            assertEquals(at(zone, 9, 0, day = 14), next(zone, 9, 0, 10, 1), "an hour and a minute past is tomorrow's")
            assertEquals(at(zone, 8, 30, day = 14), next(zone, 8, 30, 10, 0), "90 minutes past is tomorrow's")
        }
    }

    @Test
    fun whatItGivesIsNeverLateNeverADayAheadAndReadsBackAsItself() {
        val now = at(bucharest, 14, 20)
        for (zone in listOf(utc, bucharest)) {
            for (minute in 0 until 24 * 60 step 7) {
                val moment = MeetingTime.nextOccurrence(minute, now, zone)
                assertFalse(MeetingTime.isLate(moment, now), "minute $minute in $zone")
                assertTrue(moment - now < 24 * 3600, "minute $minute in $zone")
                assertEquals(minute, minuteOfDay(moment, zone), "minute $minute in $zone")
            }
        }
    }

    @Test
    fun aTimeOfDayAroundMidnightIsTheNextOneOnThatClock() {
        for (zone in listOf(utc, bucharest)) {
            assertEquals(at(zone, 0, 15, day = 14), next(zone, 0, 15, 23, 50), "25 minutes ahead, over midnight")
            assertEquals(at(zone, 0, 0, day = 14), next(zone, 0, 0, 23, 59), "a minute ahead, over midnight")
            assertEquals(at(zone, 22, 30, day = 14), next(zone, 22, 30, 23, 45), "75 minutes past, shortly before midnight")
            assertEquals(at(zone, 0, 10), next(zone, 0, 10, 0, 40), "30 minutes past, just after midnight")
            assertEquals(at(zone, 23, 30), next(zone, 23, 30, 0, 20), "the evening ahead of a night's first minutes")
            assertEquals(at(zone, 23, 59), next(zone, 23, 59, 22, 0), "the last minute of the day")
        }
    }

    @Test
    fun theSameTimeOfDayIsAnotherMomentInEachZone() {
        // 09:00 UTC is 12:00 in Bucharest
        val now = midnight + 9 * 3600
        assertEquals(midnight + 22 * 3600 + 30 * 60, MeetingTime.nextOccurrence(22 * 60 + 30, now, utc))
        assertEquals(midnight + 19 * 3600 + 30 * 60, MeetingTime.nextOccurrence(22 * 60 + 30, now, bucharest))
        // 08:00 is an hour past in UTC, so still today's; in Bucharest it is four hours past, so tomorrow's
        assertEquals(midnight + 8 * 3600, MeetingTime.nextOccurrence(8 * 60, now, utc))
        assertEquals(midnight + 24 * 3600 + 5 * 3600, MeetingTime.nextOccurrence(8 * 60, now, bucharest))
    }

    @Test
    fun midnightComesAtAnotherMomentInEachZone() {
        // 20:50 UTC is 23:50 in Bucharest: 00:15 is 25 minutes away there, and 3 hours 25 minutes away in UTC
        val now = midnight + 20 * 3600 + 50 * 60
        assertEquals(now + 25 * 60, MeetingTime.nextOccurrence(15, now, bucharest))
        assertEquals(now + 3 * 3600 + 25 * 60, MeetingTime.nextOccurrence(15, now, utc))
    }

    @Test
    fun aTimeOfDayCountsRealHoursOnTheNightTheClocksChange() {
        // Bucharest goes from 03:00 to 04:00 on 29 March 2026: from 00:30 to 05:00 is three and a half hours, not four and a half
        val now = LocalDateTime(2026, 3, 29, 0, 30).toInstant(bucharest).epochSeconds
        val five = MeetingTime.nextOccurrence(5 * 60, now, bucharest)
        assertEquals(now + 3 * 3600 + 30 * 60, five)
        assertEquals(5 * 60, minuteOfDay(five, bucharest))
    }

    @Test
    fun aMinuteOutsideTheDayIsRefused() {
        assertFailsWith<IllegalArgumentException> { MeetingTime.nextOccurrence(-1, midnight, utc) }
        assertFailsWith<IllegalArgumentException> { MeetingTime.nextOccurrence(24 * 60, midnight, utc) }
    }
}
