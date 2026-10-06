package ro.safetyplease.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.safetyplease.app.App
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.MainActivity
import ro.safetyplease.app.core.nodePrefix
import ro.safetyplease.app.crypto.QrCodes
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Role
import ro.safetyplease.app.data.Settings
import ro.safetyplease.app.incidents.ReportDraft
import ro.safetyplease.app.location.GeoFix
import ro.safetyplease.app.mesh.MeshState
import ro.safetyplease.app.mesh.RadioStatus
import ro.safetyplease.app.venue.GeoPoint
import ro.safetyplease.app.venue.Venue
import ro.safetyplease.app.venue.Zone

enum class Tab { MESSAGES, REPORT, MAP, INCIDENTS }

sealed interface Dest {
    data class Conversation(val id: String) : Dest
    data class Profile(val conversation: String) : Dest
    data object AddFriend : Dest
    data object NewGroup : Dest

    /** Mesaj nou: grup nou, prieten nou sau unul dintre prieteni. */
    data object NewChat : Dest
    data class ReportSent(val incidentId: String) : Dest
    data object MyReports : Dest
    data class Incident(val id: String) : Dest

    /** Setarile, deschise din bula ta din bara de sus. */
    data object Me : Dest
    data object Demo : Dest
    data class Pin(val lat: Double?, val lon: Double?, val zone: String, val label: String) : Dest

    /**
     * Harta evenimentului: zona ta, zonele, punctul de intalnire si, pentru staff, incidentele deschise.
     * Cu [sendTo], zona aleasa pleaca pe loc in acea conversatie.
     */
    data class Map(val meeting: Boolean = false, val sendTo: String? = null) : Dest
}

sealed interface ScanOutcome {
    data class FriendAdded(val name: String) : ScanOutcome
    data object OwnCode : ScanOutcome
    data object StaffOn : ScanOutcome
    data object AnchorOn : ScanOutcome
    data object WrongEvent : ScanOutcome
    data object Unknown : ScanOutcome
}

/** O atingere dubla in timpul tranzitiei nu pune acelasi ecran de doua ori in stiva. */
fun MutableList<Dest>.push(dest: Dest) {
    if (lastOrNull() != dest) add(dest)
}

/** Pozitia valabila acum: cea simulata din modul demo are prioritate, altfel fixul GPS cat timp e recent. */
fun currentPoint(settings: Settings, fix: GeoFix?, nowMs: Long): GeoPoint? {
    val simLat = settings.simLat
    val simLon = settings.simLon
    return when {
        simLat != null && simLon != null -> GeoPoint(simLat, simLon)
        fix != null && fix.isFresh(nowMs) -> GeoPoint(fix.lat, fix.lon)
        else -> null
    }
}

/** Coordonatele pleaca doar daca nu contrazic zona aleasa: altfel pinul de pe harta staff-ului ar arata alt loc decat zona. */
fun reportPoint(zone: String, point: GeoPoint?, venue: Venue): GeoPoint? =
    point?.takeIf { zone.isEmpty() || venue.zoneAt(it.lat, it.lon)?.id == zone }

