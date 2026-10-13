package ro.safetyplease.ui.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeAlarm
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.MsgKind
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertLevel
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.AlertReason
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.needsEscalation
import ro.safetyplease.core.session.Notifications
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.exercise_title
import ro.safetyplease.ui.resources.layers_reminder_title
import ro.safetyplease.ui.resources.layers_reminder_walk
import ro.safetyplease.ui.resources.msg_group_left
import ro.safetyplease.ui.resources.msg_zone
import ro.safetyplease.ui.resources.notif_assigned_title
import ro.safetyplease.ui.resources.notif_escalated_title
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
import ro.safetyplease.ui.resources.sharing_bar
import ro.safetyplease.ui.resources.zone_unknown
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.Replies

/**
 * The app's notifications on an iPhone, saying what the Android ones say. In an [exercise] every title says so and
 * nothing breaks through Focus. The texts are read off the main thread, so each one is posted a moment later.
 */
class IosNotifications(private val venue: Venue, private val exercise: Boolean, private val appName: String) : Notifications {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun shiftEnded() = later {
        post(SHIFT_ENDED, getString(Res.string.notif_shift_ended_title), getString(Res.string.notif_shift_ended_text), OpenTarget.HOME)
    }

    /** A location share without an end still runs: who sees the phone is a tap away. */
    override fun shareReminder(people: Int) = later {
        post(SHARING, getPluralString(Res.plurals.sharing_bar, people, people), "", OpenTarget.SHARING)
    }

    /** "Amintește-mi": the set starts soon, and with the walk it is time to leave. */
    override fun setReminder(set: ScheduleItem, stage: String, walkMin: Int) = later {
        val text = if (walkMin > 0) getString(Res.string.layers_reminder_walk, stage, walkMin) else stage
        val title = getString(Res.string.layers_reminder_title, set.title, Labels.clock(set.start * 1000))
        post("set-${set.stage}@${set.start}", title, text, OpenTarget.HOME)
    }

    /** A staff alert as the alert planner decided it: one that has to be heard breaks through Focus, unless it's a drill. */
    override fun staffAlert(post: AlertPost) = later {
        val incident = post.incident
        val zone = if (incident.zone.isEmpty()) getString(Res.string.zone_unknown) else venue.zoneName(incident.zone)
        val category = getString(Labels.category(incident.category))
        val title = when (post.route.reason) {
            AlertReason.ASSIGNED -> getString(Res.string.notif_assigned_title, category)
            AlertReason.UNACCEPTED -> getString(Res.string.notif_unaccepted_title, incident.teamName, category)
            AlertReason.ROLE, AlertReason.EVERYONE -> getString(Res.string.notif_widened_title, category)
            AlertReason.ESCALATED -> getString(Res.string.notif_escalated_title, category)
            AlertReason.RESPONDER -> getString(Res.string.notif_responder_title, zone)
            else -> getString(Res.string.notif_incident_title, category, getString(Labels.urgency(incident.severity)))
        }
        val insistent = post.route.level == AlertLevel.INSISTENT && !exercise
        post(
            alert(incident.incidentId), marked(title), if (incident.description.isEmpty()) zone else "$zone · ${incident.description}",
            OpenTarget.INCIDENTS, if (insistent) Level.TIME_SENSITIVE else Level.ACTIVE, sound = !post.silent,
        )
    }

    override fun cancelStaffAlert(incidentId: String) = cancel(alert(incidentId))

    /**
     * Status of your report, in the words of its screen. An urgent report nobody on staff has confirmed in time tells
     * you to look for help in person, with 112 a tap away.
     */
    override fun reportUpdate(report: MyReport, nowMs: Long) = later {
        // only the acknowledged status names the team; the others leave the argument out
        val status = Labels.myReportStatus(report.status, report.teamName.isNotEmpty())?.let { getString(it, report.teamName) }
        val escalate = report.needsEscalation(nowMs)
        if (status == null && !escalate) return@later
        val id = "report-${report.incidentId}"
        val open = OpenTarget.REPORT_PREFIX + report.incidentId
        when {
            // a discreet report is answered as quietly as it was sent, and in front of others it says nothing at all
            Discreet.isDiscreet(report.description) -> post(id, appName, "", open, Level.PASSIVE, sound = false)
            escalate -> post(
                id, marked(getString(Res.string.report_escalate_title)), listOfNotNull(getString(Res.string.report_escalate_text), status).joinToString("\n"),
                open, category = IosApp.CALL_112,
            )
            else -> post(id, marked(getString(Res.string.notif_report_title)), status.orEmpty(), open)
        }
    }

    /** The console's latest answer to your report: what you are waiting for, so it sounds in the foreground too. */
    override fun reportResponse(report: MyReport) = later {
        val response = report.responses.lastOrNull() ?: return@later
        val text = getString(Replies.response(response.code), Replies.landmarkName(venue, response.landmark))
        post("response-${report.incidentId}", getString(Res.string.notif_response_title), text, OpenTarget.REPORT_PREFIX + report.incidentId)
    }

    override fun chatMessage(message: ChatMessage, senderName: String) = later {
        val text = when (message.kind) {
            MsgKind.TEXT -> message.text
            MsgKind.QUICK -> getString(Labels.quick(message.quickCode))
            MsgKind.ZONE -> getString(Res.string.msg_zone, venue.zoneName(message.zone))
            MsgKind.SYSTEM -> getString(Labels.systemNotice(message.conversation), senderName)
            MsgKind.LEFT -> getString(Res.string.msg_group_left, message.text)
        }
        post(chat(message.conversation), senderName, text, OpenTarget.CHAT_PREFIX + message.conversation, thread = message.conversation)
    }

    override fun cancelConversation(conversation: String) = cancel(chat(conversation))

    /**
     * A notice from the organiser, one per topic: a newer one replaces it, a withdrawn one takes it away. A pause or an
     * evacuation for this phone breaks through Focus; elsewhere, or in an exercise, it doesn't.
     */
    override fun notice(notice: Notice, title: String, text: String, alarm: NoticeAlarm) {
        val id = "notice-${notice.topic}"
        if (notice.cancelled) return cancel(id)
        later { post(id, marked(title), text, OpenTarget.HOME, if (alarm == NoticeAlarm.LOUD) Level.TIME_SENSITIVE else Level.ACTIVE) }
    }

    private fun later(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private suspend fun marked(title: String) = if (exercise) getString(Res.string.exercise_title, title) else title

    private enum class Level(val ios: UNNotificationInterruptionLevel) {
        PASSIVE(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive),
        ACTIVE(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive),

        /** Through Focus, where the app is allowed to; the person can turn it off in Settings. */
        TIME_SENSITIVE(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive),
    }

    private fun post(
        id: String,
        title: String,
        body: String,
        open: String,
        level: Level = Level.ACTIVE,
        sound: Boolean = true,
        thread: String? = null,
        category: String? = null,
    ) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        content.setUserInfo(mapOf<Any?, Any?>(IosApp.OPEN to open))
        content.setInterruptionLevel(level.ios)
        if (sound) content.setSound(UNNotificationSound.defaultSound)
        thread?.let(content::setThreadIdentifier)
        category?.let(content::setCategoryIdentifier)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id, content, null), null)
    }

    private fun cancel(id: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    private companion object {
        const val SHIFT_ENDED = "shift-ended"
        const val SHARING = "sharing"

        fun alert(incidentId: String) = "alert-$incidentId"

        fun chat(conversation: String) = "chat-$conversation"
    }
}
