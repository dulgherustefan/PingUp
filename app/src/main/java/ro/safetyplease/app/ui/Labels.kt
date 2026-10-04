package ro.safetyplease.app.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import ro.safetyplease.app.R
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentCategory
import ro.safetyplease.app.protocol.QuickCode
import ro.safetyplease.app.protocol.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Legatura dintre constantele de protocol si textele din strings.xml. */
object Labels {
    @StringRes
    fun category(category: Int): Int = when (category) {
        IncidentCategory.MEDICAL -> R.string.cat_medical
        IncidentCategory.VIOLENCE -> R.string.cat_violence
        IncidentCategory.LOST_PERSON -> R.string.cat_lost
        IncidentCategory.HARASSMENT -> R.string.cat_harassment
        IncidentCategory.CROWD -> R.string.cat_crowd
        IncidentCategory.FIRE -> R.string.cat_fire
        else -> R.string.cat_other
    }

    @StringRes
    fun severity(severity: Int): Int = when (severity) {
        Severity.URGENT -> R.string.sev_urgent
        Severity.MEDIUM -> R.string.sev_medium
        else -> R.string.sev_low
    }

    @StringRes
    fun quick(code: Int): Int = when (code) {
        QuickCode.WHERE_ARE_YOU -> R.string.quick_where
        QuickCode.COMING -> R.string.quick_coming
        QuickCode.LOW_BATTERY -> R.string.quick_battery
        else -> R.string.quick_meet
    }

    fun staffStatus(context: Context, status: Int, team: String): String = when (status) {
        AckStatus.RESOLVED -> context.getString(R.string.staff_status_resolved, team)
        AckStatus.ACKNOWLEDGED -> context.getString(R.string.staff_status_taken, team)
        AckStatus.RECEIVED -> context.getString(R.string.staff_status_new)
        else -> context.getString(R.string.staff_status_new)
    }

    fun clock(timeMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))

    fun ago(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(timeMs, nowMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
