package ro.safetyplease.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.FileSystem
import okio.SYSTEM
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeBoard
import ro.safetyplease.core.announce.NoticeAlarm
import ro.safetyplease.core.announce.NoticeReach
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.announce.Notices
import ro.safetyplease.core.announce.Whereabouts
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.chat.PositionFrame
import ro.safetyplease.core.chat.PositionPolicy
import ro.safetyplease.core.control.ConsoleLog
import ro.safetyplease.core.control.ConsoleRecorder
import ro.safetyplease.core.control.ControlMessages
import ro.safetyplease.core.control.HeardPeer
import ro.safetyplease.core.control.MapEditor
import ro.safetyplease.core.control.PeerBook
import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.control.ReporterRecords
import ro.safetyplease.core.control.SentPoint
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.CryptoRandom
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.Revocations
import ro.safetyplease.core.crypto.ShiftCard
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.data.BlockedPerson
import ro.safetyplease.core.data.ChatStore
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.PositionStore
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.event.EventKind
import ro.safetyplease.core.event.EventPack
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.event.EventShelf
import ro.safetyplease.core.event.Loaded
import ro.safetyplease.core.event.MyProgram
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.core.event.ProgramReminders
import ro.safetyplease.core.event.StoredPack
import ro.safetyplease.core.event.modeFileName
import ro.safetyplease.core.incidents.AlertPlanner
import ro.safetyplease.core.incidents.EscalationReminder
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.incidents.nextEscalation
import ro.safetyplease.core.location.Positioning
import ro.safetyplease.core.map.MapDownloads
import ro.safetyplease.core.map.PackRegistry
import ro.safetyplease.core.mesh.AnchorHeartbeat
import ro.safetyplease.core.mesh.BatteryWatch
import ro.safetyplease.core.mesh.CachedIncidents
import ro.safetyplease.core.mesh.DeviceReadings
import ro.safetyplease.core.mesh.MeshConfig
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.MeshEvent
import ro.safetyplease.core.mesh.PacketLog
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.metrics.MeshMetrics
import ro.safetyplease.core.metrics.MetricsLog
import ro.safetyplease.core.metrics.NetworkLog
import ro.safetyplease.core.position.Regasire
import ro.safetyplease.core.position.Search
import ro.safetyplease.core.position.ShareChoice
import ro.safetyplease.core.position.SharePlan
import ro.safetyplease.core.position.ShareTime
import ro.safetyplease.core.position.SharingNow
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.PointStatus
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.safety.CheckinData
import ro.safetyplease.core.safety.CheckinManager
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.parse
import ro.safetyplease.core.venue.route.WalkRouter
import kotlin.concurrent.Volatile
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Lives as long as the app's process: identity, stores, the mesh engine, chat and incident managers, and what keeps
 * them running. The same on every phone; what differs comes in through [platform].
 */
open class PingUpSession(private val platform: AppPlatform) {
    val clock: Clock = platform.clock
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One thread for mesh, radio, chat and incidents, so network state needs no locks. */
    val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val crypto: Crypto = platform.crypto
    private val vault = platform.vault
    private var identityLost = false
    val identity: Identity = loadIdentity()

    private val dataDir = platform.filesDir / "data"
    val settings = JsonStore(dataDir / "settings.json", Settings.serializer(), Settings(), ioScope)
    val friends = JsonStore(dataDir / "friends.json", ListSerializer(Friend.serializer()), emptyList(), ioScope)
    val groups = JsonStore(dataDir / "groups.json", ListSerializer(Group.serializer()), emptyList(), ioScope)
    val blocked = JsonStore(dataDir / "blocked.json", ListSerializer(BlockedPerson.serializer()), emptyList(), ioScope)
    val chatStore = ChatStore(dataDir, ioScope)

    /** The event packs loaded on this phone and the one in use. */
    val events = JsonStore(dataDir / "events.json", EventShelf.serializer(), EventShelf(), ioScope)
    private val bundledKeys = Json.parseToJsonElement(platform.asset("staff_public.json")).jsonObject
    private val root: ByteArray? = bundledKeys["root"]?.jsonPrimitive?.content?.hexToBytes()

    /** Kept for every event: a pack whose organiser or event key was revoked no longer loads. */
    private val revokedStore = Revocations.store(dataDir / "revocations.json", ioScope)

    /**
     * The event in use, checked again at every start; null for the venue and keys the app ships, also once the
     * active pack's event is over. Switching events starts a new session, so everything below is built for one event.
     */
    val event: EventPack? = events.value.activePack(crypto, root, clock.wallMs() / 1000, revokedStore.value.ids)

    /** An exercise: banner on every screen, no alarm sound, and its own incidents, cache, console log and staff keys. */
    val exercise: Boolean = event?.exercise == true

    val incidentStore = JsonStore(modeFile("incidents.json"), IncidentData.serializer(), IncidentData(), ioScope)

    private val venueText: String = event?.venue ?: platform.asset("venue.json")

    @Volatile
    private var named: Pair<String, Venue> = platform.language().let { it to Venue.parse(venueText, it) }

    /**
     * The event's map with its names in the language the app speaks now: the person may change it while the app runs.
     * The places and shapes stay the same; only the names follow.
     */
    val venue: Venue
        get() {
            val language = platform.language()
            val held = named
            if (held.first == language) return held.second
            return Venue.parse(venueText, language).also { named = language to it }
        }
    val grid = SiteGrid.of(venue.bounds, event?.gridMeters ?: SiteGrid.CELL_METERS)

