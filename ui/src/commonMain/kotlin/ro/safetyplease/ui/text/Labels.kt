package ro.safetyplease.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.QuickCode
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.answer_no
import ro.safetyplease.ui.resources.answer_unasked
import ro.safetyplease.ui.resources.answer_unknown
import ro.safetyplease.ui.resources.answer_yes
import ro.safetyplease.ui.resources.cat_crowd
import ro.safetyplease.ui.resources.cat_crowd_example
import ro.safetyplease.ui.resources.cat_fire
import ro.safetyplease.ui.resources.cat_fire_example
import ro.safetyplease.ui.resources.cat_harassment
import ro.safetyplease.ui.resources.cat_harassment_example
import ro.safetyplease.ui.resources.cat_lost_child
import ro.safetyplease.ui.resources.cat_lost_child_example
import ro.safetyplease.ui.resources.cat_lost_friend
import ro.safetyplease.ui.resources.cat_lost_friend_example
import ro.safetyplease.ui.resources.cat_medical
import ro.safetyplease.ui.resources.cat_medical_example
import ro.safetyplease.ui.resources.cat_other
import ro.safetyplease.ui.resources.cat_other_example
import ro.safetyplease.ui.resources.cat_violence
import ro.safetyplease.ui.resources.cat_violence_example
import ro.safetyplease.ui.resources.control_state_reopened
import ro.safetyplease.ui.resources.control_state_unfounded
import ro.safetyplease.ui.resources.date_pattern_day
import ro.safetyplease.ui.resources.date_pattern_full
import ro.safetyplease.ui.resources.date_pattern_recent
import ro.safetyplease.ui.resources.date_pattern_short
import ro.safetyplease.ui.resources.date_today
import ro.safetyplease.ui.resources.date_yesterday
import ro.safetyplease.ui.resources.hop_direct
import ro.safetyplease.ui.resources.msg_friend_added
import ro.safetyplease.ui.resources.msg_group_invite
import ro.safetyplease.ui.resources.quick_battery
import ro.safetyplease.ui.resources.quick_coming
import ro.safetyplease.ui.resources.quick_meet
import ro.safetyplease.ui.resources.quick_where
import ro.safetyplease.ui.resources.sev_normal
import ro.safetyplease.ui.resources.sev_urgent
import ro.safetyplease.ui.resources.staff_status_cancelled
import ro.safetyplease.ui.resources.staff_status_closed
import ro.safetyplease.ui.resources.staff_status_handed_over
import ro.safetyplease.ui.resources.staff_status_new
import ro.safetyplease.ui.resources.staff_status_on_scene
import ro.safetyplease.ui.resources.staff_status_resolved
import ro.safetyplease.ui.resources.staff_status_taken
import ro.safetyplease.ui.resources.status_acknowledged
import ro.safetyplease.ui.resources.status_acknowledged_pending
import ro.safetyplease.ui.resources.status_closed_by_control
import ro.safetyplease.ui.resources.status_handed_over
import ro.safetyplease.ui.resources.status_on_scene
import ro.safetyplease.ui.resources.status_received_full
import ro.safetyplease.ui.resources.status_reopened
import ro.safetyplease.ui.resources.status_resolved
import ro.safetyplease.ui.resources.status_unfounded
import ro.safetyplease.ui.resources.time_hours_ago
import ro.safetyplease.ui.resources.time_minutes_ago
import ro.safetyplease.ui.resources.time_minutes_short
import ro.safetyplease.ui.resources.time_now
import ro.safetyplease.ui.resources.time_now_short
import ro.safetyplease.ui.resources.via_phones
import kotlin.time.Clock
import kotlin.time.Instant

/** Maps protocol constants to the shared texts. */
object Labels {
    fun category(category: Int): StringResource = when (category) {
        IncidentCategory.MEDICAL -> Res.string.cat_medical
        IncidentCategory.VIOLENCE -> Res.string.cat_violence
        IncidentCategory.LOST_CHILD -> Res.string.cat_lost_child
        IncidentCategory.LOST_FRIEND -> Res.string.cat_lost_friend
        IncidentCategory.HARASSMENT -> Res.string.cat_harassment
        IncidentCategory.CROWD -> Res.string.cat_crowd
        IncidentCategory.FIRE -> Res.string.cat_fire
        else -> Res.string.cat_other
    }

    fun categoryExample(category: Int): StringResource = when (category) {
        IncidentCategory.MEDICAL -> Res.string.cat_medical_example
        IncidentCategory.VIOLENCE -> Res.string.cat_violence_example
        IncidentCategory.LOST_CHILD -> Res.string.cat_lost_child_example
        IncidentCategory.LOST_FRIEND -> Res.string.cat_lost_friend_example
        IncidentCategory.HARASSMENT -> Res.string.cat_harassment_example
        IncidentCategory.CROWD -> Res.string.cat_crowd_example
        IncidentCategory.FIRE -> Res.string.cat_fire_example
        else -> Res.string.cat_other_example
    }

    fun urgency(severity: Int): StringResource = if (severity == Severity.URGENT) Res.string.sev_urgent else Res.string.sev_normal

    /** A notice in a group means we were invited; in a friend chat, that they added us by our code. */
    fun systemNotice(conversation: String): StringResource =
        if (Conversations.isGroup(conversation)) Res.string.msg_group_invite else Res.string.msg_friend_added

