package ro.safetyplease.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.PositionRecord
import ro.safetyplease.core.position.FriendAge
import ro.safetyplease.core.position.FriendGuide
import ro.safetyplease.core.position.FriendPace
import ro.safetyplease.core.position.FriendRing
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.position.SharingNow
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.turnedTo
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.pos_ago
import ro.safetyplease.ui.resources.pos_arrow
import ro.safetyplease.ui.resources.pos_directions
import ro.safetyplease.ui.resources.pos_landmark
import ro.safetyplease.ui.resources.pos_last_known
import ro.safetyplease.ui.resources.pos_near
import ro.safetyplease.ui.resources.pos_no_me
import ro.safetyplease.ui.resources.pos_now
import ro.safetyplease.ui.resources.pos_pace_request
import ro.safetyplease.ui.resources.pos_pace_still
import ro.safetyplease.ui.resources.pos_ring
import ro.safetyplease.ui.resources.pos_stage
import ro.safetyplease.ui.resources.share_prec_approx_short
import ro.safetyplease.ui.resources.share_prec_exact_short
import ro.safetyplease.ui.resources.share_prec_zone_short
import ro.safetyplease.ui.resources.share_until
import ro.safetyplease.ui.resources.share_until_stopped
import ro.safetyplease.ui.resources.sharing_bar
import ro.safetyplease.ui.resources.sharing_bar_open
import ro.safetyplease.ui.text.Labels

/** A friend's shared position as the screens show it: age, the ring grown with that age, and what the sender said of its pace. */
class FriendSpot(val friend: Friend, val point: GeoPoint, val ageMs: Long, val ringM: Double, val pace: FriendPace) {
    val age: FriendAge get() = FriendAge.of(ageMs)
    val minutes: Int get() = FriendAge.minutes(ageMs)

    companion object {
        /** Only positions still within their session: a record past [PositionRecord.expiresAtMs] is not shown at all. */
        fun of(records: List<PositionRecord>, friends: List<Friend>, nowMs: Long, venue: Venue): List<FriendSpot> =
            records.filter { it.expiresAtMs > nowMs }.mapNotNull { record ->
                val friend = friends.firstOrNull { it.nodeId == record.sender } ?: return@mapNotNull null
                val point = GeoPoint(record.lat, record.lon)
                val age = record.ageMs(nowMs)
                FriendSpot(friend, point, age, FriendRing.radiusM(FriendRing.accuracy95(record.accuracy), age, point, venue.bounds), FriendPace.of(record.nextUpdate))
            }
    }
}

@Composable
fun friendAgeText(spot: FriendSpot): String = when (spot.age) {
    FriendAge.NOW -> stringResource(Res.string.pos_now)
    FriendAge.RECENT, FriendAge.FADED -> stringResource(Res.string.pos_ago, spot.minutes)
    FriendAge.STALE -> stringResource(Res.string.pos_last_known, spot.minutes)
}

/** The age, the ring and, when the sender said so, "la cerere" or "staționar". */
@Composable
fun friendSpotText(spot: FriendSpot): String = listOfNotNull(
    friendAgeText(spot),
    stringResource(Res.string.pos_ring, spot.ringM.toInt()),
    when (spot.pace) {
        FriendPace.ON_REQUEST -> stringResource(Res.string.pos_pace_request)
        FriendPace.STATIONARY -> stringResource(Res.string.pos_pace_still)
        else -> null
    },
).joinToString(" · ")

@Composable
fun guideText(guide: FriendGuide): String = when (guide) {
    FriendGuide.Near -> stringResource(Res.string.pos_near)
    is FriendGuide.Arrow -> stringResource(Res.string.pos_arrow, guide.distanceM, stringArrayResource(Res.array.pos_directions)[guide.sector])
    is FriendGuide.Toward ->
        if (guide.stage != null) stringResource(Res.string.pos_stage, guide.distanceM, guide.stage!!)
        else stringResource(Res.string.pos_landmark, guide.distanceM, guide.landmark ?: 0)
}

/** Which way and how far, with an arrow only when [Guide] allows one; [headingDeg] turns it with the phone. */
@Composable
fun FriendGuideRow(guide: FriendGuide, headingDeg: Float?, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (guide is FriendGuide.Arrow) {
            Icon(Sym.Send, null, Modifier.size(28.dp).turnedTo(Guide.arrowDeg(guide.bearingDeg, headingDeg)), tint = colors.accent)
        } else {
            Icon(Sym.Place, null, Modifier.size(28.dp), tint = colors.accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(guideText(guide), style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.SemiBold, color = colors.label)
    }
}

@Composable
fun precisionText(precision: Int): String = stringResource(
    when (precision) {
        SharePrecision.EXACT -> Res.string.share_prec_exact_short
        SharePrecision.APPROXIMATE -> Res.string.share_prec_approx_short
        else -> Res.string.share_prec_zone_short
    },
)

/** "Până la 22:30" or "Până o oprești". */
@Composable
fun untilText(endsAtMs: Long?): String =
    if (endsAtMs == null) stringResource(Res.string.share_until_stopped) else stringResource(Res.string.share_until, Labels.clock(endsAtMs))

/**
 * The thin bar over every screen while this phone shares its location: text and icon, never color alone. A tap opens
 * "Cine mă vede acum". [statusBar] pads it under the status bar when nothing else above does.
 */
@Composable
fun SharingBar(sharing: SharingNow, statusBar: Boolean, onOpen: () -> Unit) {
    val colors = AppTheme.colors
    val text = pluralStringResource(Res.plurals.sharing_bar, sharing.people, sharing.people)
    val until = untilText(sharing.endsAtMs)
    Row(
        Modifier.fillMaxWidth().background(colors.accent).then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
            .clickable(onClickLabel = stringResource(Res.string.sharing_bar_open), role = Role.Button, onClick = onOpen)
            .semantics { stateDescription = until }
            .heightIn(min = 36.dp).padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Sym.MyLocation, null, Modifier.size(14.dp), tint = colors.onAccent)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold, color = colors.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A friend's position card body: where to go, then how old and how sure it is. */
@Composable
fun FriendSpotLines(spot: FriendSpot, guide: FriendGuide?, headingDeg: Float?) {
    Column {
        if (guide != null) FriendGuideRow(guide, headingDeg)
        else Text(
            stringResource(Res.string.pos_no_me), style = MaterialTheme.typography.subheadline, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 12.dp),
        )
        Text(
            friendSpotText(spot), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
            modifier = Modifier.padding(start = Gutter, end = Gutter, bottom = 12.dp),
        )
    }
}
