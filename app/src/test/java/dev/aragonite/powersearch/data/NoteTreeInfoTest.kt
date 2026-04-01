package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for NoteTreeInfo and folder-related data structures.
 */
class NoteTreeInfoTest {

    @Test
    fun testNoteMetadataWithFolderName() {
        val note = NoteMetadata(
            documentId = "doc1",
            title = "Meeting Notes",
            parentUniqueId = "Moffitt",
            folderName = "Moffitt"
        )
        assertEquals("Moffitt", note.folderName)
        assertEquals("Moffitt", note.parentUniqueId)
    }

    @Test
    fun testNoteMetadataDefaultFolderIsEmpty() {
        val note = NoteMetadata(
            documentId = "doc1",
            title = "Test",
            parentUniqueId = ""
        )
        assertEquals("", note.folderName)
    }

    @Test
    fun testNoteTreeInfoContainsAllData() {
        val notes = mapOf(
            "doc1" to NoteMetadata("doc1", "Note 1", "Moffitt", "Moffitt"),
            "doc2" to NoteMetadata("doc2", "Note 2", "Personal", "Personal")
        )
        val folders = mapOf("uuid1" to "Moffitt", "uuid2" to "Personal")
        val deleted = setOf("doc3", "doc4")
        val deletedFolders = setOf("uuid3")

        val info = NoteTreeInfo(notes, folders, deleted, deletedFolders)

        assertEquals(2, info.notes.size)
        assertEquals(2, info.folders.size)
        assertEquals(2, info.deletedNoteIds.size)
        assertEquals(1, info.deletedFolderIds.size)
        assertTrue("doc3" in info.deletedNoteIds)
        assertTrue("doc1" in info.notes)
        assertEquals("Moffitt", info.folders["uuid1"])
    }

    @Test
    fun testDeletedNoteIdSkipping() {
        val deletedIds = setOf("abc123", "def456", "ghi789")
        val testDocId = "abc123"
        assertTrue(testDocId in deletedIds, "Deleted note should be skipped")
        assertFalse("xyz000" in deletedIds, "Active note should not be skipped")
    }

    @Test
    fun testFolderAssignmentByUuidSubstring() {
        // Simulates the folder assignment logic: check if folder UUID appears in blob bytes
        val folders = mapOf(
            "aba69ebf-6b58-4aef-b5a3-6767977256f3" to "Moffitt",
            "57c19a47-c5ef-4a73-a4f5-31b8cb93461e" to "Personal"
        )
        val blobContent = "some data aba69ebf-6b58-4aef-b5a3-6767977256f3 more data"

        var foundFolder = ""
        for ((uuid, name) in folders) {
            if (blobContent.contains(uuid)) {
                foundFolder = name
                break
            }
        }
        assertEquals("Moffitt", foundFolder)
    }

    @Test
    fun testNoFolderMatchReturnsEmpty() {
        val folders = mapOf(
            "aba69ebf-6b58-4aef-b5a3-6767977256f3" to "Moffitt"
        )
        val blobContent = "some data with no folder uuid"

        var foundFolder = ""
        for ((uuid, name) in folders) {
            if (blobContent.contains(uuid)) {
                foundFolder = name
                break
            }
        }
        assertEquals("", foundFolder)
    }

    @Test
    fun testEmptyNoteTreeInfo() {
        val info = NoteTreeInfo(emptyMap(), emptyMap(), emptySet(), emptySet())
        assertTrue(info.notes.isEmpty())
        assertTrue(info.folders.isEmpty())
        assertTrue(info.deletedNoteIds.isEmpty())
        assertTrue(info.deletedFolderIds.isEmpty())
    }
}
