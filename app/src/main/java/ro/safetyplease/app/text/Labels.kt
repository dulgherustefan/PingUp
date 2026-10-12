package ro.safetyplease.app.text

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.delay
import ro.safetyplease.app.R
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.QuickCode
import ro.safetyplease.core.protocol.Severity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Maps protocol constants to strings.xml. */
object Labels {
    @StringRes
    fun category(category: Int): Int = when (category) {
        IncidentCategory.MEDICAL -> R.string.cat_medical
        IncidentCategory.VIOLENCE -> R.string.cat_violence
        IncidentCategory.LOST_CHILD -> R.string.cat_lost_child
        IncidentCategory.LOST_FRIEND -> R.string.cat_lost_friend
        IncidentCategory.HARASSMENT -> R.string.cat_harassment
        IncidentCategory.CROWD -> R.string.cat_crowd
        IncidentCategory.FIRE -> R.string.cat_fire
        else -> R.string.cat_other
    }

    @StringRes
    fun categoryExample(category: Int): Int = when (category) {
        IncidentCategory.MEDICAL -> R.string.cat_medical_example
        IncidentCategory.VIOLENCE -> R.string.cat_violence_example
        IncidentCategory.LOST_CHILD -> R.string.cat_lost_child_example
        IncidentCategory.LOST_FRIEND -> R.string.cat_lost_friend_example
        IncidentCategory.HARASSMENT -> R.string.cat_harassment_example
        IncidentCategory.CROWD -> R.string.cat_crowd_example
        IncidentCategory.FIRE -> R.string.cat_fire_example
        else -> R.string.cat_other_example
    }

    @StringRes
    fun urgency(severity: Int): Int = if (severity == Severity.URGENT) R.string.sev_urgent else R.string.sev_normal

    /** A notice in a group means we were invited; in a friend chat, that they added us by our code. */
    @StringRes
    fun systemNotice(conversation: String): Int =
        if (Conversations.isGroup(conversation)) R.string.msg_group_invite else R.string.msg_friend_added

    @StringRes
    fun answer(answer: Int): Int = when (answer) {
        Answer.YES -> R.string.answer_yes
        Answer.NO -> R.string.answer_no
        Answer.UNKNOWN -> R.string.answer_unknown
        else -> R.string.answer_unasked
    }

    @StringRes
    fun quick(code: Int): Int = when (code) {
        QuickCode.WHERE_ARE_YOU -> R.string.quick_where
        QuickCode.COMING -> R.string.quick_coming
        QuickCode.LOW_BATTERY -> R.string.quick_battery
        else -> R.string.quick_meet
    }

    /**
     * Your report's status, claiming no more than we know: RECEIVED comes automatically from the first staff phone,
     * so only ACKNOWLEDGED says a person saw it. Null when there's nothing to tell.
     */
    @StringRes
    fun myReportStatus(status: Int, hasTeam: Boolean): Int? = when (status) {
        AckStatus.RECEIVED -> R.string.status_received_full
        AckStatus.ACKNOWLEDGED -> if (hasTeam) R.string.status_acknowledged else R.string.status_acknowledged_pending
        AckStatus.RESOLVED -> R.string.status_resolved
        AckStatus.ON_SCENE -> R.string.status_on_scene
        AckStatus.HANDED_OVER -> R.string.status_handed_over
        AckStatus.CLOSED_BY_CONTROL -> R.string.status_closed_by_control
        AckStatus.UNFOUNDED -> R.string.status_unfounded
        AckStatus.REOPENED -> R.string.status_reopened
        else -> null
    }

    fun staffStatus(context: Context, status: Int, team: String): String = when (status) {
        AckStatus.CANCELLED -> context.getString(R.string.staff_status_cancelled)
        AckStatus.RESOLVED -> context.getString(R.string.staff_status_resolved, team)
        AckStatus.ACKNOWLEDGED -> context.getString(R.string.staff_status_taken, team)
        AckStatus.ON_SCENE -> context.getString(R.string.staff_status_on_scene, team)
        AckStatus.HANDED_OVER -> context.getString(R.string.staff_status_handed_over, team)
        AckStatus.CLOSED_BY_CONTROL -> context.getString(R.string.staff_status_closed)
        AckStatus.UNFOUNDED -> context.getString(R.string.control_state_unfounded)
        AckStatus.REOPENED -> context.getString(R.string.control_state_reopened)
        else -> context.getString(R.string.staff_status_new)
    }

    fun clock(timeMs: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(timeMs))

    fun date(resources: Resources, timeMs: Long): String = format(resources, R.string.date_pattern_full, timeMs)

    /**
     * "5 min ago". The system formatter would answer in the phone's language, which may not be the app's.
     * [resources] must come from a context wrapped by AppLocale.
     */
    fun ago(resources: Resources, timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val minutes = ((nowMs - timeMs) / 60_000L).toInt()
        return when {
            minutes < 1 -> resources.getString(R.string.time_now)
            minutes < 60 -> resources.getQuantityString(R.plurals.time_minutes_ago, minutes, minutes)
            minutes < 24 * 60 -> resources.getQuantityString(R.plurals.time_hours_ago, minutes / 60, minutes / 60)
            else -> format(resources, R.string.date_pattern_recent, timeMs)
        }
    }

    /** Time in the conversation list: "Acum", minutes within the hour, today's time, weekday, then the date. */
    fun listTime(resources: Resources, timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val minutes = ((nowMs - timeMs) / 60_000L).toInt()
        return when {
            minutes < 1 -> resources.getString(R.string.time_now_short)
            minutes < 60 -> resources.getString(R.string.time_minutes_short, minutes)
            daysBetween(timeMs, nowMs) == 0 -> clock(timeMs)
            daysBetween(timeMs, nowMs) < 6 -> SimpleDateFormat("EEE", locale(resources)).format(Date(timeMs))
            else -> format(resources, R.string.date_pattern_short, timeMs)
        }
    }

    /** Day header in a conversation: "Azi", "Ieri" or the date. */
    fun dayLabel(resources: Resources, timeMs: Long, nowMs: Long = System.currentTimeMillis()): String = when (daysBetween(timeMs, nowMs)) {
        0 -> resources.getString(R.string.date_today)
        1 -> resources.getString(R.string.date_yesterday)
        else -> format(resources, R.string.date_pattern_day, timeMs)
    }

    private fun format(resources: Resources, @StringRes pattern: Int, timeMs: Long): String =
        SimpleDateFormat(resources.getString(pattern), locale(resources)).format(Date(timeMs))

    private fun locale(resources: Resources): Locale = resources.configuration.locales[0]

    /** Midnights between two moments, on the phone's clock. */
    fun daysBetween(earlierMs: Long, laterMs: Long): Int {
        fun midnight(ms: Long) = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((midnight(laterMs) - midnight(earlierMs) + 3_600_000L) / 86_400_000L).toInt()
    }

    /** One hop means a direct link; the rest are the phones the packet went through. */
    fun hops(resources: Resources, hops: Int): String =
        if (hops <= 1) resources.getString(R.string.hop_direct)
        else resources.getQuantityString(R.plurals.via_phones, hops - 1, hops - 1)
}

/** Current time, refreshed while the screen is shown so "acum 5 min" doesn't freeze. */
@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun agoText(timeMs: Long, nowMs: Long): String = Labels.ago(LocalResources.current, timeMs, nowMs)

@Composable
fun hopsText(hops: Int): String = Labels.hops(LocalResources.current, hops)

@Composable
fun listTime(timeMs: Long, nowMs: Long): String = Labels.listTime(LocalResources.current, timeMs, nowMs)
