package ro.safetyplease.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeAlarm
import ro.safetyplease.core.announce.NoticeOrder
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertLevel
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.AlertReason
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.ResponderTarget
import ro.safetyplease.core.incidents.needsEscalation
import ro.safetyplease.core.safety.SafetyNotice
import ro.safetyplease.core.session.Notifications
import ro.safetyplease.core.session.ScheduledAlert
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.checkin_alert_silent
import ro.safetyplease.ui.resources.checkin_alerted_none
import ro.safetyplease.ui.resources.checkin_alerted_none_title
import ro.safetyplease.ui.resources.checkin_alerted_text
import ro.safetyplease.ui.resources.checkin_alerted_title
import ro.safetyplease.ui.resources.exercise_title
import ro.safetyplease.ui.resources.iphone_bluetooth_off_text
import ro.safetyplease.ui.resources.iphone_bluetooth_off_title
import ro.safetyplease.ui.resources.iphone_closed_text
import ro.safetyplease.ui.resources.iphone_closed_title
import ro.safetyplease.ui.resources.layers_reminder_title
import ro.safetyplease.ui.resources.layers_reminder_walk
import ro.safetyplease.ui.resources.msg_group_left
import ro.safetyplease.ui.resources.msg_zone
import ro.safetyplease.ui.resources.notif_assigned_title
import ro.safetyplease.ui.resources.notif_checkin_text
import ro.safetyplease.ui.resources.notif_checkin_title
import ro.safetyplease.ui.resources.notif_escalated_title
import ro.safetyplease.ui.resources.notif_friend_silent_text
import ro.safetyplease.ui.resources.notif_friend_silent_text_no_fix
import ro.safetyplease.ui.resources.notif_incident_title
import ro.safetyplease.ui.resources.notif_report_title
import ro.safetyplease.ui.resources.notif_responder_title
import ro.safetyplease.ui.resources.notif_response_title
import ro.safetyplease.ui.resources.notif_shift_ended_text
import ro.safetyplease.ui.resources.notif_shift_ended_title
import ro.safetyplease.ui.resources.notif_unaccepted_title
import ro.safetyplease.ui.resources.notif_widened_title
import ro.safetyplease.ui.resources.report_escalate_text
import ro.safetyplease.ui.resources.report_escalate_title
import ro.safetyplease.ui.resources.responder_dea
import ro.safetyplease.ui.resources.responder_dea_name
import ro.safetyplease.ui.resources.responder_dea_patient
import ro.safetyplease.ui.resources.responder_distance
import ro.safetyplease.ui.resources.responder_no_dea
import ro.safetyplease.ui.resources.responder_title
import ro.safetyplease.ui.resources.sharing_bar
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.ui.safety.deadlineText
import ro.safetyplease.ui.safety.shortDuration
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.Replies
import ro.safetyplease.ui.text.wallNowMs
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * The app's notifications on an iPhone, saying what the Android ones say. In an [exercise] every title says so and
 * nothing rings as an alarm or breaks through Focus. Every post, schedule and cancel runs in turn on one queue, so a
 * cancel never overtakes the post it takes back, and a failure is logged, never fatal. What the session schedules ahead
 * ([ScheduledAlert]) iOS shows at its time even with the app suspended; when the running app then posts the same
 * thing, the one iOS showed stands.
 */
