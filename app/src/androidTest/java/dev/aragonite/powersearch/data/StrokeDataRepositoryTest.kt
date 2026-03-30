package dev.aragonite.powersearch.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class StrokeDataRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var ksyncRoot: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("test_", "_dir").apply {
            delete()
            mkdirs()
        }
        ksyncRoot = File(tempDir, "ksync")
        ksyncRoot.mkdirs()
    }

    private fun createSyntheticPointFile(noteId: String, pageId: String, revisionId: String): File {
        val pointDir = File(ksyncRoot, "point/$noteId/$pageId")
        pointDir.mkdirs()
        val file = File(pointDir, revisionId)

        val fos = FileOutputStream(file)
        DataOutputStream(fos).use { dos ->
            // Write 76-byte header
            val header = ByteArray(76)
            dos.write(header)

            // Write a single shape data block with multiple points
            val shapeOffset = 76
            dos.writeShort(0) // attrA
            dos.writeShort(0) // attrB

            // Write 2 points for testing
            dos.writeFloat(100.5f)
            dos.writeFloat(200.75f)
            dos.writeShort(2)
            dos.writeShort(2000) // ~0.49 when normalized
            dos.writeInt(50)

            dos.writeFloat(110.5f)
            dos.writeFloat(210.75f)
            dos.writeShort(2)
            dos.writeShort(3000) // ~0.73 when normalized
            dos.writeInt(100)

            val shapeDataLength = 4 + 16 * 2
            val xrefOffset = 76 + shapeDataLength

            // Write xref table
            val uuid = "550e8400-e29b-41d4-a716-446655440000"
            dos.writeBytes(uuid)
            dos.writeInt(shapeOffset)
            dos.writeInt(shapeDataLength)

            // Write xref offset at end
            dos.writeInt(xrefOffset)
        }

        return file
    }

    private fun createSyntheticPagePbFile(noteId: String, pageId: String): File {
        val pbDir = File(ksyncRoot, "document/$noteId/virtual/page/pb")
        pbDir.mkdirs()
        val file = File(pbDir, pageId)

        // Create a simple protobuf file with JSON bounds embedded
        val jsonBounds = """{"left":0,"top":0,"right":1404,"bottom":1872}"""
        file.writeText(jsonBounds)

        return file
    }

    @Test
    fun testGetPointFileReturnsCorrectPath() {
        val repo = StrokeDataRepository(ksyncRoot)

        val file = repo.getPointFile("note123", "page456", "rev789")

        assertEquals(
            "Point file path should match",
            File(ksyncRoot, "point/note123/page456/rev789").path,
            file.path
        )
    }

    @Test
    fun testListPointFilesReturnsEmptyWhenNotFound() {
        val repo = StrokeDataRepository(ksyncRoot)

        val files = repo.listPointFiles("nonexistent")

        assertTrue("List should be empty for non-existent note", files.isEmpty())
    }

    @Test
    fun testListPointFilesReturnsFilesWhenFound() {
        createSyntheticPointFile("note123", "page456", "rev789")
        val repo = StrokeDataRepository(ksyncRoot)

        val files = repo.listPointFiles("note123")

        assertFalse("List should not be empty", files.isEmpty())
        assertTrue("Should contain file", files.any { it.name == "rev789" })
    }

    @Test
    fun testReadStrokesForShapeReturnsStroke() {
        val file = createSyntheticPointFile("note123", "page456", "rev789")
        val repo = StrokeDataRepository(ksyncRoot)

        val stroke = repo.readStrokesForShapeByUuid(file, "550e8400-e29b-41d4-a716-446655440000")

        assertNotNull("Stroke should not be null", stroke)
        assertNotNull("Points should not be null", stroke!!.points)
        assertEquals("Should have 2 points", 2, stroke.points.size)
    }

    @Test
    fun testReadStrokesForShapeReturnsNullForNonexistentUuid() {
        val file = createSyntheticPointFile("note123", "page456", "rev789")
        val repo = StrokeDataRepository(ksyncRoot)

        val stroke = repo.readStrokesForShapeByUuid(file, "nonexistent-uuid")

        assertNull("Stroke should be null for non-existent UUID", stroke)
    }

    @Test
    fun testReadStrokesForShapeConvertsPressure() {
        val file = createSyntheticPointFile("note123", "page456", "rev789")
        val repo = StrokeDataRepository(ksyncRoot)

        val stroke = repo.readStrokesForShapeByUuid(file, "550e8400-e29b-41d4-a716-446655440000")!!

        val point1 = stroke.points[0]
        assertNotNull("Pressure should not be null", point1.pressure)
        val pressure1 = point1.pressure!!
        assertTrue("Pressure should be in 0-1 range", pressure1 >= 0f && pressure1 <= 1f)
        // 2000 / 4095 = ~0.488
        assertTrue("Pressure should be ~0.488", abs(pressure1 - (2000f / 4095f)) < 0.01)

        val point2 = stroke.points[1]
        assertNotNull("Pressure should not be null", point2.pressure)
        val pressure2 = point2.pressure!!
        // 3000 / 4095 = ~0.733
        assertTrue("Pressure should be ~0.733", abs(pressure2 - (3000f / 4095f)) < 0.01)
    }

    @Test
    fun testGetPageDimensionsReturnsNull() {
        val repo = StrokeDataRepository(ksyncRoot)

        val dims = repo.getPageDimensions("nonexistent", "page456")

        assertNull("Should return null for non-existent page", dims)
    }

    @Test
    fun testGetPageDimensionsExtractsWidthAndHeight() {
        val pbFile = createSyntheticPagePbFile("note123", "page456")
        // Verify file was created
        assertTrue("Page PB file should exist", pbFile.exists())
        assertTrue("Page PB file should be readable", pbFile.isFile)
        val content = pbFile.readText()
        assertTrue("File should contain JSON bounds", content.contains("\"left\""))

        val repo = StrokeDataRepository(ksyncRoot)
        val dims = repo.getPageDimensions("note123", "page456")

        assertNotNull("Dimensions should not be null", dims)
        assertEquals("Width should be 1404", 1404f, dims!!.width, 0.1f)
        assertEquals("Height should be 1872", 1872f, dims.height, 0.1f)
    }
}