    /** The control console's shift log; written only while this phone is the console. */
    val consoleLog = JsonStore(
        modeFile("console_log.json"), ConsoleLog.serializer(), ConsoleLog(), ioScope, ConsoleLog.SCHEMA_VERSION,
    )
    val console = ConsoleRecorder(consoleLog, grid, clock) { id -> incidentStore.value.staff.firstOrNull { it.incidentId == id } }

    /** The other mode's console log, real or exercise, read once for the edition report; never written here. */
    val otherConsoleLog: ConsoleLog by lazy {
        val other = if (exercise) EventKind.REAL else EventKind.EXERCISE
        JsonStore(
            dataDir / modeFileName("console_log.json", other), ConsoleLog.serializer(), ConsoleLog(), ioScope, ConsoleLog.SCHEMA_VERSION,
        ).value
    }

    /** When the console last heard each phone; kept while this phone is the console. */
    val heard = MutableStateFlow<Map<Int, HeardPeer>>(emptyMap())

    /** The last verified status of each point, by its number; kept while this phone is the console. */
    val points = MutableStateFlow<Map<Int, PointReport>>(emptyMap())

    /** What this staff phone last said of each point, by its number. */
    val sentPoints = MutableStateFlow<Map<Int, SentPoint>>(emptyMap())
    private val pointNumbers = PointBoard.points(venue).mapTo(HashSet()) { it.number }

    /** The console's last shared log, in the cache; the share sheet reads the files from here. */
    val exportDir = platform.cacheDir / "export"

    /** The pilot's measurements, kept on this phone until someone exports them; none names the phone. */
    val metricsLog = JsonStore(dataDir / "metrics.json", MetricsLog.serializer(), MetricsLog(), ioScope, MetricsLog.SCHEMA_VERSION)
    val networkLog = JsonStore(dataDir / "network_log.json", NetworkLog.serializer(), NetworkLog(), ioScope, NetworkLog.SCHEMA_VERSION)

    /** The reporters' metrics given to the console, to join with its log. */
    val reporterMetrics = JsonStore(
        dataDir / "reporter_metrics.json", ReporterRecords.serializer(), ReporterRecords(), ioScope, ReporterRecords.SCHEMA_VERSION,
    )
    private val meshMetrics = MeshMetrics(metricsLog, clock)

    val keyChain: KeyChain
    val staffCrypto: StaffCrypto

    init {
        fun hex(name: String) = bundledKeys.getValue(name).jsonPrimitive.content.hexToBytes()
        fun certificates(name: String) =
            bundledKeys[name]?.jsonArray.orEmpty().mapNotNull { Certificate.decode(it.jsonPrimitive.content.hexToBytes()) }
        val bundled = StaffPublicKeys(hex("box"), hex("sign"))
        val public = event?.publicKeys(bundled) ?: bundled
        val issuers = event?.issuers ?: certificates("chain")
        keyChain = KeyChain(crypto, clock, public.sign, root, issuers, event?.reportKeys ?: certificates("reportKeys"))
        staffCrypto = StaffCrypto(crypto, public, keyChain)
    }

    private val vaultStaff = modeFileName(VAULT_STAFF, event?.kind)
    private val vaultShift = modeFileName(VAULT_SHIFT, event?.kind)

    @Volatile
    var staffSecret: StaffSecretKeys? = loadStaffSecret()
        private set

    val log = PacketLog(platform.debug).apply { sink = platform::log }
    val radio = platform.radio(meshScope, identity.nodeId)

    /** The reports and ACKs this phone carries for store-and-forward, so a restart doesn't lose them. */
    private val meshCache = JsonStore(modeFile("mesh_cache.json"), CachedIncidents.serializer(), CachedIncidents(), ioScope)

    val engine = MeshEngine(
        meshScope, radio, clock, CryptoRandom(crypto), identity.nodeId,
        MeshConfig(nicknameInHello = platform.debug, allowTestPackets = platform.debug),
        log, meshCache,
        keyChain,
    )

    private val notifications = platform.notifications(venue, exercise)

    /** What has to show at its time even while the app sleeps, handed to the phone ahead of time ([scheduleAhead]). */
    val alertSchedule = AlertScheduler(notifications, clock)

    /** GNSS fixes, the landmark the person says they stand by and, on Android with the setting on, steps. */
    val positioning = Positioning(clock)

    /** The phone's position as the app shows and sends it; null without permission or a fix. */
    val locationFix: StateFlow<GeoFix?> = positioning.position

    /** The offline map downloads; null while this phone has no pack store. */
    open val maps: MapDownloads? get() = null

    /** The map packs on this phone; null while it has no pack store. */
    open val packs: PackRegistry? get() = null

    /** The battery, with the power profile in force and how long it lasts at that profile's pace. */
    val battery = BatteryWatch(clock) { percent, charging ->
        val role = settings.value.role
        PowerPolicy.profile(role.hasStaffKeys, role == Role.ANCHOR, percent, charging)
    }.also { platform.watchBattery(it::update) }

    /** Until when (monotonic) scanning and advertising stay at LOW_LATENCY after an incident is sent. */
    val boostUntil = MutableStateFlow(0L)

    /** Until when (wall time) the console's alerts stay silent: "Silențios 10 min". */
    val mutedUntil = MutableStateFlow(0L)

    /** A screen of the app is in front of the person. */
    val foreground = MutableStateFlow(false)

    @Volatile
    var openConversation: String? = null

