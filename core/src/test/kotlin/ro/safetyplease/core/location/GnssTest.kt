package ro.safetyplease.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class GnssTest {
    private val l1 = 1_575_420_000.0
    private val l5 = 1_176_450_000.0

    private fun fix(accuracy: Float, l5: Boolean) = LocationFix(44.0, 28.0, accuracy, null, null, FixMethod.GNSS, 0, 0, l5)

    @Test
    fun l5IsTheCarrierWithinOneMegahertz() {
        assertTrue(L5.isL5(l5))
        assertTrue(L5.isL5(l5 + 999_000))
        assertTrue(L5.isL5(l5 - 1_000_000))
        assertFalse(L5.isL5(l5 + 1_000_001))
        assertFalse(L5.isL5(l1))
    }

    @Test
    fun l5CountsOnlyForSatellitesUsedInTheFix() {
        assertFalse(L5.inFix(listOf(SatSignal(true, 40f, l1), SatSignal(false, 30f, l5))))
        assertFalse(L5.inFix(listOf(SatSignal(true, 40f, null))))
        assertTrue(L5.inFix(listOf(SatSignal(true, 40f, l1), SatSignal(true, 35f, l5 + 200_000))))
        assertTrue(L5.supported(listOf(l1, 1_176_450_000.0)))
        assertFalse(L5.supported(listOf(l1)))
    }

    @Test
    fun summaryAndMinuteSamples() {
        val stats = GnssStats()
        stats.onStatus(listOf(SatSignal(true, 40f, l1), SatSignal(true, 30f, l5 + 3_000), SatSignal(false, 10f, null)))
        stats.onFirstFix(12_500)
        stats.onFix(fix(3f, l5 = true))
        stats.onFix(fix(5f, l5 = false))
        stats.onFix(fix(9f, l5 = false))
        val s = stats.summary()
        assertEquals(2, s.satsInFix)
        assertEquals(35f, s.cn0Mean!!, 1e-4f)
        assertEquals(listOf(1176.45, 1575.42), s.carriersMhz)
        assertEquals(12.5f, s.ttffS!!, 1e-4f)
        assertEquals(5f, s.accMedian)
        assertEquals(3f, s.accBest)
        assertEquals(9f, s.accWorst)
        assertEquals(3, s.fixes)
        assertEquals(33, s.l5Pct)

        val first = stats.sample(60_000, 87)
        assertEquals(GnssSample(60_000, 33, 2f, 35f, 5f, 12.5f, 3, 87), first)
        // a minute with nothing in it
        assertEquals(GnssSample(120_000, null, null, null, null, 12.5f, 0, 86), stats.sample(120_000, 86))
        assertEquals(2, stats.samples().size)
        assertNull(GnssStats().summary().l5Pct)
    }

    @Test
    fun resetWindowDropsTheUnfinishedMinuteOnly() {
        val stats = GnssStats()
        stats.onStatus(listOf(SatSignal(true, 40f, l1)))
        stats.onFix(fix(4f, l5 = true))
        stats.resetWindow()
        assertEquals(GnssSample(60_000, null, null, null, null, null, 0, 80), stats.sample(60_000, 80))
        assertEquals(1, stats.summary().fixes)
    }

    @Test
    fun csvHasTheCampaignColumns() {
        val samples = listOf(
            GnssSample(1_760_000_000_000, 50, 11.5f, 38.25f, 3.04f, 4.2f, 30, 91),
            GnssSample(1_760_000_060_000, null, null, null, null, null, 0, 90),
        )
        val lines = GnssCsv.csv("Samsung SM-A546B", 34, samples, ZoneOffset.UTC).removePrefix("﻿").split("\r\n")
        assertEquals("model,api,l5,satsInFix,cn0Mean,accMedian,ttffS,fixes,batteryPct,timestamp", lines[0])
        assertEquals("Samsung SM-A546B,34,50,11.5,38.3,3.0,4.2,30,91,2025-10-09T08:53:20Z", lines[1])
        assertEquals("Samsung SM-A546B,34,,,,,,0,90,2025-10-09T08:54:20Z", lines[2])
        assertEquals("", lines[3])
    }
}
