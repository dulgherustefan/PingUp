package ro.safetyplease.core.ble

import ro.safetyplease.core.protocol.NodeFlags
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AdvertDataTest {
    @Test
    fun theSixBytesAreVersionFlagsThenThePrefixMostSignificantFirst() {
        // what java.nio.ByteBuffer wrote before: the bytes older phones read
        assertContentEquals(byteArrayOf(1, 0x11, 0x01, 0x02, 0x03, 0x04), AdvertData.encode(0x11, 0x01020304))
    }

    @Test
    fun whatIsWrittenReadsBack() {
        for (prefix in listOf(0, 1, 0x7fffffff, -1, Int.MIN_VALUE, 0x80a0c0e0.toInt())) {
            assertEquals(AdvertFields(0x91, prefix), AdvertData.decode(AdvertData.encode(0x91, prefix)))
        }
    }

    @Test
    fun withoutUsableDataThePhoneAcceptsConnectionsUnderNoPrefix() {
        val unknown = AdvertFields(NodeFlags.ACCEPTS_CONNECTIONS, null)
        // an iPhone advertises no service data at all
        assertEquals(unknown, AdvertData.decode(null))
        assertEquals(unknown, AdvertData.decode(byteArrayOf(1, 0x11, 0, 0, 0)))
        assertEquals(unknown, AdvertData.decode(byteArrayOf(2, 0x11, 0, 0, 0, 1)))
    }

    @Test
    fun extraBytesAfterTheSixAreLeftAlone() {
        assertEquals(AdvertFields(0x11, 5), AdvertData.decode(byteArrayOf(1, 0x11, 0, 0, 0, 5, 9, 9)))
    }
}
