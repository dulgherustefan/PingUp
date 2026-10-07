package ro.safetyplease.app.demo

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.app.ui.designsystem.ConfirmDialog
import ro.safetyplease.app.ui.designsystem.LocalAppColors
import ro.safetyplease.app.ui.designsystem.ScreenScaffold
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.shortHex
import ro.safetyplease.core.util.toHex

/** Demo mode, debug builds only: tools for presenting and for debugging the transport. */
object Demo {
    const val AVAILABLE = true

    @Volatile
    private var pinger: TestPinger? = null

    fun install(container: AppContainer) {
        if (pinger == null) pinger = TestPinger(container).also { it.start() }
    }

    fun pinger(container: AppContainer): TestPinger {
        install(container)
        return pinger!!
    }

    /** Demo staff keys bundled in debug builds; null if they don't match the app's public staff keys. */
    fun staffCard(context: Context, role: StaffRole, team: String, zone: String): StaffCard? = runCatching {
        val text = context.assets.open("demo_staff.json").bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(text).jsonObject
        StaffCard(
            json.getValue("boxSeed").jsonPrimitive.content.hexToBytes(),
            json.getValue("signSeed").jsonPrimitive.content.hexToBytes(),
            role, team, zone,
        )
    }.getOrNull()

    fun becomeStaff(context: Context, c: AppContainer, team: String): Boolean {
        val card = staffCard(context, StaffRole.STAFF, team, "") ?: return false
        return c.activateStaff(card)
    }

    fun becomeAnchor(context: Context, c: AppContainer, zone: String): Boolean {
        val card = staffCard(context, StaffRole.ANCHOR, "Ancora $zone".take(20), zone) ?: return false
        return c.activateStaff(card)
    }

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

    private class PeerRow(val prefix: Int, val name: String, val linked: String?, val rssi: Int?, val flags: Int, val muted: Boolean)

    @Composable
    fun Screen(vm: AppViewModel) {
        val c = vm.c
        val context = LocalContext.current
        val colors = LocalAppColors.current
        val mesh by vm.mesh.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val position by vm.position.collectAsStateWithLifecycle()
        val log by c.log.entries.collectAsStateWithLifecycle()
        val pinger = remember { pinger(c) }
        val tests by pinger.lines.collectAsStateWithLifecycle()
        var confirmWipe by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }

        val peers = buildList {
            for (link in mesh.links.filter { it.peerId != 0L }) {
                val prefix = link.peerId.nodePrefix()
                val seen = mesh.seen.firstOrNull { it.prefix == prefix }
                add(
                    PeerRow(
                        prefix, link.nickname ?: link.peerId.shortHex(),
                        stringResource(if (link.outgoing) R.string.demo_link_out else R.string.demo_link_in),
                        seen?.rssi, link.flags, link.muted,
                    )
                )
            }
            for (seen in mesh.seen) {
                val prefix = seen.prefix ?: continue
                if (none { it.prefix == prefix }) add(PeerRow(prefix, prefix.toHex(), null, seen.rssi, seen.flags, false))
            }
            for (prefix in settings.ignoredPrefixes) {
                if (none { it.prefix == prefix }) add(PeerRow(prefix, prefix.toHex(), null, null, 0, false))
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
                        val failed = stringResource(R.string.demo_keys_mismatch)
                        RoleButton(stringResource(R.string.demo_role_participant), settings.role == Role.PARTICIPANT) { c.leaveStaff() }
                        RoleButton(stringResource(R.string.demo_role_staff), settings.role == Role.STAFF) {
                            if (!becomeStaff(context, c, "Echipa ${settings.nickname}".take(20))) notice = failed
                        }
                        RoleButton(stringResource(R.string.demo_role_anchor), settings.role == Role.ANCHOR) {
                            if (becomeAnchor(context, c, vm.currentZoneId().ifEmpty { "main-stage" })) vm.stack.clear() else notice = failed
                        }
                    }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { confirmWipe = true }) { Text(stringResource(R.string.demo_wipe)) }
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