    /** What friends share with this phone and what it shares, kept across restarts until each session ends. */
    val positions = PositionStore.open(dataDir / "positions.json", ioScope)
    private val positionFrame = PositionFrame.of(event, venue.bounds)

    val chat = ChatManager(
        meshScope, engine, crypto, identity, friends, groups, blocked, chatStore, clock, CryptoRandom(crypto),
        myNickname = { settings.value.nickname },
        shareZone = { settings.value.shareZone },
        positions = positions,
        positionFrame = { positionFrame },
        onCheckinMissed = { sender, missed -> checkin.onFriendMissed(sender, missed) },
    ) { message, sender ->
        if (!foreground.value || openConversation != message.conversation) notifications.chatMessage(message, sender)
    }

    /** "M-am pierdut de grup", the last gasp at low battery and a lost person's last position in a report. */
    val regasire = Regasire(
        meshScope, chat, positions, clock, venue, identity.nodeId,
        JsonStore(dataDir / "searches.json", ListSerializer(Search.serializer()), emptyList(), ioScope),
        walk = { walkDistance }, mapState = { mapState.value }, myPosition = { positioning.position.value },
        battery = { battery.state.value.percent.coerceIn(0, 100) }, frame = { positionFrame },
    )

    private val checkinStore = JsonStore(dataDir / "checkin.json", CheckinData.serializer(), CheckinData(), ioScope)

    /** The check-in on the way, and the alerts of friends who missed theirs. */
    val checkin: CheckinManager = CheckinManager(
        meshScope, checkinStore, clock,
        friends = { friends.value },
        lastFix = { platform.location.value },
        alert = chat::sendCheckinMissed,
        withdraw = chat::withdrawCheckinMissed,
        tell = { to, text -> to.forEach { chat.sendText(Conversations.friend(it), text) } },
        notify = notifications::safety,
    )

    /** What this phone shares now and with how many people; null when nothing. */
    val sharing: StateFlow<SharingNow?> = combine(chat.activeShares, settings.state, friends.state, groups.state, minuteTicks()) { active, s, _, _, _ ->
        SharingNow.of(active, s.sharePlans, ::recipientsOf, clock.wallMs())
    }.stateIn(ioScope, SharingStarted.Eagerly, null)

    /** Who a conversation reaches: the friend, or the group's members but this phone. */
    fun recipientsOf(conversation: String): List<Long> =
        if (Conversations.isGroup(conversation)) {
            groups.value.firstOrNull { Conversations.group(it.id) == conversation }?.members?.map { it.nodeId }?.filter { it != identity.nodeId }.orEmpty()
        } else {
            friends.value.filter { Conversations.friend(it.nodeId) == conversation }.map { it.nodeId }
        }

    /** The start of the festival day: the pack's, or 06:00 local time without a pack. */
    @OptIn(ExperimentalTime::class)
    fun dayStartMs(): Long = event?.let { it.dayStart * 1000 } ?: run {
        val zone = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochMilliseconds(clock.wallMs()).toLocalDateTime(zone).date
        today.atTime(LocalTime(NO_PACK_DAY_START_HOUR, 0)).toInstant(zone).toEpochMilliseconds()
    }

    /** Shares this phone's position with [conversation] as [choice] says; the caller is a visible screen. */
    fun startShare(conversation: String, choice: ShareChoice) {
        val length = choice.length ?: return
        val precision = choice.precision ?: return
        val now = clock.wallMs()
        val plan = SharePlan(conversation, ShareTime.endAt(length, now, dayStartMs()), now)
        settings.update { s -> s.copy(sharePlans = s.sharePlans.filterNot { it.conversation == conversation } + plan) }
        chat.startShare(conversation, ShareTime.sessionMinutes(plan.endAtMs, now), precision)
        platform.shareLocation(PositionPolicy.MIN_INTERVAL_MS)
    }

    fun stopShare(conversation: String) {
        settings.update { s -> s.copy(sharePlans = s.sharePlans.filterNot { it.conversation == conversation }) }
        chat.stopShare(conversation)
    }

    /** "Oprește" in the notification or "Oprește tot": every share ends, and nobody is told more than the STOP. */
    fun stopAllShares() {
        val conversations = chat.activeShares.value.map { it.conversation } + settings.value.sharePlans.map { it.conversation }
        settings.update { it.copy(sharePlans = emptyList()) }
        conversations.distinct().forEach(chat::stopShare)
        platform.shareLocation(null)
    }

    /** Renews mesh sessions for plans longer than two hours, ends plans whose share is gone and reminds of open-ended ones. */
    private fun keepShares() {
        val now = clock.wallMs()
        val active = chat.activeShares.value
        val plans = settings.value.sharePlans
        val kept = SharingNow.keptPlans(plans, active, now)
        for (plan in kept) {
            val session = active.firstOrNull { it.conversation == plan.conversation } ?: continue
            if (ShareTime.needsRenew(plan, session.expiresAtMs, now)) chat.renewShare(plan.conversation, ShareTime.sessionMinutes(plan.endAtMs, now))
        }
        val due = kept.filter { ShareTime.reminderDue(it, now) }
        if (due.isNotEmpty()) sharing.value?.let { notifications.shareReminder(it.people) }
        val updated = kept.map { if (it in due) it.copy(remindedAtMs = now) else it }
        if (updated == plans) return
        settings.update { s ->
            // a share started while this ran stays as it was written
            val fresh = s.sharePlans.filter { it !in plans }
            s.copy(sharePlans = updated.filter { u -> fresh.none { it.conversation == u.conversation } } + fresh)
        }
    }

