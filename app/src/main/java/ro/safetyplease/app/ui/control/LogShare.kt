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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.R
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.ui.designsystem.AppDialog
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.core.control.ConsoleView
import ro.safetyplease.core.control.ExportNames
import ro.safetyplease.core.control.LogExport
import ro.safetyplease.core.venue.Venue
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Hands the exported log to the share sheet; only the export folder in the app's cache is reachable through it. */
class LogFiles : FileProvider(R.xml.log_paths)

/** The share sheet calls it once the operator picked an app; backing out calls nothing, so nothing is logged. */
class ExportShared : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as App).container.console.exported()
    }
}

/** Says what leaves the phone, then writes the files and opens the share sheet in [scope], which outlives the dialog. */
@Composable
internal fun ExportDialog(c: AppContainer, entries: Int, scope: CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val names = rememberExportNames(c.venue)
    AppDialog(
        onDismiss, stringResource(R.string.control_export_title), stringResource(R.string.control_export_confirm),
        onConfirm = {
            scope.launch { shareLog(context, c, names) }
            onDismiss()
        },
        confirmEnabled = entries > 0,
    ) {
        Text(
            if (entries > 0) {
                stringResource(R.string.control_export_text, pluralStringResource(R.plurals.control_entries, entries, entries))
            } else {
                stringResource(R.string.control_export_empty)
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
        val events = res.getStringArray(R.array.control_log_events)
        val states = res.getStringArray(R.array.control_case_states)
        ExportNames(
            event = { events[it.ordinal] },
            category = { res.getString(Labels.category(it)) },
            severity = { res.getString(Labels.urgency(it)) },
            status = { res.getString(ackWord(it)) },
            state = { states[it.ordinal] },
            zone = { if (it.isEmpty()) "" else venue.zoneName(it) },
            answer = { res.getString(Labels.answer(it)) },
        )
    }
}

/**
 * The shift log as two CSV files and a JSON one, written to the app's cache, then the share sheet. The previous export's
 * files are deleted first; the log itself keeps everything and notes the export once an app was picked to receive it.
 */
private suspend fun shareLog(context: Context, c: AppContainer, names: ExportNames) {
    val now = c.clock.wallMs()
    val files = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val entries = c.consoleLog.value.entries
        val cases = ConsoleView.cases(entries, c.incidentStore.value.staff, c.grid).values
        val dir = c.exportDir.apply {
            deleteRecursively()
            mkdirs()
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(now))
        val json = LogExport.json(entries, cases, c.settings.value.teamName, c.venue.name, c.grid, now, zone)
        listOf(
            File(dir, "jurnal-$stamp-evenimente.csv").apply { writeText(LogExport.eventsCsv(entries, names, zone)) },
            File(dir, "jurnal-$stamp-incidente.csv").apply { writeText(LogExport.incidentsCsv(cases, names, zone)) },
            File(dir, "jurnal-$stamp.json").apply { writeText(json) },
        )
    }
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.log", it) }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val shared = PendingIntent.getBroadcast(
        context, 0, Intent(context, ExportShared::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    context.startActivity(Intent.createChooser(send, null, shared.intentSender))
}
