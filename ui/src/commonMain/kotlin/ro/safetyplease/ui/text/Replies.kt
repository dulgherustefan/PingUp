package ro.safetyplease.ui.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.protocol.ResponseCode
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.response_coming
import ro.safetyplease.ui.resources.response_landmark
import ro.safetyplease.ui.resources.response_medical
import ro.safetyplease.ui.resources.response_other
import ro.safetyplease.ui.resources.update_moved
import ro.safetyplease.ui.resources.update_other
import ro.safetyplease.ui.resources.update_resolved
import ro.safetyplease.ui.resources.update_still_active

/** The console's responses to a reporter and the reporter's updates, in words. */
object Replies {
    /** What the response asks of the reporter; "go to landmark" takes the landmark's name as its argument. */
    fun response(code: Int): StringResource = when (code) {
        ResponseCode.COMING -> Res.string.response_coming
        ResponseCode.GO_TO_LANDMARK -> Res.string.response_landmark
        ResponseCode.GO_TO_MEDICAL -> Res.string.response_medical
        else -> Res.string.response_other
    }

    /** The landmark as the site plan names it, or by its number. */
    fun landmarkName(venue: Venue, number: Int): String = venue.landmark(number)?.name?.ifEmpty { null } ?: number.toString()

    /** An update as staff read it. */
    fun update(kind: Int): StringResource = when (kind) {
        UpdateKind.STILL_ACTIVE -> Res.string.update_still_active
        UpdateKind.MOVED -> Res.string.update_moved
        UpdateKind.RESOLVED_ITSELF -> Res.string.update_resolved
        else -> Res.string.update_other
    }
}

@Composable
fun responseText(venue: Venue, code: Int, landmark: Int): String = stringResource(Replies.response(code), Replies.landmarkName(venue, landmark))
