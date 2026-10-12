package ro.safetyplease.ui.control

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.ConsoleView
import ro.safetyplease.core.control.ExportNames
import ro.safetyplease.core.control.LogExport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.report.EditionReport
import ro.safetyplease.core.report.ReportExport
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.PhoneUi
import ro.safetyplease.ui.common.exportStamp
import ro.safetyplease.ui.common.freshExportDir
import ro.safetyplease.ui.common.writeExport
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
import ro.safetyplease.ui.text.fill
import ro.safetyplease.ui.text.textsOf

/** Says what leaves the phone, then writes the files and opens the share sheet in [scope], which outlives the dialog. */
@Composable
fun ExportDialog(c: PingUpSession, phone: PhoneUi, entries: Int, scope: CoroutineScope, onDismiss: () -> Unit) {
    AppDialog(
        onDismiss, stringResource(Res.string.control_export_title), stringResource(Res.string.control_export_confirm),
        onConfirm = {
            scope.launch { shareLog(phone, c) }
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

/** The names in the log's files, read now: the export names them without waiting. */
private suspend fun exportNames(venue: Venue): ExportNames {
    val events = getStringArray(Res.array.control_log_events)
    val states = getStringArray(Res.array.control_case_states)
    return ExportNames(
        event = { events[it.ordinal] },
        category = textsOf(IncidentCategory.all, Labels::category),
        severity = textsOf(listOf(Severity.LOW, Severity.MEDIUM, Severity.URGENT), Labels::urgency),
        status = textsOf(AckStatus.NONE..AckStatus.REOPENED, ::ackWord),
        state = { states[it.ordinal] },
        zone = { if (it.isEmpty()) "" else venue.zoneName(it) },
        answer = textsOf(Answer.UNASKED..Answer.UNKNOWN, Labels::answer),
    )
}

/**
 * The shift log as two CSV files and a JSON one, written to the app's cache, then the share sheet. The previous export's
 * files are deleted first; the log itself keeps everything and notes the export once an app was picked to receive it.
 */
private suspend fun shareLog(phone: PhoneUi, c: PingUpSession) {
    val now = c.clock.wallMs()
    val names = exportNames(c.venue)
    val files = withContext(Dispatchers.IO) {
        val zone = TimeZone.currentSystemDefault()
        val entries = c.consoleLog.value.entries
        val cases = ConsoleView.cases(entries, c.incidentStore.value.staff, c.grid).values
        val dir = freshExportDir(c.exportDir)
        val stamp = exportStamp(now, zone)
        val json = LogExport.json(entries, cases, c.settings.value.teamName, c.venue.name, c.grid, now, zone)
        listOf(
            writeExport(dir, "jurnal-$stamp-evenimente.csv", LogExport.eventsCsv(entries, names, zone)),
            writeExport(dir, "jurnal-$stamp-incidente.csv", LogExport.incidentsCsv(cases, names, zone)),
            writeExport(dir, "jurnal-$stamp.json", json),
        )
    }
    // backing out of the share sheet notes nothing
    phone.shareFiles(files) { c.console.exported() }
}

/**
 * The report as a PDF in the app's language and two CSV files, one row per incident and the figures by section, then
 * the share sheet. Like the log export, it replaces the files of the last export; it doesn't note an export in the log,
 * since the log itself stays on the phone.
 */
suspend fun shareReport(phone: PhoneUi, c: PingUpSession, report: EditionReport, doc: ReportDoc) {
    val names = reportNames()
    val footer = getString(Res.string.edition_footer)
    val appName = phone.appName
    val files = withContext(Dispatchers.IO) {
        val zone = TimeZone.currentSystemDefault()
        val dir = freshExportDir(c.exportDir)
        val stamp = exportStamp(report.until, zone)
        listOf(
            (dir / "raport-$stamp.pdf").also { writeReportPdf(doc, { page -> footer.fill(appName, c.venue.name, page) }, it) },
            writeExport(dir, "raport-$stamp-incidente.csv", ReportExport.incidentsCsv(report, names, zone)),
            writeExport(dir, "raport-$stamp-sumar.csv", ReportExport.summaryCsv(report, names, zone)),
        )
    }
    phone.shareFiles(files)
}
