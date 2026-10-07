package ro.safetyplease.app.ui.common

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ro.safetyplease.app.R
import ro.safetyplease.app.text.agoText
import ro.safetyplease.app.ui.designsystem.Avatar
import ro.safetyplease.app.ui.designsystem.GlassSize
import ro.safetyplease.app.ui.designsystem.TouchTarget
import ro.safetyplease.app.ui.designsystem.pressScale
import ro.safetyplease.core.data.Friend

@Composable
fun presenceText(friend: Friend, nearby: Nearby, now: Long): String = when {
    nearby.isInRange(friend.nodeId) -> stringResource(R.string.presence_near)
    // cat de multe telefoane a trecut mesajul e un detaliu tehnic; omul vrea doar sa stie cand l-ai vazut
    friend.lastSeenAt > 0 -> stringResource(R.string.presence_seen, agoText(friend.lastSeenAt, now))
    else -> stringResource(R.string.presence_never)
}

/** Bula ta din stanga sus, pe fiecare tab: deschide ecranul tau (codul si setarile), direct, fara meniu intermediar. */
@Composable
fun MeButton(name: String, onClick: () -> Unit) {
    val label = stringResource(R.string.me_open)
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(TouchTarget).clip(CircleShape)
            .clickable(press, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.pressScale(press)) { Avatar(name, GlassSize) } }
}

const val MAX_NAME = 20
