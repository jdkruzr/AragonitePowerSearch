# Power Search MVP Implementation Plan — Phase 4: Stroke Data & Page Dimensions

**Goal:** Read binary point files (handwriting stroke data) and page dimension protobuf files from the BOOX device filesystem, converting them to AragoniteHWR data types.

**Architecture:** `StrokeDataRepository` in the `:app` module. Point file parser reads the binary format (76-byte header, xref table at end of file, 16-byte TinyPoint records). Page dimension parser reads protobuf files containing JSON bounds. Outputs AragoniteHWR's `HWRStroke`/`HWRPoint` types.

**Tech Stack:** Kotlin, `java.io.DataInputStream` (big-endian), `org.json.JSONObject` (Android built-in), AragoniteHWR data types

**Scope:** Phase 4 of 6 from original design

**Codebase verified:** 2026-03-29. After Phase 1, AragoniteHWR is available via `includeBuild`. Its API: `HWRStroke(points: List<HWRPoint>, createdAtMs: Long)`, `HWRPoint(x: Float, y: Float, dt: Int?, pressure: Float?)`. Point file format documented in ARAGONITE_POWER_SEARCH.md and design plan.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### power-search-mvp.AC1: App reads note data from .ksync filesystem
- **power-search-mvp.AC1.5 Success:** Point file parser reads xref table and extracts TinyPoint records with correct big-endian decoding
- **power-search-mvp.AC1.6 Success:** Page dimensions extracted from virtual/page/pb protobuf JSON bounds (right=width, bottom=height)

---

## Binary Format Reference

**Point file layout:**
```
Offset 0-75:   Header (76 bytes, ignored for MVP)
Offset 76..N:  Shape data blocks (variable length)
Last 4 bytes:  Big-endian int32 — absolute offset of xref table start
```

**Xref table:** Located at the offset stored in the last 4 bytes of the file. Each entry is 44 bytes:
```
Bytes 0-35:   UUID string (36 ASCII chars, e.g., "550e8400-e29b-41d4-a716-446655440000")
Bytes 36-39:  Big-endian int32 — byte offset of this shape's data within the file
Bytes 40-43:  Big-endian int32 — byte length of this shape's data
```

**Shape data block:** At the offset specified by xref entry:
```
Bytes 0-1:    attrA (big-endian short, ignored)
Bytes 2-3:    attrB (big-endian short, ignored)
Bytes 4..end: Sequence of TinyPoint records (16 bytes each)
```

Number of points = `(shapeDataLength - 4) / 16`

**TinyPoint record (16 bytes, all big-endian):**
```
Bytes 0-3:   float x      — X coordinate (screen pixels)
Bytes 4-7:   float y      — Y coordinate (screen pixels)
Bytes 8-9:   short size   — pen width (ignored for HWR)
Bytes 10-11: short pressure — 0-4095 (12-bit EMR)
Bytes 12-15: int time     — relative timestamp (ms)
```

**Conversion to HWRPoint:**
- `x` → direct
- `y` → direct
- `pressure` → `short.toFloat() / 4095f` (normalize to 0.0-1.0)
- `time` → `dt` (direct as Int)

**Page dimensions:** Located at `/sdcard/.ksync/document/{noteId}/virtual/page/pb/{pageId}`. This is a protobuf file containing an embedded JSON string with bounds: `{"left":0,"top":0,"right":1404,"bottom":1872}`. The `right` value = page width, `bottom` value = page height. These are needed as `viewWidth` and `viewHeight` parameters for `AragoniteHWR.recognizeStrokes()`.

The protobuf file has a known structure: the JSON bounds string appears as a length-delimited field. A practical approach is to scan the file bytes for the `{"left":` prefix and parse the JSON from there, rather than implementing a full protobuf decoder.

---

<!-- START_SUBCOMPONENT_A (tasks 1-3) -->

<!-- START_TASK_1 -->
### Task 1: Point file parser (xref and TinyPoint reading)

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/PointFileParser.kt`

**Implementation:**

`PointFileParser` reads a binary point file and returns parsed shape data. It is a stateless utility (no state, no Android dependencies beyond `java.io`).

```kotlin
package dev.aragonite.powersearch.data

