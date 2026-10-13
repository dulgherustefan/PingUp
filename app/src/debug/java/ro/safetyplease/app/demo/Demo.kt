package ro.safetyplease.app.demo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.R
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.shortHex
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.designsystem.ConfirmDialog
import ro.safetyplease.ui.designsystem.LocalAppColors
import ro.safetyplease.ui.designsystem.ScreenScaffold

/** Demo mode, debug builds only: tools for presenting and for debugging the transport. */
object Demo {
    const val AVAILABLE = true

    @Volatile
    private var pinger: TestPinger? = null

    // it keeps only the application context
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var recorder: FieldRecorder? = null

    fun install(container: AppContainer) {
        if (pinger == null) pinger = TestPinger(container).also { it.start() }
    }

    fun pinger(container: AppContainer): TestPinger {
        install(container)
        return pinger!!
    }

    fun recorder(context: Context, container: AppContainer): FieldRecorder {
        if (recorder == null) recorder = FieldRecorder(context.applicationContext, container, pinger(container))
        return recorder!!
    }

    /** A shift of the demo chain (assets/demo_chain.json), activated as its SPS2 code would be. */
    private fun takeShift(context: Context, c: AppContainer, role: StaffRole, team: String, zone: String): Boolean {
        val card = DemoChain.load(context, c.crypto).shift(role, team.truncateUtf8(Limits.TEAM_BYTES), zone, c.clock.wallMs() / 1000)
        return c.activateShift(card, QrCodes.encodeShift(card)) == CertStatus.VALID
    }

    /** [lead] makes the phone ring like the console for its role's urgencies. */
    fun becomeStaff(context: Context, c: AppContainer, team: String, zone: String, lead: Boolean): Boolean {
        if (!takeShift(context, c, StaffRole.STAFF, team, zone)) return false
        c.settings.update { it.copy(roleLead = lead) }
        return true
    }

    fun becomeControl(context: Context, c: AppContainer): Boolean = takeShift(context, c, StaffRole.CONTROL, "Control", "")

    fun becomeAnchor(context: Context, c: AppContainer, zone: String): Boolean = takeShift(context, c, StaffRole.ANCHOR, "Ancora $zone", zone)

    private fun packText(context: Context, c: AppContainer): String {
        fun text(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
        fun folder(dir: String) = context.assets.list(dir).orEmpty().filter { it.endsWith(".md") }.sorted().associateWith { text("$dir/$it") }
        return DemoChain.load(context, c.crypto).pack(
            Json.parseToJsonElement(text("demo_event.json")).jsonObject, text("venue.json"), text("notices.json"),
            folder("firstaid"), mapOf("ro" to folder("pages"), "en" to folder("pages-en")), c.clock.wallMs() / 1000,
        )
    }

    /**
     * Checks and keeps today's demo pack: its hash, to switch to with [AppContainer.switchEvent], which restarts the
     * app; null when it is already in use, a failure naming the status when the phone refused it.
     */
    fun loadPack(context: Context, c: AppContainer): Result<String?> {
        val text = packText(context, c)
        val loaded = c.loadEventPack(text)
        if (loaded.status != PackStatus.VALID) return Result.failure(IllegalStateException(loaded.status.name))
        val hash = c.events.value.packs.first { it.text == text }.hash
        return Result.success(hash.takeIf { it != c.events.value.active })
    }

    /** What the scenario last put on the point board, so a reset takes back only that. */
    @Volatile
    private var seededPoints: Map<Int, PointReport> = emptyMap()

    /**
     * Makes this phone the console and gives it a shift's test data ([DemoScenario]); the heat notice goes to the
     * phones around, as a real one would. False if the demo chain refused the console's shift.
     */
    fun scenario(context: Context, c: AppContainer): Boolean {
        if (c.settings.value.role != Role.CONTROL && !becomeControl(context, c)) return false
        val now = c.clock.wallMs()
        val incidents = DemoScenario.incidents(c.venue, now)
        c.incidentStore.update { data ->
            data.copy(
                staff = data.staff.filterNot { DemoScenario.isDemo(it.incidentId) } + incidents,
                dismissed = data.dismissed.filterNot(DemoScenario::isDemo),
            )
        }
        seededPoints = DemoScenario.points(c.venue, now)
        c.points.update { it + seededPoints }
        c.heard.update { it + DemoScenario.anchors(c.venue, c.grid, now) }
        c.noticeTemplates.forTopic(NoticeTopic.HEAT).firstOrNull { it.level == HEAT_LEVEL }?.let {
            c.notices.publish(it, emptyList(), "", HEAT_VALID_S)
        }
        return true
    }

    /** Takes back what [scenario] added, its lines in the console log too, and withdraws the heat notice. */
    fun reset(c: AppContainer) {
        c.incidentStore.update { data -> data.copy(staff = data.staff.filterNot { DemoScenario.isDemo(it.incidentId) }) }
        c.consoleLog.update { log -> log.copy(entries = log.entries.filterNot { DemoScenario.isDemo(it.incidentId) }) }
        val points = seededPoints
        c.points.update { board -> board.filterNot { (number, report) -> points[number] == report } }
        seededPoints = emptyMap()
        c.heard.update { it - DemoScenario.ANCHOR_PREFIXES.toSet() }
        c.notices.cancel(NoticeTopic.HEAT)
        DemoFriends.remove(c)
    }

    private const val HEAT_LEVEL = 2
    private const val HEAT_VALID_S = 4 * 3600L

    private var testIncidents = 0

    fun testIncident(c: AppContainer, zone: String, lat: Double?, lon: Double?) {
        testIncidents++
        c.incidents.report(
            ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, zone, lat, lon, "Incident de test #$testIncidents", null),
            bypassRateLimit = true,
        )
    }

