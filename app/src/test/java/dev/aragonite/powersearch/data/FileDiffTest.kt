package dev.aragonite.powersearch.data

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for FileDiff data class.
 *
 * FileDiff is JVM-testable (no Android dependencies) and represents
 * the result of comparing point files on disk against indexed state.
 *
 * These tests verify the FileDiff data structure is constructed correctly.
 */
class FileDiffTest {

    /**
     * FileDiff can be created with new files
     */
    @Test
    fun testFileDiffCreationWithNewFiles() {
        val newFile = File("/sdcard/.ksync/point/doc1/page1/rev1")
        val diff = FileDiff(
            newFiles = listOf(newFile),
            modifiedFiles = emptyList(),
            deletedPaths = emptyList()
        )

        assertEquals(1, diff.newFiles.size)
        assertEquals(newFile.absolutePath, diff.newFiles[0].absolutePath)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(0, diff.deletedPaths.size)
    }

    /**
     * FileDiff can be created with modified files
     */
    @Test
    fun testFileDiffCreationWithModifiedFiles() {
        val modifiedFile = File("/sdcard/.ksync/point/doc1/page1/rev1")
        val diff = FileDiff(
            newFiles = emptyList(),
            modifiedFiles = listOf(modifiedFile),
            deletedPaths = emptyList()
        )

        assertEquals(0, diff.newFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals(modifiedFile.absolutePath, diff.modifiedFiles[0].absolutePath)
        assertEquals(0, diff.deletedPaths.size)
    }

    /**
     * FileDiff can be created with deleted paths
     */
    @Test
    fun testFileDiffCreationWithDeletedPaths() {
        val deletedPath = "/sdcard/.ksync/point/doc1/page1/rev1"
        val diff = FileDiff(
            newFiles = emptyList(),
            modifiedFiles = emptyList(),
            deletedPaths = listOf(deletedPath)
        )

        assertEquals(0, diff.newFiles.size)
        assertEquals(0, diff.modifiedFiles.size)
        assertEquals(1, diff.deletedPaths.size)
        assertEquals(deletedPath, diff.deletedPaths[0])
    }

    /**
     * FileDiff can contain mixed changes
     */
    @Test
    fun testFileDiffWithMixedChanges() {
        val newFile1 = File("/sdcard/.ksync/point/doc1/page1/rev1")
        val newFile2 = File("/sdcard/.ksync/point/doc1/page2/rev1")
        val modFile1 = File("/sdcard/.ksync/point/doc1/page3/rev1")
        val delPath1 = "/sdcard/.ksync/point/doc1/page4/rev1"
        val delPath2 = "/sdcard/.ksync/point/doc1/page5/rev1"

        val diff = FileDiff(
            newFiles = listOf(newFile1, newFile2),
            modifiedFiles = listOf(modFile1),
            deletedPaths = listOf(delPath1, delPath2)
        )

        assertEquals(2, diff.newFiles.size)
        assertEquals(1, diff.modifiedFiles.size)
        assertEquals(2, diff.deletedPaths.size)
    }

    /**
     * FileDiff equality works correctly
     */
    @Test
    fun testFileDiffEquality() {
        val file1 = File("/sdcard/.ksync/point/doc1/page1/rev1")
        val file2 = File("/sdcard/.ksync/point/doc1/page2/rev1")
        val path1 = "/sdcard/.ksync/point/doc1/page3/rev1"

        val diff1 = FileDiff(
            newFiles = listOf(file1),
            modifiedFiles = listOf(file2),
            deletedPaths = listOf(path1)
        )

        val diff2 = FileDiff(
            newFiles = listOf(file1),
            modifiedFiles = listOf(file2),
            deletedPaths = listOf(path1)
        )

        assertEquals(diff1, diff2)
    }

    /**
     * FileDiff can be empty (all lists empty)
     */
    @Test
    fun testFileDiffEmpty() {
        val diff = FileDiff(
            newFiles = emptyList(),
            modifiedFiles = emptyList(),
            deletedPaths = emptyList()
        )

        assertTrue(diff.newFiles.isEmpty())
        assertTrue(diff.modifiedFiles.isEmpty())
        assertTrue(diff.deletedPaths.isEmpty())
    }

    /**
     * FileDiff preserves file order
     */
    @Test
    fun testFileDiffPreservesFileOrder() {
        val files = listOf(
            File("/sdcard/.ksync/point/doc1/page1/rev1"),
            File("/sdcard/.ksync/point/doc1/page2/rev1"),
            File("/sdcard/.ksync/point/doc1/page3/rev1")
        )

        val diff = FileDiff(
            newFiles = files,
            modifiedFiles = emptyList(),
            deletedPaths = emptyList()
        )

        for (i in files.indices) {
            assertEquals(files[i].absolutePath, diff.newFiles[i].absolutePath)
        }
    }
}