/** Ce arata interfata din starea mesh-ului; se schimba doar cand cineva apare, dispare sau se leaga, nu la fiecare pachet. */
data class Nearby(
    val linked: Set<Long> = emptySet(),
    val seenPrefixes: Set<Int> = emptySet(),
    val readyLinks: Int = 0,
    val radio: RadioStatus = RadioStatus(),
) {
    /** Legat direct de noi, nu doar vazut in scanare. */
    fun isLinked(nodeId: Long): Boolean = nodeId in linked

    fun isInRange(nodeId: Long): Boolean = isLinked(nodeId) || nodeId.nodePrefix() in seenPrefixes

    companion object {
        fun of(state: MeshState) = Nearby(
            state.links.mapTo(HashSet()) { it.peerId },
            state.seen.mapNotNullTo(HashSet()) { it.prefix },
            state.readyLinks,
            state.radio,
        )
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c: AppContainer = (app as App).container
    val venue = c.venue

    val settings = c.settings.state
    val mesh = c.engine.state
    val friends = c.friends.state
    val groups = c.groups.state
    val chat = c.chatStore.state
    val incidents = c.incidentStore.state

    val nearby: StateFlow<Nearby> = c.engine.state.map(Nearby::of).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Nearby.of(c.engine.state.value))

    // fara fixuri noi (sub un cort, cu ecranul stins) pozitia trebuie sa expire si fara niciun alt eveniment
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(15_000)
        }
    }

    /** Pentru afisare. Ce pleaca in retea citeste [pointNow] chiar in clipa trimiterii. */
    val position: StateFlow<GeoPoint?> = combine(c.settings.state, c.location.fix, ticker) { s, fix, _ ->
        currentPoint(s, fix, c.clock.wallMs())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var tab by mutableStateOf(Tab.MESSAGES)
    val stack = mutableStateListOf<Dest>()
    var manualZone by mutableStateOf("")

    /** Accesul refuzat definitiv: Android nu mai arata dialogul, deci butoanele duc in setarile aplicatiei. */
    var radioAccessBlocked by mutableStateOf(false)
    var locationBlocked by mutableStateOf(false)

    fun open(dest: Dest) = stack.push(dest)

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun home(tab: Tab) {
        stack.clear()
        this.tab = tab
    }

    fun openTarget(target: String) {
        stack.clear()
        when {
            target == MainActivity.OPEN_INCIDENTS -> tab = Tab.INCIDENTS
            target == MainActivity.OPEN_REPORT -> {
                tab = Tab.REPORT
                open(Dest.MyReports)
            }
            target.startsWith(MainActivity.OPEN_CHAT_PREFIX) -> {
                tab = Tab.MESSAGES
                open(Dest.Conversation(target.removePrefix(MainActivity.OPEN_CHAT_PREFIX)))
            }
        }
    }

    // --- zona ---

    fun pointNow(): GeoPoint? = currentPoint(c.settings.value, c.location.fix.value, c.clock.wallMs())

    fun autoZone(): Zone? = pointNow()?.let { venue.zoneAt(it.lat, it.lon) }

    fun currentZoneId(): String = autoZone()?.id ?: manualZone

    // --- setari ---

    fun finishOnboarding(nickname: String) = c.settings.update { it.copy(nickname = nickname.trim(), onboarded = true) }

    fun setNickname(nickname: String) = c.settings.update { it.copy(nickname = nickname.trim()) }

    fun leaveStaff() {
        c.leaveStaff()
        if (tab == Tab.INCIDENTS) tab = Tab.MESSAGES
    }

    fun dismissBatteryHint() = c.settings.update { it.copy(batteryHintDismissed = true) }

    fun setSimulatedLocation(point: GeoPoint?) = c.settings.update { it.copy(simLat = point?.lat, simLon = point?.lon) }

    fun toggleIgnore(prefix: Int) = c.settings.update { s ->
        s.copy(ignoredPrefixes = if (prefix in s.ignoredPrefixes) s.ignoredPrefixes - prefix else s.ignoredPrefixes + prefix)
    }

    // --- QR ---

    fun myQrText(): String = QrCodes.encodeFriend(c.chat.myCard())

    fun handleScan(text: String): ScanOutcome {
        QrCodes.decodeFriend(text)?.let { card ->
            return if (c.chat.addFriend(card)) ScanOutcome.FriendAdded(card.nickname) else ScanOutcome.OwnCode
        }
        QrCodes.decodeStaff(text)?.let { card ->
            if (!c.activateStaff(card)) return ScanOutcome.WrongEvent
            return if (c.settings.value.role == Role.ANCHOR) ScanOutcome.AnchorOn else ScanOutcome.StaffOn
        }
        return ScanOutcome.Unknown
    }

    // --- mesaje ---

    fun sendText(conversation: String, text: String) = c.chat.sendText(conversation, text)

    fun sendQuick(conversation: String, code: Int) = c.chat.sendQuick(conversation, code)

    /** False daca nu stim nici zona, nici pozitia: utilizatorul trebuie sa aleaga intai o zona pe harta. */
    fun sendMyZone(conversation: String): Boolean {
        val zone = currentZoneId()
        val point = reportPoint(zone, pointNow(), venue)
        if (zone.isEmpty() && point == null) return false
        c.chat.sendZone(conversation, zone, point?.lat, point?.lon)
        return true
    }

    fun resend(message: ChatMessage) = c.chat.resend(message)

    fun deleteMessage(message: ChatMessage) = c.chat.deleteMessage(message)

    fun enterConversation(conversation: String, friendId: Long?) {
        c.openConversation = conversation
        c.chat.markRead(conversation)
        c.notifier.cancelConversation(conversation)
        if (friendId != null) c.chat.ping(friendId)
    }

    fun leaveConversation(conversation: String) {
        if (c.openConversation == conversation) c.openConversation = null
        c.chat.markRead(conversation)
    }

    fun createGroup(name: String, members: List<Long>) = c.chat.createGroup(name, members)

    fun removeFriend(friend: Friend) = c.chat.removeFriend(friend.nodeId)

    fun leaveGroup(groupId: Long) = c.chat.leaveGroup(groupId)

    // --- incidente ---

    fun rateLimitWaitMs(): Long = c.incidents.rateLimitWaitMs()

    /** Id-ul raportului nou, sau null daca limita de rapoarte l-a oprit. */
    fun report(category: Int, severity: Int, zone: String, description: String, anonymous: Boolean): String? {
        val point = reportPoint(zone, pointNow(), venue)
        val nickname = if (anonymous) null else c.settings.value.nickname
        if (!c.incidents.report(ReportDraft(category, severity, zone, point?.lat, point?.lon, description, nickname))) return null
        return c.incidentStore.value.mine.lastOrNull()?.incidentId
    }

    fun acknowledge(incidentId: String) = c.incidents.acknowledge(incidentId)

    fun resolve(incidentId: String) = c.incidents.resolve(incidentId)

    fun cancelReport(incidentId: String) = c.incidents.cancel(incidentId)

    fun deleteReport(incidentId: String) = c.incidents.delete(incidentId)

    fun dismissIncident(incidentId: String) = c.incidents.dismiss(incidentId)

    fun retryRadio() {
        c.meshScope.launch { c.radio.retry() }
    }
}
