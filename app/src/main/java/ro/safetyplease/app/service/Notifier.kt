package ro.safetyplease.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ro.safetyplease.app.Emergency
import ro.safetyplease.app.MainActivity
import ro.safetyplease.app.R
import ro.safetyplease.app.text.text
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeAlarm
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
import ro.safetyplease.core.position.SharingNow
import ro.safetyplease.core.session.Notifications
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.ui.text.Replies

/**
 * The app's notifications. In an [exercise] every title says so and no alert sounds the alarm: the insistent level
 * vibrates only.
 */
// Every notify() is preceded by canPost(); lint can't see a permission check made in a helper.
@SuppressLint("MissingPermission")
class Notifier(private val context: Context, private val venue: Venue, private val exercise: Boolean) : Notifications {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESH, context.getString(R.string.channel_mesh), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                // the console and role leads: the alarm sound, through Do Not Disturb where the user allows it
                NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
                        enableVibration(true)
                        vibrationPattern = URGENT_VIBRATION
                        setBypassDnd(true)
                    },
                // the field: assignments and urgencies in the zone are felt, not heard
                NotificationChannel(CHANNEL_VIBRATE, context.getString(R.string.channel_vibrate), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        setSound(null, null)
                        enableVibration(true)
                        vibrationPattern = URGENT_VIBRATION
                    },
                NotificationChannel(CHANNEL_INCIDENT, context.getString(R.string.channel_incident), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_CHAT, context.getString(R.string.channel_chat), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SHARING, context.getString(R.string.channel_sharing_reminder), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_PROGRAM, context.getString(R.string.layers_channel_program), NotificationManager.IMPORTANCE_DEFAULT),
                // the organiser's notices, apart from the incident alarm: a pause and an evacuation sound and feel different
                NotificationChannel(CHANNEL_NOTICE, context.getString(R.string.channel_notice), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        enableVibration(true)
                        vibrationPattern = NOTICE_VIBRATION
                    },
                noticeAlarm(CHANNEL_NOTICE_PAUSE, R.string.channel_notice_pause, PAUSE_VIBRATION),
                noticeAlarm(CHANNEL_NOTICE_EVACUATION, R.string.channel_notice_evacuation, EVACUATION_VIBRATION),
            )
        )
        // it sounded on every staff phone; its alerts now go to the two channels above
        system.deleteNotificationChannel(OLD_CHANNEL_URGENT)
    }

    private fun noticeAlarm(id: String, name: Int, vibration: LongArray) =
        NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_HIGH).apply {
            val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
            enableVibration(true)
            vibrationPattern = vibration
            setBypassDnd(true)
        }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(target: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun marked(title: String): String = if (exercise) context.getString(R.string.exercise_title, title) else title

    /** While [sharing] runs the notification says so first, with "Oprește", which ends every share without a word to anyone. */
    fun foreground(links: Int, anchor: Boolean, sharing: SharingNow? = null): Notification {
        val text = when {
            links == 0 -> context.getString(R.string.notif_mesh_searching)
            else -> context.resources.getQuantityString(R.plurals.notif_mesh_links, links, links)
        }
        val title = if (sharing != null) sharingText(sharing) else context.getString(if (anchor) R.string.notif_mesh_title_anchor else R.string.notif_mesh_title)
        val builder = NotificationCompat.Builder(context, CHANNEL_MESH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(marked(title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(if (sharing != null) MainActivity.OPEN_SHARING else MainActivity.OPEN_HOME, RequestCodes.OPEN_HOME))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (sharing != null) {
            val stop = PendingIntent.getService(
                context, RequestCodes.STOP_SHARING, MeshService.stopSharingIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_notification, context.getString(R.string.sharing_stop), stop)
        }
        // an anchor only relays; its screen has no report tab, and nobody passing by should be able to switch it off
        if (!anchor) {
            val report = open(MainActivity.OPEN_REPORT, RequestCodes.REPORT_ACTION)
            builder.addAction(R.drawable.ic_report, context.getString(R.string.action_report), report)
            val stop = PendingIntent.getService(
                context, RequestCodes.STOP_MESH, Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.notif_mesh_stop), stop)
        }
        return builder.build()
    }

    fun updateForeground(links: Int, anchor: Boolean, sharing: SharingNow?) {
        if (canPost()) manager.notify(FOREGROUND_ID, foreground(links, anchor, sharing))
    }

    private fun sharingText(sharing: SharingNow): String {
        val people = sharing.people
        val until = sharing.endsAtMs ?: return context.resources.getQuantityString(R.plurals.sharing_bar, people, people)
        return context.resources.getQuantityString(R.plurals.sharing_until, people, people, Labels.clock(until))
    }

    /** Every 4 hours of a share without an end: "partajezi locația cu N prieteni". */
    override fun shareReminder(people: Int) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.sharing_bar, people, people))
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_SHARING, RequestCodes.SHARING_REMINDER))
            .build()
        manager.notify(SHARING_REMINDER_ID, notification)
    }

    /** "Amintește-mi": the set starts soon, and with the walk it is time to leave. */
    override fun setReminder(set: ScheduleItem, stage: String, walkMin: Int) {
        if (!canPost()) return
        val id = "set:${set.stage}@${set.start}".hashCode()
        val text = if (walkMin > 0) context.getString(R.string.layers_reminder_walk, stage, walkMin) else stage
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRAM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.layers_reminder_title, set.title, Labels.clock(set.start * 1000)))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_HOME, id))
            .setTimeoutAfter(maxOf(set.end * 1000 - System.currentTimeMillis(), 1))
            .build()
        manager.notify(id, notification)
    }

    /** The mesh couldn't come back on its own. Same id as the running notification, which replaces it on restart. */
    fun meshStopped() {
        if (!canPost()) return
        // the mesh channel is silent; this one has to be noticed
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_mesh_stopped_title, context.getString(R.string.app_name)))
            .setContentText(context.getString(R.string.notif_mesh_stopped_text))
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_HOME, RequestCodes.MESH_STOPPED))
            .build()
        manager.notify(FOREGROUND_ID, notification)
    }

    /** The phone's shift certificate ran out, so it went back to being a participant's. */
    override fun shiftEnded() {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_shift_ended_title))
            .setContentText(context.getString(R.string.notif_shift_ended_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_shift_ended_text)))
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_HOME, RequestCodes.SHIFT_ENDED))
            .build()
        manager.notify(SHIFT_ENDED_ID, notification)
    }

    /** A staff alert as [ro.safetyplease.core.incidents.AlertPlanner] decided it: the channel follows the level. */
    override fun staffAlert(post: AlertPost) {
        if (!canPost()) return
        val incident = post.incident
        val zone = if (incident.zone.isEmpty()) context.getString(R.string.zone_unknown) else venue.zoneName(incident.zone)
        val category = context.text(Labels.category(incident.category))
        val title = when (post.route.reason) {
            AlertReason.ASSIGNED -> context.getString(R.string.notif_assigned_title, category)
            AlertReason.UNACCEPTED -> context.getString(R.string.notif_unaccepted_title, incident.teamName, category)
            AlertReason.ROLE, AlertReason.EVERYONE -> context.getString(R.string.notif_widened_title, category)
            AlertReason.ESCALATED -> context.getString(R.string.notif_escalated_title, category)
            AlertReason.RESPONDER -> context.getString(R.string.notif_responder_title, zone)
            else -> context.getString(R.string.notif_incident_title, category, context.text(Labels.urgency(incident.severity)))
        }
        val level = post.route.level
        val channel = when (level) {
            AlertLevel.INSISTENT -> if (exercise) CHANNEL_VIBRATE else CHANNEL_ALARM
            AlertLevel.VIBRATE -> CHANNEL_VIBRATE
            else -> CHANNEL_INCIDENT
        }
        val id = incident.incidentId.hashCode()
        val open = open(MainActivity.OPEN_INCIDENTS, id)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(marked(title))
            .setContentText(if (incident.description.isEmpty()) zone else "$zone · ${incident.description}")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (level == AlertLevel.NOTIFY) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(post.silent)
        if (level == AlertLevel.INSISTENT && !exercise) builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        // an exercise never takes over the screen as a live alarm does
        if (post.fullScreen && !exercise) builder.setFullScreenIntent(open, true)
        manager.notify(id, builder.build())
    }

    override fun cancelStaffAlert(incidentId: String) = manager.cancel(incidentId.hashCode())

    /** Someone doesn't breathe nearby: how far, and the defibrillator to fetch on the way, under the incident's own id. */
    override fun responderAlert(post: AlertPost, target: ResponderTarget?) {
        if (!canPost()) return
        val incident = post.incident
        val title = target?.direct?.let { context.getString(R.string.responder_distance, it) }
            ?.let { "${context.getString(R.string.responder_title)} · $it" } ?: context.getString(R.string.responder_title)
        val deaName = target?.dea?.name?.ifEmpty { null } ?: context.getString(R.string.responder_dea_name)
        val toDea = target?.toDea
        val deaToPatient = target?.deaToPatient
        val text = when {
            target?.dea == null || deaToPatient == null -> context.getString(R.string.responder_no_dea)
            toDea != null -> context.getString(R.string.responder_dea, deaName, toDea, deaToPatient)
            else -> context.getString(R.string.responder_dea_patient, deaName, deaToPatient)
        }
        val id = incident.incidentId.hashCode()
        val open = open(MainActivity.OPEN_INCIDENTS, id)
        val builder = NotificationCompat.Builder(context, if (exercise) CHANNEL_VIBRATE else CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(marked(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(post.silent)
        if (!exercise) builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        if (post.fullScreen && !exercise) builder.setFullScreenIntent(open, true)
        manager.notify(id, builder.build())
    }

    /**
     * "Ești OK?": the alarm sound again and again until an answer or [untilMs]; tapping it opens the question. In an
     * exercise it neither takes over the screen nor rings as an alarm. False when notifications aren't allowed.
     */
    override fun manDownCheck(untilMs: Long): Boolean {
        if (!canPost() || !manager.areNotificationsEnabled()) return false
        val open = open(MainActivity.OPEN_HOME, RequestCodes.MAN_DOWN)
        val builder = NotificationCompat.Builder(context, if (exercise) CHANNEL_VIBRATE else CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(marked(context.getString(R.string.mandown_title)))
            .setContentText(context.getString(R.string.mandown_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setTimeoutAfter((untilMs - System.currentTimeMillis()).coerceAtLeast(1_000))
        if (!exercise) builder.setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(open, true)
        val notification = builder.build().apply { if (!exercise) flags = flags or Notification.FLAG_INSISTENT }
        manager.notify(MAN_DOWN_ID, notification)
        return true
    }

    override fun cancelManDownCheck() = manager.cancel(MAN_DOWN_ID)

    /**
     * Status of your report, in the same words as its screen. An urgent report nobody on staff has confirmed in time
     * tells you to look for help in person and offers to call 112.
     */
    override fun reportUpdate(report: MyReport, nowMs: Long) {
        if (!canPost()) return
        // only the acknowledged status names the team; the others leave the argument out
        val status = Labels.myReportStatus(report.status, report.teamName.isNotEmpty())?.let { context.text(it, report.teamName) }
        val escalate = report.needsEscalation(nowMs)
        if (status == null && !escalate) return
        val id = report.incidentId.hashCode() xor 0x5a5a
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_REPORT_PREFIX + report.incidentId, id))
        if (escalate) {
            val advice = context.getString(R.string.report_escalate_text)
            val call = PendingIntent.getActivity(
                context, id, Emergency.dialIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentTitle(marked(context.getString(R.string.report_escalate_title)))
                .setContentText(advice)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(advice, status).joinToString("\n")))
                .addAction(R.drawable.ic_phone, context.getString(R.string.emergency_call), call)
        } else {
            builder.setContentTitle(marked(context.getString(R.string.notif_report_title))).setContentText(status)
        }
        // a discreet report is answered as quietly as it was sent: no sound, no vibration, nothing on the lock screen
        if (Discreet.isDiscreet(report.description)) builder.setSilent(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        manager.notify(id, builder.build())
    }

    /**
     * The console's latest answer to your report, in the foreground too: it is what you are waiting for, so it sounds
     * and vibrates on the status channel.
     */
    override fun reportResponse(report: MyReport) {
        val response = report.responses.lastOrNull() ?: return
        if (!canPost()) return
        val id = report.incidentId.hashCode() xor 0x2b2b
        val text = context.text(Replies.response(response.code), Replies.landmarkName(venue, response.landmark))
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_response_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_REPORT_PREFIX + report.incidentId, id))
            .build()
        manager.notify(id, notification)
    }

    override fun chatMessage(message: ChatMessage, senderName: String) {
        if (!canPost()) return
        val text = when (message.kind) {
            MsgKind.TEXT -> message.text
            MsgKind.QUICK -> context.text(Labels.quick(message.quickCode))
            MsgKind.ZONE -> context.getString(R.string.msg_zone, venue.zoneName(message.zone))
            MsgKind.SYSTEM -> context.text(Labels.systemNotice(message.conversation), senderName)
            MsgKind.LEFT -> context.getString(R.string.msg_group_left, message.text)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_CHAT_PREFIX + message.conversation, message.conversation.hashCode()))
            .build()
        manager.notify(message.conversation.hashCode(), notification)
    }

    override fun cancelConversation(conversation: String) = manager.cancel(conversation.hashCode())

    /**
     * A notice from the organiser, one notification per topic: a newer one replaces it, a withdrawn one takes it away.
     * A pause or an evacuation for this phone takes the screen once and sounds; elsewhere, or in an exercise, it only
     * vibrates on the quiet channel.
     */
    override fun notice(notice: Notice, title: String, text: String, alarm: NoticeAlarm) {
        val id = NOTICE_ID + notice.topic
        if (notice.cancelled) return manager.cancel(id)
        if (!canPost()) return
        val urgent = alarm == NoticeAlarm.LOUD
        val channel = when {
            !urgent -> CHANNEL_NOTICE
            notice.level == NoticeLevel.EVACUATION -> CHANNEL_NOTICE_EVACUATION
            else -> CHANNEL_NOTICE_PAUSE
        }
        val open = open(MainActivity.OPEN_HOME, id)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(marked(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setTimeoutAfter(maxOf(notice.validUntil * 1000 - System.currentTimeMillis(), 1))
        if (urgent) builder.setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(open, true)
        manager.notify(id, builder.build())
    }

    companion object {
        const val FOREGROUND_ID = 1
        private const val SHIFT_ENDED_ID = 2
        private const val CHANNEL_MESH = "mesh"
        private const val OLD_CHANNEL_URGENT = "incident_urgent"
        private const val CHANNEL_ALARM = "incident_alarm"
        private const val CHANNEL_VIBRATE = "incident_vibrate"
        private const val CHANNEL_INCIDENT = "incident"
        private const val CHANNEL_STATUS = "report_status"
        private const val CHANNEL_CHAT = "chat"
        private const val CHANNEL_SHARING = "sharing_reminder"
        private const val SHARING_REMINDER_ID = 3
        private const val CHANNEL_PROGRAM = "program"
        private const val MAN_DOWN_ID = 4
        private const val NOTICE_ID = 100
        private const val CHANNEL_NOTICE = "notice"
        private const val CHANNEL_NOTICE_PAUSE = "notice_pause"
        private const val CHANNEL_NOTICE_EVACUATION = "notice_evacuation"
        private val URGENT_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 600)
        private val NOTICE_VIBRATION = longArrayOf(0, 120, 120, 120)
        private val PAUSE_VIBRATION = longArrayOf(0, 700, 300, 700)
        private val EVACUATION_VIBRATION = longArrayOf(0, 1000, 200, 1000, 200, 1000, 200, 1000)
    }
}
