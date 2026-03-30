package dev.aragonite.fleece

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleeceDecoderTest {

    @Test
    fun testDecodeEmptyData() {
        val data = byteArrayOf()
        assertNull(FleeceDecoder.decode(data))
        assertNull(FleeceDecoder.decodeAsDict(data))
    }

    @Test
    fun testDecodeOneByteData() {
        val data = byteArrayOf(0x00)
        assertNull(FleeceDecoder.decode(data))
        assertNull(FleeceDecoder.decodeAsDict(data))
    }

    @Test
    fun testDecodeRootValue() {
        // Root is the last 2 bytes: a small int value 42
        val padding = ByteArray(10)
        val root = byteArrayOf(0x00, 0x2A)  // small int 42
        val data = padding + root

        val value = FleeceDecoder.decode(data)
        assertEquals(42, value?.asInt())
    }

    @Test
    fun testDecodeRootPointerNarrow() {
        // Root (last 2 bytes) is a narrow pointer to an int earlier in the data
        // Layout: [int at offset 0] [padding] [pointer at end]
        val intValue = byteArrayOf(0x00, 0x2A)  // int 42 at offset 0
        val padding = ByteArray(10)
        val pointer = byteArrayOf(0x80.toByte(), 0x06)  // narrow pointer: 6 * 2 = 12 bytes back
        val data = intValue + padding + pointer

        val value = FleeceDecoder.decode(data)
        assertEquals(42, value?.asInt())
    }

    @Test
    fun testDecodeRootNotDict() {
        // Root is an int, not a dict
        val padding = ByteArray(10)
        val root = byteArrayOf(0x00, 0x2A)
        val data = padding + root

        assertNull(FleeceDecoder.decodeAsDict(data))
        // But decode should return the value
        val value = FleeceDecoder.decode(data)
        assertEquals(42, value?.asInt())
    }

    @Test
    fun testDecodeRootDict() {
        // Root is a dict
        val dictHeader = byteArrayOf(0x70, 0x00)  // tag 7, count 0
        val padding = ByteArray(10)
        val data = padding + dictHeader

        val dict = FleeceDecoder.decodeAsDict(data)
        assertEquals(0, dict?.count)
    }

    @Test
    fun testDecodeRootPointerToDict() {
        // Root is a pointer to a dict
        val dictHeader = byteArrayOf(0x70, 0x00)  // dict at offset 0
        val padding = ByteArray(10)
        val pointer = byteArrayOf(0x80.toByte(), 0x06)  // narrow pointer: 12 bytes back
        val data = dictHeader + padding + pointer

        val dict = FleeceDecoder.decodeAsDict(data)
        assertEquals(0, dict?.count)
    }

    @Test
    fun testDecodeChainedPointers() {
        // Root -> narrow pointer -> int
        // Layout: [int at 0-1] [padding 2-9] [pointer at 10-11]
        // Pointer at offset 10 should point to offset 0, which is 10 bytes back = 5 in 2-byte units
        val intValue = byteArrayOf(0x00, 0x2A)  // int at offset 0-1
        val padding = ByteArray(8)  // padding at offset 2-9
        val pointer = byteArrayOf(0x80.toByte(), 0x05)  // narrow pointer: 5 * 2 = 10 bytes back
        val data = intValue + padding + pointer

        val value = FleeceDecoder.decode(data)
        assertEquals(42, value?.asInt())
    }
}
