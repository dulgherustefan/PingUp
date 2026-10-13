package ro.safetyplease.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor
import kotlin.math.floor

/**
 * The modules of [text]'s QR code, row by row, dark ones true, with no quiet zone. The lowest error correction gives
 * the largest modules for the text, the easiest to read off a screen; zxing picks it too.
 */
fun qrModules(text: String): List<BooleanArray> =
    QRCodeProcessor(text, ErrorCorrectionLevel.LOW).encode().map { row -> BooleanArray(row.size) { row[it].dark } }

/**
 * [text]'s QR code in [ink], as large as whole pixels allow and centred in the space [modifier] gives it: modules on
 * pixel edges stay sharp for the camera. The card around it gives the quiet zone.
 */
@Composable
fun QrCode(text: String, ink: Color, contentDescription: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Spacer(
        modifier
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            }
            .drawWithCache {
                val n = modules.size
                val cell = floor(size.minDimension / n)
                val left = floor((size.width - cell * n) / 2)
                val top = floor((size.height - cell * n) / 2)
                // one path for all the dark modules, so neighbours meet without a seam
                val path = Path()
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        if (modules[y][x]) path.addRect(Rect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell))
                    }
                }
                onDrawBehind { drawPath(path, ink) }
            },
    )
}
