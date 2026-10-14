package ro.safetyplease.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.ReservedNames
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.PositionRecord
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.Priority
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.incidents.StaffCode
import ro.safetyplease.core.incidents.StaffCodes
import ro.safetyplease.core.incidents.StatusFilter
import ro.safetyplease.core.incidents.UpdateDraft
import ro.safetyplease.core.position.AskLimit
import ro.safetyplease.core.position.ShareChoice
import ro.safetyplease.core.position.SharingNow
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.LocationFields
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.StaffFields
import ro.safetyplease.core.protocol.Triage
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.session.PingUpSession
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.ui.common.Nearby
import ro.safetyplease.ui.map.MapLayers
import ro.safetyplease.ui.map.MapOverlays
import ro.safetyplease.ui.map.NavTarget
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.ask_wait
import ro.safetyplease.ui.resources.card112_notif_title
import ro.safetyplease.ui.safety.SafetyScreen
import ro.safetyplease.ui.safety.card112Text

enum class Tab { MESSAGES, REPORT, MAP, INCIDENTS }

/** A report form filled in ahead: someone reported from their profile. */
data class ReportPreset(val category: Int, val description: String)

sealed interface Dest {
    data class Conversation(val id: String) : Dest
    data class Profile(val conversation: String) : Dest
    data object AddFriend : Dest
    data object NewGroup : Dest

    data class ReportSent(val incidentId: String) : Dest
    data object MyReports : Dest

    /** The discreet "mă simt în nesiguranță" report. */
    data object Discreet : Dest

    /** The one-tap codes of a field phone. */
    data object StaffCodes : Dest

    /** Where a staff phone tells the console how its points are. */
    data object Points : Dest

    /** The people this phone ignores. */
    data object Blocked : Dest
    data class Incident(val id: String) : Dest

    /** Your profile and settings. */
    data object Me : Dest
    data object Privacy : Dest
    data object Demo : Dest

    /** The event packs on this phone and the one in use. */
    data object Event : Dest
    data class Pin(val lat: Double?, val lon: Double?, val zone: String, val label: String) : Dest

    /**
     * Event map: your zone, the zones, the meeting point and, for staff, open incidents.
     * With [spot] (a place type, like "medical"), it opens on the way to the nearest such place.
     * With [sendTo], the chosen zone is sent straight to that conversation. With [friend], it opens on that friend's
     * shared position.
     */
    data class Map(val spot: String = "", val sendTo: String? = null, val friend: Long? = null) : Dest

    /** The walk to [target], step by step ([AppViewModel.navigate]). */
    data class Walk(val target: NavTarget) : Dest

    /** Offline first-aid sheets, then one of them by [id]. */
    data object FirstAid : Dest
    data class FirstAidSheet(val id: String) : Dest

    /** The offline information pages, then one of them by [id]. */
    data object Pages : Dest
    data class Page(val id: String) : Dest

    /** What sharing the location means, before the phone asks for it. */
    data object ShareIntro : Dest

    /** Friends and groups, each with its switch, all off until turned on. */
    data object ShareChoose : Dest

    /** The stages' schedule, "Planul meu" and its reminders. */
    data object Program : Dest

    /** "Cine mă vede acum": each share with its time left, stopped one by one or all at once. */
    data object Sharing : Dest

    /** The check-in on the way, "Nu sunt bine" and the 112 card. */
    data class Safety(val screen: SafetyScreen) : Dest
}

/** What one tap on the staff codes screen did. */
sealed interface StaffCodeResult {
    /** A staff report left; [incidentId] can still be cancelled. */
    data class Reported(val incidentId: String) : StaffCodeResult

    /** The team's incident moved to its next field state. */
    data object Moved : StaffCodeResult

    /** The team holds no incident this code could move. */
    data object NothingToMove : StaffCodeResult

    data object RateLimited : StaffCodeResult
}

