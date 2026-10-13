package ro.safetyplease.ui.control

import ro.safetyplease.ui.common.qrModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandOverQrTest {
    @Test
    fun aLongCardIsCutSoItsCodeAlwaysFits() {
        val long = "Notă: pacient găsit lângă scenă, răcire începută. ".repeat(200)
        val (text, cut) = handOverQr(long)
        assertTrue(cut)
        assertTrue(text.encodeToByteArray().size <= QR_MAX_BYTES)
        assertTrue(long.startsWith(text))
        assertTrue(qrModules(text).isNotEmpty(), "past 2953 bytes no code fits; this one does")

        val short = "Incident 1a2b3c4d: Medical"
        assertEquals(short to false, handOverQr(short))
        assertFalse(handOverQr(short).second)
    }
}