    private fun minuteTicks() = flow {
        while (true) {
            emit(Unit)
            delay(SHARE_TICK_MS)
        }
    }

    val incidents = IncidentManager(
        meshScope, engine, crypto, staffCrypto, incidentStore, clock,
        staffSecret = { staffSecret },
        teamName = { settings.value.teamName },
        onStaffAlertCleared = {
            notifications.cancelStaffAlert(it)
            alertSchedule.stopRepeat(it)
        },
        onReportUpdate = { if (!foreground.value) notifications.reportUpdate(it, clock.wallMs()) },
        onReportSent = { boostUntil.value = clock.monoMs() + PowerPolicy.BOOST_MS },
        onReportResponse = { notifications.reportResponse(it) },
        onAck = { ack, own, hops -> if (settings.value.role == Role.CONTROL) console.sawAck(ack, own, hops) },
        staffCap = { if (settings.value.role == Role.CONTROL) IncidentManager.MAX_STAFF_CONSOLE else IncidentManager.MAX_STAFF },
        priority = { staffChain.priority },
        divert = { staffChain.divert(it) },
    )

    private val revocations = Revocations(
        meshScope, engine, keyChain,
        revokedStore,
        resends = { settings.value.role == Role.CONTROL },
    ) {
        // a revoked shift signs nothing any more; the phone goes back to being a participant's
        if (keyChain.credential?.let { keyChain.status(it.certificate) == CertStatus.REVOKED } == true) leaveStaff()
    }

    /** The texts the console's notices name: the pack's, or the app's own. */
    val noticeTemplates: NoticeTemplates =
        event?.notices ?: NoticeTemplates.parse(platform.asset("notices.json")) ?: NoticeTemplates.NONE

    /** The organiser's notices: signed here on a console, shown to staff and to participants who opted in. */
    val notices = Notices(
        meshScope, engine, keyChain, crypto, clock,
        JsonStore(modeFile("notices.json"), NoticeBoard.serializer(), NoticeBoard(), ioScope),
        resends = { settings.value.role == Role.CONTROL },
        onNotice = ::onNotice,
    )

    fun showsNotices(s: Settings): Boolean = s.role == Role.STAFF || (s.role == Role.PARTICIPANT && s.notices)

    /** An evacuation for this phone's zone shows on every participant's and team's phone, whatever [Settings.notices] says. */
    fun showsEvacuation(s: Settings): Boolean = s.role == Role.STAFF || s.role == Role.PARTICIPANT

    /** The zones' states and the evacuation in force, from the console's latest notices, whoever shows them. */
    val mapState: StateFlow<MapState> = combine(notices.board, minuteTicks()) { board, _ -> MapState.of(board, venue, clock.wallMs() / 1000) }
        .stateIn(ioScope, SharingStarted.Eagerly, MapState.of(notices.board.value, venue, clock.wallMs() / 1000))

    /** The console's zone states, sent as map states. */
    val mapEditor = MapEditor(meshScope, venue, clock, notices, settings.state.map { it.role == Role.CONTROL })

        /** The walking routes over the venue, built on first use; plan with them off the main thread. */
    val walkRouter = WalkRouter(venue)

    /**
     * Metres of walking between two points of the site, around water, fences and the zones the console closed, with
     * staff access on a staff phone; in a straight line off the site's map. It may plan a route: call it off the main
     * thread.
     */
    val walkDistance: WalkDistance = walkRouter.distance({ mapState.value }) { settings.value.role.hasStaffKeys }

    /** "Planul meu" from the event's schedule; its reminders ring from here while the session runs. */
    val program = ProgramReminders(
        meshScope, JsonStore(dataDir / "program.json", MyProgram.serializer(), MyProgram(), ioScope), event?.id.orEmpty(),
        event?.schedule.orEmpty(), venue, clock,
        settings = { settings.value }, fix = { platform.location.value }, walk = { walkDistance },
    ) { set, walkMin -> notifications.setReminder(set, venue.zoneName(set.stage), walkMin) }

    /** Priority, crowd levels, hand-over notes, responders and man-down. */
    val staffChain: StaffChain = StaffChain(
        meshScope, ioScope, platform, notifications, clock, settings, incidentStore.state, venue, { walkDistance },
        { draft, bypass -> incidents.report(draft, bypass) },
        modeFile("density.json"), modeFile("handover.json"), dataDir / "staff_chain.json", exportDir, foreground,
    )

    /** The alerts that already sounded here, by topic: the seq of the last. */
    private val sounded = mutableMapOf<Int, Long>()

    private fun whereabouts(): Whereabouts = NoticeReach.whereabouts(venue, settings.value, locationFix.value, clock.wallMs())

    /**
     * The console logs every notice it sends or hears; a phone that shows them notifies, unless it signed the notice or
     * the notice has no words (the map state). An alarm sounds only where the notice is for this phone's zone. An
     * evacuation for this phone's zone, and its withdrawal, come through even with the notices turned off.
     */
    internal fun onNotice(notice: Notice, own: Boolean) {
        val title = platform.noticeTitle(notice)
        val text = platform.noticeBody(noticeTemplates, venue, notice)
        if (settings.value.role == Role.CONTROL) console.notice(notice, if (notice.cancelled) title else "$title: $text", own)
        if (own) return
        val alarm = NoticeReach.alarmAt(notice, venue, whereabouts(), exercise)
        if (!NoticeReach.posts(notice, alarm, showsNotices(settings.value), showsEvacuation(settings.value))) return
        notifications.notice(notice, title, text, alarm)
        if (alarm != NoticeAlarm.NONE) sounded[notice.topic] = notice.seq
    }

