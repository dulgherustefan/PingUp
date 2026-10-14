package ro.safetyplease.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.safetyplease.ui.ModalAsk
import ro.safetyplease.ui.ModalKind
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.subheadline

/** An alert left open while a walk is on screen, as a band above it; a tap opens it again over the route. */
@Composable
fun ModalBanner(ask: ModalAsk, statusBar: Boolean, onOpen: () -> Unit) {
    val red = ask.kind.rank <= ModalKind.RESPONDER.rank
    val ink = if (red) Color.White else Color.Black
    Row(
        Modifier.fillMaxWidth().background(if (red) AppTheme.colors.alert else AppTheme.colors.orange)
            .then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
            .clickable(onClickLabel = ask.title, role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Report, null, Modifier.size(18.dp), tint = ink)
        Spacer(Modifier.width(10.dp))
        Text(
            ask.title, style = MaterialTheme.typography.subheadline, fontWeight = FontWeight.Bold, color = ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Icon(Sym.Chevron, null, Modifier.size(16.dp), tint = ink)
    }
}
