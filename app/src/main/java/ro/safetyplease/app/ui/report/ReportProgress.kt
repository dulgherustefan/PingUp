package ro.safetyplease.app.ui.report

import androidx.annotation.StringRes
import ro.safetyplease.app.R
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.protocol.AckStatus

/** Venue place type of first aid, as in venue.json. */
internal const val MEDICAL = "medical"

/** How far the report got, from 0 (waiting for a phone) to 4 (closed). A reopened incident is back with staff. */
fun reportStep(report: MyReport): Int = when {
    AckStatus.isClosed(report.status) -> 4
    AckStatus.isTaken(report.status) || report.status == AckStatus.REOPENED -> 3
    report.status == AckStatus.RECEIVED -> 2
    report.sent -> 1
    else -> 0
}

/**
 * Label of a timeline step. RECEIVED only means a staff phone got the report, sent automatically, so its step speaks of
 * a phone; "seen" belongs to a person on staff, from ACKNOWLEDGED on. The current step names the status it reached.
 */
@StringRes
fun stepLabel(step: Int, report: MyReport): Int = when (step) {
    0 -> R.string.status_waiting_link
    1 -> R.string.status_sending
    2 -> R.string.status_received
    3 -> when (report.status) {
        AckStatus.ON_SCENE -> R.string.status_on_scene
        AckStatus.HANDED_OVER -> R.string.status_handed_over
        AckStatus.REOPENED -> R.string.status_reopened
        else -> if (reportStep(report) >= 3 && report.teamName.isNotEmpty()) R.string.status_acknowledged else R.string.status_acknowledged_pending
    }
    else -> when (report.status) {
        AckStatus.CLOSED_BY_CONTROL -> R.string.status_closed_by_control
        AckStatus.UNFOUNDED -> R.string.status_unfounded
        else -> R.string.status_resolved
    }
}

/** What the current step adds under its label: at RECEIVED, that no person has confirmed yet. */
@StringRes
fun stepNote(step: Int): Int? = if (step == 2) R.string.status_received_note else null