    /**
     * A phone that walks into the zones of an evacuation still in force, after it came, gets its alarm then, once; as
     * does one whose fix now reaches them.
     */
    private fun soundOnArrival() {
        val s = settings.value
        if (!showsNotices(s) && !showsEvacuation(s)) return
        val here = whereabouts()
        for ((held, notice) in notices.board.value.active(clock.wallMs() / 1000)) {
            if (held.own || held.alerted || !NoticeReach.isEvacuation(notice) || sounded[notice.topic] == notice.seq) continue
            val alarm = NoticeReach.alarmAt(notice, venue, here, exercise)
            if (alarm == NoticeAlarm.NONE) continue
            notifications.notice(notice, platform.noticeTitle(notice), platform.noticeBody(noticeTemplates, venue, notice), alarm)
            sounded[notice.topic] = notice.seq
        }
    }

    /** This phone holds a shift certificate the chain accepts now. */
    val verified: Boolean get() = keyChain.credential?.let { keyChain.status(it.certificate) == CertStatus.VALID } == true

    init {
        if (identityLost) settings.update { it.copy(identityReplaced = true) }
        meshScope.launch {
            engine.start()
            chat.start()
            checkin.start()
            incidents.start()
            staffChain.start()
            revocations.start()
            notices.start()
            settings.state.collect { s ->
                engine.setRole(staff = s.role.hasStaffKeys, anchor = s.role == Role.ANCHOR)
                engine.setNickname(s.nickname)
                engine.setIgnoredPrefixes(s.ignoredPrefixes.toSet())
            }
        }
        meshScope.launch { remindUnconfirmed() }
        meshScope.launch {
            while (true) {
                keepShares()
                delay(SHARE_TICK_MS)
            }
        }
        meshScope.launch {
            // a share keeps the GPS going off-screen, where the phone allows it
            chat.activeShares.map { shares -> shares.isNotEmpty() }.distinctUntilChanged().collect { on ->
                platform.shareLocation(if (on) PositionPolicy.MIN_INTERVAL_MS else null)
            }
        }
        meshScope.launch {
            positioning.fused.filterNotNull().collect { (fix, method) ->
                chat.onFix(fix.lat, fix.lon, fix.accuracyM.takeIf { it > 0f }?.toDouble(), fix.courseDeg?.toDouble(), method, fix.timeMs)
            }
        }
        meshScope.launch {
            val exact = chat.activeShares.map { shares -> shares.any { it.precision == SharePrecision.EXACT } }
            positioning.run(platform.location, settings.state.map { it.deadReckoning }, exact)
        }
        meshScope.launch { battery.state.collect { regasire.onBattery(it.percent, it.charging) } }
        meshScope.launch { endExpiredShift() }
        meshScope.launch { routeAlerts() }
        meshScope.launch {
            while (true) {
                delay(MeshMetrics.INTERVAL_MS)
                meshMetrics.snapshot(engine.state.value, settings.value.role)
            }
        }
        ioScope.launch { logMobileNetwork() }
        meshScope.launch {
            // the first value is where the phone was when the notices it holds came: they were decided then
            combine(settings.state, platform.location) { _, _ -> whereabouts() }.distinctUntilChanged().drop(1).collect { soundOnArrival() }
        }
        meshScope.launch {
            settings.state.map { it.role == Role.CONTROL }.distinctUntilChanged().collectLatest { control ->
                if (control) {
                    watchAsConsole()
                } else {
                    heard.value = emptyMap()
                    points.value = emptyMap()
                }
            }
        }
        meshScope.launch { scheduleAhead() }
    }

    /**
     * Runs the mesh until cancelled: the radio on, at the power the role, the battery and [screenOn] call for, and an
     * anchor's heartbeat to the console. The Android app calls it from its foreground service, the iPhone app while
     * Bluetooth may run in the background.
     */
    suspend fun runMesh(screenOn: Flow<Boolean>) {
        val boosting = MutableStateFlow(false)
        val reportPending = MutableStateFlow(false)
        meshScope.launch { radio.start() }
        try {
            coroutineScope { keepMeshRunning(screenOn, boosting, reportPending) }
        } finally {
            meshScope.launch { radio.stop() }
        }
    }

    private fun CoroutineScope.keepMeshRunning(
        screenOn: Flow<Boolean>,
        boosting: MutableStateFlow<Boolean>,
        reportPending: MutableStateFlow<Boolean>,
    ) {
        launch {
            boostUntil.collectLatest { until ->
                val remaining = until - clock.monoMs()
                boosting.value = remaining > 0
                if (remaining > 0) {
                    delay(remaining)
                    boosting.value = false
                }
            }
        }
        launch {
            incidentStore.state.map { it.mine }.distinctUntilChanged().collectLatest { mine ->
                while (true) {
                    val until = IncidentManager.pendingUntil(mine, clock.wallMs())
                    reportPending.value = until != null
                    if (until == null) break
                    delay(until - clock.wallMs())
                }
            }
        }
        launch {
            val role = settings.state.map { it.role }.distinctUntilChanged()
            combine(role, battery.state, screenOn, boosting, reportPending) { r, b, screen, boost, pending ->
                PowerPolicy.plan(r.hasStaffKeys, r == Role.ANCHOR, b.percent, b.charging, screen, boost, pending)
            }.distinctUntilChanged().collect { plan ->
                meshScope.launch { radio.setPower(plan) }
            }
        }
        launch {
            settings.state.map { it.role == Role.ANCHOR }.distinctUntilChanged().collectLatest { anchor ->
                if (anchor) beatAsAnchor()
            }
        }
    }

