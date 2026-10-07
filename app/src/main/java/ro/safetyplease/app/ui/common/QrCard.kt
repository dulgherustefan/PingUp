package ro.safetyplease.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.designsystem.title1
import ro.safetyplease.app.ui.designsystem.title3

/** QR code with [ink] modules on white and no quiet zone: the card around it provides the margin. */
fun qrBitmap(text: String, size: Int = 640, ink: Int = android.graphics.Color.BLACK): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) ink else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// The code card ignores the theme so the code reads the same day and night. White name on forest green
// is 7.5:1 and the dark green code on white is over 11:1, enough for any camera.
private val QrBorder = Color(0xFF2B5E45)
private val QrInk = 0xFF17402E.toInt()
private val QrFrame = Color(0xFFE9E9E9)

/** Width of the code card; the buttons below it align to it. */
val QrCardWidth = 296.dp

/** Your code on a forest green card, your name below it. Tap to open it full screen. */
@Composable
fun QrBadge(code: String, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = remember(code) { qrBitmap(code, ink = QrInk).asImageBitmap() }
    Column(
        modifier.widthIn(max = QrCardWidth).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(QrBorder)
            .clickable(onClickLabel = stringResource(R.string.add_friend_enlarge), role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // white in every theme so any camera can read it
        Image(
            bitmap, stringResource(R.string.add_friend_mine),
            Modifier.padding(start = 40.dp, end = 40.dp, top = 32.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .background(Color.White).border(2.dp, QrFrame, RoundedCornerShape(12.dp)).padding(16.dp),
        )
        Text(
            name, style = MaterialTheme.typography.title3, color = Color.White, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
        )
    }
}

/** Full-screen code on white, easier to scan up close or in low light. Tap to close. */
@Composable
fun BigCodeDialog(code: String, name: String, onDismiss: () -> Unit) {
    val bitmap = remember(code) { qrBitmap(code).asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().clickable(onClickLabel = stringResource(R.string.close), onClick = onDismiss).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap, stringResource(R.string.add_friend_mine),
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(name, style = MaterialTheme.typography.title1, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}
