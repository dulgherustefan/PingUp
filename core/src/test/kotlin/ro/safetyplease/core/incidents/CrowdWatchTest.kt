package ro.safetyplease.core.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.incidents.CrowdWatch.MIN_REPORTS
import ro.safetyplease.core.incidents.CrowdWatch.WINDOW_MS

class CrowdWatchTest {
    private val minute = 60_000L
    private val now = 100 * minute

    private fun reports(zone: String, count: Int, startMinutesAgo: Long, everyMinutes: Long = 1) =
        List(count) { CrowdReport(zone, now - (startMinutesAgo - it * everyMinutes) * minute) }

    @Test
    fun enoughReportsFromOneZoneInTheWindowMakeASignalWithTheirSpan() {
        val signals = CrowdWatch.signals(reports("main-stage", MIN_REPORTS, startMinutesAgo = 8), now)
        val signal = signals.single()
        assertEquals("main-stage", signal.zone)
        assertEquals(MIN_REPORTS, signal.count)
        assertEquals("the first to the last report", MIN_REPORTS - 1, signal.minutes)
    }

    @Test
    fun oneShortOfTheThresholdIsNothing() {
        assertTrue(CrowdWatch.signals(reports("main-stage", MIN_REPORTS - 1, startMinutesAgo = 5), now).isEmpty())
    }

    @Test
    fun reportsOlderThanTheWindowDoNotCountAndTheSignalFadesWithTime() {
        val all = reports("main-stage", MIN_REPORTS + 1, startMinutesAgo = 9)
        assertEquals(MIN_REPORTS + 1, CrowdWatch.signals(all, now).single().count)
        assertEquals("the oldest has left the window", MIN_REPORTS, CrowdWatch.signals(all, now + 2 * minute).single().count)
        assertTrue(CrowdWatch.signals(all, now + 3 * minute).isEmpty())
        assertTrue(CrowdWatch.signals(all, now + WINDOW_MS + minute).isEmpty())
    }

    @Test
    fun zonesAreCountedApartAndTheBusiestComesFirst() {
        val mixed = reports("main-stage", MIN_REPORTS + 2, 8) + reports("bar", MIN_REPORTS, 6) + reports("food", MIN_REPORTS - 1, 5)
        val signals = CrowdWatch.signals(mixed, now)
        assertEquals(listOf("main-stage", "bar"), signals.map { it.zone })
        assertEquals(listOf(MIN_REPORTS + 2, MIN_REPORTS), signals.map { it.count })
    }

    @Test
    fun reportsWithoutAZoneSayNothingAboutAPlaceAndSpellingOfTheZoneDoesNotSplitIt() {
        assertTrue(CrowdWatch.signals(reports("", MIN_REPORTS, 5), now).isEmpty())
        val spelled = reports("Bar", 3, 5) + reports("bar ", 2, 2)
        assertEquals(MIN_REPORTS, CrowdWatch.signals(spelled, now).single().count)
    }

    @Test
    fun reportsWrittenAtTheSameMomentSpanOneMinute() {
        val same = List(MIN_REPORTS) { CrowdReport("bar", now - 2 * minute) }
        assertEquals(1, CrowdWatch.signals(same, now).single().minutes)
    }
}