    /**
     * Tells the console this anchor is alive, how its battery is and what it hears (PROTOCOL.md, section 6.9), with
     * its square of the site grid once it had a fix there and, with the mobile network log on, its last sample.
     */
    private suspend fun beatAsAnchor() {
        val heartbeat = AnchorHeartbeat()
        var square: Pair<Int, Int>? = null
        while (true) {
            delay(AnchorHeartbeat.CHECK_MS)
            val b = battery.state.value
            val device = DeviceReadings(b.percent, b.charging, b.temperatureC)
            val settings = settings.value
            val now = clock.wallMs()
            platform.location.value?.takeIf { it.isFresh(now) }?.let { fix -> grid.squareAt(fix.lat, fix.lon)?.let { square = it } }
            val sample = networkLog.value.samples.lastOrNull()
                ?.takeIf { settings.networkLog && now - it.at <= 2 * NetworkLog.INTERVAL_MS }
            val mobile = sample?.let(AnchorHeartbeat::mobileOf)
            heartbeat.next(engine.state.value, device, settings.anchorZone, clock.monoMs(), mobile, square)
                ?.let { sendHeartbeat(it) }
        }
    }

    /**
     * While this phone is the console: logs every report it opens, remembers when it last heard each phone and keeps
     * the anchors' heartbeats.
     * Files a past export left in the cache go when the console starts; the log keeps everything they held.
     */
    private suspend fun watchAsConsole() = coroutineScope {
        launch(Dispatchers.IO) { FileSystem.SYSTEM.deleteRecursively(exportDir) }
        launch { incidentStore.state.collect { console.sawIncidents(it.staff) } }
        launch { engine.state.collect { state -> heard.update { PeerBook.update(it, state, clock.wallMs(), clock.monoMs()) } } }
        launch {
            engine.events.collect { event ->
                if (event !is MeshEvent.Received) return@collect
                val secret = staffSecret ?: return@collect
                val plain = ControlMessages.open(staffCrypto, secret, event.packet) ?: return@collect
                when (plain[0].toInt()) {
                    ControlChannel.HEARTBEAT -> Heartbeat.decode(plain)?.let { beat ->
                        heard.update { PeerBook.heartbeat(it, event.packet.sender, beat, clock.wallMs()) }
                    }
                    ControlChannel.POINT_STATUS -> if (staffCrypto.chain.checkPoint(plain) == Verdict.VALID) {
                        PointStatus.decode(plain)?.let(::offerPoint)
                    }
                }
            }
        }
    }

    private fun offerPoint(status: PointStatus) {
        points.update { PointBoard.offer(it, status, pointNumbers, clock.wallMs()) }
    }

    /** An anchor's heartbeat, to whichever console the mesh reaches. */
    fun sendHeartbeat(beat: Heartbeat) {
        meshScope.launch {
            engine.unicast(PacketType.PRIVATE, ControlChannel.ID, ControlMessages.seal(staffCrypto, beat.encode()))
        }
    }

    /**
     * A point's status, signed in this phone's team name, to whichever console the mesh reaches; false if this phone
     * can't sign or sent that point too recently. A console takes its own at once.
     */
    fun sendPoint(point: Int, level: Int): Boolean {
        val secret = staffSecret ?: return false
        val now = clock.wallMs()
        if (!PointBoard.maySend(sentPoints.value, point, level, now)) return false
        val status = staffCrypto.signPoint(point, level, now / 1000, settings.value.teamName, secret)
        sentPoints.update { it + (point to SentPoint(level, now)) }
        if (settings.value.role == Role.CONTROL) offerPoint(status)
        meshScope.launch {
            engine.unicast(PacketType.PRIVATE, ControlChannel.ID, ControlMessages.seal(staffCrypto, status.encode()))
        }
        return true
    }

    /**
     * The staff alerts, from what each incident's route is now: they change with the store, the settings and the
     * mute, and in time, as an alert repeats or widens.
     */
    private suspend fun routeAlerts() {
        val planner = AlertPlanner(clock.wallMs())
        combine(incidentStore.state, settings.state, mutedUntil, staffChain.position, staffChain.nearIncidents) { data, s, muted, here, near ->
            Triple(data, Routing.profile(s).copy(position = here, near = near).takeIf { s.role.hasStaffKeys }, muted)
        }.collectLatest { (data, me, muted) ->
            if (me == null) {
                alertSchedule.keepRepeats(emptySet())
                return@collectLatest
            }
            val own = data.mine.mapTo(HashSet()) { it.incidentId }
            while (true) {
                val now = clock.wallMs()
                val plan = planner.plan(me, data.staff, own, now, muted)
                plan.stop.forEach(notifications::cancelStaffAlert)
                plan.post.forEach { staffChain.post(it) }
                // what rings again while the app sleeps follows: never muted, nor for an incident gone from the list
                plan.stop.forEach { alertSchedule.stopRepeat(it) }
                plan.post.forEach { alertSchedule.repeated(it) }
                alertSchedule.keepRepeats(if (now < muted) emptySet() else data.staff.mapTo(HashSet()) { it.incidentId })
                val next = plan.nextAt ?: break
                delay(next - now)
            }
        }
    }

