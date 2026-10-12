package ro.safetyplease.app.ui.me

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.ui.common.freshExportDir
import ro.safetyplease.app.ui.common.shareFiles
import ro.safetyplease.core.control.DeliveryRow
import ro.safetyplease.core.control.MetricsExport
import ro.safetyplease.core.control.MetricsJoin
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.metrics.MeshSnapshot
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.venue.SiteGrid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What this phone measured for the pilot; [delivery] only on the console. */
internal class PhoneMetrics(
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
    fun writeTo(dir: File, nowMs: Long, zone: TimeZone): List<File> {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(nowMs))
        fun file(suffix: String, text: String) = File(dir, "metrici-$stamp$suffix").apply { writeText(text) }
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
internal fun AppContainer.phoneMetrics(): PhoneMetrics {
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
internal suspend fun shareMetrics(context: Context, c: AppContainer) {
    val files = withContext(Dispatchers.IO) {
        c.phoneMetrics().writeTo(freshExportDir(c.exportDir), c.clock.wallMs(), TimeZone.currentSystemDefault())
    }
    shareFiles(context, files)
}

/** Adds the reporters' records from metrics exports the operator picked; how many there were. */
internal suspend fun importMetrics(context: Context, c: AppContainer, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
    val records = uris.flatMap { uri ->
        runCatching { context.contentResolver.openInputStream(uri)?.use(::readBounded) }.getOrNull()
            ?.let(MetricsExport::reportsIn).orEmpty()
    }
    if (records.isNotEmpty()) c.reporterMetrics.update { it.merged(records) }
    records.size
}

/** The whole stream as text, or null past [MetricsExport.MAX_IMPORT_BYTES]: a file that big isn't an export. */
internal fun readBounded(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (out.size() + n > MetricsExport.MAX_IMPORT_BYTES) return null
        out.write(buffer, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
