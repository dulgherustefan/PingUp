package ro.safetyplease.app.ui.control

import ro.safetyplease.app.text.text
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.edition_kind_edition
import ro.safetyplease.ui.resources.edition_kind_shift
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.edition_share
import ro.safetyplease.ui.resources.edition_title
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import ro.safetyplease.core.report.AnchorSeen
import ro.safetyplease.core.report.EditionReport
import ro.safetyplease.core.report.EditionReports
import ro.safetyplease.core.report.ModeLog
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.ScreenScaffold
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.SegmentedControl
import ro.safetyplease.ui.designsystem.subheadline

internal enum class ReportKind(val label: StringResource) { SHIFT(Res.string.edition_kind_shift), EDITION(Res.string.edition_kind_edition) }

/**
 * The shift or the whole edition, from this console's logs of both modes, its metrics and the anchors it heard. The
 * shift ends now and starts where the previous one was exported.
 */
internal fun buildReport(c: PingUpSession, kind: ReportKind, now: Long): EditionReport {
    val current = ModeLog(c.consoleLog.value.entries, c.incidentStore.value.staff)
    val other = ModeLog(c.otherConsoleLog.entries)
    val (real, exercise) = if (c.exercise) other to current else current to other
    val from = when (kind) {
        ReportKind.SHIFT -> EditionReports.shiftStart(current.entries, now)
        ReportKind.EDITION -> (real.entries + exercise.entries).minOfOrNull { it.at } ?: now
    }
    val anchors = c.heard.value.values.filter { it.anchor }
        .map { AnchorSeen(it.beat?.zone.orEmpty(), it.lastHeardMs, it.beat?.batteryPercent) }
    return EditionReports.build(
        from, now, real, exercise, c.grid, c.metricsLog.value.snapshots, c.networkLog.value.samples,
        c.reporterMetrics.value.reports, anchors, TimeZone.currentSystemDefault(),
    )
}

/** The report as it will leave the console, before the operator shares it as a PDF and two CSV files. */
@Composable
internal fun ReportDialog(c: PingUpSession, scope: CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    var kind by rememberSaveable { mutableStateOf(ReportKind.SHIFT) }
    val shown by produceState<Pair<EditionReport, ReportDoc>?>(null, kind, res) {
        value = null
        value = withContext(Dispatchers.Default) {
            val now = c.clock.wallMs()
            val report = buildReport(c, kind, now)
            report to reportDoc(report, res.text(kind.label), res, c.venue, now)
        }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenScaffold(
            stringResource(Res.string.edition_title), onDismiss,
            actions = {
                AppButton(
                    stringResource(Res.string.edition_share),
                    { shown?.let { (report, doc) -> scope.launch { shareReport(context, c, report, doc) } } },
                    kind = ButtonKind.Secondary, enabled = shown != null, compact = true,
                )
            },
        ) { padding ->
            LazyColumn(contentPadding = padding) {
                item {
                    SegmentedControl(
                        ReportKind.entries.map { it to stringResource(it.label) }, kind, { kind = it },
                        Modifier.padding(horizontal = Gutter),
                    )
                }
                val doc = shown?.second
                if (doc != null) {
                    item {
                        Text(
                            doc.subtitle, style = MaterialTheme.typography.subheadline,
                            modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 12.dp),
                        )
                    }
                    items(doc.sections) { section -> ReportSection(section) }
                }
            }
        }
    }
}

@Composable
private fun ReportSection(section: DocSection) {
    SectionTitle(section.title)
    InsetGroup {
        section.lines.forEachIndexed { i, line ->
            if (i > 0) GroupDivider(16.dp)
            if (line.value.isEmpty()) {
                Text(line.label, style = MaterialTheme.typography.subheadline, modifier = Modifier.fillMaxWidth().padding(16.dp))
            } else {
                GroupRow(line.label, value = line.value)
            }
        }
    }
}