    private fun flagLetters(flags: Int): String = buildString {
        if (flags and NodeFlags.STAFF != 0) append('S')
        if (flags and NodeFlags.ANCHOR != 0) append('A')
        if (flags and NodeFlags.PENDING_INCIDENT != 0) append('I')
        if (flags and NodeFlags.ACCEPTS_CONNECTIONS != 0) append('C')
    }.ifEmpty { "-" }

    private class PeerRow(val prefix: Int, val name: String, val linked: String?, val rssi: Int?, val flags: Int)

    @Composable
    fun Screen(vm: AppViewModel) {
        val context = LocalContext.current
        // the demo drives Android parts too (radio probe, recorder): the whole container, not just the session
        val c = (context.applicationContext as App).container
        val colors = LocalAppColors.current
        val mesh by vm.mesh.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val position by vm.position.collectAsStateWithLifecycle()
        val log by c.log.entries.collectAsStateWithLifecycle()
        val pinger = remember { pinger(c) }
        val tests by pinger.lines.collectAsStateWithLifecycle()
        val recorder = remember { recorder(context, c) }
        val recording by recorder.recording.collectAsStateWithLifecycle()
        var confirmWipe by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        var measureNotice by remember { mutableStateOf<String?>(null) }
        var kitNotice by remember { mutableStateOf<String?>(null) }

        // Android pauses unfiltered scans with the screen off
        val view = LocalView.current
        val census = recording?.mode == FieldRecorder.Mode.CENSUS
        DisposableEffect(census) {
            view.keepScreenOn = census
            onDispose { view.keepScreenOn = false }
        }

        val peers = buildList {
            for (link in mesh.links.filter { it.peerId != 0L }) {
                val prefix = link.peerId.nodePrefix()
                // a peer that rotates advertises another prefix than its nodeId's; the link knows which
                val seen = mesh.seen.firstOrNull { it.prefix == (link.prefix ?: prefix) }
                add(
                    PeerRow(
                        prefix, link.nickname ?: link.peerId.shortHex(),
                        stringResource(if (link.outgoing) R.string.demo_link_out else R.string.demo_link_in),
                        seen?.rssi, link.flags,
                    )
                )
            }
            for (seen in mesh.seen) {
                val prefix = seen.prefix ?: continue
                if (none { it.prefix == prefix } && mesh.links.none { it.prefix == prefix }) {
                    add(PeerRow(prefix, prefix.toHex(), null, seen.rssi, seen.flags))
                }
            }
            for (prefix in settings.ignoredPrefixes) {
                if (none { it.prefix == prefix }) add(PeerRow(prefix, prefix.toHex(), null, null, 0))
            }
        }

        ScreenScaffold(title = stringResource(R.string.demo_title), onBack = { vm.back() }) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(stringResource(R.string.demo_radio)) {
                    Text(
                        stringResource(
                            R.string.demo_radio_state,
                            yesNo(mesh.radio.bluetoothOn), yesNo(mesh.radio.scanning), yesNo(mesh.radio.advertising), yesNo(mesh.radio.canAdvertise),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.demo_radio_caps,
                            yesNo(mesh.radio.codedPhy), yesNo(mesh.radio.extendedAdvertising), mesh.radio.maxAdvertisingDataLength,
                            yesNo(mesh.radio.multipleAdvertisement), yesNo(mesh.radio.longRangeActive),
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.demo_node, c.identity.nodeId.toHex(), mesh.sent, mesh.received, mesh.relayed, mesh.dropped),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Card(stringResource(R.string.demo_peers, peers.size)) {
                    if (peers.isEmpty()) Text(stringResource(R.string.demo_no_peers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (peer in peers) {
                        val ignored = peer.prefix in settings.ignoredPrefixes
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).clip(CircleShape).background(
                                    when {
                                        ignored -> colors.danger
                                        peer.linked != null -> colors.ok
                                        else -> colors.textSecondary
                                    }
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(
                                        peer.prefix.toHex(),
                                        peer.linked ?: stringResource(R.string.demo_visible),
                                        peer.rssi?.let { "$it dBm" },
                                        flagLetters(peer.flags),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(stringResource(R.string.demo_ignore), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = ignored, onCheckedChange = { vm.toggleIgnore(peer.prefix) })
                        }
                    }
                }

                Card(stringResource(R.string.demo_actions)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pinger.ping() }) { Text(stringResource(R.string.demo_send_test)) }
                        FilledTonalButton(onClick = {
                            testIncident(c, vm.currentZoneId().ifEmpty { "main-stage" }, position?.lat, position?.lon)
                        }) { Text(stringResource(R.string.demo_test_incident)) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val failed = stringResource(R.string.demo_chain_refused)
                        RoleButton(stringResource(R.string.demo_role_participant), settings.role == Role.PARTICIPANT) { c.leaveStaff() }
                        RoleButton(stringResource(R.string.demo_role_staff), settings.role == Role.STAFF) {
                            if (!becomeStaff(context, c, "Medical 1", vm.currentZoneId(), lead = true)) notice = failed
                        }
                        RoleButton(stringResource(R.string.demo_role_anchor), settings.role == Role.ANCHOR) {
                            if (becomeAnchor(context, c, vm.currentZoneId().ifEmpty { "main-stage" })) vm.stack.clear() else notice = failed
                        }
                        RoleButton(stringResource(R.string.demo_role_control), settings.role == Role.CONTROL) {
                            if (becomeControl(context, c)) vm.stack.clear() else notice = failed
                        }
                    }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { confirmWipe = true }) { Text(stringResource(R.string.demo_wipe)) }
                }

                Card(stringResource(R.string.demo_kit)) {
                    Text(
                        stringResource(R.string.demo_kit_text),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val refused = stringResource(R.string.demo_chain_refused)
                        val inUse = stringResource(R.string.demo_pack_active)
                        val packRefused = stringResource(R.string.demo_pack_refused)
                        Button(onClick = { kitNotice = if (scenario(context, c)) null else refused }) {
                            Text(stringResource(R.string.demo_scenario))
                        }
                        OutlinedButton(onClick = {
                            reset(c)
                            kitNotice = null
                        }) { Text(stringResource(R.string.demo_scenario_reset)) }
                        FilledTonalButton(onClick = {
                            loadPack(context, c).fold(
                                onSuccess = { hash -> if (hash == null) kitNotice = inUse else c.switchEvent(hash) },
                                onFailure = { kitNotice = packRefused.format(it.message) },
                            )
                        }) { Text(stringResource(R.string.demo_pack)) }
                    }
                    kitNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }

                Card(stringResource(R.string.demo_measure)) {
                    val current = recording
                    Text(
                        if (current == null) stringResource(R.string.demo_measure_idle)
                        else stringResource(
                            if (current.mode == FieldRecorder.Mode.FIELD) R.string.demo_measure_field else R.string.demo_measure_census,
                            current.file.name, current.rows,
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val failed = stringResource(R.string.demo_measure_failed)
                        val none = stringResource(R.string.demo_measure_none)
                        fun start(mode: FieldRecorder.Mode) {
                            measureNotice = if (runCatching { recorder.start(mode) }.isSuccess) null else failed
                        }
                        if (current == null) {
                            Button(onClick = { start(FieldRecorder.Mode.FIELD) }) { Text(stringResource(R.string.demo_measure_start)) }
                            FilledTonalButton(onClick = { start(FieldRecorder.Mode.CENSUS) }) { Text(stringResource(R.string.demo_measure_census_start)) }
                            OutlinedButton(onClick = { measureNotice = if (recorder.share(context)) null else none }) {
                                Text(stringResource(R.string.demo_log_export))
                            }
                        } else {
                            Button(onClick = { recorder.stop() }) { Text(stringResource(R.string.demo_measure_stop)) }
                        }
                    }
                    measureNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }

                Card(stringResource(R.string.demo_tests)) {
                    if (tests.isEmpty()) Text(stringResource(R.string.demo_tests_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Mono(tests.takeLast(12).joinToString("\n"))
                }

                Card(stringResource(R.string.demo_log, log.size)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.log.exportText())
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Text(stringResource(R.string.demo_log_export)) }
                        OutlinedButton(onClick = { c.log.clear() }) { Text(stringResource(R.string.clear)) }
                    }
                    Mono(log.takeLast(60).reversed().joinToString("\n") { it.format() })
                }
            }
        }

        if (confirmWipe) {
            ConfirmDialog(stringResource(R.string.demo_wipe), stringResource(R.string.demo_wipe_text), { confirmWipe = false }) {
                c.wipeAllData()
            }
        }
    }

    @Composable
    private fun yesNo(value: Boolean) = stringResource(if (value) R.string.demo_yes else R.string.demo_no)

    @Composable
    private fun RoleButton(label: String, active: Boolean, onClick: () -> Unit) {
        if (active) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun Mono(text: String) {
        if (text.isEmpty()) return
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).horizontalScroll(rememberScrollState()),
        )
    }
}
