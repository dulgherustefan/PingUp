package ro.safetyplease.ui.me

import kotlinx.datetime.TimeZone
import okio.Buffer
import okio.FileSystem
import okio.Path
import ro.safetyplease.core.control.DeliveryRow
import ro.safetyplease.core.control.MetricsExport
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.ui.common.readBounded
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetricsShareTest {
    private val fs = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "metrics-${Random.nextLong().toULong()}"

    @AfterTest
    fun clean() = fs.deleteRecursively(root)

    private fun newFolder(): Path = (root / "${Random.nextInt().toUInt()}").also { fs.createDirectories(it) }

    private val grid = SiteGrid.of(Bounds(43.948, 28.632, 43.9512, 28.6382))
    private val t0 = 1_791_832_500_000L
    private val zone = TimeZone.of("Europe/Bucharest")
    private val report = ReportTiming("0a".repeat(16), t0, 1, 3)

    private fun metrics(role: Role, network: List<NetworkSample> = emptyList(), delivery: List<DeliveryRow> = emptyList()) =
        PhoneMetrics(role, "Beach", grid, listOf(report), emptyList(), network, delivery)

    private fun suffixes(metrics: PhoneMetrics): List<String> =
        metrics.writeTo(newFolder(), t0, zone).map { it.name.substringAfter("metrici-").dropWhile { c -> c.isDigit() || c == '-' } }

    @Test
    fun aPhoneWithoutTheNetworkLogExportsItsReportsAndTheMesh() {
        assertEquals(listOf("rapoarte.csv", "retea.csv", ".json"), suffixes(metrics(Role.PARTICIPANT)))
    }

    @Test
    fun theMobileLogAndTheJoinComeOnlyWhereTheyExist() {
        assertEquals(
            listOf("rapoarte.csv", "retea.csv", "mobil.csv", ".json"),
            suffixes(metrics(Role.STAFF, network = listOf(NetworkSample(t0)))),
        )
        assertEquals(listOf("rapoarte.csv", "retea.csv", "livrare.csv", ".json"), suffixes(metrics(Role.CONTROL)))
    }

    @Test
    fun theFilesAreNamedOnThePhonesClock() {
        // 19:15 UTC is 22:15 in Bucharest, in summer time until the end of October
        assertEquals("metrici-2026-10-12-2215.json", metrics(Role.STAFF).writeTo(newFolder(), t0, zone).last().name)
    }

    @Test
    fun theJsonFileIsWhatTheConsoleReadsBack() {
        val json = metrics(Role.STAFF).writeTo(newFolder(), t0, zone).single { it.name.endsWith(".json") }
        assertEquals(listOf(report), MetricsExport.reportsIn(fs.read(json) { readUtf8() }))
    }

    @Test
    fun readingStopsAtTheImportLimit() {
        val max = MetricsExport.MAX_IMPORT_BYTES.toLong()
        assertEquals("{}", readBounded(Buffer().writeUtf8("{}"), max))
        assertNull(readBounded(Buffer().write(ByteArray(MetricsExport.MAX_IMPORT_BYTES + 1)), max))
    }
}