import java.io.DataInputStream
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
        val raf = RandomAccessFile(file, "r")
        return raf.use {
            // Read xref offset from last 4 bytes
            it.seek(it.length() - 4)
            val xrefOffset = it.readInt() // big-endian by default in DataInputStream/RandomAccessFile

            // Calculate number of xref entries
            val xrefSize = it.length() - 4 - xrefOffset
            val entryCount = (xrefSize / XREF_ENTRY_SIZE).toInt()

            it.seek(xrefOffset.toLong())
            val entries = mutableListOf<XrefEntry>()
            val uuidBytes = ByteArray(36)
            repeat(entryCount) {_ ->
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
        val raf = RandomAccessFile(file, "r")
        return raf.use {
            it.seek(entry.dataOffset.toLong())
            // Skip 4 bytes of shape attributes (attrA, attrB)
            it.readShort() // attrA
            it.readShort() // attrB
            val pointCount = (entry.dataLength - SHAPE_ATTR_SIZE) / TINY_POINT_SIZE
            val points = mutableListOf<TinyPoint>()
            repeat(pointCount) { _ ->
                points.add(TinyPoint(
                    x = raf.readFloat(),
                    y = raf.readFloat(),
                    size = raf.readShort(),
                    pressure = raf.readShort(),
                    time = raf.readInt()
                ))
            }
            points
        }
    }
}
```

Note: `RandomAccessFile.readInt()`, `readFloat()`, `readShort()` all use big-endian byte order, matching the point file format.

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add PointFileParser for binary point file reading`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: StrokeDataRepository with HWR conversion and page dimensions

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/StrokeDataRepository.kt`

**Implementation:**

`StrokeDataRepository` wraps `PointFileParser` and adds:
1. TinyPoint → HWRPoint/HWRStroke conversion (pressure normalization)
2. Page dimension reading from protobuf files
3. File enumeration (listing available point files for a note)

```kotlin
package dev.aragonite.powersearch.data

import dev.aragonite.hwr.HWRPoint
import dev.aragonite.hwr.HWRStroke
import org.json.JSONObject
import java.io.File

data class PageDimensions(val width: Float, val height: Float)

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

    fun readStrokesForShape(pointFile: File, shapeUuid: String): HWRStroke? {
        val xref = PointFileParser.readXref(pointFile)
        val entry = xref.find { it.shapeUuid == shapeUuid } ?: return null
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
```

The page dimensions parser scans for `{"left":` in the protobuf bytes and parses the JSON bounds. Default dimensions (1404x1872) match the standard BOOX Note Air/Max screen size as a fallback.

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add StrokeDataRepository with HWR conversion and page dimensions`
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Point file parser and StrokeDataRepository tests

**Verifies:** power-search-mvp.AC1.5, power-search-mvp.AC1.6

**Files:**
- Create: `app/src/test/java/dev/aragonite/powersearch/data/PointFileParserTest.kt`
- Create: `app/src/androidTest/java/dev/aragonite/powersearch/data/StrokeDataRepositoryTest.kt`

**Prerequisite: Capture test fixtures from device**

Pull real point files and page dimension files from a BOOX device:

```bash
# Find a point file
adb shell find /sdcard/.ksync/point/ -type f | head -1
# Pull it
adb pull /sdcard/.ksync/point/{noteId}/{pageId}/{revisionId} app/src/test/resources/sample_point_file.bin

# Find a page dimension file
adb shell find /sdcard/.ksync/document/ -path "*/virtual/page/pb/*" -type f | head -1
# Pull it
adb pull /sdcard/.ksync/document/{noteId}/virtual/page/pb/{pageId} app/src/test/resources/sample_page_pb.bin
```

**Testing:**

`PointFileParserTest` — plain JVM unit test (no Android deps). Load the sample point file from test resources.

Tests must verify:
- **power-search-mvp.AC1.5:** `readXref()` returns a non-empty list of `XrefEntry`. Each entry has a 36-char UUID string, non-negative offset, positive length. `readShapePoints()` for the first xref entry returns non-empty list of `TinyPoint`. Each point has reasonable coordinate values (x > 0, y > 0), pressure in 0-4095 range. Verify big-endian decoding by checking that x/y values are in a plausible screen coordinate range (0-3000) rather than garbage values.

`StrokeDataRepositoryTest` — instrumented test on device (needs filesystem access).

Tests must verify:
- **power-search-mvp.AC1.6:** `getPageDimensions()` for a known note/page returns non-null `PageDimensions` with width > 0 and height > 0. Values should be plausible screen dimensions (e.g., width ~1404, height ~1872 for standard BOOX devices).
- `readStrokesForShape()` returns an `HWRStroke` with non-empty points list. Each `HWRPoint` has pressure in 0.0-1.0 range (normalized).
- `readStrokesForShape()` with non-existent shapeUuid returns null.
- `listPointFiles()` for a known note returns non-empty list.
- `listPointFiles()` for non-existent documentId returns empty list.

**Verification:**

Run: `./gradlew :app:test` (for PointFileParserTest)
Run: `./gradlew :app:connectedDebugAndroidTest` (for StrokeDataRepositoryTest, with device)
Expected: All tests pass

**Commit:** `test: add point file parser and StrokeDataRepository tests`
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_A -->