sealed interface ScanOutcome {
    data class FriendAdded(val name: String) : ScanOutcome
    data object OwnCode : ScanOutcome
    data object StaffOn : ScanOutcome
    data object AnchorOn : ScanOutcome
    data object ControlOn : ScanOutcome
    data object WrongEvent : ScanOutcome

    /** A shift code the event revoked, or whose shift isn't now. */
    data object Revoked : ScanOutcome
    data object NotNow : ScanOutcome

    /** An SPR1 code this event's keys signed. */
    data object RevocationsApplied : ScanOutcome

    /** An SPE1 code for the pack already in use, or for one on the phone that no longer holds. */
    data object EventInUse : ScanOutcome
    data object EventOver : ScanOutcome
    data object Unknown : ScanOutcome
}

/** What a notification, a tile or a shortcut asks the app to open. */
object OpenTarget {
    const val HOME = "home"
    const val INCIDENTS = "incidents"

    /** One incident, by id: its page on a staff phone, picked in the queue on the console; from its alert. */
    const val INCIDENT_PREFIX = "incident:"

    /** The report categories, for a new report; the tile, the shortcut and the mesh notification open it. */
    const val REPORT = "report"

    /** The discreet report, straight to its confirmation; its own Quick Settings tile opens it. */
    const val DISCREET = "discreet"

    /** One of your reports, by id. */
    const val REPORT_PREFIX = "report:"
    const val CHAT_PREFIX = "chat:"

    /** "Cine mă vede acum", from the notification of a location share. */
    const val SHARING = "sharing"

    /** The check-in on the way, from its notification, and the alerts of friends who missed theirs. */
    const val CHECKIN = "checkin"
    const val CHECKIN_ALERTS = "checkin-alerts"

    /** The 112 card, from the notification posted with the call. */
    const val CARD_112 = "card112"
}

/** Where a link from a notification, the tile or a shortcut lands: a tab and maybe a screen on top; null for neither. */
fun landing(target: String): Pair<Tab, Dest?>? = when {
    target == OpenTarget.INCIDENTS -> Tab.INCIDENTS to null
    target.startsWith(OpenTarget.INCIDENT_PREFIX) -> Tab.INCIDENTS to Dest.Incident(target.removePrefix(OpenTarget.INCIDENT_PREFIX))
    target == OpenTarget.REPORT -> Tab.REPORT to null
    target == OpenTarget.DISCREET -> Tab.REPORT to Dest.Discreet
    target.startsWith(OpenTarget.REPORT_PREFIX) -> Tab.REPORT to Dest.ReportSent(target.removePrefix(OpenTarget.REPORT_PREFIX))
    target.startsWith(OpenTarget.CHAT_PREFIX) -> Tab.MESSAGES to Dest.Conversation(target.removePrefix(OpenTarget.CHAT_PREFIX))
    target == OpenTarget.SHARING -> Tab.MESSAGES to Dest.Sharing
    target == OpenTarget.CHECKIN -> Tab.REPORT to Dest.Safety(SafetyScreen.CHECKIN)
    target == OpenTarget.CHECKIN_ALERTS -> Tab.REPORT to Dest.Safety(SafetyScreen.FRIEND_ALERTS)
    target == OpenTarget.CARD_112 -> Tab.REPORT to Dest.Safety(SafetyScreen.CARD_112)
    else -> null
}

/** A double tap during a transition must not push the same screen twice. */
fun MutableList<Dest>.push(dest: Dest) {
    if (lastOrNull() != dest) add(dest)
}

/**
 * What a link does to the screens on the stack: a tab alone clears them, so the tab shows; a screen comes to the top,
 * back to its own copy when one is open, so what lies under it (a draft, a walk) is kept.
 */
