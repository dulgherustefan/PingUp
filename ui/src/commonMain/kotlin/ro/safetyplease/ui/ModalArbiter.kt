package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The dialogs that open by themselves over every screen, highest first. Kinds of the same [rank] follow their order
 * here: "Ești OK?" counts down, so it goes before the evacuation's screen.
 */
enum class ModalKind(val rank: Int) {
    MAN_DOWN(1),
    EVACUATION(1),
    RESPONDER(2),
    NOTICE(3),
    FRIEND(4),
    MEETUP(5),
}

/** One source's dialog, [key] unique to what it asks, until [untilMs] (wall time); [title] names it on a banner. */
data class ModalAsk(val kind: ModalKind, val key: String, val untilMs: Long = Long.MAX_VALUE, val title: String = "")

enum class ModalShow { DIALOG, BANNER, WAIT }

/** The one dialog on screen and the alert reduced to a banner over a walk; each null when there is none. */
data class ModalPick(val dialog: ModalAsk?, val banner: ModalAsk?) {
    fun show(key: String): ModalShow = when (key) {
        dialog?.key -> ModalShow.DIALOG
        banner?.key -> ModalShow.BANNER
        else -> ModalShow.WAIT
    }
}

object ModalOrder {
    /**
     * Which of [asks] opens now. Asks the user [acted] on and expired ones are gone; the rest wait their turn by rank.
     * While a walk that a dialog of [walkFrom] started is on screen, nothing opens over the route but what takes the
     * whole screen (rank 1): an alert above [walkFrom] becomes a banner, unless the user [reopened] it, and the rest
     * wait for the walk to end. While [evacuating], friends' dialogs wait even when the evacuation is minimised.
     */
    fun pick(
        asks: Collection<ModalAsk>,
        acted: Set<String>,
        nowMs: Long,
        walkFrom: ModalKind? = null,
        reopened: String? = null,
        evacuating: Boolean = false,
    ): ModalPick {
        val live = asks
            .filter { it.key !in acted && it.untilMs > nowMs && !(evacuating && it.kind.rank >= ModalKind.FRIEND.rank) }
            .sortedWith(compareBy({ it.kind.rank }, { it.kind.ordinal }))
        val top = live.firstOrNull() ?: return ModalPick(null, null)
        if (walkFrom == null || top.kind.rank == 1) return ModalPick(top, null)
        val above = live.firstOrNull { it.kind.rank < walkFrom.rank } ?: return ModalPick(null, null)
        return if (above.key == reopened) ModalPick(above, null) else ModalPick(null, above)
    }
}

/**
 * At most one of the dialogs that open by themselves is on screen, by [ModalKind]; the others wait in their sources and
 * open when it closes. A link to a friend's alert, tapped in a notification, waits the same way behind anything above
 * friends. [walkOnScreen] tells whether the route screen is on top.
 */
class ModalArbiter(private val clock: () -> Long, private val walkOnScreen: () -> Boolean) {
    private val asks = mutableStateMapOf<ModalKind, ModalAsk>()
    private var acted by mutableStateOf(emptySet<String>())
    private var walkFrom by mutableStateOf<ModalKind?>(null)
    private var reopened by mutableStateOf<String?>(null)
    private var held: String? = null

    /** An evacuation concerns this phone, on its whole screen or minimised. */
    var evacuating by mutableStateOf(false)

    val pick: ModalPick
        get() = ModalOrder.pick(asks.values, acted, clock(), walkFrom?.takeIf { walkOnScreen() }, reopened, evacuating)

    /** The source of [kind] asks [ask] now, or nothing (null). */
    fun offer(kind: ModalKind, ask: ModalAsk?) {
        if (ask == null) {
            if (kind in asks) asks.remove(kind)
            prune()
        } else if (asks[kind] != ask) {
            asks[kind] = ask
            prune()
        }
    }

    /** The user answered [key]; it doesn't open again, and when the answer opened a walk ([walks]), it is not covered. */
    fun acted(key: String, walks: Boolean = false) {
        acted = acted + key
        if (key == reopened) reopened = null
        if (walks) asks.values.firstOrNull { it.key == key }?.let { walkFrom = it.kind }
    }

    /** The banner over a walk opens as its dialog again. */
    fun reopen(key: String) {
        reopened = key
    }

    /** The route screen left the top. */
    fun walkEnded() {
        walkFrom = null
        reopened = null
    }

    /** Whether friends' dialogs and links wait now. */
    val holdsFriends: Boolean get() = evacuating || (pick.dialog?.kind?.rank ?: Int.MAX_VALUE) < ModalKind.FRIEND.rank

    /** False holds [target], a link from a notification, until [holdsFriends] ends ([release]). */
    fun admits(target: String): Boolean {
        if (target !in FRIEND_LINKS || !holdsFriends) return true
        held = target
        return false
    }

    /** The link held while friends waited, to open now, once. */
    fun release(): String? = held.also { held = null }

    // an answer is remembered while its source still offers it: some take a moment to drop what was answered
    private fun prune() {
        val offered = asks.values.mapTo(HashSet()) { it.key }
        if (acted.any { it !in offered }) acted = acted.filterTo(HashSet()) { it in offered }
    }

    private companion object {
        val FRIEND_LINKS = setOf(OpenTarget.CHECKIN_ALERTS)
    }
}

/** Offers [ask] for [kind] from a host and says how it may show; the offer goes when the host leaves. */
@Composable
fun ModalArbiter.ask(kind: ModalKind, ask: ModalAsk?): ModalShow {
    SideEffect { offer(kind, ask) }
    DisposableEffect(kind) { onDispose { offer(kind, null) } }
    return if (ask == null) ModalShow.WAIT else pick.show(ask.key)
}
