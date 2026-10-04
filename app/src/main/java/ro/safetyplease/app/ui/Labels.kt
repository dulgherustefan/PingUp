package ro.safetyplease.app.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import ro.safetyplease.app.AppLocale
import ro.safetyplease.app.R
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentCategory
import ro.safetyplease.app.protocol.QuickCode
import ro.safetyplease.app.protocol.Severity
import java.text.SimpleDateFormat
import java.util.Date

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
    fun categoryExample(category: Int): Int = when (category) {
        IncidentCategory.MEDICAL -> R.string.cat_medical_example
        IncidentCategory.VIOLENCE -> R.string.cat_violence_example
        IncidentCategory.LOST_PERSON -> R.string.cat_lost_example
        IncidentCategory.HARASSMENT -> R.string.cat_harassment_example
        IncidentCategory.CROWD -> R.string.cat_crowd_example
        IncidentCategory.FIRE -> R.string.cat_fire_example
        else -> R.string.cat_other_example
    }

    @StringRes
    fun urgency(severity: Int): Int = if (severity == Severity.URGENT) R.string.sev_urgent else R.string.sev_normal

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
        else -> context.getString(R.string.staff_status_new)
    }

    fun clock(timeMs: Long): String = SimpleDateFormat("HH:mm", AppLocale.ROMANIAN).format(Date(timeMs))

    fun date(timeMs: Long): String = SimpleDateFormat("d MMMM, HH:mm", AppLocale.ROMANIAN).format(Date(timeMs))

    /**
     * "acum 5 min". Formatorul sistemului ar raspunde in limba telefonului, in mijlocul unei fraze in romana.
     * [resources] trebuie sa vina dintr-un context al aplicatiei, care e fixat pe romana.
     */
    fun ago(resources: Resources, timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val minutes = ((nowMs - timeMs) / 60_000L).toInt()
        return when {
            minutes < 1 -> resources.getString(R.string.time_now)
            minutes < 60 -> resources.getQuantityString(R.plurals.time_minutes_ago, minutes, minutes)
            minutes < 24 * 60 -> resources.getQuantityString(R.plurals.time_hours_ago, minutes / 60, minutes / 60)
            else -> SimpleDateFormat("d MMM, HH:mm", AppLocale.ROMANIAN).format(Date(timeMs))
        }
    }

    /** Drumul in cuvinte: un hop inseamna legatura directa, restul sunt telefoanele prin care a trecut pachetul. */
    fun hops(resources: Resources, hops: Int): String =
        if (hops <= 1) resources.getString(R.string.hop_direct)
        else resources.getQuantityString(R.plurals.via_phones, hops - 1, hops - 1)
}

@Composable
fun agoText(timeMs: Long): String = Labels.ago(LocalResources.current, timeMs)

@Composable
fun hopsText(hops: Int): String = Labels.hops(LocalResources.current, hops)