    /**
     * Hands the phone, ahead of time, what has to show at its time even while the app sleeps: an iPhone runs it only
     * while Bluetooth wakes it. The urgent report's escalation, the check-in's question, the set reminders, the end of
     * the shift and the share reminder follow the state here; the insistent alerts ring again from [routeAlerts]. The
     * timers here keep running while the app does, and the phone shows each subject once.
     */
    private suspend fun scheduleAhead() = coroutineScope {
        launch { incidentStore.state.map { it.mine }.distinctUntilChanged().collect { alertSchedule.escalations(it) } }
        launch { checkin.state.map { it.current }.distinctUntilChanged().collect { alertSchedule.checkin(it) } }
        launch {
            settings.state.map { s -> keyChain.credential?.certificate?.validUntil?.takeIf { s.role != Role.PARTICIPANT } }
                .distinctUntilChanged().collect { alertSchedule.shiftEnd(it) }
        }
        launch {
            combine(settings.state.map { it.sharePlans }, sharing) { plans, now -> plans to (now?.people ?: 0) }
                .distinctUntilChanged().collect { (plans, people) -> alertSchedule.share(plans, people) }
        }
        if (program.schedule.isNotEmpty()) {
            launch {
                // the walk to each stage counts from where the phone is as the app leaves the front, the last it may know
                combine(program.state, foreground) { p, _ -> p }.collectLatest { p ->
                    val reminders = withContext(Dispatchers.Default) {
                        val from = program.origin()
                        AlertSchedule.sets(p, program.schedule, clock.wallMs() / 1000, { program.walkMeters(from, it) }) { venue.zoneName(it.stage) }
                    }
                    alertSchedule.sets(reminders)
                }
            }
        }
    }

    /** While staff keep the log on: the mobile network every 2 minutes, on the grid square of a fresh position. */
    private suspend fun logMobileNetwork() {
        settings.state.map { it.networkLog && it.role != Role.PARTICIPANT }.distinctUntilChanged().collectLatest { on ->
            if (!on) return@collectLatest
            while (true) {
                val now = clock.wallMs()
                val cell = platform.location.value?.takeIf { it.isFresh(now) }?.let { grid.cellAt(it.lat, it.lon) }.orEmpty()
                val sample = platform.sampleMobileNetwork(now, cell)
                networkLog.update { it.with(sample) }
                delay(NetworkLog.INTERVAL_MS)
            }
        }
    }

    /** A shift certificate that ran out leaves the phone a participant's, as a revoked one does, and says so. */
    private suspend fun endExpiredShift() {
        settings.state.map { it.role != Role.PARTICIPANT }.distinctUntilChanged().collectLatest { onShift ->
            while (onShift) {
                val cert = keyChain.credential?.certificate
                if (cert != null && clock.wallMs() / 1000 > cert.validFrom && keyChain.status(cert) == CertStatus.OUTSIDE_WINDOW) {
                    notifications.shiftEnded()
                    leaveStaff()
                }
                delay(SHIFT_CHECK_MS)
            }
        }
    }

    /** When an urgent report of yours has waited too long for a person on staff, tells you so from the background. */
    private suspend fun remindUnconfirmed() {
        val reminder = EscalationReminder(clock.wallMs())
        combine(incidentStore.state, foreground) { data, fg -> data.mine to fg }.collectLatest { (mine, fg) ->
            while (true) {
                val now = clock.wallMs()
                reminder.due(mine, now, fg).forEach { notifications.reportUpdate(it, now) }
                val next = nextEscalation(mine, now) ?: break
                delay(next - now)
            }
        }
    }

    /** False if the QR belongs to another event. Anchors don't keep the secret keys, since they sit unattended. */
    fun activateStaff(card: StaffCard): Boolean {
        val secret = staffCrypto.secretFromSeeds(card.boxSeed, card.signSeed) ?: return false
        vault.delete(vaultShift)
        keyChain.credential = null
        if (card.role == StaffRole.ANCHOR) {
            vault.delete(vaultStaff)
            staffSecret = null
            settings.update { it.copy(role = Role.ANCHOR, teamName = card.teamName, anchorZone = card.zone, staffZone = "", roleLead = false) }
        } else {
            vault.save(vaultStaff, card.boxSeed + card.signSeed)
            staffSecret = secret
            val role = if (card.role == StaffRole.CONTROL) Role.CONTROL else Role.STAFF
            settings.update { it.copy(role = role, teamName = card.teamName, anchorZone = "", staffZone = card.zone, roleLead = false) }
            incidents.reprocessCached()
        }
        return true
    }

    /**
     * An SPS2 code: VALID once active, otherwise why the chain refuses its certificate. An anchor keeps only its shift
     * key, which proves its role and opens nothing; a team without the day's report key relays reports it can't open.
     */
    fun activateShift(card: ShiftCard, text: String): CertStatus {
        val status = keyChain.status(card.certificate)
        if (status != CertStatus.VALID) return status
        val (credential, secret) = staffCrypto.credentialFrom(card) ?: return CertStatus.UNKNOWN
        vault.delete(vaultStaff)
        vault.save(vaultShift, text.trim().encodeToByteArray())
        keyChain.credential = credential
        card.reportKey?.let(keyChain::addReportKey)
        staffSecret = secret
        settings.update { it.onShift(card).copy(roleLead = false) }
        if (secret != null) incidents.reprocessCached()
        return CertStatus.VALID
    }

