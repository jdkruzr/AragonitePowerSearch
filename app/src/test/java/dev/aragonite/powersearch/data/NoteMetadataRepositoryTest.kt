package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // Create a dedicated empty temp directory with no couch subdirectory
        val tempDir = File.createTempFile("test", "").parentFile!!
        val emptyDir = File(tempDir, "empty-ksync-${System.nanoTime()}")
        emptyDir.mkdir()

        try {
            val repo = NoteMetadataRepository(emptyDir)

            // If couch dir doesn't exist, should return null
            val userId = repo.discoverUserId()
            assertNull(userId, "discoverUserId should return null when couch directory doesn't exist")
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun testDiscoverUserIdReturnsUserIdWhenNoteTreeDirExists() {
        // Create a temp directory with a valid NOTE_TREE.cblite2 directory
        val tempDir = File.createTempFile("test", "").parentFile!!
        val ksyncDir = File(tempDir, "test-ksync-${System.nanoTime()}")
        ksyncDir.mkdir()

        val couchDir = File(ksyncDir, "couch")
        couchDir.mkdir()

        val noteTreeDir = File(couchDir, "someuser-NOTE_TREE.cblite2")
        noteTreeDir.mkdir()

        try {
            val repo = NoteMetadataRepository(ksyncDir)

            val userId = repo.discoverUserId()
            assertNotNull(userId, "discoverUserId should return non-null when NOTE_TREE directory exists")
            assertEquals("someuser", userId, "discoverUserId should extract user ID from directory name")
        } finally {
            ksyncDir.deleteRecursively()
        }
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
}
