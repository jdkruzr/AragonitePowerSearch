package dev.aragonite.powersearch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class PointFileParserTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("test_", "_dir").apply {
            delete()
            mkdirs()
        }
    }

    private fun createSyntheticPointFile(): File {
        val file = File(tempDir, "test_point.bin")
        val fos = FileOutputStream(file)

        DataOutputStream(fos).use { dos ->
            // Write 76-byte header
            val header = ByteArray(76)
            dos.write(header)

            // Write a single shape data block
            val shapeOffset = 76
            val xCoord = 100.5f
            val yCoord = 200.75f
            val size = 2.toShort()
            val pressure = 2000.toShort() // Should be normalized to ~0.49
            val time = 50

            dos.writeShort(0) // attrA
            dos.writeShort(0) // attrB
            dos.writeFloat(xCoord)
            dos.writeFloat(yCoord)
            dos.writeShort(size.toInt())
            dos.writeShort(pressure.toInt())
            dos.writeInt(time)

            val shapeDataLength = 4 + 16 // attrs (4 bytes) + 1 TinyPoint (16 bytes)
            val xrefOffset = 76 + shapeDataLength

            // Write xref table (1 entry)
            val uuid = "550e8400-e29b-41d4-a716-446655440000"
            dos.writeBytes(uuid)
            dos.writeInt(shapeOffset)
            dos.writeInt(shapeDataLength)

            // Write xref offset at end of file
            dos.writeInt(xrefOffset)
        }

        return file
    }

    @Test
    fun testReadXrefReturnsNonEmptyList() {
        val file = createSyntheticPointFile()

        val entries = PointFileParser.readXref(file)

        assertFalse("Xref entries should not be empty", entries.isEmpty())
    }

    @Test
    fun testReadXrefParsesMandatoryFields() {
        val file = createSyntheticPointFile()

        val entries = PointFileParser.readXref(file)

        assertEquals("Should have one entry", 1, entries.size)
        val entry = entries[0]
        assertEquals("UUID should be 36 chars", 36, entry.shapeUuid.length)
        assertEquals("UUID should match", "550e8400-e29b-41d4-a716-446655440000", entry.shapeUuid)
        assertTrue("Offset should be non-negative", entry.dataOffset >= 0)
        assertTrue("Length should be positive", entry.dataLength > 0)
    }

    @Test
    fun testReadShapePointsReturnsNonEmptyList() {
        val file = createSyntheticPointFile()
        val entries = PointFileParser.readXref(file)
        val entry = entries[0]

        val points = PointFileParser.readShapePoints(file, entry)

        assertFalse("Points list should not be empty", points.isEmpty())
    }

    @Test
    fun testReadShapePointsDecodesBigEndian() {
        val file = createSyntheticPointFile()
        val entries = PointFileParser.readXref(file)
        val entry = entries[0]

        val points = PointFileParser.readShapePoints(file, entry)

        assertEquals("Should have one point", 1, points.size)
        val point = points[0]

        // Check coordinates are in plausible range (big-endian decoding)
        // If little-endian, floats would be garbage values
        assertTrue("X should be in plausible range", point.x > 0 && point.x < 3000)
        assertTrue("Y should be in plausible range", point.y > 0 && point.y < 3000)
        assertTrue("X should be close to 100.5", abs(point.x - 100.5f) < 0.1)
        assertTrue("Y should be close to 200.75", abs(point.y - 200.75f) < 0.1)
    }

    @Test
    fun testReadShapePointsDecodesPressure() {
        val file = createSyntheticPointFile()
        val entries = PointFileParser.readXref(file)
        val entry = entries[0]

        val points = PointFileParser.readShapePoints(file, entry)

        val point = points[0]
        assertTrue("Pressure should be in 0-4095 range", point.pressure in 0..4095)
        assertEquals("Pressure should be 2000", 2000.toShort(), point.pressure)
    }

    @Test
    fun testReadShapePointsDecodesTime() {
        val file = createSyntheticPointFile()
        val entries = PointFileParser.readXref(file)
        val entry = entries[0]

        val points = PointFileParser.readShapePoints(file, entry)

        val point = points[0]
        assertEquals("Time should be 50", 50, point.time)
    }

    @Test
    fun testReadShapePointsWithMultiplePoints() {
        val file = File(tempDir, "test_point_multi.bin")
        val fos = FileOutputStream(file)

        DataOutputStream(fos).use { dos ->
            // Write 76-byte header
            val header = ByteArray(76)
            dos.write(header)

            // Write shape data with multiple points
            val shapeOffset = 76
            dos.writeShort(0) // attrA
            dos.writeShort(0) // attrB

            // Write 3 points
            for (i in 0..2) {
                dos.writeFloat((100 + i * 10).toFloat())
                dos.writeFloat((200 + i * 10).toFloat())
                dos.writeShort(2)
                dos.writeShort(2000)
                dos.writeInt(50 * (i + 1))
            }

            val shapeDataLength = 4 + 16 * 3
            val xrefOffset = 76 + shapeDataLength

            // Write xref table
            val uuid = "550e8400-e29b-41d4-a716-446655440000"
            dos.writeBytes(uuid)
            dos.writeInt(shapeOffset)
            dos.writeInt(shapeDataLength)

            // Write xref offset
            dos.writeInt(xrefOffset)
        }

        val entries = PointFileParser.readXref(file)
        val points = PointFileParser.readShapePoints(file, entries[0])

        assertEquals("Should have 3 points", 3, points.size)
        assertEquals("First point time should be 50", 50, points[0].time)
        assertEquals("Second point time should be 100", 100, points[1].time)
        assertEquals("Third point time should be 150", 150, points[2].time)
    }

    @Test
    fun testReadXrefWithEmptyFile() {
        val file = File(tempDir, "empty.bin")
        file.createNewFile()

        val entries = PointFileParser.readXref(file)

        assertTrue("Empty file should return empty list", entries.isEmpty())
    }

    @Test
    fun testReadXrefWithFileLessThanFourBytes() {
        val file = File(tempDir, "too_small.bin")
        file.writeBytes(byteArrayOf(1, 2, 3))

        val entries = PointFileParser.readXref(file)

        assertTrue("File < 4 bytes should return empty list", entries.isEmpty())
    }

    @Test
    fun testReadShapePointsWithEntryBeyondFileBounds() {
        val file = createSyntheticPointFile()
        // Create an entry that points beyond the actual file bounds
        val invalidEntry = XrefEntry(
            shapeUuid = "invalid",
            dataOffset = file.length().toInt() + 100,
            dataLength = 20
        )

        val points = PointFileParser.readShapePoints(file, invalidEntry)

        assertTrue("Entry beyond file bounds should return empty list", points.isEmpty())
    }
}
