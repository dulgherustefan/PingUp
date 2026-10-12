package ro.safetyplease.app.ui.me

import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.control.DeliveryRow
import ro.safetyplease.core.control.MetricsExport
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import java.io.ByteArrayInputStream

class MetricsShareTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val grid = SiteGrid.of(Bounds(43.948, 28.632, 43.9512, 28.6382))
    private val t0 = 1_791_832_500_000L
    private val zone = TimeZone.of("Europe/Bucharest")
    private val report = ReportTiming("0a".repeat(16), t0, 1, 3)

    private fun metrics(role: Role, network: List<NetworkSample> = emptyList(), delivery: List<DeliveryRow> = emptyList()) =
        PhoneMetrics(role, "Beach", grid, listOf(report), emptyList(), network, delivery)

    private fun suffixes(metrics: PhoneMetrics): List<String> =
        metrics.writeTo(tmp.newFolder(), t0, zone).map { it.name.substringAfter("metrici-").dropWhile { c -> c.isDigit() || c == '-' } }

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
    fun theJsonFileIsWhatTheConsoleReadsBack() {
        val json = metrics(Role.STAFF).writeTo(tmp.newFolder(), t0, zone).single { it.name.endsWith(".json") }
        assertEquals(listOf(report), MetricsExport.reportsIn(json.readText()))
    }

    @Test
    fun readingStopsAtTheImportLimit() {
        assertEquals("{}", readBounded(ByteArrayInputStream("{}".toByteArray())))
        assertNull(readBounded(ByteArrayInputStream(ByteArray(MetricsExport.MAX_IMPORT_BYTES + 1))))
    }
}
