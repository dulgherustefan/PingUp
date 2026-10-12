package ro.safetyplease.app.ui.common

import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.me_open
import ro.safetyplease.ui.resources.presence_near
import ro.safetyplease.ui.resources.presence_never
import ro.safetyplease.ui.resources.presence_seen
import ro.safetyplease.ui.resources.presence_zone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.common.Nearby
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.GlassSize
import ro.safetyplease.ui.designsystem.TouchTarget
import ro.safetyplease.ui.designsystem.pressScale

@Composable
fun presenceText(friend: Friend, nearby: Nearby, now: Long): String = when {
    nearby.isInRange(friend.nodeId) -> stringResource(Res.string.presence_near)
    // the hop count is a technical detail; people only care when the friend was last seen
    friend.lastSeenAt > 0 -> stringResource(Res.string.presence_seen, agoText(friend.lastSeenAt, now))
    else -> stringResource(Res.string.presence_never)
}

/** [presenceText], then the zone the friend last told us and how long ago, once they have told us one. */
@Composable
fun friendStatus(friend: Friend, nearby: Nearby, now: Long, venue: Venue): String {
    val presence = presenceText(friend, nearby, now)
    if (friend.lastZone.isEmpty()) return presence
    return stringResource(Res.string.presence_zone, presence, venue.zoneName(friend.lastZone), agoText(friend.lastZoneAt, now))
}

/** Your avatar, top left on every tab: opens your profile directly. */
@Composable
fun MeButton(name: String, onClick: () -> Unit) {
    val label = stringResource(Res.string.me_open)
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(TouchTarget).clip(CircleShape)
            .clickable(press, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.pressScale(press)) { Avatar(name, GlassSize) } }
}

const val MAX_NAME = 20
