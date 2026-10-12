package ro.safetyplease.core.event

import kotlinx.serialization.Serializable
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.util.toHex

/** A pack this phone checked and kept, as the organiser signed it. */
@Serializable
data class StoredPack(
    val hash: String,
    val id: String,
    val version: Int,
    val kind: EventKind,
    val name: String,
    val validUntil: Long,
    val eventKey: String,
    val text: String,
)

enum class Added { NEW, UPDATED, KNOWN, OLDER, SHARED_KEY }

/** What loading a pack did: [added] is null when the pack was refused for [status]. */
class Loaded(val status: PackStatus, val added: Added?)

/**
 * The event packs on this phone. Several events can be kept; [active] names the one in use, empty for the venue and
 * keys the app ships. A pack replaces an older version of itself; an exercise never shares its event key with a real
 * event, so nothing signed for one is accepted in the other.
 */
@Serializable
data class EventShelf(val packs: List<StoredPack> = emptyList(), val active: String = "") {
    fun active(): StoredPack? = packs.firstOrNull { it.hash == active }

    fun byHash(hash: ByteArray): StoredPack? = hash.toHex().let { hex -> packs.firstOrNull { it.hash == hex } }

    /** The pack a gate code names, to switch to; null when this phone lacks it or already uses it, so its role stays. */
    fun gateTarget(hash: ByteArray): StoredPack? = byHash(hash)?.takeIf { it.hash != active }

    fun with(pack: EventPack, text: String): Pair<EventShelf, Added> {
        val hash = pack.hashHex
        if (packs.any { it.hash == hash }) return this to Added.KNOWN
        val eventKey = pack.event.key.toHex()
        if (packs.any { it.eventKey == eventKey && it.kind != pack.kind }) return this to Added.SHARED_KEY
        val previous = packs.firstOrNull { it.id == pack.id && it.kind == pack.kind }
        if (previous != null && previous.version >= pack.version) return this to Added.OLDER
        val stored = StoredPack(hash, pack.id, pack.version, pack.kind, pack.name, pack.validUntil, eventKey, text)
        val kept = packs.filter { it !== previous } + stored
        val nowActive = if (previous != null && active == previous.hash) hash else active
        return EventShelf(kept, nowActive) to if (previous == null) Added.NEW else Added.UPDATED
    }

    /** Checks [text] and keeps it if it is a valid pack. */
    fun load(text: String, crypto: Crypto, root: ByteArray?, now: Long, revoked: Collection<Long> = emptySet()): Pair<EventShelf, Loaded> {
        val check = EventPacks.check(text, crypto, root, now, revoked)
        val pack = check.pack ?: return this to Loaded(check.status, null)
        val (shelf, added) = with(pack, text)
        return shelf to Loaded(PackStatus.VALID, added)
    }

    /** [hash] empty goes back to what the app ships. */
    fun select(hash: String): EventShelf = if (hash.isEmpty() || packs.any { it.hash == hash }) copy(active = hash) else this

    fun without(hash: String): EventShelf = EventShelf(packs.filter { it.hash != hash }, if (active == hash) "" else active)

    /** The active pack checked again at [now]; null if there is none or it no longer holds, as after its event or a revocation. */
    fun activePack(crypto: Crypto, root: ByteArray?, now: Long, revoked: Collection<Long> = emptySet()): EventPack? =
        active()?.let { EventPacks.check(it.text, crypto, root, now, revoked).pack }
}

/** An exercise keeps its incidents, store-and-forward cache, console log and staff keys under names of their own. */
fun modeFileName(name: String, kind: EventKind?): String {
    if (kind != EventKind.EXERCISE) return name
    val dot = name.lastIndexOf('.')
    return if (dot < 0) name + EXERCISE_SUFFIX else name.substring(0, dot) + EXERCISE_SUFFIX + name.substring(dot)
}

private const val EXERCISE_SUFFIX = "_exercise"
