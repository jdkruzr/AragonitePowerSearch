package dev.aragonite.powersearch.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aragonite.powersearch.data.db.IndexedShape
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Instrumented tests for deep linking to BOOX Notes app.
 *
 * Tests verify:
 * - AC3.3: Intent construction with correct component, documentId, parentUniqueId
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkTest {

    /**
     * AC3.3: Construct the Intent for a known IndexedShape.
     * Assert the Intent has correct component and extras.
     */
    @Test
    fun testBuildNoteIntentConstructsCorrectComponent() {
        // Arrange
        val shape = IndexedShape(
            shapeId = "shape-123",
            documentId = "doc-456",
            pageId = "page-789",
            parentUniqueId = "parent-999",
            noteTitle = "Test Note",
            recognizedText = "test text",
            pointFilePath = "/path/test",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        )

        // Act
        val intent = buildNoteIntent(shape)

        // Assert: Component is correct
        assertEquals(
            "com.onyx.android.note",
            intent.component?.packageName
        )
        assertEquals(
            "com.onyx.android.note.note.ui.ScribbleActivity",
            intent.component?.className
        )
    }

    /**
     * AC3.3: Verify documentId extra is present and matches the shape.
     */
    @Test
    fun testBuildNoteIntentIncludesDocumentIdExtra() {
        // Arrange
        val expectedDocumentId = "doc-abc123"
        val shape = IndexedShape(
            shapeId = "shape-123",
            documentId = expectedDocumentId,
            pageId = "page-789",
            parentUniqueId = "parent-999",
            noteTitle = "Test Note",
            recognizedText = "test text",
            pointFilePath = "/path/test",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        )

        // Act
        val intent = buildNoteIntent(shape)

        // Assert
        assertEquals(expectedDocumentId, intent.getStringExtra("documentId"))
    }

    /**
     * AC3.3: Verify parentUniqueId extra is present and matches the shape.
     */
    @Test
    fun testBuildNoteIntentIncludesParentUniqueIdExtra() {
        // Arrange
        val expectedParentUniqueId = "parent-xyz789"
        val shape = IndexedShape(
            shapeId = "shape-123",
            documentId = "doc-456",
            pageId = "page-789",
            parentUniqueId = expectedParentUniqueId,
            noteTitle = "Test Note",
            recognizedText = "test text",
            pointFilePath = "/path/test",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        )

        // Act
        val intent = buildNoteIntent(shape)

        // Assert
        assertEquals(expectedParentUniqueId, intent.getStringExtra("parentUniqueId"))
    }

    /**
     * AC3.3: Verify jump_from_document_path extra is present and identifies the calling app.
     */
    @Test
    fun testBuildNoteIntentIncludesJumpFromDocumentPathExtra() {
        // Arrange
        val shape = IndexedShape(
            shapeId = "shape-123",
            documentId = "doc-456",
            pageId = "page-789",
            parentUniqueId = "parent-999",
            noteTitle = "Test Note",
            recognizedText = "test text",
            pointFilePath = "/path/test",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        )

        // Act
        val intent = buildNoteIntent(shape)

        // Assert: jump_from_document_path is the app package identifier
        assertEquals(
            "dev.aragonite.powersearch",
            intent.getStringExtra("jump_from_document_path")
        )
    }

    /**
     * AC3.3: Comprehensive test - verify all intent properties are correct.
     */
    @Test
    fun testBuildNoteIntentCompleteVerification() {
        // Arrange
        val shape = IndexedShape(
            shapeId = "test-shape-id",
            documentId = "test-doc-id",
            pageId = "test-page-id",
            parentUniqueId = "test-parent-id",
            noteTitle = "Integration Test Note",
            recognizedText = "integration test content",
            pointFilePath = "/sdcard/.ksync/point/test/test/test",
            pointFileModified = 1234567890L,
            pointFileSize = 10000L,
            indexedAt = System.currentTimeMillis()
        )

        // Act
        val intent = buildNoteIntent(shape)

        // Assert all properties
        assert(intent.component != null) { "Component should not be null" }
        assertEquals("com.onyx.android.note", intent.component!!.packageName)
        assertEquals("com.onyx.android.note.note.ui.ScribbleActivity", intent.component!!.className)
        assertEquals("test-doc-id", intent.getStringExtra("documentId"))
        assertEquals("test-parent-id", intent.getStringExtra("parentUniqueId"))
        assertEquals("dev.aragonite.powersearch", intent.getStringExtra("jump_from_document_path"))
    }
}
