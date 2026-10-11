package ro.safetyplease.app.demo

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.text.Labels
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.shortHex
import ro.safetyplease.core.util.toHex

/**
 * TEST packets: a broadcast ping that every node answers with an addressed echo.
 * The echo carries the outbound hop count, so round-trip time is measured on one clock.
 */
class TestPinger(private val c: AppContainer) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var seq = 0

    private val _rows = MutableSharedFlow<FieldRow>(extraBufferCapacity = 64)

    /** The same events as [lines], as field CSV rows; nothing is kept while nobody records. */
    val rows: SharedFlow<FieldRow> = _rows

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
            val links = c.engine.state.value.readyLinks
            add("TEST #$number sent to $links links")
            _rows.tryEmit(FieldRow("test_sent", seq = number.toLong(), name = "links", value = links.toString()))
        }
    }

    private fun onTest(packet: Packet) {
        parseOrNull {
            val r = WireReader(packet.payload)
            val kind = r.u8()
            val number = r.u32()
            val originMs = r.i64()
            val hopsThere = r.u8()
            val peer = packet.sender.nodePrefix().toHex()
            if (kind == PING) {
                add("TEST #$number from ${packet.sender.shortHex()}: ${packet.hops} hops")
                _rows.tryEmit(FieldRow("test_ping", peer = peer, seq = number, hops = packet.hops))
                val echo = WireWriter().u8(ECHO).u32(number).i64(originMs).u8(packet.hops).toByteArray()
                c.engine.unicast(PacketType.TEST, packet.sender, echo, encrypted = false)
            } else {
                val rtt = c.clock.wallMs() - originMs
                add("echo #$number from ${packet.sender.shortHex()}: $hopsThere hops out, ${packet.hops} back; $rtt ms round trip")
                _rows.tryEmit(FieldRow("test_echo", peer = peer, seq = number, hops = hopsThere, hopsBack = packet.hops, rttMs = rtt))
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
