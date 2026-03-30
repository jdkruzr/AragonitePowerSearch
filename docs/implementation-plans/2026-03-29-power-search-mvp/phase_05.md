# Power Search MVP Implementation Plan — Phase 5: Search Index & HWR Integration

**Goal:** Room database with FTS full-text search, AragoniteHWR wrapper, and full indexing pipeline that orchestrates the scan→diff→metadata→strokes→HWR→store workflow.

**Architecture:** Three new components: `IndexRepository` (Room + FTS4 database for search index and diff state), `HWRRepository` (thin wrapper around AragoniteHWR service binding), and `Indexer` (orchestrates the full reindex pipeline using all four repositories).

**Tech Stack:** Room with FTS4 (not FTS5 — Room has `@Fts4` but no `@Fts5` annotation; FTS4 achieves the same keyword search goal), AragoniteHWR, Kotlin coroutines

**Scope:** Phase 5 of 6 from original design

**Codebase verified:** 2026-03-29. After Phases 1-4: `:app` module has `NoteMetadataRepository`, `StrokeDataRepository`, `PointFileParser` with data classes. `:fleece` module has `FleeceDecoder`. AragoniteHWR available via composite build. Room dependency declared in `app/build.gradle.kts` but no Room database created yet.

**Design deviation:** The design plan specifies FTS5, but Room only supports FTS3/FTS4 via annotations. FTS4 provides the same full-text MATCH queries needed for keyword search. Using `@Fts4` with `contentEntity` gives automatic sync between the main table and FTS index, with zero manual SQL triggers.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### power-search-mvp.AC2: HWR processes shapes and stores results
- **power-search-mvp.AC2.1 Success:** Strokes converted to HWRPoint with pressure normalized (short/4095f) and passed to recognizeStrokes()
- **power-search-mvp.AC2.2 Success:** Recognized text stored in Room database keyed by shapeId, with note metadata (title, documentId, pageId, parentUniqueId)
- **power-search-mvp.AC2.3 Failure:** HWR returning empty/null for a shape does not block indexing of remaining shapes

### power-search-mvp.AC4: Manual reindex with diff
- **power-search-mvp.AC4.1 Success:** New point files (not in index) are processed and added
- **power-search-mvp.AC4.2 Success:** Modified point files (changed timestamp or size) are re-processed and updated
- **power-search-mvp.AC4.3 Success:** Deleted point files (in index but not on filesystem) are removed from index
- **power-search-mvp.AC4.5 Failure:** Reindex with no storage permission shows informative error, not crash
- **power-search-mvp.AC4.6 Failure:** Reindex when HWR service unavailable reports error and skips recognition gracefully

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->

