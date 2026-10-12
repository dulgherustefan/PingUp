package ro.safetyplease.app.text

import android.content.res.Resources
import androidx.annotation.StringRes
import ro.safetyplease.app.R
import ro.safetyplease.core.protocol.ResponseCode
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.venue.Venue

/** The console's responses to a reporter and the reporter's updates, in words. */
object Replies {
    /** What the response asks of the reporter; "go to landmark" names it as the site plan does, or by its number. */
    fun response(resources: Resources, venue: Venue, code: Int, landmark: Int): String = when (code) {
        ResponseCode.COMING -> resources.getString(R.string.response_coming)
        ResponseCode.GO_TO_LANDMARK -> resources.getString(R.string.response_landmark, landmarkName(venue, landmark))
        ResponseCode.GO_TO_MEDICAL -> resources.getString(R.string.response_medical)
        else -> resources.getString(R.string.response_other)
    }

    fun landmarkName(venue: Venue, number: Int): String = venue.landmark(number)?.name?.ifEmpty { null } ?: number.toString()

    /** An update as staff read it. */
    @StringRes
    fun update(kind: Int): Int = when (kind) {
        UpdateKind.STILL_ACTIVE -> R.string.update_still_active
        UpdateKind.MOVED -> R.string.update_moved
        UpdateKind.RESOLVED_ITSELF -> R.string.update_resolved
        else -> R.string.update_other
    }
}