class IosNotifications(
    private val venue: Venue,
    private val exercise: Boolean,
    private val appName: String,
    private val log: (String) -> Unit,
) : Notifications {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log("notifications stopped: $e") })
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** What each staff alert said last, by incident: it rings again in the same words. Only the queue touches it. */
    private val said = HashMap<String, Note>()

    /** The words for [closed], read ahead: iOS gives the app no time to read them as it ends it. */
    @Volatile private var closedWords: Pair<String, String>? = null

    init {
        scope.launch {
            for (job in queue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("notification failed: $e")
                }
            }
        }
        later {
            // a new session schedules again all it wants; what an earlier one left would ring for nothing
            center.removeAllPendingNotificationRequests()
            // open again: the app no longer needs to say it closed
            remove(CLOSED)
            closedWords = IosTexts.text(Res.string.iphone_closed_title, appName) to IosTexts.text(Res.string.iphone_closed_text)
        }
    }

    override fun shiftEnded() = later { showOnce(shiftNote()) }

    /** A location share without an end still runs: who sees the phone is a tap away. */
    override fun shareReminder(people: Int) = later { showOnce(shareNote(people), withinMs = SHARE_SEEN_MS) }

    /** "Amintește-mi": the set starts soon, and with the walk it is time to leave. */
    override fun setReminder(set: ScheduleItem, stage: String, walkMin: Int) = later { showOnce(setNote(set, stage, walkMin)) }

    /**
     * The check-in on the way. The question is scheduled with iOS at the deadline, so it comes on time with the app
     * suspended; "Am ajuns", "+15" and "+30" answer it from the notification ([IosApp]).
     */
    override fun safety(notice: SafetyNotice) = when (notice) {
        // only what showed: the question scheduled ahead is the session's to move or take back
        SafetyNotice.CheckinCleared -> later { center.removeDeliveredNotificationsWithIdentifiers(listOf(ScheduledAlert.CHECKIN)) }
        is SafetyNotice.CheckinDue -> later { showOnce(checkinNote(notice.label)) }
        is SafetyNotice.CheckinAlerted -> later {
            val title: String
            val text: String
            if (notice.friends == 0) {
                title = IosTexts.text(Res.string.checkin_alerted_none_title)
                text = IosTexts.text(Res.string.checkin_alerted_none, notice.label)
            } else {
                title = IosTexts.text(Res.string.checkin_alerted_title)
                text = IosTexts.plural(Res.plurals.checkin_alerted_text, notice.friends, notice.friends, notice.label)
            }
            show(Note(ScheduledAlert.CHECKIN, title, text, OpenTarget.CHECKIN, key = "alerted"))
        }
        is SafetyNotice.FriendSilent -> later {
            val now = wallNowMs()
            val due = deadlineText(notice.deadlineMs, now)
            val late = shortDuration(now - notice.deadlineMs)
            val text = if (notice.fixedAtMs > 0) {
                IosTexts.text(Res.string.notif_friend_silent_text, due, late, shortDuration(now - notice.fixedAtMs))
            } else {
                IosTexts.text(Res.string.notif_friend_silent_text_no_fix, due, late)
            }
            show(
                Note(
                    "friend-silent-${notice.name}-${notice.label}", IosTexts.text(Res.string.checkin_alert_silent, notice.name, notice.label), text,
                    OpenTarget.CHECKIN_ALERTS, Level.TIME_SENSITIVE,
                ),
            )
        }
    }

    /**
     * A staff alert as the alert planner decided it: one that has to be heard rings the alarm tone and breaks through
     * Focus, as a Critical Alert once Apple grants it; one meant to be felt only vibrates. An exercise never rings.
     */
    override fun staffAlert(post: AlertPost) = later { if (!ringsAlready(post)) remember(post, staffNote(post)) }

    /** Someone doesn't breathe nearby: how far, and the defibrillator to fetch on the way, under the incident's own id. */
    override fun responderAlert(post: AlertPost, target: ResponderTarget?) = later {
        if (!ringsAlready(post)) remember(post, responderNote(post, target))
    }

    override fun cancelStaffAlert(incidentId: String) = later {
        said.remove(incidentId)
        remove(ScheduledAlert.alert(incidentId))
    }

    /**
     * Status of your report, in the words of its screen. An urgent report nobody on staff has confirmed in time tells
     * you to look for help in person, with 112 a tap away; that one is scheduled ahead, and shows once.
     */
    override fun reportUpdate(report: MyReport, nowMs: Long) = later {
        val discreet = Discreet.isDiscreet(report.description)
        if (report.needsEscalation(nowMs)) return@later showOnce(escalationNote(report.incidentId, report.status, report.teamName, discreet))
        // only the acknowledged status names the team; the others leave the argument out
        val status = Labels.myReportStatus(report.status, report.teamName.isNotEmpty())?.let { IosTexts.text(it, report.teamName) } ?: return@later
        val id = "report-${report.incidentId}"
        val open = OpenTarget.REPORT_PREFIX + report.incidentId
        // a discreet report is answered as quietly as it was sent, and in front of others it says nothing at all
        if (discreet) return@later show(Note(id, appName, "", open, Level.PASSIVE, Tone.NONE))
        show(Note(id, marked(IosTexts.text(Res.string.notif_report_title)), status, open))
    }

    /**
     * The console's latest answer to your report: what you are waiting for, so it sounds in the foreground too. A discreet
     * report's answer makes no sound and says nothing in front of others, like its status.
     */
    override fun reportResponse(report: MyReport) = later {
        val response = report.responses.lastOrNull() ?: return@later
        val id = "response-${report.incidentId}"
        val open = OpenTarget.REPORT_PREFIX + report.incidentId
        if (Discreet.isDiscreet(report.description)) return@later show(Note(id, appName, "", open, Level.PASSIVE, Tone.NONE))
        val text = IosTexts.text(Replies.response(response.code), Replies.landmarkName(venue, response.landmark))
        show(Note(id, IosTexts.text(Res.string.notif_response_title), text, open))
    }

    override fun chatMessage(message: ChatMessage, senderName: String) = later {
        val text = when (message.kind) {
            MsgKind.TEXT -> message.text
            MsgKind.QUICK -> IosTexts.text(Labels.quick(message.quickCode))
            MsgKind.ZONE -> IosTexts.text(Res.string.msg_zone, venue.zoneName(message.zone))
            MsgKind.SYSTEM -> IosTexts.text(Labels.systemNotice(message.conversation), senderName)
            MsgKind.LEFT -> IosTexts.text(Res.string.msg_group_left, message.text)
        }
        show(Note(chat(message.conversation), senderName, text, OpenTarget.CHAT_PREFIX + message.conversation, thread = message.conversation))
    }

    override fun cancelConversation(conversation: String) = later { remove(chat(conversation)) }

    /**
     * A notice from the organiser, one per slot (a topic; the evacuations apart from the other alerts): a newer one replaces it, a withdrawn one takes it away, and it leaves
     * the screen once it no longer holds ([sweep]). A pause or an evacuation for this phone rings its own alarm and
     * breaks through Focus; in an exercise it only vibrates, elsewhere it is an ordinary notification.
     */
    override fun notice(notice: Notice, title: String, text: String, alarm: NoticeAlarm) {
        val id = "notice-${NoticeOrder.slot(notice)}"
        if (notice.cancelled) return later { remove(id) }
        later {
            val (level, tone) = when (alarm) {
                NoticeAlarm.LOUD -> Level.CRITICAL to if (notice.level == NoticeLevel.EVACUATION) Tone.EVACUATION else Tone.PAUSE
                NoticeAlarm.SILENT -> Level.ACTIVE to Tone.FELT
                NoticeAlarm.NONE -> Level.ACTIVE to Tone.DEFAULT
            }
            show(Note(id, marked(title), text, OpenTarget.HOME, level, tone, untilMs = notice.validUntil * 1000))
        }
    }

    override fun scheduleAlert(alert: ScheduledAlert) = later {
        when (alert) {
            is ScheduledAlert.Escalation -> showAt(escalationNote(alert.incidentId, alert.status, alert.teamName, alert.discreet), alert.atMs)
            is ScheduledAlert.CheckinDue -> showAt(checkinNote(alert.label), alert.atMs)
            is ScheduledAlert.SetReminder -> showAt(setNote(alert.set, alert.stage, alert.walkMin), alert.atMs, onClock = true)
            is ScheduledAlert.ShiftEnd -> showAt(shiftNote(), alert.atMs, onClock = true)
            is ScheduledAlert.ShareReminder -> showAt(shareNote(alert.people), alert.atMs)
            is ScheduledAlert.AlertRepeat -> {
                val incident = alert.post.incident.incidentId
                val note = said[incident] ?: staffNote(alert.post)
                showAt(note.copy(id = alert.id), alert.atMs, everyMs = alert.everyMs)
            }
        }
    }

    override fun cancelScheduled(id: String) = later { remove(id) }

    /** Bluetooth is off with the app out of the front, so no alert can reach it; false takes the word back. */
    fun bluetoothOff(off: Boolean) = later {
        if (!off) return@later remove(BLUETOOTH)
        val title = IosTexts.text(Res.string.iphone_bluetooth_off_title, appName)
        show(Note(BLUETOOTH, title, IosTexts.text(Res.string.iphone_bluetooth_off_text), OpenTarget.HOME))
    }

    /**
     * iOS is ending the app: no alert reaches it until the person opens it again, and the notification says so. It
     * posts straight away, past the queue, and waits a moment for iOS to take it: the process ends as this returns.
     */
    fun closed() {
        val (title, text) = closedWords ?: return
        val done = dispatch_semaphore_create(0)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(CLOSED, content(Note(CLOSED, title, text, OpenTarget.HOME)), null)) { _ ->
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, CLOSED_WAIT_NS))
    }

    /** What has run out leaves the screen: iOS keeps a delivered notification until it's dismissed. */
    fun sweep() = later {
        val now = wallNowMs()
        val gone = delivered().filter { untilOf(it)?.let { until -> until <= now } == true }.map { it.request.identifier }
        if (gone.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(gone)
    }

    /** Waits, at most [IDLE_WAIT_MS], until all asked before reached iOS: an answer from a notification before iOS suspends the app. */
    suspend fun idle() {
        val done = CompletableDeferred<Unit>()
        later { done.complete(Unit) }
        withTimeoutOrNull(IDLE_WAIT_MS) { done.await() }
    }

    /** The session is replaced: nothing more goes through here. */
    fun close() {
        queue.close()
        scope.cancel()
    }

    private fun later(block: suspend () -> Unit) {
        queue.trySend(block)
    }

    private suspend fun marked(title: String) = if (exercise) IosTexts.text(Res.string.exercise_title, title) else title

    // --- what each notification says ---

    private suspend fun escalationNote(incidentId: String, status: Int, teamName: String, discreet: Boolean): Note {
        val id = ScheduledAlert.escalation(incidentId)
        val open = OpenTarget.REPORT_PREFIX + incidentId
        val key = "status-$status"
        if (discreet) return Note(id, appName, "", open, Level.PASSIVE, Tone.NONE, key = key)
        // only the acknowledged status names the team; the others leave the argument out
        val now = Labels.myReportStatus(status, teamName.isNotEmpty())?.let { IosTexts.text(it, teamName) }
        val text = listOfNotNull(IosTexts.text(Res.string.report_escalate_text), now).joinToString("\n")
        return Note(
            id, marked(IosTexts.text(Res.string.report_escalate_title)), text, open, if (exercise) Level.ACTIVE else Level.TIME_SENSITIVE,
            category = IosApp.CALL_112, key = key,
        )
    }

    private suspend fun checkinNote(label: String) = Note(
        ScheduledAlert.CHECKIN, IosTexts.text(Res.string.notif_checkin_title), IosTexts.text(Res.string.notif_checkin_text, label), OpenTarget.CHECKIN,
        Level.TIME_SENSITIVE, category = IosApp.CHECKIN, key = "due-$label",
    )

    private suspend fun setNote(set: ScheduleItem, stage: String, walkMin: Int): Note {
        val text = if (walkMin > 0) IosTexts.text(Res.string.layers_reminder_walk, stage, walkMin) else stage
        val title = IosTexts.text(Res.string.layers_reminder_title, set.title, Labels.clock(set.start * 1000))
        return Note(ScheduledAlert.setReminder(set), title, text, OpenTarget.HOME, key = "set", untilMs = set.end * 1000)
    }

    private suspend fun shiftNote() = Note(
        ScheduledAlert.SHIFT_ENDED, IosTexts.text(Res.string.notif_shift_ended_title), IosTexts.text(Res.string.notif_shift_ended_text), OpenTarget.HOME,
        key = "shift",
    )

    private suspend fun shareNote(people: Int) =
        Note(ScheduledAlert.SHARING, IosTexts.plural(Res.plurals.sharing_bar, people, people), "", OpenTarget.SHARING, key = "share")

    private suspend fun staffNote(post: AlertPost): Note {
        val incident = post.incident
        val zone = zoneName(incident.zone)
        val category = IosTexts.text(Labels.category(incident.category))
        val title = when (post.route.reason) {
            AlertReason.ASSIGNED -> IosTexts.text(Res.string.notif_assigned_title, category)
            AlertReason.UNACCEPTED -> IosTexts.text(Res.string.notif_unaccepted_title, incident.teamName, category)
            AlertReason.ROLE, AlertReason.EVERYONE -> IosTexts.text(Res.string.notif_widened_title, category)
            AlertReason.ESCALATED -> IosTexts.text(Res.string.notif_escalated_title, category)
            AlertReason.RESPONDER -> IosTexts.text(Res.string.notif_responder_title, zone)
            else -> IosTexts.text(Res.string.notif_incident_title, category, IosTexts.text(Labels.urgency(incident.severity)))
        }
        val (level, tone) = alarm(post)
        val body = if (incident.description.isEmpty()) zone else "$zone · ${incident.description}"
        return Note(
            ScheduledAlert.alert(incident.incidentId), marked(title), body, OpenTarget.INCIDENT_PREFIX + incident.incidentId, level, tone,
            thread = ScheduledAlert.alert(incident.incidentId),
        )
    }

    private suspend fun responderNote(post: AlertPost, target: ResponderTarget?): Note {
        val incident = post.incident
        val heading = IosTexts.text(Res.string.responder_title)
        val title = target?.direct?.let { "$heading · ${IosTexts.text(Res.string.responder_distance, it)}" } ?: heading
        val dea = target?.dea
        val toDea = target?.toDea
        val deaToPatient = target?.deaToPatient
        val text = when {
            dea == null || deaToPatient == null -> IosTexts.text(Res.string.responder_no_dea)
            else -> {
                val name = dea.name.ifEmpty { IosTexts.text(Res.string.responder_dea_name) }
                if (toDea != null) IosTexts.text(Res.string.responder_dea, name, toDea, deaToPatient) else IosTexts.text(Res.string.responder_dea_patient, name, deaToPatient)
            }
        }
        val (level, tone) = alarm(post.copy(route = post.route.copy(level = AlertLevel.INSISTENT)))
        return Note(
            ScheduledAlert.alert(incident.incidentId), marked(title), text, OpenTarget.INCIDENT_PREFIX + incident.incidentId, level, tone,
            thread = ScheduledAlert.alert(incident.incidentId),
        )
    }

    /** How a staff alert rings: the alarm for the insistent level, felt only in the field or in an exercise, nothing muted. */
    private fun alarm(post: AlertPost): Pair<Level, Tone> = when {
        post.silent -> Level.ACTIVE to Tone.NONE
        post.route.level == AlertLevel.INSISTENT && !exercise -> Level.CRITICAL to Tone.ALARM
        post.route.level == AlertLevel.INSISTENT || post.route.level == AlertLevel.VIBRATE -> Level.ACTIVE to Tone.FELT
        else -> Level.ACTIVE to Tone.DEFAULT
    }

    private suspend fun zoneName(zone: String) = if (zone.isEmpty()) IosTexts.text(Res.string.zone_unknown) else venue.zoneName(zone)

    // --- posting ---

    /** One notification as iOS shows it. [key] tells two of the same [id] apart; at [untilMs] it leaves the screen. */
    private data class Note(
        val id: String,
        val title: String,
        val body: String,
        val open: String,
        val level: Level = Level.ACTIVE,
        val tone: Tone = Tone.DEFAULT,
        val thread: String? = null,
        val category: String? = null,
        val key: String = "",
        val untilMs: Long? = null,
    )

    private enum class Level {
        PASSIVE,
        ACTIVE,

        /** Through Focus, where the app is allowed to; the person can turn it off in Settings. */
        TIME_SENSITIVE,

        /** Through the silent switch and Focus too, once Apple grants the entitlement ([criticalAlerts]); Time Sensitive until then. */
        CRITICAL,
    }

    /** The sounds the app ships (tools/make_alarm_sounds.py), each under 30 seconds as iOS requires. */
    private enum class Tone(val file: String?) {
        NONE(null),
        DEFAULT(null),
        EVACUATION("pingup_evacuation.caf"),
        PAUSE("pingup_pause.caf"),
        ALARM("pingup_alarm.caf"),

        /** A second of silence: the phone vibrates as its settings say, and nothing is heard. */
        FELT("pingup_silence.caf"),
    }

    private fun content(note: Note): UNMutableNotificationContent {
        val critical = note.level == Level.CRITICAL && criticalAlerts && !exercise
        val content = UNMutableNotificationContent()
        content.setTitle(note.title)
        content.setBody(note.body)
        // strings only: iOS keeps the info as a property list
        content.setUserInfo(mapOf<Any?, Any?>(IosApp.OPEN to note.open, KEY to note.key, UNTIL to (note.untilMs?.toString() ?: "")))
        content.setInterruptionLevel(
            when {
                critical -> UNNotificationInterruptionLevel.UNNotificationInterruptionLevelCritical
                note.level == Level.CRITICAL || note.level == Level.TIME_SENSITIVE -> UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive
                note.level == Level.PASSIVE -> UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive
                else -> UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
            },
        )
        content.setSound(sound(note.tone, critical))
        note.thread?.let(content::setThreadIdentifier)
        note.category?.let(content::setCategoryIdentifier)
        return content
    }

    private fun sound(tone: Tone, critical: Boolean): UNNotificationSound? {
        val file = tone.file
        return when {
            tone == Tone.NONE -> null
            file == null -> if (critical) UNNotificationSound.defaultCriticalSound else UNNotificationSound.defaultSound
            critical -> UNNotificationSound.criticalSoundNamed(file, withAudioVolume = 1.0f)
            else -> UNNotificationSound.soundNamed(file)
        }
    }

    private suspend fun show(note: Note) = add(UNNotificationRequest.requestWithIdentifier(note.id, content(note), null))

    /**
     * Has iOS show [note] at [atMs], by the clock for a time of day ([onClock]); with [everyMs], again every [everyMs]
     * after (a minute at least) until it's taken back. A time already past schedules nothing.
     */
    private suspend fun showAt(note: Note, atMs: Long, onClock: Boolean = false, everyMs: Long? = null) {
        val seconds = (atMs - wallNowMs()) / 1000.0
        val trigger: UNNotificationTrigger = when {
            everyMs != null -> UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(maxOf(everyMs / 1000.0, MIN_REPEAT_S), repeats = true)
            seconds < 1.0 -> return
            onClock -> {
                val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
                val at = NSCalendar.currentCalendar.components(units, fromDate = NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0))
                UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(at, repeats = false)
            }
            else -> UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
        }
        add(UNNotificationRequest.requestWithIdentifier(note.id, content(note), trigger))
    }

    /** Posts [note] unless iOS showed it already, or is about to: scheduled ahead, at its time it was the one to show. */
    private suspend fun showOnce(note: Note, withinMs: Long? = null) {
        val now = wallNowMs()
        val shown = delivered().any {
            it.request.identifier == note.id && keyOf(it.request) == note.key && (withinMs == null || now - it.date.timeIntervalSince1970 * 1000 <= withinMs)
        }
        if (shown || pending().any { it.identifier == note.id && keyOf(it) == note.key && dueWithin(it, now, SOON_MS) }) return
        show(note)
    }

    /** A plain repeat of a staff alert iOS already rings again every minute: the copy iOS shows says the same. */
    private suspend fun ringsAlready(post: AlertPost): Boolean =
        post.repeat && pending().any { it.identifier == ScheduledAlert.repeat(post.incident.incidentId) }

    private suspend fun remember(post: AlertPost, note: Note) {
        said[post.incident.incidentId] = note
        show(note)
    }

    private suspend fun add(request: UNNotificationRequest) = suspendCancellableCoroutine { done ->
        center.addNotificationRequest(request) { error ->
            if (error != null) log("notification ${request.identifier} not posted: ${error.localizedDescription}")
            done.resume(Unit)
        }
    }

    private fun remove(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }

    private suspend fun delivered(): List<UNNotification> = suspendCancellableCoroutine { done ->
        center.getDeliveredNotificationsWithCompletionHandler { list -> done.resume(list.orEmpty().filterIsInstance<UNNotification>()) }
    }

    private suspend fun pending(): List<UNNotificationRequest> = suspendCancellableCoroutine { done ->
        center.getPendingNotificationRequestsWithCompletionHandler { list -> done.resume(list.orEmpty().filterIsInstance<UNNotificationRequest>()) }
    }

    private fun keyOf(request: UNNotificationRequest): String? = request.content.userInfo[KEY] as? String

    private fun untilOf(notification: UNNotification): Long? = (notification.request.content.userInfo[UNTIL] as? String)?.toLongOrNull()

    private fun dueWithin(request: UNNotificationRequest, nowMs: Long, withinMs: Long): Boolean {
        val next = when (val trigger = request.trigger) {
            is UNTimeIntervalNotificationTrigger -> trigger.nextTriggerDate()
            is UNCalendarNotificationTrigger -> trigger.nextTriggerDate()
            else -> null
        } ?: return false
        return next.timeIntervalSince1970 * 1000 - nowMs <= withinMs
    }

    companion object {
        /** The notification's info: what tells two of one id apart, and when it leaves the screen (wall time, ms). */
        const val KEY = "key"
        const val UNTIL = "until"

        /** Off by default: the build turns them on only once Apple grants the entitlement (APP_STORE.md). */
        val criticalAlerts: Boolean by lazy {
            when (val flag = NSBundle.mainBundle.objectForInfoDictionaryKey("PingUpCriticalAlerts")) {
                is String -> flag.equals("YES", ignoreCase = true) || flag == "1" || flag.equals("true", ignoreCase = true)
                is NSNumber -> flag.boolValue
                is Boolean -> flag
                else -> false
            }
        }

        private const val CLOSED = "closed"
        private const val BLUETOOTH = "bluetooth-off"
        private const val CLOSED_WAIT_NS = 2_000_000_000L
        private const val IDLE_WAIT_MS = 5_000L

        /** What iOS will show within this long counts as shown: the app's own timer and iOS's differ by moments. */
        private const val SOON_MS = 5_000L

        /** A share reminder that showed this recently is the one the app would post. */
        private const val SHARE_SEEN_MS = 30 * 60_000L

        /** iOS repeats nothing more often. */
        private const val MIN_REPEAT_S = 60.0

        fun chat(conversation: String) = "chat-$conversation"
    }
}