<!-- START_TASK_1 -->
### Task 1: Room database schema (IndexedShape entity + FTS4)

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/db/IndexedShape.kt`
- Create: `app/src/main/java/dev/aragonite/powersearch/data/db/IndexedShapeFts.kt`
- Create: `app/src/main/java/dev/aragonite/powersearch/data/db/IndexDao.kt`
- Create: `app/src/main/java/dev/aragonite/powersearch/data/db/SearchDatabase.kt`

**Implementation:**

Define the Room entity, FTS entity, DAO, and database.

`IndexedShape.kt`:
```kotlin
package dev.aragonite.powersearch.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "indexed_shapes")
data class IndexedShape(
    @PrimaryKey val shapeId: String,
    val documentId: String,
    val pageId: String,
    val parentUniqueId: String,
    val noteTitle: String,
    val recognizedText: String,
    val pointFilePath: String,
    val pointFileModified: Long,
    val pointFileSize: Long,
    val indexedAt: Long
)
```

`IndexedShapeFts.kt`:
```kotlin
package dev.aragonite.powersearch.data.db

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = IndexedShape::class)
@Entity(tableName = "indexed_shapes_fts")
data class IndexedShapeFts(
    val recognizedText: String,
    val noteTitle: String
)
```

With `contentEntity = IndexedShape::class`, Room automatically syncs the FTS index when rows are inserted/updated/deleted in `indexed_shapes`.

`IndexDao.kt`:
```kotlin
package dev.aragonite.powersearch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShape(shape: IndexedShape)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShapes(shapes: List<IndexedShape>)

    @Query("DELETE FROM indexed_shapes WHERE pointFilePath = :pointFilePath")
    suspend fun deleteByPointFile(pointFilePath: String)

    @Query("DELETE FROM indexed_shapes WHERE shapeId = :shapeId")
    suspend fun deleteShape(shapeId: String)

    @Query("SELECT pointFilePath, pointFileModified, pointFileSize FROM indexed_shapes GROUP BY pointFilePath")
    suspend fun getIndexedFileState(): List<IndexedFileInfo>

    @Query("""
        SELECT s.* FROM indexed_shapes s
        JOIN indexed_shapes_fts fts ON s.rowid = fts.rowid
        WHERE indexed_shapes_fts MATCH :query
    """)
    suspend fun search(query: String): List<IndexedShape>

    @Query("SELECT COUNT(*) FROM indexed_shapes")
    suspend fun getIndexedShapeCount(): Int
}

data class IndexedFileInfo(
    val pointFilePath: String,
    val pointFileModified: Long,
    val pointFileSize: Long
)
```

`SearchDatabase.kt`:
```kotlin
package dev.aragonite.powersearch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [IndexedShape::class, IndexedShapeFts::class], version = 1)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun indexDao(): IndexDao

    companion object {
        @Volatile
        private var INSTANCE: SearchDatabase? = null

        fun create(context: Context): SearchDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchDatabase::class.java,
                    "power_search.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles (Room annotation processing via KSP generates schema)

**Commit:** `feat: add Room database schema with IndexedShape and FTS4 index`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: IndexRepository with diff logic

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/IndexRepository.kt`

**Implementation:**

`IndexRepository` wraps the DAO and adds filesystem diff logic.

```kotlin
package dev.aragonite.powersearch.data

import dev.aragonite.powersearch.data.db.IndexDao
import dev.aragonite.powersearch.data.db.IndexedShape
import java.io.File

data class FileDiff(
    val newFiles: List<File>,
    val modifiedFiles: List<File>,
    val deletedPaths: List<String>
)

class IndexRepository(private val dao: IndexDao) {

    suspend fun computeDiff(currentFiles: Map<String, Pair<Long, Long>>): FileDiff {
        val indexedState = dao.getIndexedFileState().associateBy { it.pointFilePath }

        val newFiles = mutableListOf<File>()
        val modifiedFiles = mutableListOf<File>()

        for ((path, fileMeta) in currentFiles) {
            val (modified, size) = fileMeta
            val indexed = indexedState[path]
            if (indexed == null) {
                newFiles.add(File(path))
            } else if (indexed.pointFileModified != modified || indexed.pointFileSize != size) {
                modifiedFiles.add(File(path))
            }
        }

        val deletedPaths = indexedState.keys.filter { it !in currentFiles }

        return FileDiff(newFiles, modifiedFiles, deletedPaths)
    }

    suspend fun upsertShape(shape: IndexedShape) = dao.upsertShape(shape)

    suspend fun deleteByPointFile(path: String) = dao.deleteByPointFile(path)

    suspend fun search(query: String): List<IndexedShape> {
        if (query.isBlank()) return emptyList()
        return dao.search(query)
    }

    suspend fun getIndexedShapeCount(): Int = dao.getIndexedShapeCount()
}
```

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add IndexRepository with filesystem diff logic`
<!-- END_TASK_2 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3-4) -->

<!-- START_TASK_3 -->
### Task 3: HWRRepository (AragoniteHWR wrapper)

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/HWRRepository.kt`

**Implementation:**

`HWRRepository` wraps AragoniteHWR's service binding and recognition calls.

```kotlin
package dev.aragonite.powersearch.data

