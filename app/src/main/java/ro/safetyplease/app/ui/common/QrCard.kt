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

/** Codul QR, cu modulele in [ink] pe alb si fara margine: marginea alba o da cardul pe care sta. */
fun qrBitmap(text: String, size: Int = 640, ink: Int = android.graphics.Color.BLACK): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) ink else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// culorile cardului cu cod nu urmeaza tema: codul trebuie sa se citeasca la fel ziua si noaptea.
// Verdele de padure din logo; numele alb pe el are 7,5:1, iar codul verde-negru pe alb peste 11:1, cat sa-l prinda orice camera.
private val QrBorder = Color(0xFF2B5E45)
private val QrInk = 0xFF17402E.toInt()
private val QrFrame = Color(0xFFE9E9E9)

/** Latimea cardului cu cod; butoanele de sub el se aliniaza cu el. */
val QrCardWidth = 296.dp

/**
 * Codul tau pe un card verde de padure, ca in Signal: patratul alb cu codul si numele tau dedesubt, in alb.
 * Atins, se deschide mare, pe tot ecranul.
 */
@Composable
fun QrBadge(code: String, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = remember(code) { qrBitmap(code, ink = QrInk).asImageBitmap() }
    Column(
        modifier.widthIn(max = QrCardWidth).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(QrBorder)
            .clickable(onClickLabel = stringResource(R.string.add_friend_enlarge), role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // alb pe orice tema: orice camera trebuie sa il poata citi
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

/** Codul mare, pe alb, peste tot ecranul: de aproape sau in lumina slaba se citeste mai usor. Atingerea il inchide. */
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
