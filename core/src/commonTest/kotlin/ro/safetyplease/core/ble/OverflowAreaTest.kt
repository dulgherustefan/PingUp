package ro.safetyplease.core.ble

import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverflowAreaTest {
    /** The 16 mask bytes an iPhone sends for [uuid] alone: everything after Apple's company id and the type byte. */
    private fun mask(uuid: String) = OverflowArea.payload(uuid).copyOfRange(1, OverflowArea.SIZE)

    private fun only(byte: Int, value: Int) = ByteArray(16).also { it[byte] = value.toByte() }

    @Test
    fun theBitIsTheOneIphonesSetForTheUuid() {
        // David G. Young's table, read off iPhones (2020): entry i sets byte i / 8, the bit 0x80 shr (i % 8)
        assertContentEquals(only(0, 0x80), mask("00000000-0000-0000-0000-00000000007c"))
        assertContentEquals(only(0, 0x40), mask("00000000-0000-0000-0000-000000000037"))
        assertContentEquals(only(8, 0x80), mask("00000000-0000-0000-0000-00000000003e"))
        assertContentEquals(only(15, 0x01), mask("00000000-0000-0000-0000-000000000039"))
        // the NHS COVID-19 app's Android filter for its own service, in production in 2020
        assertEquals("0100000000000000000040000000000000", OverflowArea.payload("c1f5983c-fa94-4ac8-8e2e-bb86d6de9b21").toHex())
        // Crownstone's captures of 16-bit UUIDs, which iOS expands with the Bluetooth base UUID; 0x1001 and 0x3333 collide
        assertContentEquals(only(4, 0x10), mask("0000c007-0000-1000-8000-00805f9b34fb"))
        assertContentEquals(only(7, 0x08), mask("0000c008-0000-1000-8000-00805f9b34fb"))
        assertContentEquals(only(7, 0x02), mask("00001001-0000-1000-8000-00805f9b34fb"))
        assertContentEquals(only(7, 0x02), mask("00003333-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun pingUpsBitAndTheFilterThatLooksOnlyAtIt() {
        assertEquals(114, OverflowArea.bit(BleLayout.SERVICE))
        assertContentEquals(only(14, 0x04), mask(BleLayout.SERVICE))
        val filter = OverflowArea.filterMask(BleLayout.SERVICE)
        assertEquals(OverflowArea.SIZE, filter.size)
        assertEquals("ff000000000000000000000000000004" + "00", filter.toHex(), "the type byte and our bit, nothing of other apps")
    }

    @Test
    fun aRecordCarriesOurBitWhateverElseAppleAdvertisesBesideIt() {
        val bit = OverflowArea.bit(BleLayout.SERVICE)
        val flags = "020106"
        val ours = "14ff4c0001" + "0000000000000000000000000000" + "0400"
        // another app's bit and ours: the mask is shared by every app in the background
        val shared = "14ff4c0001" + "0000000000000000400000000000" + "0400"
        val nearby = "07ff4c0010020b00"
        assertTrue(OverflowArea.inRecord((flags + ours).hexToBytes(), bit))
        assertTrue(OverflowArea.inRecord((flags + shared).hexToBytes(), bit))
        // Android 14 keeps only the last Apple block of a record and 15 joins them, so its own parsing can't be trusted
        assertTrue(OverflowArea.inRecord((flags + nearby + ours).hexToBytes(), bit))
        assertTrue(OverflowArea.inRecord((flags + ours + "00" + nearby).hexToBytes(), bit), "padding between the parts")
        // Apple packs [type, length, value] items in one block; the overflow item has no length byte (linky, iOS 27)
        assertTrue(OverflowArea.inRecord((flags + "18ff4c00" + "10020b00" + "01" + "0000000000000000000000000000" + "0400").hexToBytes(), bit))
        assertFalse(OverflowArea.inRecord((flags + "18ff4c00" + "10020b00" + "01" + "00".repeat(16)).hexToBytes(), bit))
        assertFalse(OverflowArea.inRecord((flags + "09ff4c00" + "10020b00" + "0104").hexToBytes(), bit), "an area cut short")
        assertFalse(OverflowArea.inRecord((flags + "14ff4c0001" + "00".repeat(16)).hexToBytes(), bit), "another app's area")
        assertFalse(OverflowArea.inRecord((flags + "14ff4c0002" + "0000000000000000000000000000" + "0400").hexToBytes(), bit), "not type 1")
        assertFalse(OverflowArea.inRecord((flags + "14ff750001" + "0000000000000000000000000000" + "0400").hexToBytes(), bit), "not Apple")
        assertFalse(OverflowArea.inRecord((flags + "14ff4c0001" + "00000000").hexToBytes(), bit), "cut short")
        assertFalse(OverflowArea.inRecord(null, bit))
        assertFalse(OverflowArea.inRecord(ByteArray(0), bit))
    }
}