import android.content.Context
import dev.aragonite.hwr.AragoniteHWR
import dev.aragonite.hwr.HWRStroke

class HWRRepository(private val context: Context) {

    private var isBound = false

    suspend fun bind(): Boolean {
        if (isBound) return true
        isBound = AragoniteHWR.bindAndAwait(context)
        return isBound
    }

    fun unbind() {
        if (isBound) {
            AragoniteHWR.unbind(context)
            isBound = false
        }
    }

    suspend fun recognizeStrokes(
        strokes: List<HWRStroke>,
        viewWidth: Float,
        viewHeight: Float
    ): String? {
        if (!isBound) return null
        return AragoniteHWR.recognizeStrokes(strokes, viewWidth, viewHeight)
    }
}
```

This is a thin wrapper. The main value is lifecycle management (bind once, use many times during reindex, unbind when done) and null safety.

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add HWRRepository wrapping AragoniteHWR service`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Indexer orchestration

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/Indexer.kt`

**Implementation:**

The `Indexer` is the core use case class that orchestrates the full reindex pipeline. It coordinates all four repositories.

```kotlin
package dev.aragonite.powersearch.data

import android.os.Environment
import dev.aragonite.powersearch.data.db.IndexedShape
import java.io.File

data class IndexProgress(
    val phase: String,
    val current: Int,
    val total: Int
)