    private fun Settings.onShift(card: ShiftCard): Settings {
        val role = when (card.role) {
            StaffRole.ANCHOR -> Role.ANCHOR
            StaffRole.CONTROL -> Role.CONTROL
            StaffRole.STAFF -> Role.STAFF
        }
        val anchor = role == Role.ANCHOR
        return copy(role = role, teamName = card.teamName, anchorZone = if (anchor) card.zone else "", staffZone = if (anchor) "" else card.zone)
    }

    /** Checks a pack and keeps it; it is used only once selected. */
    fun loadEventPack(text: String): Loaded {
        var loaded: Loaded? = null
        events.update { shelf -> shelf.load(text, crypto, root, clock.wallMs() / 1000, revokedStore.value.ids).also { loaded = it.second }.first }
        return loaded!!
    }

    /** A pack from an https link, as the organiser publishes it before the event; null if it can't be fetched. */
    suspend fun downloadEventPack(link: String): String? {
        val url = link.trim().takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
        val bytes = platform.download(url, EventPacks.MAX_BYTES + 1L) ?: return null
        return withContext(Dispatchers.IO) { EventPacks.read(Buffer().write(bytes)) }
    }

    /**
     * Makes [hash] the event in use, empty for what the app ships, and starts the app again to load it. The phone
     * leaves its role here; the keys of each kind of event stay, and the shift it holds for the next one comes back.
     */
    fun switchEvent(hash: String) {
        events.update { it.select(hash) }
        settings.update { it.copy(role = Role.PARTICIPANT, teamName = "", anchorZone = "", staffZone = "", roleLead = false) }
        // what was reported or sent just before the switch must not go with the process
        flush()
        platform.restart()
    }

    /**
     * Writes now what the stores keep for 150 ms, before the process may end: another event loading, or the iPhone app
     * going to the background, where iOS may end it later without a word.
     */
    fun flush() {
        listOf(
            events, settings, friends, groups, incidentStore, consoleLog, metricsLog, networkLog, reporterMetrics, meshCache, revokedStore,
            checkinStore,
        ).forEach { it.flush() }
        positions.flush()
        chatStore.flush()
        staffChain.flush()
    }

    fun removeEventPack(hash: String) = events.update { it.without(hash) }

    /** The pack still holds now, so switching to it loads it. */
    fun usable(pack: StoredPack): Boolean =
        EventPacks.check(pack.text, crypto, root, clock.wallMs() / 1000, revokedStore.value.ids).status == PackStatus.VALID

    /** An SPR1 code: the certificate ids it newly revokes, or null if this event's keys didn't sign it. */
    fun applyRevocation(payload: ByteArray): List<Long>? = revocations.add(payload)

    fun leaveStaff() {
        vault.delete(vaultStaff)
        vault.delete(vaultShift)
        keyChain.credential = null
        staffSecret = null
        mutedUntil.value = 0
        settings.update { it.copy(role = Role.PARTICIPANT, teamName = "", anchorZone = "", staffZone = "", roleLead = false) }
    }

    fun wipeAllData() = platform.wipe()

    /** The language the app is shown in, "ro" or "en". */
    fun language(): String = platform.language()

    /** The files of a folder the app ships with, by name: the first-aid sheets, the information pages. */
    fun assets(folder: String): Map<String, String> = platform.assets(folder)

    /** A conversation came on screen: its notification goes. */
    fun dismissConversation(conversation: String) = notifications.cancelConversation(conversation)

    /**
     * Stops all the session runs, so that a new one can take its place in the same process, as the iPhone app does to
     * load another event. Writes still waiting are dropped: [switchEvent] flushes them first.
     */
    fun close() {
        meshScope.cancel()
        ioScope.cancel()
    }

    private fun modeFile(name: String) = dataDir / modeFileName(name, event?.kind)

    private fun loadIdentity(): Identity {
        vault.load(VAULT_IDENTITY)?.let { bytes -> Identity.import(bytes)?.let { return it } }
        // a file we can't decrypt means the old identity is gone (its key in the key store was lost): keep it, say so
        if (vault.exists(VAULT_IDENTITY)) {
            vault.setAside(VAULT_IDENTITY)
            identityLost = true
        }
        return Identity.generate(crypto).also { vault.save(VAULT_IDENTITY, it.export()) }
    }

    private fun loadStaffSecret(): StaffSecretKeys? {
        vault.load(vaultShift)?.let { text ->
            val card = QrCodes.decodeShift(text.decodeToString()) ?: return null
            val (credential, secret) = staffCrypto.credentialFrom(card) ?: return null
            keyChain.credential = credential
            card.reportKey?.let(keyChain::addReportKey)
            // after a switch of events: the shift this phone holds for the one now in use
            if (settings.value.role == Role.PARTICIPANT) settings.update { it.onShift(card) }
            return secret
        }
        val seeds = vault.load(vaultStaff) ?: return null
        if (seeds.size != 2 * Crypto.SEED) return null
        return staffCrypto.secretFromSeeds(seeds.copyOfRange(0, Crypto.SEED), seeds.copyOfRange(Crypto.SEED, seeds.size))
    }

    companion object {
        private const val VAULT_IDENTITY = "identity"
        private const val VAULT_STAFF = "staff"
        private const val VAULT_SHIFT = "shift"
        private const val SHIFT_CHECK_MS = 60_000L
        private const val SHARE_TICK_MS = 30_000L
        private const val NO_PACK_DAY_START_HOUR = 6
    }
}
