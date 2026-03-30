package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for IndexResult data class.
 *
 * IndexResult is JVM-testable (no Android dependencies) and represents
 * the outcome of an indexing operation.
 *
 * These tests verify the IndexResult data structure is constructed correctly.
 */
class IndexResultTest {

    /**
     * IndexResult with successful reindex (no errors)
     */
    @Test
    fun testIndexResultSuccess() {
        val result = IndexResult(
            processed = 10,
            failed = 0,
            deleted = 2,
            error = null
        )

        assertEquals(10, result.processed)
        assertEquals(0, result.failed)
        assertEquals(2, result.deleted)
        assertNull(result.error)
    }

    /**
     * IndexResult with failure (error message present)
     */
    @Test
    fun testIndexResultFailure() {
        val errorMsg = "Storage permission required. Grant 'All Files Access' in Settings."
        val result = IndexResult(
            processed = 0,
            failed = 0,
            deleted = 0,
            error = errorMsg
        )

        assertEquals(0, result.processed)
        assertEquals(0, result.failed)
        assertEquals(0, result.deleted)
        assertEquals(errorMsg, result.error)
    }

    /**
     * IndexResult with partial failure (some processed, some failed)
     */
    @Test
    fun testIndexResultPartialFailure() {
        val result = IndexResult(
            processed = 5,
            failed = 2,
            deleted = 1,
            error = "HWR service unavailable — shapes indexed without recognition text"
        )

        assertEquals(5, result.processed)
        assertEquals(2, result.failed)
        assertEquals(1, result.deleted)
        assertEquals("HWR service unavailable — shapes indexed without recognition text", result.error)
    }

    /**
     * IndexResult equality works correctly
     */
    @Test
    fun testIndexResultEquality() {
        val result1 = IndexResult(10, 2, 1, null)
        val result2 = IndexResult(10, 2, 1, null)

        assertEquals(result1, result2)
    }

    /**
     * IndexResult with zero counts
     */
    @Test
    fun testIndexResultZeroCounts() {
        val result = IndexResult(0, 0, 0, null)

        assertEquals(0, result.processed)
        assertEquals(0, result.failed)
        assertEquals(0, result.deleted)
        assertNull(result.error)
    }

    /**
     * IndexResult with large counts
     */
    @Test
    fun testIndexResultLargeCounts() {
        val result = IndexResult(1000, 50, 10, null)

        assertEquals(1000, result.processed)
        assertEquals(50, result.failed)
        assertEquals(10, result.deleted)
    }

    /**
     * IndexResult toString provides readable output
     */
    @Test
    fun testIndexResultToString() {
        val result = IndexResult(5, 1, 2, null)
        val str = result.toString()

        // Just verify toString doesn't crash and contains field names
        assert("processed" in str || "5" in str)
    }
}
