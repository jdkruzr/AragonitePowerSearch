package dev.aragonite.powersearch.data

import java.io.File
import java.io.RandomAccessFile

data class XrefEntry(
    val shapeUuid: String,
    val dataOffset: Int,
    val dataLength: Int
)

data class TinyPoint(
    val x: Float,
    val y: Float,
    val size: Short,
    val pressure: Short,
    val time: Int
)

object PointFileParser {

    private const val HEADER_SIZE = 76
    private const val XREF_ENTRY_SIZE = 44
    private const val TINY_POINT_SIZE = 16
    private const val SHAPE_ATTR_SIZE = 4

    fun readXref(file: File): List<XrefEntry> {
        // Validate minimum file size
        if (file.length() < HEADER_SIZE + 4) {
            return emptyList()
        }

        val raf = RandomAccessFile(file, "r")
        return raf.use {
            // Read xref offset from last 4 bytes
            it.seek(it.length() - 4)
            val xrefOffset = it.readInt() // big-endian by default in RandomAccessFile

            // Validate xref offset bounds
            if (xrefOffset < HEADER_SIZE || xrefOffset >= it.length() - 4) {
                return emptyList()
            }

            // Calculate number of xref entries
            val xrefSize = it.length() - 4 - xrefOffset
            val entryCount = (xrefSize / XREF_ENTRY_SIZE).toInt()

            // Validate entry count
            if (entryCount <= 0) {
                return emptyList()
            }

            it.seek(xrefOffset.toLong())
            val entries = mutableListOf<XrefEntry>()
            val uuidBytes = ByteArray(36)
            repeat(entryCount) { _ ->
                raf.readFully(uuidBytes)
                val uuid = String(uuidBytes, Charsets.US_ASCII)
                val offset = raf.readInt()
                val length = raf.readInt()
                entries.add(XrefEntry(uuid, offset, length))
            }
            entries
        }
    }

    fun readShapePoints(file: File, entry: XrefEntry): List<TinyPoint> {
        // Validate entry bounds
        if (entry.dataOffset < 0 || entry.dataOffset + entry.dataLength > file.length()) {
            return emptyList()
        }

        // Validate minimum data length
        if (entry.dataLength < SHAPE_ATTR_SIZE) {
            return emptyList()
        }

        val raf = RandomAccessFile(file, "r")
        return raf.use {
            it.seek(entry.dataOffset.toLong())
            // Skip 4 bytes of shape attributes (attrA, attrB)
            it.readShort() // attrA
            it.readShort() // attrB
            val pointCount = (entry.dataLength - SHAPE_ATTR_SIZE) / TINY_POINT_SIZE
            val points = mutableListOf<TinyPoint>()
            repeat(pointCount) { _ ->
                points.add(
                    TinyPoint(
                        x = raf.readFloat(),
                        y = raf.readFloat(),
                        size = raf.readShort(),
                        pressure = raf.readShort(),
                        time = raf.readInt()
                    )
                )
            }
            points
        }
    }
}
