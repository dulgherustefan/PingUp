package ro.safetyplease.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.ui.me.phoneMetrics
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.QuickCode
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.shortHex
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.toLong
import ro.safetyplease.core.venue.GeoPoint
import java.time.ZoneId

/**
 * Test commands over adb, debug builds only. Replies go to logcat, tag MeshDebug.
 *
 *   adb shell am broadcast -n org.pingup.app/ro.safetyplease.app.demo.DebugCommandReceiver --es cmd status
 *
 * Field measurements (see [FieldRecorder]): --es cmd measure --es action start|census|stop|export;
 * start also takes --ei pings 100 --ei every 1000 to send TEST packets while recording. The CSV files:
 *   adb pull /sdcard/Android/data/org.pingup.app/files/measure/
 * The pilot metrics, as the settings screen exports them, go to the same folder: --es cmd metrics.
 *
 * Demo kit (README, "Demo"): control, staff (--es team "Medical 1" --es zone main-stage --ez lead true) and
 * anchor (--es zone bar) take shifts of the demo key chain; pack loads the demo event pack and restarts the app;
 * scenario seeds the console's test data and reset takes it back; prieteni seeds friends sharing their position.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as App).container
        val cmd = intent.getStringExtra("cmd") ?: return
        val result = runCatching { run(context, c, cmd, intent) }.getOrElse { "error: ${it.message}" }
        Log.i(TestPinger.TAG, "[$cmd] $result")
    }

    private fun run(context: Context, c: AppContainer, cmd: String, intent: Intent): String {
        fun arg(name: String) = intent.getStringExtra(name) ?: error("missing --es $name")
        return when (cmd) {
            "onboard" -> {
                c.settings.update { it.copy(nickname = arg("nick"), onboarded = true) }
                c.settings.flush()
                "nickname ${arg("nick")}"
            }
            "status" -> status(c)
            "myqr" -> QrCodes.encodeFriend(c.chat.myCard())
            "friend" -> {
                val card = QrCodes.decodeFriend(arg("qr")) ?: error("invalid QR")
                "added=${c.chat.addFriend(card)} ${card.nickname} ${card.nodeId.toHex()}"
            }
            "ignore" -> {
                val prefixes = arg("prefix").split(',').filter { it.isNotBlank() }.map { it.trim().toLong(16).toInt() }
                c.settings.update { it.copy(ignoredPrefixes = prefixes) }
                "ignoring ${prefixes.joinToString { it.toHex() }}"
            }
            "unignore" -> {
                c.settings.update { it.copy(ignoredPrefixes = emptyList()) }
                "ignore list is empty"
            }
            "test" -> {
                Demo.pinger(c).ping()
                "sent"
            }
            "incident" -> {
                val zone = intent.getStringExtra("zone") ?: "main-stage"
                val center = c.venue.zone(zone)?.center
                Demo.testIncident(c, zone, center?.lat, center?.lon)
                "reported in $zone"
            }
            "staff" -> {
                val team = intent.getStringExtra("team") ?: "Medical 1"
                val zone = intent.getStringExtra("zone").orEmpty()
                "staff=${Demo.becomeStaff(context, c, team, zone, intent.getBooleanExtra("lead", false))} $team $zone"
            }
            "anchor" -> "anchor=${Demo.becomeAnchor(context, c, intent.getStringExtra("zone") ?: "bar")}"
            "control" -> "control=${Demo.becomeControl(context, c)}"
            "pack" -> {
                val hash = Demo.loadPack(context, c).getOrElse { return "pack refused: ${it.message}" } ?: return "pack already in use"
                // switching restarts the app, so the reply goes out first
                Log.i(TestPinger.TAG, "[pack] switching to ${hash.take(12)}, the app restarts")
                c.switchEvent(hash)
                "switched"
            }
            "scenario" -> if (Demo.scenario(context, c)) "scenario loaded" else "the demo chain refused the console's shift"
            "reset" -> {
                Demo.reset(c)
                "scenario removed"
            }
            "prieteni" -> DemoFriends.seed(c)
            "participant" -> {
                c.leaveStaff()
                "participant"
            }
            "take", "resolve" -> {
                val open = c.incidentStore.value.staff.lastOrNull { !AckStatus.isClosed(it.status) } ?: error("no open incident")
                if (cmd == "take") c.incidents.acknowledge(open.incidentId) else c.incidents.resolve(open.incidentId)
                "$cmd ${open.incidentId.take(8)}"
            }
            "cancel" -> {
                val open = c.incidentStore.value.mine.lastOrNull { !it.cancelled && !AckStatus.isClosed(it.status) }
                    ?: error("no open report")
                c.incidents.cancel(open.incidentId)
                "cancel ${open.incidentId.take(8)}"
            }
            "text" -> {
                c.chat.sendText(Conversations.friend(arg("to").hexToBytes().toLong()), arg("msg"))
                "queued in outbox"
            }
            "where" -> {
                c.chat.sendQuick(Conversations.friend(arg("to").hexToBytes().toLong()), QuickCode.WHERE_ARE_YOU)
                "asked"
            }
            "zone" -> {
                val zone = arg("zone")
                val center = c.venue.zone(zone)?.center
                c.chat.sendZone(Conversations.friend(arg("to").hexToBytes().toLong()), zone, center?.lat, center?.lon)
                "zone sent"
            }
            "simulate" -> {
                val point = c.venue.zone(arg("zone"))?.center ?: error("unknown zone")
                c.settings.update { it.copy(simLat = point.lat, simLon = point.lon) }
                "simulated location ${GeoPoint(point.lat, point.lon)}"
            }
            "measure" -> measure(Demo.recorder(context, c), arg("action"), intent)
            "metrics" -> {
                val dir = Demo.recorder(context, c).directory ?: error("external storage unavailable")
                val files = c.phoneMetrics().writeTo(dir.apply { mkdirs() }, c.clock.wallMs(), ZoneId.systemDefault())
                "${files.size} files: adb pull $dir/"
            }
            else -> "unknown command"
        }
    }

    private fun measure(recorder: FieldRecorder, action: String, intent: Intent): String = when (action) {
        "start" -> {
            val pings = intent.getIntExtra("pings", 0)
            val file = recorder.start(FieldRecorder.Mode.FIELD, pings, intent.getIntExtra("every", 1_000).toLong())
            "recording to ${file.name}" + if (pings > 0) ", $pings TEST packets" else ""
        }
        "census" -> "census to ${recorder.start(FieldRecorder.Mode.CENSUS).name}; keep the screen on"
        "stop" -> recorder.stop()?.let { "stopped, ${it.rows} rows in ${it.file.name}" } ?: "not recording"
        "export" -> "${recorder.files().size} files: adb pull ${recorder.directory}/"
        else -> "unknown action $action"
    }

    private fun status(c: AppContainer): String {
        val mesh = c.engine.state.value
        val settings = c.settings.value
        val incidents = c.incidentStore.value
        val chat = c.chatStore.value
        return buildString {
            append("node=${c.identity.nodeId.toHex()} prefix=${c.identity.nodeId.nodePrefix().toHex()} nick=${settings.nickname} role=${settings.role}")
            append(" | radio bt=${mesh.radio.bluetoothOn} scan=${mesh.radio.scanning} adv=${mesh.radio.advertising} periph=${mesh.radio.canAdvertise}")
            append(" | links=${mesh.links.joinToString { "${it.peerId.shortHex()}${if (it.outgoing) ">" else "<"}" }}")
            append(" | seen=${mesh.seen.joinToString { "${it.prefix?.toHex()}@${it.rssi}" }}")
            append(" | tx=${mesh.sent} rx=${mesh.received} relay=${mesh.relayed} drop=${mesh.dropped} cache=${mesh.cachedReports}")
            append(" | reports=${incidents.mine.joinToString { "${it.incidentId.take(6)}:${it.status}:${it.teamName}" }}")
            append(" | staff=${incidents.staff.joinToString { "${it.incidentId.take(6)}:${it.status}:h${it.hops}:${it.zone}" }}")
            append(" | friends=${c.friends.value.joinToString { "${it.nickname}:${it.nodeId.toHex()}:h${it.lastHops}" }}")
            append(" | messages=${chat.messages.takeLast(6).joinToString { "${if (it.fromMe) ">" else "<"}${it.kind}:${it.text}${it.zone}:${it.status}:h${it.hops}" }}")
            append(" | outbox=${chat.outbox.size}")
        }
    }
}
