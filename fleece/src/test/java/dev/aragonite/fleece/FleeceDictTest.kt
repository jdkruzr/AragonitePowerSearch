package dev.aragonite.fleece

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleeceDictTest {

    @Test
    fun testDictCountParsing() {
        // Dict header: tag 7, count in lower 11 bits
        // 0x70 0x02 = tag 7, narrow (bit 3=0), count=2
        val header = byteArrayOf(0x70, 0x02)
        val padding = ByteArray(20)
        val data = header + padding

        val dict = FleeceDict(FleeceValue(data, 0))
        assertEquals(2, dict.count)
        assertEquals(false, dict.isWide)
        assertEquals(2, dict.slotWidth)
    }

    @Test
    fun testDictWideFlag() {
        // Wide dict: bit 3 of byte 0 set
        // 0x78 = 0111 1000 = tag 7, bit 3 set
        val header = byteArrayOf(0x78, 0x02)
        val padding = ByteArray(20)
        val data = header + padding

        val dict = FleeceDict(FleeceValue(data, 0))
        assertEquals(true, dict.isWide)
        assertEquals(4, dict.slotWidth)
    }

    @Test
    fun testDictGet() {
        // Create a narrow dict with one key-value pair
        // Header: 0x70 0x01 = narrow dict, count 1
        // Key 0 (string "x"): 0x41 = tag 4, length 1, then "x"
        // Value 0 (int 42): 0x00 0x2A
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 'x'.code.toByte())
        val value = byteArrayOf(0x00, 0x2A)
        val padding = ByteArray(10)
        val data = header + key + value + padding

        val dict = FleeceDict(FleeceValue(data, 0))
        val retrievedValue = dict.get("x")
        assertEquals(42, retrievedValue?.asInt())
    }

    @Test
    fun testDictGetReturnsNullForMissingKey() {
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 'x'.code.toByte())
        val value = byteArrayOf(0x00, 0x2A)
        val padding = ByteArray(10)
        val data = header + key + value + padding

        val dict = FleeceDict(FleeceValue(data, 0))
        assertNull(dict.get("missing"))
    }

    @Test
    fun testDictGetString() {
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 'n'.code.toByte())
        val value = byteArrayOf(0x42, 'h'.code.toByte(), 'i'.code.toByte())
        val padding = ByteArray(10)
        val data = header + key + value + padding

        val dict = FleeceDict(FleeceValue(data, 0))
        assertEquals("hi", dict.getString("n"))
    }

    @Test
    fun testDictGetInt() {
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 'n'.code.toByte())
        val value = byteArrayOf(0x00, 0x2A)
        val padding = ByteArray(10)
        val data = header + key + value + padding

        val dict = FleeceDict(FleeceValue(data, 0))
        assertEquals(42, dict.getInt("n"))
    }
}
