package ro.safetyplease.app.demo

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.core.shortHex
import ro.safetyplease.app.mesh.MeshEvent
import ro.safetyplease.app.protocol.Packet
import ro.safetyplease.app.protocol.PacketType
import ro.safetyplease.app.protocol.WireReader
import ro.safetyplease.app.protocol.WireWriter
import ro.safetyplease.app.protocol.parseOrNull
import ro.safetyplease.app.ui.Labels

/**
 * Pachetele TEST: un ping difuzat la care fiecare nod raspunde cu un ecou adresat.
 * Ecoul aduce inapoi numarul de hop-uri al drumului dus, iar timpul dus-intors se masoara pe un singur ceas.
 */
class TestPinger(private val c: AppContainer) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var seq = 0

    fun start() {
        c.meshScope.launch {
            c.engine.events.collect { event ->
                if (event is MeshEvent.Received && event.packet.type == PacketType.TEST) onTest(event.packet)
            }
        }
    }

    fun ping() {
        c.meshScope.launch {
            val number = ++seq
            val payload = WireWriter().u8(PING).u32(number.toLong()).i64(c.clock.wallMs()).u8(0).toByteArray()
            c.engine.broadcast(PacketType.TEST, payload)
            add("TEST #$number trimis catre ${c.engine.state.value.readyLinks} legaturi")
        }
    }

    private fun onTest(packet: Packet) {
        parseOrNull {
            val r = WireReader(packet.payload)
            val kind = r.u8()
            val number = r.u32()
            val originMs = r.i64()
            val hopsThere = r.u8()
            if (kind == PING) {
                add("TEST #$number de la ${packet.sender.shortHex()}: ${packet.hops} hop-uri")
                val echo = WireWriter().u8(ECHO).u32(number).i64(originMs).u8(packet.hops).toByteArray()
                c.engine.unicast(PacketType.TEST, packet.sender, echo, encrypted = false)
            } else {
                val rtt = c.clock.wallMs() - originMs
                add("ecou #$number de la ${packet.sender.shortHex()}: dus $hopsThere hop-uri, intors ${packet.hops}, $rtt ms dus-intors")
            }
        }
    }

    private fun add(line: String) {
        Log.i(TAG, line)
        _lines.value = (_lines.value + "${Labels.clock(c.clock.wallMs())} $line").takeLast(60)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    companion object {
        const val TAG = "MeshDebug"
        private const val PING = 0
        private const val ECHO = 1
    }
}