    fun answer(answer: Int): StringResource = when (answer) {
        Answer.YES -> Res.string.answer_yes
        Answer.NO -> Res.string.answer_no
        Answer.UNKNOWN -> Res.string.answer_unknown
        else -> Res.string.answer_unasked
    }

    fun quick(code: Int): StringResource = when (code) {
        QuickCode.WHERE_ARE_YOU -> Res.string.quick_where
        QuickCode.COMING -> Res.string.quick_coming
        QuickCode.LOW_BATTERY -> Res.string.quick_battery
        else -> Res.string.quick_meet
    }

    /**
     * Your report's status, claiming no more than we know: RECEIVED comes automatically from the first staff phone,
     * so only ACKNOWLEDGED says a person saw it, and names the team as its argument. Null when there's nothing to tell.
     */
    fun myReportStatus(status: Int, hasTeam: Boolean): StringResource? = when (status) {
        AckStatus.RECEIVED -> Res.string.status_received_full
        AckStatus.ACKNOWLEDGED -> if (hasTeam) Res.string.status_acknowledged else Res.string.status_acknowledged_pending
        AckStatus.RESOLVED -> Res.string.status_resolved
        AckStatus.ON_SCENE -> Res.string.status_on_scene
        AckStatus.HANDED_OVER -> Res.string.status_handed_over
        AckStatus.CLOSED_BY_CONTROL -> Res.string.status_closed_by_control
        AckStatus.UNFOUNDED -> Res.string.status_unfounded
        AckStatus.REOPENED -> Res.string.status_reopened
        else -> null
    }

    /** A team's status as staff read it; the ones that name the team take it as their argument. */
    fun staffStatus(status: Int): StringResource = when (status) {
        AckStatus.CANCELLED -> Res.string.staff_status_cancelled
        AckStatus.RESOLVED -> Res.string.staff_status_resolved
        AckStatus.ACKNOWLEDGED -> Res.string.staff_status_taken
        AckStatus.ON_SCENE -> Res.string.staff_status_on_scene
        AckStatus.HANDED_OVER -> Res.string.staff_status_handed_over
        AckStatus.CLOSED_BY_CONTROL -> Res.string.staff_status_closed
        AckStatus.UNFOUNDED -> Res.string.control_state_unfounded
        AckStatus.REOPENED -> Res.string.control_state_reopened
        else -> Res.string.staff_status_new
    }

    /** "14:30" on the phone's clock. */
    fun clock(timeMs: Long): String {
        val t = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return two(t.hour) + ":" + two(t.minute)
    }

    /** Midnights between two moments, on the phone's clock. */
    fun daysBetween(earlierMs: Long, laterMs: Long): Int {
        val zone = TimeZone.currentSystemDefault()
        fun day(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
        return day(earlierMs).daysUntil(day(laterMs))
    }

    internal fun two(n: Int) = if (n < 10) "0$n" else n.toString()
}

/** Now, by the wall clock. */
fun wallNowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Current time, refreshed while the screen is shown so "acum 5 min" doesn't freeze. */
@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    val now by produceState(wallNowMs()) {
        while (true) {
            delay(periodMs)
            value = wallNowMs()
        }
    }
    return now
}

@Composable
fun staffStatusText(status: Int, team: String): String = stringResource(Labels.staffStatus(status), team)

/** "5 min ago", in the app's language whatever the phone's. */
@Composable
fun agoText(timeMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - timeMs) / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.time_now)
        minutes < 60 -> pluralStringResource(Res.plurals.time_minutes_ago, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(Res.plurals.time_hours_ago, minutes / 60, minutes / 60)
        else -> dateText(Res.string.date_pattern_recent, timeMs)
    }
}

/** Time in the conversation list: "Acum", minutes within the hour, today's time, weekday, then the date. */
@Composable
fun listTime(timeMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - timeMs) / 60_000L).toInt()
    val days = Labels.daysBetween(timeMs, nowMs)
    return when {
        minutes < 1 -> stringResource(Res.string.time_now_short)
        minutes < 60 -> stringResource(Res.string.time_minutes_short, minutes)
        days == 0 -> Labels.clock(timeMs)
        days < 6 -> formatDate("EEE", timeMs, rememberDateNames())
        else -> dateText(Res.string.date_pattern_short, timeMs)
    }
}

/** Day header in a conversation: "Azi", "Ieri" or the date. */
@Composable
fun dayLabel(timeMs: Long, nowMs: Long = wallNowMs()): String = when (Labels.daysBetween(timeMs, nowMs)) {
    0 -> stringResource(Res.string.date_today)
    1 -> stringResource(Res.string.date_yesterday)
    else -> dateText(Res.string.date_pattern_day, timeMs)
}

/** The full date and time: "12 octombrie, 14:30". */
@Composable
fun dateText(timeMs: Long): String = dateText(Res.string.date_pattern_full, timeMs)

@Composable
private fun dateText(pattern: StringResource, timeMs: Long): String = formatDate(stringResource(pattern), timeMs, rememberDateNames())

/** One hop means a direct link; the rest are the phones the packet went through. */
@Composable
fun hopsText(hops: Int): String =
    if (hops <= 1) stringResource(Res.string.hop_direct) else pluralStringResource(Res.plurals.via_phones, hops - 1, hops - 1)
