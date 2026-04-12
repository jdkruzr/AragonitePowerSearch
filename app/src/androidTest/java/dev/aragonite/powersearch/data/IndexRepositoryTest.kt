package dev.aragonite.powersearch.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aragonite.powersearch.data.db.IndexedShape
import dev.aragonite.powersearch.data.db.SearchDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instrumented tests for IndexRepository diff logic and FTS search.
 *
 * These tests use Room's in-memory database to verify:
 * - Diff computation (new/modified/deleted file detection)
 * - FTS full-text search
 * - Upsert and delete operations
 *
 * Acceptance Criteria Coverage:
 * - power-search-mvp.AC4.1: New files are identified in diff
 * - power-search-mvp.AC4.2: Modified files are identified in diff
 * - power-search-mvp.AC4.3: Deleted files are identified in diff
 */
@RunWith(AndroidJUnit4::class)
class IndexRepositoryTest {

    private lateinit var db: SearchDatabase
    private lateinit var repository: IndexRepository

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Create in-memory database for testing
        db = Room.inMemoryDatabaseBuilder(
            context,
            SearchDatabase::class.java
        ).build()
        repository = IndexRepository(db.indexDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    // ===== Diff Logic Tests =====

    /**
     * AC4.1: New files (not in index) should appear in FileDiff.newFiles
     */
    @Test
    fun testComputeDiffIdentifiesNewFiles() = runTest {
        // Arrange: No indexed files yet
        val newFilePath = "/sdcard/.ksync/point/doc1/page1/rev1"
        val currentFiles = mapOf(
            newFilePath to Pair(1000L, 5000L)
        )

        // Act
        val diff = repository.computeDiff(currentFiles)

        // Assert: The file should be in newFiles
        assertEquals(1, diff.newFiles.size)
        assertEquals(newFilePath, diff.newFiles[0].absolutePath)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(0, diff.deletedPaths.size)
    }

    /**
     * AC4.2: Modified files (same path, different timestamp/size) should appear in FileDiff.modifiedFiles
     */
    @Test
    fun testComputeDiffIdentifiesModifiedFilesByTimestamp() = runTest {
        // Arrange: Insert a shape with specific file metadata
        val filePath = "/sdcard/.ksync/point/doc1/page1/rev1"
        val originalMod = 1000L
        val originalSize = 5000L

        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test Note",
            recognizedText = "hello",
            pointFilePath = filePath,
            pointFileModified = originalMod,
            pointFileSize = originalSize,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Query with same path but different timestamp
        val currentFiles = mapOf(
            filePath to Pair(2000L, originalSize) // Modified timestamp
        )
        val diff = repository.computeDiff(currentFiles)

        // Assert: File should be in modifiedFiles
        assertEquals(0, diff.newFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals(filePath, diff.modifiedFiles[0].absolutePath)
        assertEquals(0, diff.deletedPaths.size)
    }

    /**
     * AC4.2: Modified files (same path, different size) should appear in FileDiff.modifiedFiles
     */
    @Test
    fun testComputeDiffIdentifiesModifiedFilesBySize() = runTest {
        // Arrange: Insert a shape with specific file metadata
        val filePath = "/sdcard/.ksync/point/doc1/page1/rev1"
        val originalMod = 1000L
        val originalSize = 5000L

        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test Note",
            recognizedText = "hello",
            pointFilePath = filePath,
            pointFileModified = originalMod,
            pointFileSize = originalSize,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Query with same path but different size
        val currentFiles = mapOf(
            filePath to Pair(originalMod, 6000L) // Modified size
        )
        val diff = repository.computeDiff(currentFiles)

        // Assert: File should be in modifiedFiles
        assertEquals(0, diff.newFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals(filePath, diff.modifiedFiles[0].absolutePath)
        assertEquals(0, diff.deletedPaths.size)
    }

    /**
     * AC4.3: Deleted files (in index but not on filesystem) should appear in FileDiff.deletedPaths
     */
    @Test
    fun testComputeDiffIdentifiesDeletedFiles() = runTest {
        // Arrange: Insert a shape
        val filePath = "/sdcard/.ksync/point/doc1/page1/rev1"
        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test Note",
            recognizedText = "hello",
            pointFilePath = filePath,
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Query with empty map (no files on filesystem)
        val currentFiles = emptyMap<String, Pair<Long, Long>>()
        val diff = repository.computeDiff(currentFiles)

        // Assert: File should be in deletedPaths
        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(1, diff.deletedPaths.size)
        assertEquals(filePath, diff.deletedPaths[0])
    }

    /**
     * No diff when files match (same timestamp and size)
     */
    @Test
    fun testComputeDiffReturnsEmptyWhenFilesMatch() = runTest {
        // Arrange: Insert a shape
        val filePath = "/sdcard/.ksync/point/doc1/page1/rev1"
        val mod = 1000L
        val size = 5000L

        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test Note",
            recognizedText = "hello",
            pointFilePath = filePath,
            pointFileModified = mod,
            pointFileSize = size,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Query with matching metadata
        val currentFiles = mapOf(filePath to Pair(mod, size))
        val diff = repository.computeDiff(currentFiles)

        // Assert: No diff entries
        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(0, diff.deletedPaths.size)
    }

    /**
     * Diff handles mixed new, modified, and deleted files
     */
    @Test
    fun testComputeDiffHandlesMixedChanges() = runTest {
        // Arrange: Insert multiple shapes
        val file1 = "/sdcard/.ksync/point/doc1/page1/rev1"
        val file2 = "/sdcard/.ksync/point/doc1/page2/rev1"
        val file3 = "/sdcard/.ksync/point/doc1/page3/rev1"

        // file1: Will be modified
        // file2: Will be deleted
        // file3: Will stay unchanged
        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test",
            recognizedText = "a",
            pointFilePath = file1,
            pointFileModified = 1000L,
            pointFileSize = 1000L,
            indexedAt = System.currentTimeMillis()
        ))
        repository.upsertShape(IndexedShape(
            shapeId = "shape-2",
            documentId = "doc1",
            pageId = "page2",
            parentUniqueId = "parent1",
            noteTitle = "Test",
            recognizedText = "b",
            pointFilePath = file2,
            pointFileModified = 1000L,
            pointFileSize = 1000L,
            indexedAt = System.currentTimeMillis()
        ))
        repository.upsertShape(IndexedShape(
            shapeId = "shape-3",
            documentId = "doc1",
            pageId = "page3",
            parentUniqueId = "parent1",
            noteTitle = "Test",
            recognizedText = "c",
            pointFilePath = file3,
            pointFileModified = 1000L,
            pointFileSize = 1000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Query with file1 modified, file2 absent (deleted), file3 unchanged, file4 new
        val file4 = "/sdcard/.ksync/point/doc1/page4/rev1"
        val currentFiles = mapOf(
            file1 to Pair(2000L, 1000L), // Modified
            file3 to Pair(1000L, 1000L), // Unchanged
            file4 to Pair(1000L, 1000L)  // New
        )
        val diff = repository.computeDiff(currentFiles)

        // Assert
        assertEquals(1, diff.newFiles.size)
        assertEquals(file4, diff.newFiles[0].absolutePath)

        assertEquals(1, diff.modifiedFiles.size)
        assertEquals(file1, diff.modifiedFiles[0].absolutePath)

        assertEquals(1, diff.deletedPaths.size)
        assertEquals(file2, diff.deletedPaths[0])
    }

    // ===== FTS Search Tests =====

    /**
     * Blank query returns empty list
     */
    @Test
    fun testSearchWithBlankQueryReturnsEmpty() = runTest {
        // Arrange
        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test Note",
            recognizedText = "hello world",
            pointFilePath = "/path/1",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act
        val results = repository.search("")
        val resultsBlank = repository.search("   ")

        // Assert
        assertEquals(0, results.size)
        assertEquals(0, resultsBlank.size)
    }

    /**
     * Search matches recognized text (FTS on recognizedText field)
     */
    @Test
    fun testSearchMatchesRecognizedText() = runTest {
        // Arrange
        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "My Note",
            recognizedText = "hello world",
            pointFilePath = "/path/1",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act
        val results = repository.search("hello")

        // Assert
        assertEquals(1, results.size)
        assertEquals("shape-1", results[0].shapeId)
        assertEquals("hello world", results[0].recognizedText)
    }

    /**
     * Search matches note title (FTS on noteTitle field)
     */
    @Test
    fun testSearchMatchesNoteTitle() = runTest {
        // Arrange
        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Shopping List",
            recognizedText = "milk eggs bread",
            pointFilePath = "/path/1",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act
        val results = repository.search("Shopping")

        // Assert
        assertEquals(1, results.size)
        assertEquals("Shopping List", results[0].noteTitle)
    }

    /**
     * Upsert followed by search verifies complete workflow
     */
    @Test
    fun testUpsertAndSearchCompleteWorkflow() = runTest {
        // Arrange & Act
        val shape1 = IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Notes on Python",
            recognizedText = "def factorial(n): return n",
            pointFilePath = "/path/1",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        )
        val shape2 = IndexedShape(
            shapeId = "shape-2",
            documentId = "doc1",
            pageId = "page2",
            parentUniqueId = "parent1",
            noteTitle = "Notes on Java",
            recognizedText = "public static void main",
            pointFilePath = "/path/2",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        )

        repository.upsertShape(shape1)
        repository.upsertShape(shape2)

        // Assert: Search for Python-related content
        val pythonResults = repository.search("Python")
        assertEquals(1, pythonResults.size)
        assertEquals("shape-1", pythonResults[0].shapeId)

        // Assert: Search for code keyword
        val defResults = repository.search("def")
        assertEquals(1, defResults.size)
        assertEquals("shape-1", defResults[0].shapeId)

        // Assert: Search for Java-related content
        val javaResults = repository.search("Java")
        assertEquals(1, javaResults.size)
        assertEquals("shape-2", javaResults[0].shapeId)
    }

    /**
     * Multiple shapes can share the same point file path (multiple shapes per file)
     */
    @Test
    fun testMultipleShapesPerPointFile() = runTest {
        // Arrange
        val filePath = "/sdcard/.ksync/point/doc1/page1/rev1"

        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test",
            recognizedText = "hello",
            pointFilePath = filePath,
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))
        repository.upsertShape(IndexedShape(
            shapeId = "shape-2",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test",
            recognizedText = "world",
            pointFilePath = filePath,
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Delete by point file path
        repository.deleteByPointFile(filePath)

        // Assert: Both shapes should be deleted
        val count = repository.getIndexedShapeCount()
        assertEquals(0, count)
    }

    /**
     * Search with FTS special characters (unclosed quotes) returns empty list without throwing.
     * This ensures malformed query syntax doesn't crash the app.
     */
    @Test
    fun testSearchWithFtsSpecialCharactersDoesNotThrow() = runTest {
        // Arrange: Insert a shape with recognizable text
        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Test Note",
            recognizedText = "hello world",
            pointFilePath = "/path/1",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Search with unclosed quote (FTS4 syntax error)
        val results = repository.search("\"unclosed")

        // Assert: Returns empty list without throwing (query is sanitized/escaped)
        assertEquals(0, results.size)

        // Act: Search with asterisk (FTS operator)
        val resultsWildcard = repository.search("hello*")

        // Assert: Also returns empty without throwing
        assertEquals(0, resultsWildcard.size)

        // Act: Search with parentheses
        val resultsParens = repository.search("(hello)")

        // Assert: Also safe
        assertEquals(0, resultsParens.size)
    }

    // ===== Time-bounded empty-page retry =====

    /**
     * deleteEmptyPagesModifiedSince(cutoffMs) removes empty-recognizedText rows
     * whose pointFileModified is >= cutoffMs, and leaves everything else alone.
     *
     * This is the recovery path for the "black hole" scenario: when an earlier
     * indexing run had HWR unavailable, shapes were persisted with empty text
     * and the same (mtime, size) as the file, so computeDiff() skips them
     * forever. Time-bounded deletion lets users retry recent pages without
     * stomping on successfully-OCR'd rows.
     */
    @Test
    fun testDeleteEmptyPagesModifiedSinceRemovesOnlyEmptyRecentRows() = runTest {
        val cutoff = 5_000L

        // Empty + recent (mtime >= cutoff) -> should be deleted
        repository.upsertShape(IndexedShape(
            shapeId = "empty-recent",
            documentId = "doc1", pageId = "p1", parentUniqueId = "f1",
            noteTitle = "Recent Empty", recognizedText = "",
            pointFilePath = "/path/empty-recent",
            pointFileModified = 10_000L, pointFileSize = 100L,
            indexedAt = System.currentTimeMillis()
        ))

        // Empty + at the cutoff boundary -> should be deleted (inclusive)
        repository.upsertShape(IndexedShape(
            shapeId = "empty-at-cutoff",
            documentId = "doc1", pageId = "p2", parentUniqueId = "f1",
            noteTitle = "Boundary Empty", recognizedText = "",
            pointFilePath = "/path/empty-at-cutoff",
            pointFileModified = cutoff, pointFileSize = 100L,
            indexedAt = System.currentTimeMillis()
        ))

        // Empty + old (mtime < cutoff) -> should be KEPT
        repository.upsertShape(IndexedShape(
            shapeId = "empty-old",
            documentId = "doc1", pageId = "p3", parentUniqueId = "f1",
            noteTitle = "Old Empty", recognizedText = "",
            pointFilePath = "/path/empty-old",
            pointFileModified = 1_000L, pointFileSize = 100L,
            indexedAt = System.currentTimeMillis()
        ))

        // Non-empty + recent -> should be KEPT (don't stomp on valid output)
        repository.upsertShape(IndexedShape(
            shapeId = "text-recent",
            documentId = "doc1", pageId = "p4", parentUniqueId = "f1",
            noteTitle = "Recent Text", recognizedText = "hello world",
            pointFilePath = "/path/text-recent",
            pointFileModified = 10_000L, pointFileSize = 100L,
            indexedAt = System.currentTimeMillis()
        ))

        // Non-empty + old -> should be KEPT
        repository.upsertShape(IndexedShape(
            shapeId = "text-old",
            documentId = "doc1", pageId = "p5", parentUniqueId = "f1",
            noteTitle = "Old Text", recognizedText = "world hello",
            pointFilePath = "/path/text-old",
            pointFileModified = 1_000L, pointFileSize = 100L,
            indexedAt = System.currentTimeMillis()
        ))

        assertEquals(5, repository.getIndexedShapeCount())

        val deleted = repository.deleteEmptyPagesModifiedSince(cutoff)

        assertEquals(2, deleted)
        assertEquals(3, repository.getIndexedShapeCount())

        // Spot-check survivors via search (non-empty rows are still searchable).
        val hello = repository.search("hello")
        assertEquals(2, hello.size)

        // The "empty-old" row survives even though it has no text -- verify by
        // re-running computeDiff with matching metadata, which should skip it.
        val currentFiles = mapOf(
            "/path/empty-old" to Pair(1_000L, 100L)
        )
        val diff = repository.computeDiff(currentFiles)
        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
    }

    /**
     * After deletion, a subsequent computeDiff() sees the file as "new" so
     * it flows through HWR again. This is the whole point of the feature:
     * removing the row makes the file re-indexable.
     */
    @Test
    fun testDeletedEmptyRowsReappearAsNewInDiff() = runTest {
        val filePath = "/sdcard/.ksync/point/doc1/page1/rev1"
        val mtime = 10_000L

        repository.upsertShape(IndexedShape(
            shapeId = "shape-1",
            documentId = "doc1", pageId = "page1", parentUniqueId = "f1",
            noteTitle = "Test", recognizedText = "",
            pointFilePath = filePath,
            pointFileModified = mtime, pointFileSize = 100L,
            indexedAt = System.currentTimeMillis()
        ))

        // Before deletion: file matches DB, diff is empty.
        val beforeDiff = repository.computeDiff(mapOf(filePath to Pair(mtime, 100L)))
        assertEquals(0, beforeDiff.newFiles.size)

        // Delete empty rows with mtime >= 5000.
        assertEquals(1, repository.deleteEmptyPagesModifiedSince(5_000L))

        // After deletion: same filesystem state shows up as "new" and will
        // flow through the HWR pipeline on the next indexing run.
        val afterDiff = repository.computeDiff(mapOf(filePath to Pair(mtime, 100L)))
        assertEquals(1, afterDiff.newFiles.size)
        assertEquals(filePath, afterDiff.newFiles[0].absolutePath)
    }

    /**
     * Upsert replaces existing shape (same shapeId)
     */
    @Test
    fun testUpsertReplacesExistingShape() = runTest {
        // Arrange: Insert initial shape
        val shapeId = "shape-1"
        repository.upsertShape(IndexedShape(
            shapeId = shapeId,
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "Old Title",
            recognizedText = "old text",
            pointFilePath = "/path/1",
            pointFileModified = 1000L,
            pointFileSize = 5000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Act: Upsert with same shapeId but different data
        repository.upsertShape(IndexedShape(
            shapeId = shapeId,
            documentId = "doc1",
            pageId = "page1",
            parentUniqueId = "parent1",
            noteTitle = "New Title",
            recognizedText = "new text",
            pointFilePath = "/path/1",
            pointFileModified = 2000L,
            pointFileSize = 6000L,
            indexedAt = System.currentTimeMillis()
        ))

        // Assert: Count should still be 1, and new data should be stored
        assertEquals(1, repository.getIndexedShapeCount())
        val results = repository.search("new")
        assertEquals(1, results.size)
        assertEquals("New Title", results[0].noteTitle)
        assertEquals("new text", results[0].recognizedText)
    }
}
