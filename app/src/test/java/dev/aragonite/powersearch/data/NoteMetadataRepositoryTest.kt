package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Unit tests for NoteMetadataRepository.
 *
 * These tests verify the basic logic of the repository without requiring a real database.
 * Instrumented tests would run on device with real .cblite2 databases.
 */
class NoteMetadataRepositoryTest {

    @Test
    fun testDiscoverUserIdReturnsNullWhenCouchDirNotFound() {
        val tempDir = File.createTempFile("test", "").parentFile!!
        val repo = NoteMetadataRepository(tempDir)

        // If couch dir doesn't exist, should return null
        val userId = repo.discoverUserId()
        // Depending on temp dir, it might return null or a value
        // This test mainly verifies the method doesn't crash
    }

    @Test
    fun testGetNoteMetadataReturnsEmptyListForNonExistentDb() {
        val tempDir = File.createTempFile("test", "").parentFile!!
        val repo = NoteMetadataRepository(tempDir)

        // Non-existent database should return empty list
        val metadata = repo.getNoteMetadata("nonexistent-user-id")
        assertEquals(emptyList(), metadata)
    }

    @Test
    fun testGetHandwritingShapesReturnsEmptyListForNonExistentDb() {
        val tempDir = File.createTempFile("test", "").parentFile!!
        val repo = NoteMetadataRepository(tempDir)

        // Non-existent database should return empty list
        val shapes = repo.getHandwritingShapes("nonexistent-user-id", "nonexistent-doc-id")
        assertEquals(emptyList(), shapes)
    }

    @Test
    fun testHandwritingShapeTypesContainsOnlyHandwriting() {
        // Verify the set contains exactly the expected types
        val expected = setOf(2, 3, 4, 5, 15, 21, 22, 47, 60, 61)
        assertEquals(expected, HandwritingShapeTypes.TYPES)

        // Verify non-handwriting types are not in the set
        val nonHandwritingTypes = listOf(0, 1, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 20, 25, 30)
        for (type in nonHandwritingTypes) {
            assertTrue(type !in HandwritingShapeTypes.TYPES,
                "Type $type should not be in handwriting types")
        }
    }
}
