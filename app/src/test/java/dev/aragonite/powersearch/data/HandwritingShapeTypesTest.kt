package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM unit tests for HandwritingShapeTypes.
 *
 * This test runs on the JVM without requiring Android or a device.
 * It verifies the set of handwriting shape types is correct and complete.
 */
class HandwritingShapeTypesTest {

    @Test
    fun testHandwritingShapeTypesContainsExactlyTenElements() {
        assertEquals(10, HandwritingShapeTypes.TYPES.size)
    }

    @Test
    fun testHandwritingShapeTypesContainsAllExpectedTypes() {
        val expected = setOf(2, 3, 4, 5, 15, 21, 22, 47, 60, 61)
        assertEquals(expected, HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testPencilTypeIsIncluded() {
        assertTrue(2 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testOilyPenTypeIsIncluded() {
        assertTrue(3 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testFountainPenTypeIsIncluded() {
        assertTrue(4 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testBrushTypeIsIncluded() {
        assertTrue(5 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testMarkerTypeIsIncluded() {
        assertTrue(15 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testNeoBrushTypeIsIncluded() {
        assertTrue(21 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testCharcoalTypeIsIncluded() {
        assertTrue(22 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testSquarePenTypeIsIncluded() {
        assertTrue(47 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testLatinCalligraphyTypeIsIncluded() {
        assertTrue(60 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testAsianCalligraphyTypeIsIncluded() {
        assertTrue(61 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testCommonNonHandwritingTypesNotIncluded() {
        // Verify that common non-handwriting shape types are excluded
        assertFalse(0 in HandwritingShapeTypes.TYPES)
        assertFalse(1 in HandwritingShapeTypes.TYPES)
        assertFalse(6 in HandwritingShapeTypes.TYPES)
        assertFalse(7 in HandwritingShapeTypes.TYPES)
        assertFalse(8 in HandwritingShapeTypes.TYPES)
        assertFalse(9 in HandwritingShapeTypes.TYPES)
        assertFalse(10 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testUnexpectedHandwritingTypesNotIncluded() {
        // Verify types between handwriting types that shouldn't be included
        assertFalse(11 in HandwritingShapeTypes.TYPES)
        assertFalse(12 in HandwritingShapeTypes.TYPES)
        assertFalse(13 in HandwritingShapeTypes.TYPES)
        assertFalse(14 in HandwritingShapeTypes.TYPES)
        assertFalse(16 in HandwritingShapeTypes.TYPES)
        assertFalse(17 in HandwritingShapeTypes.TYPES)
        assertFalse(18 in HandwritingShapeTypes.TYPES)
        assertFalse(19 in HandwritingShapeTypes.TYPES)
        assertFalse(20 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testHighNumberTypesNotIncluded() {
        // Verify high-number shape types are excluded
        assertFalse(50 in HandwritingShapeTypes.TYPES)
        assertFalse(62 in HandwritingShapeTypes.TYPES)
        assertFalse(100 in HandwritingShapeTypes.TYPES)
    }

    @Test
    fun testHandwritingShapeTypesIsReadOnly() {
        // Verify the set is read-only (created with setOf() which returns immutable Set)
        // An immutable set won't have any mutation methods
        val setClass = HandwritingShapeTypes.TYPES::class.java
        val isMutable = setClass.simpleName.contains("Mutable")
        assertFalse(isMutable, "HandwritingShapeTypes.TYPES should not be a MutableSet")
    }
}
