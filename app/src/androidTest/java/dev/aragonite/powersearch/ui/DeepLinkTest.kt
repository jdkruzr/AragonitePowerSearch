package dev.aragonite.powersearch.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aragonite.powersearch.data.db.IndexedShape
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instrumented tests for deep linking to BOOX Notes app.
 *
 * Tests verify:
 * - AC3.3: Intent construction with correct component and OPEN_NOTE_BEAN JSON extra
 *
 * ScribbleActivity reads its note reference from intent.getStringExtra("OPEN_NOTE_BEAN"),
 * a JSON string containing documentId, parentUniqueId, and title.
 * Discovered via JADX decompilation of knote2-release.apk.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkTest {

    private fun makeShape(
        documentId: String = "doc-456",
        parentUniqueId: String = "parent-999",
        noteTitle: String = "Test Note"
    ) = IndexedShape(
        shapeId = "shape-123",
        documentId = documentId,
        pageId = "page-789",
        parentUniqueId = parentUniqueId,
        noteTitle = noteTitle,
        recognizedText = "test text",
        pointFilePath = "/path/test",
        pointFileModified = 1000L,
        pointFileSize = 5000L,
        indexedAt = System.currentTimeMillis()
    )

    @Test
    fun testBuildNoteIntentConstructsCorrectComponent() {
        val intent = buildNoteIntent(makeShape())
        val component = intent.component
        assertNotNull(component, "Component should not be null")
        assertEquals("com.onyx.android.note", component.packageName)
        assertEquals("com.onyx.android.note.note.ui.ScribbleActivity", component.className)
    }

    @Test
    fun testBuildNoteIntentIncludesOpenNoteBeanJson() {
        val intent = buildNoteIntent(makeShape())
        val json = intent.getStringExtra("OPEN_NOTE_BEAN")
        assertNotNull(json, "OPEN_NOTE_BEAN extra should not be null")
        // Should be valid JSON
        val obj = JSONObject(json)
        assertTrue(obj.has("documentId"), "JSON should contain documentId")
    }

    @Test
    fun testBuildNoteIntentJsonContainsDocumentId() {
        val intent = buildNoteIntent(makeShape(documentId = "doc-abc123"))
        val json = JSONObject(intent.getStringExtra("OPEN_NOTE_BEAN")!!)
        assertEquals("doc-abc123", json.getString("documentId"))
    }

    @Test
    fun testBuildNoteIntentJsonContainsParentUniqueId() {
        val intent = buildNoteIntent(makeShape(parentUniqueId = "parent-xyz789"))
        val json = JSONObject(intent.getStringExtra("OPEN_NOTE_BEAN")!!)
        assertEquals("parent-xyz789", json.getString("parentUniqueId"))
    }

    @Test
    fun testBuildNoteIntentJsonContainsTitle() {
        val intent = buildNoteIntent(makeShape(noteTitle = "My Test Note"))
        val json = JSONObject(intent.getStringExtra("OPEN_NOTE_BEAN")!!)
        assertEquals("My Test Note", json.getString("title"))
    }

    @Test
    fun testBuildNoteIntentJsonHandlesQuotesInTitle() {
        val intent = buildNoteIntent(makeShape(noteTitle = "Note with \"quotes\""))
        val json = intent.getStringExtra("OPEN_NOTE_BEAN")
        assertNotNull(json)
        // Should be parseable despite quotes in title
        val obj = JSONObject(json)
        assertTrue(obj.getString("title").contains("quotes"))
    }

    @Test
    fun testBuildNoteIntentCompleteVerification() {
        val intent = buildNoteIntent(makeShape(
            documentId = "test-doc-id",
            parentUniqueId = "test-parent-id",
            noteTitle = "Integration Test Note"
        ))

        val component = intent.component
        assertNotNull(component, "Component should not be null")
        assertEquals("com.onyx.android.note", component.packageName)
        assertEquals("com.onyx.android.note.note.ui.ScribbleActivity", component.className)

        val json = JSONObject(intent.getStringExtra("OPEN_NOTE_BEAN")!!)
        assertEquals("test-doc-id", json.getString("documentId"))
        assertEquals("test-parent-id", json.getString("parentUniqueId"))
        assertEquals("Integration Test Note", json.getString("title"))
    }
}
