package dev.aragonite.powersearch.data

// pattern: Imperative Shell

import dev.aragonite.hwr.HWRPoint
import dev.aragonite.hwr.HWRStroke
import org.json.JSONObject
import java.io.File

data class PageDimensions(val width: Float, val height: Float)

@Suppress("SdCardPath")
class StrokeDataRepository(private val ksyncRoot: File = File("/sdcard/.ksync")) {

    fun getPointFile(documentId: String, pageId: String, revisionId: String): File {
        return File(ksyncRoot, "point/$documentId/$pageId/$revisionId")
    }

    fun listPointFiles(documentId: String): List<File> {
        val noteDir = File(ksyncRoot, "point/$documentId")
        if (!noteDir.exists()) return emptyList()
        return noteDir.walkTopDown()
            .filter { it.isFile }
            .toList()
    }

    fun readStrokesForShape(pointFile: File, entry: XrefEntry): HWRStroke? {
        val tinyPoints = PointFileParser.readShapePoints(pointFile, entry)
        if (tinyPoints.isEmpty()) return null

        val hwrPoints = tinyPoints.map { tp ->
            HWRPoint(
                x = tp.x,
                y = tp.y,
                dt = tp.time,
                pressure = tp.pressure.toFloat() / 4095f
            )
        }
        return HWRStroke(points = hwrPoints, createdAtMs = System.currentTimeMillis())
    }

    // Backward-compatible UUID-based lookup (re-reads xref, used by tests)
    fun readStrokesForShapeByUuid(pointFile: File, shapeUuid: String): HWRStroke? {
        val xref = PointFileParser.readXref(pointFile)
        val entry = xref.find { it.shapeUuid == shapeUuid } ?: return null
        return readStrokesForShape(pointFile, entry)
    }

    fun getPageDimensions(documentId: String, pageId: String): PageDimensions? {
        val pbFile = File(ksyncRoot, "document/$documentId/virtual/page/pb/$pageId")
        if (!pbFile.exists()) return null
        val bytes = pbFile.readBytes()
        val content = String(bytes, Charsets.UTF_8)
        // Find the JSON bounds object within the protobuf
        val jsonStart = content.indexOf("{\"left\":")
        if (jsonStart < 0) return null
        val jsonEnd = content.indexOf("}", jsonStart)
        if (jsonEnd < 0) return null
        val json = JSONObject(content.substring(jsonStart, jsonEnd + 1))
        val width = json.optDouble("right", 1404.0).toFloat()
        val height = json.optDouble("bottom", 1872.0).toFloat()
        return PageDimensions(width, height)
    }
}
