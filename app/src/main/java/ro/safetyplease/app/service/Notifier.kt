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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ro.safetyplease.app.MainActivity
import ro.safetyplease.app.R
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.MsgKind
import ro.safetyplease.app.data.MyReport
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.Severity
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.venue.Venue

// Fiecare notify() e precedat de canPost(); lint nu vede verificarea de permisiune printr-o functie ajutatoare.
@SuppressLint("MissingPermission")
class Notifier(private val context: Context, private val venue: Venue) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESH, context.getString(R.string.channel_mesh), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_URGENT, context.getString(R.string.channel_urgent), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                    },
                NotificationChannel(CHANNEL_INCIDENT, context.getString(R.string.channel_incident), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_CHAT, context.getString(R.string.channel_chat), NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
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

    fun foreground(links: Int, anchor: Boolean): Notification {
        val text = when {
            links == 0 -> context.getString(R.string.notif_mesh_searching)
            else -> context.resources.getQuantityString(R.plurals.notif_mesh_links, links, links)
        }
        return NotificationCompat.Builder(context, CHANNEL_MESH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (anchor) R.string.notif_mesh_title_anchor else R.string.notif_mesh_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(MainActivity.OPEN_HOME, 0))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateForeground(links: Int, anchor: Boolean) {
        if (canPost()) manager.notify(FOREGROUND_ID, foreground(links, anchor))
    }

    fun staffAlert(incident: StaffIncident) {
        if (!canPost()) return
        val urgent = incident.severity == Severity.URGENT
        val zone = if (incident.zone.isEmpty()) context.getString(R.string.zone_unknown) else venue.zoneName(incident.zone)
        val title = context.getString(
            R.string.notif_incident_title,
            context.getString(Labels.category(incident.category)),
            context.getString(Labels.urgency(incident.severity)),
        )
        val builder = NotificationCompat.Builder(context, if (urgent) CHANNEL_URGENT else CHANNEL_INCIDENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (incident.description.isEmpty()) zone else "$zone · ${incident.description}")
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_INCIDENTS, incident.incidentId.hashCode()))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (urgent) builder.setCategory(NotificationCompat.CATEGORY_ALARM).setDefaults(NotificationCompat.DEFAULT_SOUND)
        manager.notify(incident.incidentId.hashCode(), builder.build())
    }

    fun cancelStaffAlert(incidentId: String) = manager.cancel(incidentId.hashCode())

    fun reportUpdate(report: MyReport) {
        if (!canPost()) return
        val text = when (report.status) {
            AckStatus.RECEIVED -> context.getString(R.string.status_received)
            AckStatus.ACKNOWLEDGED -> context.getString(R.string.status_acknowledged, report.teamName)
            AckStatus.RESOLVED -> context.getString(R.string.status_resolved)
            else -> return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_report_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(MainActivity.OPEN_REPORT, 1))
            .build()
        manager.notify(report.incidentId.hashCode() xor 0x5a5a, notification)
    }

    fun chatMessage(message: ChatMessage, senderName: String) {
        if (!canPost()) return
        val text = when (message.kind) {
            MsgKind.TEXT -> message.text
            MsgKind.QUICK -> context.getString(Labels.quick(message.quickCode))
            MsgKind.ZONE -> context.getString(R.string.msg_zone, venue.zoneName(message.zone))
            MsgKind.SYSTEM -> context.getString(R.string.msg_group_invite, senderName)
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

    fun cancelConversation(conversation: String) = manager.cancel(conversation.hashCode())

    companion object {
        const val FOREGROUND_ID = 1
        private const val CHANNEL_MESH = "mesh"
        private const val CHANNEL_URGENT = "incident_urgent"
        private const val CHANNEL_INCIDENT = "incident"
        private const val CHANNEL_STATUS = "report_status"
        private const val CHANNEL_CHAT = "chat"
    }
}
