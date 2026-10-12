package ro.safetyplease.ui.me

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import okio.Path
import ro.safetyplease.core.control.DeliveryRow
import ro.safetyplease.core.control.MetricsExport
import ro.safetyplease.core.control.MetricsJoin
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.metrics.MeshSnapshot
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.common.PickedFile
import ro.safetyplease.ui.common.exportStamp
import ro.safetyplease.ui.common.freshExportDir
import ro.safetyplease.ui.common.readBounded
import ro.safetyplease.ui.common.writeExport

/** What this phone measured for the pilot; [delivery] only on the console. */
class PhoneMetrics(
    val role: Role,
    val venue: String,
    val grid: SiteGrid,
    val reports: List<ReportTiming>,
    val snapshots: List<MeshSnapshot>,
    val network: List<NetworkSample>,
    val delivery: List<DeliveryRow>,
) {
    /**
     * The CSV files and the JSON one, named by [nowMs]: the reports and the mesh always, the mobile network log once it
     * has samples, the delivery join on the console.
     */
    fun writeTo(dir: Path, nowMs: Long, zone: TimeZone): List<Path> {
        val stamp = exportStamp(nowMs, zone)
        fun file(suffix: String, text: String) = writeExport(dir, "metrici-$stamp$suffix", text)
        return buildList {
            add(file("-rapoarte.csv", MetricsExport.reportsCsv(reports, zone)))
            add(file("-retea.csv", MetricsExport.meshCsv(snapshots, zone)))
            if (network.isNotEmpty()) add(file("-mobil.csv", MetricsExport.networkCsv(network, zone)))
            if (role == Role.CONTROL) add(file("-livrare.csv", MetricsExport.deliveryCsv(delivery, zone)))
            add(file(".json", MetricsExport.json(role, venue, grid, reports, snapshots, network, delivery, nowMs, zone)))
        }
    }
}

/** The console joins its log with the reporters' records it was given and with its own reports. */
fun PingUpSession.phoneMetrics(): PhoneMetrics {
    val role = settings.value.role
    val reports = incidentStore.value.mine.map { ReportTiming.of(it, grid) }
    val delivery = if (role == Role.CONTROL) {
        MetricsJoin.join(consoleLog.value.entries, reporterMetrics.value.merged(reports).reports)
    } else {
        emptyList()
    }
    return PhoneMetrics(role, venue.name, grid, reports, metricsLog.value.snapshots, networkLog.value.samples, delivery)
}

/** Writes the metrics to the export folder, replacing the last export's files, and opens the share sheet. */
suspend fun shareMetrics(phone: PhoneUi, c: PingUpSession) {
    val files = withContext(Dispatchers.IO) {
        c.phoneMetrics().writeTo(freshExportDir(c.exportDir), c.clock.wallMs(), TimeZone.currentSystemDefault())
    }
    phone.shareFiles(files)
}

/** Adds the reporters' records from the metrics exports the operator picked; how many there were. */
suspend fun importMetrics(c: PingUpSession, files: List<PickedFile>): Int = withContext(Dispatchers.IO) {
    // a file past the limit isn't an export
    val records = files.flatMap { file ->
        runCatching { readBounded(file.open(), MetricsExport.MAX_IMPORT_BYTES.toLong()) }.getOrNull()?.let(MetricsExport::reportsIn).orEmpty()
    }
    if (records.isNotEmpty()) c.reporterMetrics.update { it.merged(records) }
    records.size
}
