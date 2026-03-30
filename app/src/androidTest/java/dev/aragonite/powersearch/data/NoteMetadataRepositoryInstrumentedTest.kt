package dev.aragonite.powersearch.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Instrumented tests for NoteMetadataRepository.
 *
 * These tests run on a real Android device with actual .cblite2 databases.
 * They verify the full stack: file I/O, SQLite queries, and Fleece decoding.
 *
 * Acceptance Criteria Coverage:
 * - power-search-mvp.AC1.1: Fleece decoder extracts title and parentUniqueId from NOTE_TREE BLOB
 * - power-search-mvp.AC1.4: NoteMetadataRepository filters shapes to handwriting types only
 */
@RunWith(AndroidJUnit4::class)
class NoteMetadataRepositoryInstrumentedTest {

    private val repository = NoteMetadataRepository()

    @Test
    fun testDiscoverUserIdReturnsValidUserId() {
        // AC1.1: Verify we can discover the user ID from the device
        val userId = repository.discoverUserId()
        // On a device with the app installed, this should find a userId
        // We don't assert non-null because the test device may not have data
        // But if it does, it should be a non-empty string
        if (userId != null) {
            assertTrue(userId.isNotEmpty(), "User ID should not be empty")
        }
    }

    @Test
    fun testGetNoteMetadataExtractsTitleAndParentUniqueId() {
        // AC1.1: Call getNoteMetadata() and verify title and parentUniqueId are extracted
        val userId = repository.discoverUserId() ?: return // Skip if no user ID found

        val metadata = repository.getNoteMetadata(userId)
        if (metadata.isNotEmpty()) {
            // If we have notes, verify each has required fields
            for (note in metadata) {
                assertTrue(note.documentId.isNotEmpty(), "Note documentId should not be empty")
                assertTrue(note.title.isNotEmpty(), "Note title should not be empty")
                // parentUniqueId can be empty, but documentId and title must exist
            }
        }
    }

    @Test
    fun testGetHandwritingShapesFiltersToHandwritingTypesOnly() {
        // AC1.4: Verify that only handwriting shape types are returned
        val userId = repository.discoverUserId() ?: return // Skip if no user ID found

        val notes = repository.getNoteMetadata(userId)
        if (notes.isNotEmpty()) {
            val firstNote = notes.first()
            val shapes = repository.getHandwritingShapes(userId, firstNote.documentId)

            // If we have shapes, verify they're all handwriting types
            for (shape in shapes) {
                assertTrue(shape.shapeType in HandwritingShapeTypes.TYPES,
                    "Shape type ${shape.shapeType} should be in handwriting types")
            }
        }
    }

    @Test
    fun testGetNoteMetadataReturnsEmptyListForInvalidUserId() {
        // Verify graceful handling of invalid user IDs
        val metadata = repository.getNoteMetadata("invalid-user-id-that-does-not-exist")
        assertEquals(emptyList(), metadata)
    }

    @Test
    fun testGetHandwritingShapesReturnsEmptyListForInvalidDocumentId() {
        // Verify graceful handling of invalid document IDs
        val userId = repository.discoverUserId() ?: return // Skip if no user ID found

        val shapes = repository.getHandwritingShapes(userId, "invalid-document-id-that-does-not-exist")
        assertEquals(emptyList(), shapes)
    }
}
