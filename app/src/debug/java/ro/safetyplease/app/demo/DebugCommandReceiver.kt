package ro.safetyplease.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
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

/**
 * Comenzi de test prin adb, doar in build-ul debug. Raspunsul apare in logcat, tag MeshDebug.
 *
 *   adb shell am broadcast -n ro.safetyplease.app/.demo.DebugCommandReceiver --es cmd status
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as App).container
        val cmd = intent.getStringExtra("cmd") ?: return
        val result = runCatching { run(context, c, cmd, intent) }.getOrElse { "eroare: ${it.message}" }
        Log.i(TestPinger.TAG, "[$cmd] $result")
    }

    private fun run(context: Context, c: AppContainer, cmd: String, intent: Intent): String {
        fun arg(name: String) = intent.getStringExtra(name) ?: error("lipseste --es $name")
        return when (cmd) {
            "onboard" -> {
                c.settings.update { it.copy(nickname = arg("nick"), onboarded = true) }
                c.settings.flush()
                "nickname ${arg("nick")}"
            }
            "status" -> status(c)
            "myqr" -> QrCodes.encodeFriend(c.chat.myCard())
            "friend" -> {
                val card = QrCodes.decodeFriend(arg("qr")) ?: error("QR invalid")
                "adaugat=${c.chat.addFriend(card)} ${card.nickname} ${card.nodeId.toHex()}"
            }
            "ignore" -> {
                val prefixes = arg("prefix").split(',').filter { it.isNotBlank() }.map { it.trim().toLong(16).toInt() }
                c.settings.update { it.copy(ignoredPrefixes = prefixes) }
                "ignora ${prefixes.joinToString { it.toHex() }}"
            }
            "unignore" -> {
                c.settings.update { it.copy(ignoredPrefixes = emptyList()) }
                "lista de ignorati e goala"
            }
            "test" -> {
                Demo.pinger(c).ping()
                "trimis"
            }
            "incident" -> {
                val zone = intent.getStringExtra("zone") ?: "main-stage"
                val center = c.venue.zone(zone)?.center
                Demo.testIncident(c, zone, center?.lat, center?.lon)
                "raportat in $zone"
            }
            "staff" -> "staff=${Demo.becomeStaff(context, c, intent.getStringExtra("team") ?: "Echipa demo")}"
            "anchor" -> "ancora=${Demo.becomeAnchor(context, c, intent.getStringExtra("zone") ?: "bar")}"
            "participant" -> {
                c.leaveStaff()
                "participant"
            }
            "take", "resolve" -> {
                val open = c.incidentStore.value.staff.lastOrNull { it.status < AckStatus.RESOLVED } ?: error("niciun incident deschis")
                if (cmd == "take") c.incidents.acknowledge(open.incidentId) else c.incidents.resolve(open.incidentId)
                "$cmd ${open.incidentId.take(8)}"
            }
            "cancel" -> {
                val open = c.incidentStore.value.mine.lastOrNull { !it.cancelled && it.status < AckStatus.RESOLVED }
                    ?: error("niciun raport deschis")
                c.incidents.cancel(open.incidentId)
                "cancel ${open.incidentId.take(8)}"
            }
            "text" -> {
                c.chat.sendText(Conversations.friend(arg("to").hexToBytes().toLong()), arg("msg"))
                "pus in outbox"
            }
            "where" -> {
                c.chat.sendQuick(Conversations.friend(arg("to").hexToBytes().toLong()), QuickCode.WHERE_ARE_YOU)
                "intrebat"
            }
            "zone" -> {
                val zone = arg("zone")
                val center = c.venue.zone(zone)?.center
                c.chat.sendZone(Conversations.friend(arg("to").hexToBytes().toLong()), zone, center?.lat, center?.lon)
                "zona trimisa"
            }
            "simulate" -> {
                val point = c.venue.zone(arg("zone"))?.center ?: error("zona necunoscuta")
                c.settings.update { it.copy(simLat = point.lat, simLon = point.lon) }
                "locatie simulata ${GeoPoint(point.lat, point.lon)}"
            }
            else -> "comanda necunoscuta"
        }
    }

    private fun status(c: AppContainer): String {
        val mesh = c.engine.state.value
        val settings = c.settings.value
        val incidents = c.incidentStore.value
        val chat = c.chatStore.value
        return buildString {
            append("nod=${c.identity.nodeId.toHex()} prefix=${c.identity.nodeId.nodePrefix().toHex()} nick=${settings.nickname} rol=${settings.role}")
            append(" | radio bt=${mesh.radio.bluetoothOn} scan=${mesh.radio.scanning} adv=${mesh.radio.advertising} periph=${mesh.radio.canAdvertise}")
            append(" | legaturi=${mesh.links.joinToString { "${it.peerId.shortHex()}${if (it.outgoing) ">" else "<"}${if (it.muted) "(mut)" else ""}" }}")
            append(" | vazuti=${mesh.seen.joinToString { "${it.prefix?.toHex()}@${it.rssi}" }}")
            append(" | tx=${mesh.sent} rx=${mesh.received} relay=${mesh.relayed} drop=${mesh.dropped} cache=${mesh.cachedReports}")
            append(" | rapoarte=${incidents.mine.joinToString { "${it.incidentId.take(6)}:${it.status}:${it.teamName}" }}")
            append(" | staff=${incidents.staff.joinToString { "${it.incidentId.take(6)}:${it.status}:h${it.hops}:${it.zone}" }}")
            append(" | prieteni=${c.friends.value.joinToString { "${it.nickname}:${it.nodeId.toHex()}:h${it.lastHops}" }}")
            append(" | mesaje=${chat.messages.takeLast(6).joinToString { "${if (it.fromMe) ">" else "<"}${it.kind}:${it.text}${it.zone}:${it.status}:h${it.hops}" }}")
            append(" | outbox=${chat.outbox.size}")
        }
    }
}
