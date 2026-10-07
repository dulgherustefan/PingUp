package ro.safetyplease.app.ui.people

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** Decodes a QR from a camera frame. No Android classes, so it can be tested on the JVM. */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * [luminance] is the frame's Y plane. Cameras often pad the end of each row,
     * so the row width in memory ([rowStride]) can exceed [width].
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luminance.size < rowStride * (height - 1) + width) return null
        return try {
            val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        }
    }
}
