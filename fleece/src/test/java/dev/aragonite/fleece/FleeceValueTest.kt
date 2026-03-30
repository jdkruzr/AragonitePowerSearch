package dev.aragonite.fleece

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleeceValueTest {

    @Test
    fun testTagExtraction() {
        val data = byteArrayOf(0x40, 0x00)
        val value = FleeceValue(data, 0)
        assertEquals(0x4, value.tag)
    }

    @Test
    fun testIsPointerDetection() {
        val pointerData = byteArrayOf(0x80.toByte(), 0x00)
        assertTrue(FleeceValue(pointerData, 0).isPointer)

        val nonPointerData = byteArrayOf(0x40, 0x00)
        assertFalse(FleeceValue(nonPointerData, 0).isPointer)
    }

    @Test
    fun testSmallIntDecoding() {
        val data = byteArrayOf(0x00, 0x00)
        val value = FleeceValue(data, 0)
        assertEquals(0, value.asInt())
    }

    @Test
    fun testSmallIntPositive() {
        val data = byteArrayOf(0x00, 0x42)
        val value = FleeceValue(data, 0)
        assertEquals(66, value.asInt())
    }

    @Test
    fun testSmallIntNegative() {
        val data = byteArrayOf(0x0F, 0xFF.toByte())
        val value = FleeceValue(data, 0)
        assertEquals(-1, value.asInt())
    }

    @Test
    fun testLongIntDecoding() {
        // Long int: tag 1, low nibble of byte 1 is length
        // 0x10 = tag 1, low nibble 0
        // 0x01 = length 1 byte
        // 0x42 = data (66 in LE)
        val data = byteArrayOf(0x10, 0x01, 0x42)
        val value = FleeceValue(data, 0)
        assertEquals(66, value.asInt())
    }

    @Test
    fun testBoolFalseDecoding() {
        // Special: tag 3, low nibble = 4 for false
        val data = byteArrayOf(0x34, 0x00)
        val value = FleeceValue(data, 0)
        assertEquals(false, value.asBool())
    }

    @Test
    fun testBoolTrueDecoding() {
        // Special: tag 3, low nibble = 8 for true, byte 1 = 0x08
        val data = byteArrayOf(0x30, 0x08)
        val value = FleeceValue(data, 0)
        assertEquals(true, value.asBool())
    }

    @Test
    fun testStringDecodingShortInline() {
        val helloBytes = "hello".toByteArray()
        val data = byteArrayOf(0x45) + helloBytes + byteArrayOf(0x00)
        val value = FleeceValue(data, 0)
        assertEquals("hello", value.asString())
    }

    @Test
    fun testStringDecodingWithVarint() {
        val testString = "a".repeat(24)
        val varintBytes = byteArrayOf(0x18.toByte())
        val stringBytes = testString.toByteArray()
        val data = byteArrayOf(0x4F.toByte()) + varintBytes + stringBytes
        val value = FleeceValue(data, 0)
        assertEquals(testString, value.asString())
    }

    @Test
    fun testNarrowPointerDereference() {
        // Build layout: [int at offset 0] [pointer at offset 2]
        // Int: 0x00, 0x42 = small int, value 66
        // Pointer at offset 2: 0x80 0x01 = pointer, 1 in 2-byte units = 2 bytes back
        // That points to offset 0 (2 - 2 = 0)
        val target = byteArrayOf(0x00, 0x42)  // int 66 at offset 0-1
        val pointer = byteArrayOf(0x80.toByte(), 0x01)  // narrow pointer: 1 * 2 = 2 bytes back
        val data = target + pointer

        val pointerValue = FleeceValue(data, 2)  // pointer is at offset 2
        assertTrue(pointerValue.isPointer)

        val dereferenced = pointerValue.deref(wide = false)
        assertEquals(66, dereferenced.asInt())
    }

    @Test
    fun testWidePointerDereference() {
        // Similar but with wide pointer
        val target = byteArrayOf(0x00, 0x42)  // int at offset 0-1
        val widePointer = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)  // wide pointer: 1 * 2 = 2 bytes back
        val data = target + widePointer

        val pointerValue = FleeceValue(data, 2)  // pointer is at offset 2
        assertTrue(pointerValue.isPointer)

        val dereferenced = pointerValue.deref(wide = true)
        assertEquals(66, dereferenced.asInt())
    }

    @Test
    fun testPointerChaining() {
        // Simple chain: narrow pointer -> int
        val intValue = byteArrayOf(0x00, 0x42)
        val pointer1 = byteArrayOf(0x80.toByte(), 0x01)
        val data = intValue + pointer1

        val pointerValue = FleeceValue(data, 2)
        val dereferenced = pointerValue.deref(wide = false)
        assertEquals(66, dereferenced.asInt())
    }
}
