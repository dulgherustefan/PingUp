package ro.safetyplease.core.control

import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.control.StaffDensity.PHONE_GAP_MS
import ro.safetyplease.core.control.StaffDensity.REFRESH_MS
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.Severity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaffDensityTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val zones = 6
    private val gap = StaffDensity.zoneGapMs(zones)
    private val t0 = 10_000_000L

    private fun heard(zone: String, level: Int, at: Long, id: String = "$zone-$at") = DensityReading(zone, level, at, at, "Steward", id)

    @Test
    fun theWholeNetworkStaysUnderATenthOfAPacketASecond() {
        for (count in listOf(1, 3, 6, 12, 40)) {
            val perSecond = count * 2 * 1000.0 / StaffDensity.zoneGapMs(count)
            assertTrue(perSecond <= StaffDensity.BUDGET_PER_S + 1e-9, "$count zones: $perSecond/s")
        }
        assertEquals(StaffDensity.MIN_ZONE_GAP_MS, StaffDensity.zoneGapMs(1), "a small venue still waits a minute")
        assertEquals(120_000L, gap, "six zones: one report and one rise per zone every two minutes")
    }

    @Test
    fun aPhoneSendsAtMostOneEveryTwoMinutes() {
        val log = DensityLog(sentAt = t0)
        assertEquals(DensityVerdict.PHONE_WAIT, StaffDensity.check(log, "a", 3, t0 + PHONE_GAP_MS - 1, zones))
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "a", 3, t0 + PHONE_GAP_MS, zones))
        assertEquals(DensityVerdict.SEND, StaffDensity.check(DensityLog(), "a", 3, t0, zones), "a phone that never sent")
    }

    @Test
    fun aZoneTakesOneReportPerGapWhoeverSendsIt() {
        val log = DensityLog(listOf(heard("a", 2, t0)))
        assertEquals(DensityVerdict.ZONE_RECENT, StaffDensity.check(log, "a", 3, t0 + gap - 1, zones))
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "b", 3, t0 + 1, zones), "another zone is free")
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "a", 3, t0 + gap, zones))
    }

    @Test
    fun aRiseToAnIncidentPassesOncePerGap() {
        val log = DensityLog(listOf(heard("a", 3, t0)))
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "a", 4, t0 + 1_000, zones), "packed: the console must know now")
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "a", 5, t0 + 1_000, zones))
        val risen = DensityLog(log.readings + heard("a", 5, t0 + 1_000))
        assertEquals(DensityVerdict.ZONE_RECENT, StaffDensity.check(risen, "a", 5, t0 + 2_000, zones), "the zone is an incident already")
        assertEquals(DensityVerdict.ZONE_RECENT, StaffDensity.check(risen, "a", 4, t0 + 2_000, zones))
    }

    @Test
    fun theSameLevelAgainWaitsForTheRefresh() {
        val log = DensityLog(listOf(heard("a", 3, t0)))
        assertEquals(DensityVerdict.KNOWN, StaffDensity.check(log, "a", 3, t0 + gap, zones))
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "a", 2, t0 + gap, zones), "a change goes after the gap")
        assertEquals(DensityVerdict.SEND, StaffDensity.check(log, "a", 3, t0 + REFRESH_MS, zones), "the same level confirms the zone still is so")
    }

    @Test
    fun levelsOneToThreeAreRoutineFourAndFiveAreIncidents() {
        assertTrue(StaffDensity.isRoutine(IncidentCategory.CROWD, StaffDensity.extras(3)))
        assertFalse(StaffDensity.isRoutine(IncidentCategory.CROWD, StaffDensity.extras(4)))
        assertFalse(StaffDensity.isRoutine(IncidentCategory.CROWD, ReportExtras.NONE))
        assertFalse(StaffDensity.isRoutine(IncidentCategory.MEDICAL, StaffDensity.extras(2)), "only a crowd report")
        assertEquals(listOf(Severity.LOW, Severity.LOW, Severity.LOW, Severity.MEDIUM, Severity.URGENT), (1..5).map(StaffDensity::severity))
    }

    @Test
    fun theTimelineKeepsEachZonesChanges() {
        val readings = listOf(
            heard("b", 2, t0), heard("a", 1, t0 + 1), heard("a", 1, t0 + 400_000), heard("a", 3, t0 + 500_000),
            heard("b", 4, t0 + 600_000), heard("a", 5, t0 + 700_000), heard("a", 2, t0 + 9_000_000),
        )
        val steps = StaffDensity.timeline(readings, t0, t0 + 1_000_000)
        assertEquals(
            listOf(DensityStep("a", t0 + 1, 1), DensityStep("a", t0 + 500_000, 3), DensityStep("a", t0 + 700_000, 5), DensityStep("b", t0, 2), DensityStep("b", t0 + 600_000, 4)),
            steps,
        )
        assertEquals(mapOf("a" to 2, "b" to 4), StaffDensity.latest(readings).mapValues { it.value.level })
    }

    @Test
    fun theBoardKeepsEachReportOnce() = runTest {
        val board = DensityBoard(JsonStore(tmp.root / "density.json", DensityLog.serializer(), DensityLog(), backgroundScope))
        assertTrue(board.offer(heard("a", 3, t0, "x")))
        assertFalse(board.offer(heard("a", 3, t0, "x")), "the same report from another route")
        assertFalse(board.offer(heard("a", 0, t0, "y")), "not a level")
        board.sent(t0)
        assertEquals(1, board.value.readings.size)
        assertEquals(t0, board.value.sentAt)
    }
}