class Indexer(
    private val noteMetadata: NoteMetadataRepository,
    private val strokeData: StrokeDataRepository,
    private val index: IndexRepository,
    private val hwr: HWRRepository
) {
    suspend fun reindex(onProgress: (IndexProgress) -> Unit = {}): IndexResult {
        // AC4.5: Check storage permission before accessing filesystem
        if (!Environment.isExternalStorageManager()) {
            return IndexResult(0, 0, 0, "Storage permission required. Grant 'All Files Access' in Settings.")
        }

        val ksyncRoot = File("/sdcard/.ksync")
        var processed = 0
        var failed = 0
        var deleted = 0

        // Step 1: Scan point files
        onProgress(IndexProgress("Scanning files", 0, 0))
        val pointDir = File(ksyncRoot, "point")
        if (!pointDir.exists()) return IndexResult(0, 0, 0, "No point directory found")

        val currentFiles = mutableMapOf<String, Pair<Long, Long>>()
        pointDir.walkTopDown().filter { it.isFile }.forEach { file ->
            currentFiles[file.absolutePath] = Pair(file.lastModified(), file.length())
        }

        // Step 2: Compute diff
        onProgress(IndexProgress("Computing diff", 0, 0))
        val diff = index.computeDiff(currentFiles)
        val filesToProcess = diff.newFiles + diff.modifiedFiles
        val total = filesToProcess.size

        // Step 3: Bind HWR service (AC4.6: graceful handling if unavailable)
        val hwrAvailable = hwr.bind()

        // Step 4: Discover user ID and cache note metadata
        val userId = noteMetadata.discoverUserId()
        val noteMetadataMap: Map<String, NoteMetadata> = if (userId != null) {
            noteMetadata.getNoteMetadata(userId).associateBy { it.documentId }
        } else emptyMap()

        // Cache handwriting shapes per document to avoid repeated DB reads
        val handwritingShapeCache = mutableMapOf<String, Set<String>>()

        // Step 5: Process new/modified files
        val modifiedSet = diff.modifiedFiles.toSet()
        for ((i, pointFile) in filesToProcess.withIndex()) {
            onProgress(IndexProgress("Indexing", i + 1, total))
            try {
                // For modified files, remove old shapes before re-indexing to prevent stale entries
                if (pointFile in modifiedSet) {
                    index.deleteByPointFile(pointFile.absolutePath)
                }
                processPointFile(pointFile, userId, hwrAvailable, noteMetadataMap, handwritingShapeCache)
                processed++
            } catch (e: Exception) {
                failed++
            }
        }

        // Step 6: Remove deleted files
        for (path in diff.deletedPaths) {
            index.deleteByPointFile(path)
            deleted++
        }

        // Step 7: Unbind HWR
        hwr.unbind()

        val error = if (!hwrAvailable) "HWR service unavailable — shapes indexed without recognition text" else null
        return IndexResult(processed, failed, deleted, error)
    }

    private suspend fun processPointFile(
        pointFile: File,
        userId: String?,
        hwrAvailable: Boolean,
        noteMetadataMap: Map<String, NoteMetadata>,
        handwritingShapeCache: MutableMap<String, Set<String>>
    ) {
        // Extract documentId and pageId from path:
        // /sdcard/.ksync/point/{documentId}/{pageId}/{revisionId}
        val parts = pointFile.absolutePath.split("/")
        val pointIdx = parts.indexOf("point")
        if (pointIdx < 0 || pointIdx + 3 >= parts.size) return
        val documentId = parts[pointIdx + 1]
        val pageId = parts[pointIdx + 2]

        // Get note metadata from cache (not DB — already loaded in reindex())
        val note = noteMetadataMap[documentId]
        val noteTitle = note?.title ?: ""
        val parentUniqueId = note?.parentUniqueId ?: ""

        // Get page dimensions for HWR
        val pageDims = strokeData.getPageDimensions(documentId, pageId)
        val viewWidth = pageDims?.width ?: 1404f
        val viewHeight = pageDims?.height ?: 1872f

        // Read xref to get shapes in this file
        val xref = PointFileParser.readXref(pointFile)

        // Get handwriting shape filter from cache (per-document, loaded once)
        val handwritingShapeIds: Set<String>? = if (userId != null) {
            handwritingShapeCache.getOrPut(documentId) {
                noteMetadata.getHandwritingShapes(userId, documentId)
                    .map { it.uniqueId }
                    .toSet()
            }
        } else null

        for (entry in xref) {
            // Filter to handwriting shapes if we have metadata
            if (handwritingShapeIds != null && entry.shapeUuid !in handwritingShapeIds) continue

            val stroke = strokeData.readStrokesForShape(pointFile, entry.shapeUuid) ?: continue

            // Recognize text (skip if HWR unavailable — AC2.3, AC4.6)
            val recognizedText = if (hwrAvailable) {
                try {
                    hwr.recognizeStrokes(listOf(stroke), viewWidth, viewHeight) ?: ""
                } catch (e: Exception) {
                    "" // AC2.3: HWR failure doesn't block other shapes
                }
            } else ""

            val shape = IndexedShape(
                shapeId = entry.shapeUuid,
                documentId = documentId,
                pageId = pageId,
                parentUniqueId = parentUniqueId,
                noteTitle = noteTitle,
                recognizedText = recognizedText,
                pointFilePath = pointFile.absolutePath,
                pointFileModified = pointFile.lastModified(),
                pointFileSize = pointFile.length(),
                indexedAt = System.currentTimeMillis()
            )
            index.upsertShape(shape)
        }
    }
}

data class IndexResult(
    val processed: Int,
    val failed: Int,
    val deleted: Int,
    val error: String?
)
```

Key design decisions:
- The Indexer is a plain class with constructor-injected dependencies — no DI framework, matching AragoniteHWR conventions.
- HWR failure for one shape doesn't block others (AC2.3) — caught and continued.
- If HWR service isn't available, shapes are still indexed with empty text (AC4.6 handling).
- Diff logic reuses `IndexRepository.computeDiff()`.
- Progress callback allows the UI to show status.

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add Indexer orchestrating reindex pipeline`
<!-- END_TASK_4 -->

<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 5-6) -->

<!-- START_TASK_5 -->
### Task 5: IndexRepository diff logic tests