fun MutableList<Dest>.land(dest: Dest?) {
    if (dest == null) return clear()
    val open = lastIndexOf(dest)
    if (open < 0) push(dest) else while (lastIndex > open) removeAt(lastIndex)
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

/** From this age on, a zone picked by hand says how old it is, so a stale one doesn't go into a report unnoticed. */
const val ZONE_AGE_SHOWN_MS = 5 * 60_000L

/** How long ago the zone was picked by hand, once that is worth saying; null if there's none or it's recent. */
fun pickedZoneAge(settings: Settings, nowMs: Long): Long? {
    if (settings.manualZone.isEmpty() || settings.manualZoneAt <= 0) return null
    return (nowMs - settings.manualZoneAt).takeIf { it >= ZONE_AGE_SHOWN_MS }
}

/** A friend gets coordinates only if they agree with the zone sent with them; otherwise their map pin would contradict it. */
fun chatPoint(zone: String, point: GeoPoint?, venue: Venue): GeoPoint? =
    point?.takeIf { zone.isEmpty() || venue.zoneAt(it.lat, it.lon)?.id == zone }

/** Where a report is written from: the position, how far off it may be in metres (0 when unknown), the nearest landmark. */
data class ReportSpot(val point: GeoPoint, val accuracyM: Int, val landmark: Poi?) {
    /** What the report carries besides the coordinates; null when that is nothing. */
    val fields: LocationFields?
        get() = LocationFields(accuracyM, landmark?.number ?: 0).takeIf { accuracyM > 0 || landmark != null }
}

/**
 * A report goes with the position whenever there is one, whatever zone was picked: a zone isn't a place a team can walk
 * to. A simulated position has no accuracy.
 */
fun reportSpot(settings: Settings, fix: GeoFix?, nowMs: Long, venue: Venue): ReportSpot? {
    val point = currentPoint(settings, fix, nowMs) ?: return null
    val simulated = settings.simLat != null && settings.simLon != null
    val accuracy = if (simulated || fix == null) 0 else LocationFields.accuracyM(fix.accuracyM.toDouble())
    return ReportSpot(point, accuracy, venue.nearestLandmark(point))
}

/**
 * Where a report says it happened: here and now, from [spot]; for one written later, nowhere, since the phone is no
 * longer where it happened and its pin would send a team to the wrong place. The zone picked still goes.
 */
fun spotFor(spot: ReportSpot?, extras: ReportExtras): ReportSpot? = spot.takeIf { extras.happenedMinAgo == 0 }

/** The report still needs help: neither closed nor cancelled by you. */
fun MyReport.isOpen() = !AckStatus.isClosed(status) && !cancelled

/** Your reports that no longer wait for help: resolved, cancelled or being cancelled. */
fun closedReports(mine: List<MyReport>): List<String> = mine.filterNot { it.isOpen() }.map { it.incidentId }

/** Incidents under the "Inchise" filter: resolved or cancelled, each on its own whatever came from the same zone. */
fun closedIncidents(staff: List<StaffIncident>): List<String> = staff.filter(StatusFilter.RESOLVED::matches).map { it.incidentId }

/** The screens' state and what they do, over the session; [phone] is what only the phone itself can do. */
class AppViewModel(val c: PingUpSession, val phone: PhoneUi) : ViewModel() {
    /** Read at each use: its names follow the app's language. */
    val venue: Venue get() = c.venue

    val settings = c.settings.state
    val mesh = c.engine.state
    val friends = c.friends.state
    val groups = c.groups.state
    val blocked = c.blocked.state
    val chat = c.chatStore.state
    val incidents = c.incidentStore.state

    /** What the features draw on the maps, each in its own layer. */
    val mapOverlays = MapOverlays()

    /** The map's service layers, search pick and site plan, and the place "Du-mă acolo" asks the route for. */
    val layers = MapLayers()

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
    val position: StateFlow<GeoPoint?> = combine(c.settings.state, c.locationFix, ticker) { s, fix, _ ->
        currentPoint(s, fix, c.clock.wallMs())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What a report sent now would say of where you are, for the report screen; [report] reads it again at send time. */
    val spot: StateFlow<ReportSpot?> = combine(c.settings.state, c.locationFix, ticker) { s, fix, _ ->
        reportSpot(s, fix, c.clock.wallMs(), venue)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), spotNow())

    init {
        // friends learn the zone as the app learns it; no GPS runs for this alone
        viewModelScope.launch {
            combine(c.settings.state, c.locationFix, ticker) { _, _, _ -> currentZoneId() }
                .distinctUntilChanged()
                .collect(c.chat::zoneChanged)
        }
    }

    var tab by mutableStateOf(Tab.MESSAGES)
    val stack = mutableStateListOf<Dest>()

    /** Access denied for good: Android no longer shows the dialog, so the buttons open the app settings. */
    var radioAccessBlocked by mutableStateOf(false)
    var locationBlocked by mutableStateOf(false)

    /** The report screen asked for location once since the app started; after that only its button asks. */
    var locationAskedForReport = false

    /**
     * Test tools (demo mode, simulated location, technical ID) unlock after 7 taps on Version, like Android's
     * developer options, so a festival-goer can't become staff or wipe their data by accident. Lasts until the process dies.
     */
    var demoUnlocked by mutableStateOf(false)

    fun open(dest: Dest) = stack.push(dest)

    /** The dialer was opened for 112: back in the app, the card is what the person needs. */
    private var dialed112 = false

    /**
     * Every "Sună la 112": the dialer with the number filled in, then a notification with what to say, the coordinates,
     * the Plus Code and the nearest landmark, to read from the notification shade during the call.
     */
    fun call112() {
        phone.dial112()
        dialed112 = true
        viewModelScope.launch { phone.location112(getString(Res.string.card112_notif_title), card112Text(this@AppViewModel)) }
    }

    /** The app came back to the front: after a 112 call it opens on the card. */
    fun resumed() {
        if (!dialed112) return
        dialed112 = false
        open(Dest.Safety(SafetyScreen.CARD_112))
    }

    /** "Du-mă acolo": the walking route to [target], on its own screen. */
    fun navigate(target: NavTarget) = open(Dest.Walk(target))

    /** The walk on screen goes on to [target] instead, as from the defibrillator to the patient. */
    fun navigateInstead(target: NavTarget) {
        if (stack.lastOrNull() is Dest.Walk) stack.removeAt(stack.lastIndex)
        navigate(target)
    }

    /**
     * A walk starts ([on]) or ends, on the route screen or out of an evacuation: fast fixes and, with the setting, the
     * steps while it leads. Calls nest.
     */
    fun walking(on: Boolean) {
        phone.urgentLocation(on)
        c.positioning.navigating(on)
    }

    /** One dialog that opens by itself at a time, by priority; friends' links wait behind what is above them. */
    val modals = ModalArbiter(c.clock::wallMs) { stack.lastOrNull() is Dest.Walk }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun home(tab: Tab) {
        stack.clear()
        this.tab = tab
    }

    /** What the report form starts from, once, when another screen sends the user to it. */
    var reportPreset by mutableStateOf<ReportPreset?>(null)

    /** The report form, open on harassment and [description], for someone who reached the user through the app. */
    fun reportHarassment(description: String) {
        reportPreset = ReportPreset(IncidentCategory.HARASSMENT, description)
        home(Tab.REPORT)
    }

    /** An incident a link asked the console to show; the console's screen picks it and sets this back to null. */
    var consoleFocus by mutableStateOf<String?>(null)

    /** A link from a notification, the tile or a shortcut; home leaves whatever is open as it is. */
    fun openTarget(target: String) {
        if (!modals.admits(target)) return
        val (tab, dest) = landing(target) ?: return
        // the console has its own queue: the incident is picked there
        if (dest is Dest.Incident && c.settings.value.role == Role.CONTROL) {
            stack.clear()
            consoleFocus = dest.id
            return
        }
        this.tab = tab
        stack.land(dest)
    }

    // --- zone ---

    fun pointNow(): GeoPoint? = currentPoint(c.settings.value, c.locationFix.value, c.clock.wallMs())

    private fun spotNow(): ReportSpot? = reportSpot(c.settings.value, c.locationFix.value, c.clock.wallMs(), venue)

    fun autoZone(): Zone? = pointNow()?.let { venue.zoneAt(it.lat, it.lon) }

    fun currentZoneId(): String = autoZone()?.id ?: c.settings.value.manualZone

    /** Saved, so the zone you picked stands in for GPS even after Android kills the app. */
    fun setManualZone(id: String) = c.settings.update { it.copy(manualZone = id, manualZoneAt = c.clock.wallMs()) }

    // --- settings ---

    fun finishOnboarding(nickname: String) = c.settings.update { it.copy(nickname = nickname.trim(), onboarded = true) }

    fun setNickname(nickname: String) = c.settings.update { it.copy(nickname = nickname.trim()) }

    fun setRoleLead(lead: Boolean) = c.settings.update { it.copy(roleLead = lead) }

    /** A name that passes for staff is only for a phone with a shift certificate the event accepts now. */
    fun nameAllowed(nickname: String): Boolean = c.verified || !ReservedNames.imitatesStaff(nickname)

    val verified: Boolean get() = c.verified

    fun leaveStaff() {
        c.leaveStaff()
        if (tab == Tab.INCIDENTS) tab = Tab.MESSAGES
    }

    fun dismissBatteryHint() = c.settings.update { it.copy(batteryHintDismissed = true) }

    fun dismissIdentityNotice() = c.settings.update { it.copy(identityReplaced = false) }

    /** Off stops the service right away; on only saves the choice, the caller starts the mesh. */
    fun setMeshEnabled(on: Boolean) {
        c.settings.update { it.copy(meshEnabled = on) }
        if (!on) phone.stopMesh()
    }

    fun setNetworkLog(on: Boolean) = c.settings.update { it.copy(networkLog = on) }

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
            return if (c.activateStaff(card)) roleOutcome() else ScanOutcome.WrongEvent
        }
        QrCodes.decodeShift(text)?.let { card ->
            return when (c.activateShift(card, text)) {
                CertStatus.VALID -> roleOutcome()
                CertStatus.REVOKED -> ScanOutcome.Revoked
                CertStatus.OUTSIDE_WINDOW -> ScanOutcome.NotNow
                CertStatus.UNKNOWN, CertStatus.FORGED -> ScanOutcome.WrongEvent
            }
        }
        QrCodes.decodeEventGate(text)?.let { hash ->
            // the gate code only picks a pack loaded before; using it restarts the app
            val shelf = c.events.value
            val pack = shelf.byHash(hash) ?: return ScanOutcome.Unknown
            if (!c.usable(pack)) return ScanOutcome.EventOver
            val target = shelf.gateTarget(hash) ?: return ScanOutcome.EventInUse
            c.switchEvent(target.hash)
            return ScanOutcome.Unknown
        }
        QrCodes.decodeRevocation(text)?.let { payload ->
            return if (c.applyRevocation(payload) != null) ScanOutcome.RevocationsApplied else ScanOutcome.WrongEvent
        }
        return ScanOutcome.Unknown
    }

    private fun roleOutcome(): ScanOutcome = when (c.settings.value.role) {
        Role.ANCHOR -> ScanOutcome.AnchorOn
        Role.CONTROL -> ScanOutcome.ControlOn
        else -> ScanOutcome.StaffOn
    }

    // --- messages ---

    fun sendText(conversation: String, text: String) = c.chat.sendText(conversation, text)

    fun sendQuick(conversation: String, code: Int) = c.chat.sendQuick(conversation, code)

    /** False if we know neither zone nor position: the user has to pick a zone on the map first. */
    fun sendMyZone(conversation: String): Boolean {
        val zone = currentZoneId()
        val point = chatPoint(zone, pointNow(), venue)
        if (zone.isEmpty() && point == null) return false
        c.chat.sendZone(conversation, zone, point?.lat, point?.lon)
        return true
    }

    fun resend(message: ChatMessage) = c.chat.resend(message)

    fun deleteMessage(message: ChatMessage) = c.chat.deleteMessage(message)

    /**
     * "Fă-te văzut", "Vin la tine" and a meeting point: runs [send] and says how it went, with [sent] when the request went
     * out (null for a meeting point, which shows itself) and "Așteaptă puțin" when the chat refused it. It outlives the
     * screen that asked, so a dialog can close at once.
     */
    fun askFriend(sent: StringResource?, send: suspend () -> Boolean) {
        viewModelScope.launch {
            val message = if (send()) sent else Res.string.ask_wait
            if (message != null) phone.toast(getString(message))
        }
    }

    fun enterConversation(conversation: String, friendId: Long?) {
        c.openConversation = conversation
        c.chat.markRead(conversation)
        c.dismissConversation(conversation)
        if (friendId != null) c.chat.ping(friendId)
    }

    fun leaveConversation(conversation: String) {
        if (c.openConversation == conversation) c.openConversation = null
        c.chat.markRead(conversation)
    }

    fun createGroup(name: String, members: List<Long>) = c.chat.createGroup(name, members)

    fun removeFriend(friend: Friend) = c.chat.removeFriend(friend.nodeId)

    fun leaveGroup(groupId: Long) = c.chat.leaveGroup(groupId)

    fun block(nodeId: Long) = c.chat.block(nodeId)

    fun unblock(nodeId: Long) = c.chat.unblock(nodeId)

    fun setShareZone(on: Boolean) = c.settings.update { it.copy(shareZone = on) }

    // --- location sharing ---

    val sharing: StateFlow<SharingNow?> = c.sharing
    val friendPositions: StateFlow<List<PositionRecord>> = c.chat.friendPositions
    val autoAnswer: StateFlow<Boolean> = c.positions.state.map { it.autoAnswer }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.positions.value.autoAnswer)

    /** When each friend was last asked "Unde ești?" here, for the button to wait out the chat's limit. */
    val askedAt = mutableStateMapOf<Long, Long>()

    /** The explanation comes first until it was accepted once; after that, straight to the list. */
    fun openShare() = open(if (c.settings.value.locationIntro) Dest.ShareChoose else Dest.ShareIntro)

    fun acceptShareIntro() = c.settings.update { it.copy(locationIntro = true) }

    /** From the visible list: the share, the GNSS it needs, and the location type of the foreground service. */
    fun startShare(conversation: String, choice: ShareChoice) {
        c.startShare(conversation, choice)
        phone.sharingLocation(true)
    }

    fun stopShare(conversation: String) = c.stopShare(conversation)

    fun stopAllShares() = c.stopAllShares()

    fun setAutoAnswer(on: Boolean) = c.chat.setAutoAnswer(on)

    fun setPingAutoAccept(on: Boolean) = c.settings.update { it.copy(pingAutoAccept = on) }

    fun setPingWhiteScreen(on: Boolean) = c.settings.update { it.copy(pingWhiteScreen = on) }

    fun setPingSound(on: Boolean) = c.settings.update { it.copy(pingSound = on) }

    /** "Unde ești?": a position if the friend shares or answers, else the zone; at most once a minute per friend. */
    fun requestPosition(friend: Friend) {
        val now = c.clock.wallMs()
        if (AskLimit.waitMs(askedAt[friend.nodeId], now) > 0) return
        askedAt[friend.nodeId] = now
        c.chat.requestPosition(friend.nodeId)
    }

    fun setNotices(on: Boolean) = c.settings.update { it.copy(notices = on) }

    // --- incidents ---

    fun rateLimitWaitMs(): Long = c.incidents.rateLimitWaitMs()

    fun rateLimitMax(): Int = c.incidents.rateLimitMax()

    /**
     * Id of the new report, or null if the rate limit blocked it. [staff] goes only from a staff phone; [recognition]
     * is how a team tells you apart.
     */
    fun report(
        category: Int,
        severity: Int,
        zone: String,
        description: String,
        anonymous: Boolean,
        triage: Triage? = null,
        staff: StaffFields? = null,
        recognition: String = "",
        extras: ReportExtras = ReportExtras.NONE,
    ): String? {
        val spot = spotFor(spotNow(), extras)
        val nickname = if (anonymous) null else c.settings.value.nickname
        val draft = ReportDraft(
            category, severity, zone, spot?.point?.lat, spot?.point?.lon, description, nickname, triage, staff, recognition, spot?.fields,
            // the options chosen on the form, and where a lost friend was last seen when the report looks for one
            extras.copy(lastSeen = c.regasire.extrasFor(category).lastSeen ?: extras.lastSeen),
        )
        if (!c.incidents.report(draft)) return null
        return c.incidentStore.value.mine.lastOrNull()?.incidentId?.also { c.regasire.reportSent(it, category) }
    }

    /** Milliseconds until the report can take another update; null once it can't take any. */
    fun updateWaitMs(incidentId: String): Long? = c.incidents.updateWaitMs(incidentId)

    /** Whether a move can say where to: a position or a zone the user picked. */
    fun knowsWhereIAm(): Boolean = spotNow() != null || currentZoneId().isNotEmpty()

    /** A move goes with where you are now, like a report; false if the report can't take an update yet. */
    fun updateReport(incidentId: String, kind: Int): Boolean {
        val moved = kind == UpdateKind.MOVED
        val spot = if (moved) spotNow() else null
        val zone = if (moved) currentZoneId().ifEmpty { null } else null
        return c.incidents.update(incidentId, UpdateDraft(kind, zone, spot?.point?.lat, spot?.point?.lon, spot?.fields))
    }

    /** The console's predefined answer to the reporter; [landmark] only for "go to landmark". */
    fun respond(incidentId: String, code: Int, landmark: Int = 0) = c.incidents.respond(incidentId, code, landmark)

    /**
     * One tap of the staff codes: the first three leave as staff reports, with the position attached like any
     * report; the last two move the incident this team already holds.
     */
    /** False when this phone can't sign or sent that point too recently. */
    fun sendPoint(point: Int, level: Int): Boolean = c.sendPoint(point, level)

    fun sendStaffCode(code: StaffCode, label: String): StaffCodeResult {
        val spec = StaffCodes.report(code)
        if (spec != null) {
            val id = report(
                spec.category, spec.severity, currentZoneId(), label, anonymous = false,
                staff = StaffFields(0, spec.dangerContinues),
            )
            return if (id != null) StaffCodeResult.Reported(id) else StaffCodeResult.RateLimited
        }
        val target = StaffCodes.target(code, c.incidentStore.value.staff, c.settings.value.teamName) ?: return StaffCodeResult.NothingToMove
        if (code == StaffCode.ON_SCENE) onScene(target.incidentId) else handOver(target.incidentId)
        return StaffCodeResult.Moved
    }

    /** "Mă simt în nesiguranță": a harassment report, anonymous, marked for safeguarding; no sound or vibration here. */
    fun reportDiscreet(text: String, extras: ReportExtras = ReportExtras.NONE): String? =
        report(
            IncidentCategory.HARASSMENT, Priority.severity(IncidentCategory.HARASSMENT, urgent = false), currentZoneId(), Discreet.describe(text),
            anonymous = true, extras = extras,
        )

    fun acknowledge(incidentId: String) = c.incidents.acknowledge(incidentId)

    fun onScene(incidentId: String) = c.incidents.onScene(incidentId)

    fun handOver(incidentId: String) = c.incidents.handOver(incidentId)

    fun resolve(incidentId: String) = c.incidents.resolve(incidentId)

    fun cancelReport(incidentId: String) = c.incidents.cancel(incidentId)

    fun deleteReport(incidentId: String) = c.incidents.delete(incidentId)

    fun deleteClosedReports() = closedReports(c.incidentStore.value.mine).forEach(c.incidents::delete)

    fun dismissIncident(incidentId: String) = c.incidents.dismiss(incidentId)

    fun dismissClosedIncidents() = closedIncidents(c.incidentStore.value.staff).forEach(c.incidents::dismiss)

    fun retryRadio() {
        c.meshScope.launch { c.radio.retry() }
    }
}
