package ro.safetyplease.app.ui.report

import androidx.annotation.StringRes
import ro.safetyplease.app.R
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/** Venue place type of first aid, as in venue.json. */
internal const val MEDICAL = "medical"

/** How far the report got, from 0 (waiting for a phone) to 4 (resolved). */
fun reportStep(report: MyReport): Int = when {
    report.status >= AckStatus.RESOLVED -> 4
    report.status >= AckStatus.ACKNOWLEDGED -> 3
    report.status >= AckStatus.RECEIVED -> 2
    report.sent -> 1
    else -> 0
}

/**
 * Label of a timeline step. RECEIVED only means a staff phone got the report, sent automatically, so its step speaks of
 * a phone; "seen" belongs to ACKNOWLEDGED, when a person on staff took it.
 */
@StringRes
fun stepLabel(step: Int, report: MyReport): Int = when (step) {
    0 -> R.string.status_waiting_link
    1 -> R.string.status_sending
    2 -> R.string.status_received
    3 -> if (report.status >= AckStatus.ACKNOWLEDGED && report.teamName.isNotEmpty()) R.string.status_acknowledged else R.string.status_acknowledged_pending
    else -> R.string.status_resolved
}

/** What the current step adds under its label: at RECEIVED, that no person has confirmed yet. */
@StringRes
fun stepNote(step: Int): Int? = if (step == 2) R.string.status_received_note else null

/**
 * Where the way to help is measured from: your position now, else where you reported from, else the zone
 * you picked by hand. Null if we know none of them.
 */
fun helpOrigin(position: GeoPoint?, report: MyReport, manualZone: String, venue: Venue): GeoPoint? {
    val lat = report.lat
    val lon = report.lon
    val reported = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    return position ?: reported ?: venue.zone(report.zone)?.center ?: venue.zone(manualZone)?.center
}
