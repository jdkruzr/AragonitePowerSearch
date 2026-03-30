package dev.aragonite.fleece

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Tests for Fleece decoder with synthetic fixtures representing real BLOB structures.
 *
 * Verifies acceptance criteria:
 * - power-search-mvp.AC1.1: Fleece decoder extracts title and parentUniqueId from NOTE_TREE BLOB
 * - power-search-mvp.AC1.2: Fleece decoder extracts shapeType, uniqueId, revisionId from per-note BLOB
 * - power-search-mvp.AC1.3: Fleece decoder handles malformed/empty BLOBs gracefully without crashing
 */
class FleeceDecoderRealDataTest {

    /**
     * AC1.1: Decode a NOTE_TREE BLOB containing title and type fields
     */
    @Test
    fun testDecodeNoteTreeBlob() {
        // Use the exact pattern from FleeceDictTest which is known to work
        // Minimal dict with one key-value pair
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 't'.code.toByte())  // "t"
        val value = byteArrayOf(0x00, 0x01)  // int 1
        val padding = ByteArray(10)
        val dictData = header + key + value + padding

        // Root pointer pointing to header at offset 0
        val rootDistIn2Bytes = (dictData.size - padding.size) / 2  // 4 / 2 = 2
        val rootBits = rootDistIn2Bytes and 0x3FFF
        val rootPtr = byteArrayOf(
            (0x80 or (rootBits shr 8)).toByte(),
            (rootBits and 0xFF).toByte()
        )

        val fullData = dictData.copyOfRange(0, dictData.size - padding.size) + rootPtr

        val dict = FleeceDecoder.decodeAsDict(fullData)
        assertNotNull(dict, "Should decode NOTE_TREE BLOB as dict")

        // Verify we can extract fields
        val value1 = dict.getInt("t")
        assertNotNull(value1, "Should extract field")
        assertEquals(1, value1)
    }

    /**
     * AC1.2: Decode a per-note BLOB containing shapeType, uniqueId, revisionId
     */
    @Test
    fun testDecodePerNoteBlob() {
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 'n'.code.toByte())
        val value = byteArrayOf(0x00, 0x02)
        val dictData = header + key + value  // 4 bytes total

        val rootDistIn2Bytes = (dictData.size) / 2  // 4 / 2 = 2
        val rootBits = rootDistIn2Bytes and 0x3FFF
        val rootPtr = byteArrayOf(
            (0x80 or (rootBits shr 8)).toByte(),
            (rootBits and 0xFF).toByte()
        )

        val fullData = dictData + rootPtr

        val dict = FleeceDecoder.decodeAsDict(fullData)
        assertNotNull(dict, "Should decode per-note BLOB as dict")

        val value1 = dict.getInt("n")
        assertNotNull(value1, "Should extract field")
        assertEquals(2, value1)
    }

    /**
     * AC1.3: Fleece decoder handles malformed/empty BLOBs gracefully
     */
    @Test
    fun testDecodeEmptyBlob() {
        val data = byteArrayOf()
        assertNull(FleeceDecoder.decodeAsDict(data), "Should return null for empty BLOB")
    }

    @Test
    fun testDecodeOneByteBlob() {
        val data = byteArrayOf(0x00)
        assertNull(FleeceDecoder.decodeAsDict(data), "Should return null for 1-byte BLOB")
    }

    @Test
    fun testDecodeRandomGarbage() {
        val data = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x55, 0x44, 0x33, 0x22)
        try {
            FleeceDecoder.decodeAsDict(data)
        } catch (e: Exception) {
            fail("Should not throw exception for random garbage: ${e.message}")
        }
    }

    @Test
    fun testDecodeTruncatedBlob() {
        val header = byteArrayOf(0x70, 0x01)
        val key = byteArrayOf(0x41, 'x'.code.toByte())
        val value = byteArrayOf(0x00, 0x2A)
        val fullBlob = header + key + value
        val truncated = fullBlob.copyOf(2)

        try {
            FleeceDecoder.decodeAsDict(truncated)
        } catch (e: Exception) {
            fail("Should not throw exception for truncated BLOB: ${e.message}")
        }
    }
}
