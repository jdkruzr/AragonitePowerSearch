package dev.aragonite.powersearch.data

import dev.aragonite.hwr.HWRPoint
import dev.aragonite.hwr.HWRStroke
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for stroke filtering logic used in the Indexer.
 *
 * Single-point strokes (DOWN with no MOVE/UP) cause MyScript's KHwrService
 * to hang indefinitely. The Indexer must filter these out before sending
 * strokes to HWR.
 *
 * Discovered 2026-03-31 via HWR test harness: a single HWRStroke with 1 point
 * reliably kills the MyScript recognizer with a 10-second timeout.
 */
class StrokeFilterTest {

    private fun makeStroke(pointCount: Int): HWRStroke {
        val points = (0 until pointCount).map { i ->
            HWRPoint(
                x = 100f + i * 20f,
                y = 500f + i * 10f,
                dt = i * 10,
                pressure = 0.5f
            )
        }
        return HWRStroke(points = points, createdAtMs = System.currentTimeMillis())
    }

    /**
     * The filter: strokes with fewer than 2 points should be excluded.
     * This mirrors the logic in Indexer.processPointFile().
     */
    private fun filterStrokes(strokes: List<HWRStroke>): List<HWRStroke> {
        return strokes.filter { it.points.size >= 2 }
    }

    @Test
    fun testSinglePointStrokeIsFiltered() {
        val strokes = listOf(makeStroke(1))
        val filtered = filterStrokes(strokes)
        assertEquals(0, filtered.size, "Single-point stroke should be filtered out")
    }

    @Test
    fun testTwoPointStrokeIsKept() {
        val strokes = listOf(makeStroke(2))
        val filtered = filterStrokes(strokes)
        assertEquals(1, filtered.size, "Two-point stroke should be kept")
    }

    @Test
    fun testNormalStrokesUnaffected() {
        val strokes = listOf(makeStroke(10), makeStroke(50), makeStroke(100))
        val filtered = filterStrokes(strokes)
        assertEquals(3, filtered.size, "Normal strokes should all be kept")
    }

    @Test
    fun testMixedBatchFiltersPoisonOnly() {
        val strokes = listOf(
            makeStroke(20),   // keep
            makeStroke(1),    // poison — filter
            makeStroke(50),   // keep
            makeStroke(1),    // poison — filter
            makeStroke(100)   // keep
        )
        val filtered = filterStrokes(strokes)
        assertEquals(3, filtered.size, "Only single-point strokes should be filtered")
    }

    @Test
    fun testEmptyStrokeListUnaffected() {
        val filtered = filterStrokes(emptyList())
        assertEquals(0, filtered.size)
    }

    @Test
    fun testZeroPointStrokeIsFiltered() {
        val stroke = HWRStroke(points = emptyList(), createdAtMs = System.currentTimeMillis())
        val filtered = filterStrokes(listOf(stroke))
        assertEquals(0, filtered.size, "Zero-point stroke should be filtered out")
    }

    @Test
    fun testAllPoisonReturnsEmpty() {
        val strokes = listOf(makeStroke(1), makeStroke(1), makeStroke(1))
        val filtered = filterStrokes(strokes)
        assertEquals(0, filtered.size, "All-poison batch should return empty")
    }
}