**Verifies:** power-search-mvp.AC4.1, power-search-mvp.AC4.2, power-search-mvp.AC4.3

**Files:**
- Create: `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexRepositoryTest.kt`

**Implementation:**

Instrumented tests using Room's in-memory database to verify diff logic and FTS search.

**Testing:**

Tests must verify each AC listed above:
- **power-search-mvp.AC4.1:** Create an in-memory `SearchDatabase`. Call `computeDiff()` with a map containing a file path that's not in the index. Assert the returned `FileDiff.newFiles` contains that file.
- **power-search-mvp.AC4.2:** Insert an `IndexedShape` with specific `pointFileModified` and `pointFileSize`. Call `computeDiff()` with the same path but different timestamp or size. Assert `FileDiff.modifiedFiles` contains that file.
- **power-search-mvp.AC4.3:** Insert an `IndexedShape`. Call `computeDiff()` with an empty map (no files on filesystem). Assert `FileDiff.deletedPaths` contains the indexed path.

Additional tests:
- `computeDiff()` with matching timestamps/sizes → no diff entries
- `search()` with blank query returns empty list
- `search()` with a query that matches inserted text returns results
- `upsertShape()` followed by `search()` returns the shape via FTS

The in-memory Room database approach:
```kotlin
val db = Room.inMemoryDatabaseBuilder(context, SearchDatabase::class.java).build()
val repo = IndexRepository(db.indexDao())
```

**Verification:**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: All tests pass

**Commit:** `test: add IndexRepository diff logic and FTS search tests`
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Indexer integration test on device

**Verifies:** power-search-mvp.AC2.1, power-search-mvp.AC2.2, power-search-mvp.AC2.3, power-search-mvp.AC4.5, power-search-mvp.AC4.6

**Files:**
- Create: `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt`

**Implementation:**

Instrumented test that runs the full indexing pipeline on a real BOOX device with real note data.

**Testing:**

Tests must verify each AC listed above:
- **power-search-mvp.AC2.1:** After `reindex()`, query the database for any indexed shape. Assert `recognizedText` is non-empty for at least one shape (proving HWR ran with correct pressure normalization).
- **power-search-mvp.AC2.2:** After `reindex()`, verify indexed shapes have populated `shapeId`, `documentId`, `pageId`, `noteTitle`, and `recognizedText` fields.
- **power-search-mvp.AC2.3:** This is harder to test in isolation. Verify that `reindex()` completes without throwing even if some shapes fail HWR. Assert `IndexResult.processed > 0` (at least some shapes succeeded). If `IndexResult.failed > 0`, that's acceptable — it means failures were handled gracefully.
- **power-search-mvp.AC4.5:** Test that `Indexer.reindex()` returns an `IndexResult` with a non-null error message containing "Storage permission" when `Environment.isExternalStorageManager()` returns false. On device, this can be tested by revoking the permission before calling reindex (or by testing on a fresh install before granting permission). Assert `processed == 0`, `failed == 0`, `deleted == 0`, and `error` is non-null.
- **power-search-mvp.AC4.6:** Create an Indexer with a stub `HWRRepository` whose `bind()` returns false. Call `reindex()`. Assert it completes without throwing, `IndexResult.error` contains "HWR service unavailable", and indexed shapes have empty `recognizedText`. (Alternatively, test on device by stopping the KHwrService before running reindex.)

Additional tests:
- `reindex()` returns `IndexResult` with `processed > 0` on a device with notes
- Running `reindex()` twice: second run should have 0 new/modified (all up-to-date)
- `search()` after indexing returns results matching known note content

This test requires a BOOX device with at least one handwritten note.

**Verification:**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.aragonite.powersearch.data.IndexerTest`
Expected: All tests pass

**Commit:** `test: add Indexer integration test on device`
<!-- END_TASK_6 -->

<!-- END_SUBCOMPONENT_C -->
