package ro.safetyplease.core.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex

/**
 * The revocations this phone knows (PROTOCOL.md, section 6.8): applied to [chain] and kept across restarts. Where
 * [resends], as on the control console, they go out again every [RESEND_MS], so phones that were out of range or
 * joined later get them too. [onRevoked] gets the certificate ids each new revocation adds.
 */
class Revocations(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val chain: KeyChain,
    private val store: JsonStore<List<String>>,
    private val resends: () -> Boolean,
    private val onRevoked: (ids: List<Long>) -> Unit = {},
) {
    fun start() {
        for (hex in store.value) chain.revoke(hex.hexToBytes())
        scope.launch {
            engine.events.collect { e ->
                if (e is MeshEvent.Received && e.packet.type == PacketType.ANNOUNCEMENT) accept(e.packet.payload)
            }
        }
        scope.launch {
            while (isActive) {
                delay(RESEND_MS)
                if (resends()) store.value.forEach { engine.broadcast(PacketType.ANNOUNCEMENT, it.hexToBytes()) }
            }
        }
    }

    /** A revocation from an SPR1 code: applied and sent out at once. The ids it adds; null if it isn't valid here. */
    fun add(payload: ByteArray): List<Long>? {
        val fresh = accept(payload) ?: return null
        scope.launch { engine.broadcast(PacketType.ANNOUNCEMENT, payload) }
        return fresh
    }

    private fun accept(payload: ByteArray): List<Long>? {
        val fresh = chain.revoke(payload) ?: return null
        val hex = payload.toHex()
        if (hex !in store.value) store.update { (it + hex).takeLast(MAX_KEPT) }
        if (fresh.isNotEmpty()) onRevoked(fresh)
        return fresh
    }

    companion object {
        const val RESEND_MS = 10 * 60_000L

        /** Each names up to 32 certificates; an event revokes far fewer. */
        const val MAX_KEPT = 16
    }
}
