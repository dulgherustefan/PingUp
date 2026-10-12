package ro.safetyplease.core.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import java.io.File

/** Every certificate id this phone saw revoked, and the latest revocations themselves, to send again. */
@Serializable
data class RevokedList(val ids: List<Long> = emptyList(), val payloads: List<String> = emptyList())

/**
 * The revocations this phone knows (PROTOCOL.md, section 6.8): applied to [chain] and kept across restarts. Where
 * [resends], as on the control console, the latest go out again every [RESEND_MS], so phones that were out of range or
 * joined later get them too. [onRevoked] gets the certificate ids each new revocation adds.
 */
class Revocations(
    private val scope: CoroutineScope,
    private val engine: MeshEngine,
    private val chain: KeyChain,
    private val store: JsonStore<RevokedList>,
    private val resends: () -> Boolean,
    private val onRevoked: (ids: List<Long>) -> Unit = {},
) {
    fun start() {
        chain.revokeIds(store.value.ids)
        // a list saved before the ids were kept has only payloads; whatever they name that the chain still accepts
        val again = store.value.payloads.flatMap { chain.revoke(it.hexToBytes()).orEmpty() }
        if (again.isNotEmpty()) store.update { it.copy(ids = (it.ids + again).distinct()) }
        scope.launch {
            engine.events.collect { e ->
                if (e is MeshEvent.Received && e.packet.type == PacketType.ANNOUNCEMENT) accept(e.packet.payload)
            }
        }
        scope.launch {
            while (isActive) {
                delay(RESEND_MS)
                if (resends()) store.value.payloads.forEach { engine.broadcast(PacketType.ANNOUNCEMENT, it.hexToBytes()) }
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
        if (fresh.isNotEmpty() || hex !in store.value.payloads) {
            store.update { kept ->
                val payloads = if (hex in kept.payloads) kept.payloads else (kept.payloads + hex).takeLast(MAX_RESENT)
                kept.copy(ids = (kept.ids + fresh).distinct(), payloads = payloads)
            }
        }
        if (fresh.isNotEmpty()) onRevoked(fresh)
        return fresh
    }

    companion object {
        const val RESEND_MS = 10 * 60_000L

        /** Each names up to 32 certificates; an event revokes far fewer, and every id stays revoked anyway. */
        const val MAX_RESENT = 16

        private const val SCHEMA_VERSION = 2

        /** Version 1 held only the payloads, as a bare list. */
        fun store(file: File, scope: CoroutineScope): JsonStore<RevokedList> =
            JsonStore(file, RevokedList.serializer(), RevokedList(), scope, SCHEMA_VERSION) { _, data ->
                buildJsonObject { put("payloads", data) }
            }
    }
}
