package dev.aragonite.fleece

import kotlin.test.Test
import kotlin.test.assertEquals

class FleeceArrayTest {

    @Test
    fun testArrayCountParsing() {
        // Array header: tag 6, count in lower 11 bits
        // 0x60 0x03 = tag 6, narrow (bit 3=0), count=3
        val header = byteArrayOf(0x60, 0x03)
        val padding = ByteArray(20)
        val data = header + padding

        val array = FleeceArray(FleeceValue(data, 0))
        assertEquals(3, array.count)
        assertEquals(false, array.isWide)
        assertEquals(2, array.slotWidth)
    }

    @Test
    fun testArrayWideFlag() {
        // Wide array: bit 3 of byte 0 set
        // 0x68 = 0110 1000 = tag 6, bit 3 set
        val header = byteArrayOf(0x68, 0x03)
        val padding = ByteArray(20)
        val data = header + padding

        val array = FleeceArray(FleeceValue(data, 0))
        assertEquals(true, array.isWide)
        assertEquals(4, array.slotWidth)
    }

    @Test
    fun testArrayGet() {
        // Array with 2 items
        // Header: 0x60 0x02 = narrow array, count 2
        // Item 0 (int 66): 0x00 0x42
        // Item 1 (int 99): 0x00 0x63
        val header = byteArrayOf(0x60, 0x02)
        val item0 = byteArrayOf(0x00, 0x42)
        val item1 = byteArrayOf(0x00, 0x63)
        val padding = ByteArray(10)
        val data = header + item0 + item1 + padding

        val array = FleeceArray(FleeceValue(data, 0))
        assertEquals(66, array.get(0)?.asInt())
        assertEquals(99, array.get(1)?.asInt())
    }

    @Test
    fun testArrayGetOutOfBounds() {
        val header = byteArrayOf(0x60, 0x01)
        val item0 = byteArrayOf(0x00, 0x42)
        val padding = ByteArray(10)
        val data = header + item0 + padding

        val array = FleeceArray(FleeceValue(data, 0))
        assertEquals(null, array.get(5))
    }
}
