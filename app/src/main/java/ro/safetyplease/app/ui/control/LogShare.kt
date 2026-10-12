package ro.safetyplease.app.ui.control

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.app.App
import ro.safetyplease.app.R
import ro.safetyplease.app.text.quantityText
import ro.safetyplease.app.text.text
import ro.safetyplease.app.text.textArray
import ro.safetyplease.app.ui.common.freshExportDir
import ro.safetyplease.app.ui.common.shareFiles
import ro.safetyplease.core.control.ConsoleView
import ro.safetyplease.core.control.ExportNames
import ro.safetyplease.core.control.LogExport
import ro.safetyplease.core.report.EditionReport
import ro.safetyplease.core.report.ReportExport
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.control.ackWord
import ro.safetyplease.ui.designsystem.AppDialog
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_case_states
import ro.safetyplease.ui.resources.control_entries
import ro.safetyplease.ui.resources.control_export_confirm
import ro.safetyplease.ui.resources.control_export_empty
import ro.safetyplease.ui.resources.control_export_text
import ro.safetyplease.ui.resources.control_export_title
import ro.safetyplease.ui.resources.control_log_events
import ro.safetyplease.ui.resources.edition_footer
import ro.safetyplease.ui.text.Labels
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hands the exported log and metrics to the share sheet; only the export folder in the app's cache is reachable through it. */
class LogFiles : FileProvider(R.xml.log_paths)

/** The share sheet calls it once the operator picked an app; backing out calls nothing, so nothing is logged. */
class ExportShared : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as App).container.console.exported()
    }
}

/** Says what leaves the phone, then writes the files and opens the share sheet in [scope], which outlives the dialog. */
@Composable
internal fun ExportDialog(c: PingUpSession, entries: Int, scope: CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val names = rememberExportNames(c.venue)
    AppDialog(
        onDismiss, stringResource(Res.string.control_export_title), stringResource(Res.string.control_export_confirm),
        onConfirm = {
            scope.launch { shareLog(context, c, names) }
            onDismiss()
        },
        confirmEnabled = entries > 0,
    ) {
        Text(
            if (entries > 0) {
                stringResource(Res.string.control_export_text, pluralStringResource(Res.plurals.control_entries, entries, entries))
            } else {
                stringResource(Res.string.control_export_empty)
            },
            style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun rememberExportNames(venue: Venue): ExportNames {
    val res = LocalResources.current
    return remember(res, venue) {
        val events = res.textArray(Res.array.control_log_events)
        val states = res.textArray(Res.array.control_case_states)
        ExportNames(
            event = { events[it.ordinal] },
            category = { res.text(Labels.category(it)) },
            severity = { res.text(Labels.urgency(it)) },
            status = { res.text(ackWord(it)) },
            state = { states[it.ordinal] },
            zone = { if (it.isEmpty()) "" else venue.zoneName(it) },
            answer = { res.text(Labels.answer(it)) },
        )
    }
}

/**
 * The shift log as two CSV files and a JSON one, written to the app's cache, then the share sheet. The previous export's
 * files are deleted first; the log itself keeps everything and notes the export once an app was picked to receive it.
 */
private suspend fun shareLog(context: Context, c: PingUpSession, names: ExportNames) {
    val now = c.clock.wallMs()
    val files = withContext(Dispatchers.IO) {
        val zone = TimeZone.currentSystemDefault()
        val entries = c.consoleLog.value.entries
        val cases = ConsoleView.cases(entries, c.incidentStore.value.staff, c.grid).values
        val dir = freshExportDir(c.exportDir.toFile())
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(now))
        val json = LogExport.json(entries, cases, c.settings.value.teamName, c.venue.name, c.grid, now, zone)
        listOf(
            File(dir, "jurnal-$stamp-evenimente.csv").apply { writeText(LogExport.eventsCsv(entries, names, zone)) },
            File(dir, "jurnal-$stamp-incidente.csv").apply { writeText(LogExport.incidentsCsv(cases, names, zone)) },
            File(dir, "jurnal-$stamp.json").apply { writeText(json) },
        )
    }
    val shared = PendingIntent.getBroadcast(
        context, 0, Intent(context, ExportShared::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    shareFiles(context, files, shared.intentSender)
}

/**
 * The report as a PDF in the app's language and two CSV files, one row per incident and the figures by section, then
 * the share sheet. Like the log export, it replaces the files of the last export; it doesn't note an export in the log,
 * since the log itself stays on the phone.
 */
internal suspend fun shareReport(context: Context, c: PingUpSession, report: EditionReport, doc: ReportDoc) {
    val files = withContext(Dispatchers.IO) {
        val res = context.resources
        val zone = TimeZone.currentSystemDefault()
        val names = reportNames(res)
        val dir = freshExportDir(c.exportDir.toFile())
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(report.until))
        val appName = res.getString(R.string.app_name)
        val footer = { page: Int -> res.text(Res.string.edition_footer, appName, c.venue.name, page) }
        listOf(
            File(dir, "raport-$stamp.pdf").also { writeReportPdf(doc, footer, it) },
            File(dir, "raport-$stamp-incidente.csv").apply { writeText(ReportExport.incidentsCsv(report, names, zone)) },
            File(dir, "raport-$stamp-sumar.csv").apply { writeText(ReportExport.summaryCsv(report, names, zone)) },
        )
    }
    shareFiles(context, files)
}
