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
import ro.safetyplease.app.platform.location.GeoFix
import ro.safetyplease.app.ui.common.Nearby
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone

enum class Tab { MESSAGES, REPORT, MAP, INCIDENTS }

sealed interface Dest {
    data class Conversation(val id: String) : Dest
    data class Profile(val conversation: String) : Dest
    data object AddFriend : Dest
    data object NewGroup : Dest

    data class ReportSent(val incidentId: String) : Dest
    data object MyReports : Dest
    data class Incident(val id: String) : Dest

    /** Your profile and settings. */
    data object Me : Dest
    data object Demo : Dest
    data class Pin(val lat: Double?, val lon: Double?, val zone: String, val label: String) : Dest

    /**
     * Event map: your zone, the zones, the meeting point and, for staff, open incidents.
     * With [sendTo], the chosen zone is sent straight to that conversation.
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

/** A double tap during a transition must not push the same screen twice. */
fun MutableList<Dest>.push(dest: Dest) {
    if (lastOrNull() != dest) add(dest)
}

/** Current position: the demo's simulated one wins, otherwise the GPS fix while it's fresh. */
fun currentPoint(settings: Settings, fix: GeoFix?, nowMs: Long): GeoPoint? {
    val simLat = settings.simLat
    val simLon = settings.simLon
    return when {
        simLat != null && simLon != null -> GeoPoint(simLat, simLon)
        fix != null && fix.isFresh(nowMs) -> GeoPoint(fix.lat, fix.lon)
        else -> null
    }
}

/** Coordinates are sent only if they agree with the chosen zone; otherwise the staff map pin would contradict it. */
fun reportPoint(zone: String, point: GeoPoint?, venue: Venue): GeoPoint? =
    point?.takeIf { zone.isEmpty() || venue.zoneAt(it.lat, it.lon)?.id == zone }

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

    // with no new fixes (in a tent, screen off) the position must still expire on its own
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(15_000)
        }
    }

    /** For display only. What goes out on the network reads [pointNow] at send time. */
    val position: StateFlow<GeoPoint?> = combine(c.settings.state, c.location.fix, ticker) { s, fix, _ ->
        currentPoint(s, fix, c.clock.wallMs())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var tab by mutableStateOf(Tab.MESSAGES)
    val stack = mutableStateListOf<Dest>()
    var manualZone by mutableStateOf("")

    /** Access denied for good: Android no longer shows the dialog, so the buttons open the app settings. */
    var radioAccessBlocked by mutableStateOf(false)
    var locationBlocked by mutableStateOf(false)

    /**
     * Test tools (demo mode, simulated location, technical ID) unlock after 7 taps on Version, like Android's
     * developer options, so a festival-goer can't become staff or wipe their data by accident. Lasts until the process dies.
     */
    var demoUnlocked by mutableStateOf(false)

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

    // --- zone ---

    fun pointNow(): GeoPoint? = currentPoint(c.settings.value, c.location.fix.value, c.clock.wallMs())

    fun autoZone(): Zone? = pointNow()?.let { venue.zoneAt(it.lat, it.lon) }

    fun currentZoneId(): String = autoZone()?.id ?: manualZone

    // --- settings ---

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

    // --- messages ---

    fun sendText(conversation: String, text: String) = c.chat.sendText(conversation, text)

    fun sendQuick(conversation: String, code: Int) = c.chat.sendQuick(conversation, code)

    /** False if we know neither zone nor position: the user has to pick a zone on the map first. */
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

    // --- incidents ---

    fun rateLimitWaitMs(): Long = c.incidents.rateLimitWaitMs()

    /** Id of the new report, or null if the rate limit blocked it. */
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
