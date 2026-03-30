package dev.aragonite.powersearch.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataClassesTest {
    @Test
    fun testNoteMetadataDataClass() {
        val note = NoteMetadata(
            documentId = "note-123",
            title = "My Note",
            parentUniqueId = "parent-456"
        )

        assertEquals("note-123", note.documentId)
        assertEquals("My Note", note.title)
        assertEquals("parent-456", note.parentUniqueId)
    }

    @Test
    fun testShapeMetadataDataClass() {
        val shape = ShapeMetadata(
            uniqueId = "shape-789",
            shapeType = 2,
            revisionId = "rev-001"
        )

        assertEquals("shape-789", shape.uniqueId)
        assertEquals(2, shape.shapeType)
        assertEquals("rev-001", shape.revisionId)
    }
}
