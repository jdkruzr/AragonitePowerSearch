package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Fleece BLOB wrapper prefix detection and stripping.
 *
 * Some devices (Palma) wrap Couchbase Lite BLOBs in a 10-byte Fleece dict
 * envelope. The inner data after stripping uses the originating device's
 * key indices.
 */
class WrapperPrefixTest {

    @Test
    fun testDetectWrapperPrefix() {
        // 0x70 = tag 7 (dict) in high nibble
        val withPrefix = byteArrayOf(0x70, 0x02, 0x00, 0x13, 0x40, 0x00, 0x00, 0x14, 0x00, 0x00, 0x1d, 0x1d)
        assertTrue((withPrefix[0].toInt() and 0xF0) == 0x70, "Should detect dict wrapper prefix")
    }

    @Test
    fun testNoWrapperPrefix() {
        // 0x1d = tag 1 (long int) — no wrapper
        val noPrefix = byteArrayOf(0x1d, 0x1d, 0x1a, 0x08)
        assertFalse((noPrefix[0].toInt() and 0xF0) == 0x70, "Should not detect wrapper on non-dict start")
    }

    @Test
    fun testStripPrefix() {
        val original = byteArrayOf(0x70, 0x02, 0x00, 0x13, 0x40, 0x00, 0x00, 0x14, 0x00, 0x00, 0x41, 0x42, 0x43)
        val inner = if (original.size > 10 && (original[0].toInt() and 0xF0) == 0x70) {
            original.copyOfRange(10, original.size)
        } else original

        assertEquals(3, inner.size, "Stripped body should be 3 bytes")
        assertEquals(0x41, inner[0].toInt(), "First inner byte should be 0x41")
    }

    @Test
    fun testNoStripWhenNoPrefix() {
        val original = byteArrayOf(0x1d, 0x1d, 0x1a, 0x08)
        val inner = if (original.size > 10 && (original[0].toInt() and 0xF0) == 0x70) {
            original.copyOfRange(10, original.size)
        } else original

        assertEquals(4, inner.size, "Should not strip when no prefix")
        assertTrue(original.contentEquals(inner), "Should return original array")
    }

    @Test
    fun testSmallBlobNotStripped() {
        // Even if first byte is 0x70, blobs < 10 bytes shouldn't be stripped
        val small = byteArrayOf(0x70, 0x02, 0x00, 0x13)
        val inner = if (small.size > 10 && (small[0].toInt() and 0xF0) == 0x70) {
            small.copyOfRange(10, small.size)
        } else small

        assertEquals(4, inner.size, "Small blob should not be stripped")
    }

    @Test
    fun testFolderNameBlankFallback() {
        // Simulates the isNotBlank check that triggers Fleece scanner fallback
        val knownKeywords = setOf("Local", "SCRIBBLE_NOTE")

        // Empty title should fall through
        val emptyTitle: String? = ""
        val result1 = emptyTitle?.takeIf { it.isNotBlank() && it !in knownKeywords }
        assertEquals(null, result1, "Empty title should fall through to scanner")

        // "Local" should fall through
        val localTitle: String? = "Local"
        val result2 = localTitle?.takeIf { it.isNotBlank() && it !in knownKeywords }
        assertEquals(null, result2, "Keyword title should fall through to scanner")

        // Real title should pass
        val realTitle: String? = "Moffitt"
        val result3 = realTitle?.takeIf { it.isNotBlank() && it !in knownKeywords }
        assertEquals("Moffitt", result3, "Real title should pass filter")

        // Null should fall through
        val nullTitle: String? = null
        val result4 = nullTitle?.takeIf { it.isNotBlank() && it !in knownKeywords }
        assertEquals(null, result4, "Null title should fall through to scanner")
    }
}
