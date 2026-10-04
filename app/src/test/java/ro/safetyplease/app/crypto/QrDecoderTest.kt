package ro.safetyplease.app.crypto

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/**
 * Camera nu poate fi testata aici, dar pasul de decodare da: imagini QR generate, puse intr-un plan de
 * luminanta asa cum il livreaza camera (linii cu umplutura, imagine rotita, cod mic intr-un cadru mare).
 */
class QrDecoderTest {
    private val friendCode = QrCodes.encodeFriend(FriendCard("Ioana", ByteArray(32) { it.toByte() }, ByteArray(32) { (it * 3).toByte() }))
    private val staffCode = QrCodes.encodeStaff(StaffCard(ByteArray(32) { 7 }, ByteArray(32) { 9 }, StaffRole.STAFF, "Medical 2", ""))

    /** Deseneaza codul ca pe ecranul "Codul meu": module negre pe alb, cu margine. */
    private fun qrPixels(text: String, size: Int): Array<BooleanArray> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        return Array(size) { y -> BooleanArray(size) { x -> matrix[x, y] } }
    }

    private class Plane(val data: ByteArray, val rowStride: Int, val width: Int, val height: Int)

    /** Pune codul intr-un cadru gri [width] x [height], cu [padding] octeti de umplutura pe fiecare linie. */
    private fun frame(code: Array<BooleanArray>, width: Int, height: Int, padding: Int, noise: Int = 0): Plane {
        val stride = width + padding
        val random = Random(42)
        val data = ByteArray(stride * height) { 0x55 }
        val left = (width - code.size) / 2
        val top = (height - code.size) / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inside = y - top in code.indices && x - left in code.indices
                var value = if (!inside) 0x80 else if (code[y - top][x - left]) 0x18 else 0xE6
                if (noise > 0) value = (value + random.nextInt(2 * noise + 1) - noise).coerceIn(0, 255)
                data[y * stride + x] = value.toByte()
            }
        }
        return Plane(data, stride, width, height)
    }

    private fun decode(p: Plane) = QrDecoder.decode(p.data, p.rowStride, p.width, p.height)

    @Test
    fun decodesFriendCodeFromACleanFrame() {
        assertEquals(friendCode, decode(frame(qrPixels(friendCode, 400), 640, 480, padding = 0)))
    }

    @Test
    fun decodesTheDecodedTextBackIntoTheSameCard() {
        val text = decode(frame(qrPixels(friendCode, 400), 640, 480, padding = 0))!!
        val card = QrCodes.decodeFriend(text)!!
        assertEquals("Ioana", card.nickname)
    }

    @Test
    fun rowPaddingIsNotTreatedAsImage() {
        assertEquals(staffCode, decode(frame(qrPixels(staffCode, 400), 640, 480, padding = 48)))
    }

    @Test
    fun decodesWithSensorNoiseAndASmallerCode() {
        assertEquals(staffCode, decode(frame(qrPixels(staffCode, 300), 1280, 720, padding = 16, noise = 18)))
    }

    @Test
    fun decodesAFrameRotatedByNinetyDegrees() {
        // camera livreaza de obicei cadrul rotit fata de ecran
        val code = qrPixels(friendCode, 400)
        val rotated = Array(code.size) { y -> BooleanArray(code.size) { x -> code[code.size - 1 - x][y] } }
        assertEquals(friendCode, decode(frame(rotated, 640, 480, padding = 8)))
    }

    @Test
    fun framesWithoutACodeGiveNull() {
        val random = Random(7)
        val noise = ByteArray(640 * 480).also { random.nextBytes(it) }
        assertNull(QrDecoder.decode(noise, 640, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { 0x80.toByte() }, 640, 640, 480))
    }

    @Test
    fun inconsistentDimensionsAreRejectedInsteadOfCrashing() {
        assertNull(QrDecoder.decode(ByteArray(100), 640, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(640 * 480), 600, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(0), 0, 0, 0))
    }
}
