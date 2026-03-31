package dev.aragonite.powersearch.data

// pattern: Imperative Shell

import android.os.Environment
import android.util.Log
import dev.aragonite.hwr.HWRStroke
import dev.aragonite.powersearch.data.db.IndexedShape
import java.io.File

private const val TAG = "Indexer"

data class IndexProgress(
    val phase: String,
    val current: Int,
    val total: Int
)

data class IndexResult(
    val processed: Int,
    val failed: Int,
    val deleted: Int,
    val error: String?
)

@Suppress("SdCardPath")
open class Indexer(
    private val noteMetadata: NoteMetadataRepository,
    private val strokeData: StrokeDataRepository,
    private val index: IndexRepository,
    private val hwr: HWRRepository,
    private val storageChecker: () -> Boolean = { Environment.isExternalStorageManager() }
) {
    open suspend fun reindex(onProgress: (IndexProgress) -> Unit = {}): IndexResult {
        // AC4.5: Check storage permission before accessing filesystem
        if (!storageChecker()) {
            return IndexResult(0, 0, 0, "Storage permission required. Grant 'All Files Access' in Settings.")
        }

        val ksyncRoot = File("/sdcard/.ksync")
        var processed = 0
        var failed = 0
        var deleted = 0

        // Step 1: Scan point files
        onProgress(IndexProgress("Scanning files", 0, 0))
        val pointDir = File(ksyncRoot, "point")
        if (!pointDir.exists()) {
            Log.w(TAG, "Point directory does not exist: ${pointDir.absolutePath}")
            return IndexResult(0, 0, 0, "No point directory found at ${pointDir.absolutePath}")
        }

        val currentFiles = mutableMapOf<String, Pair<Long, Long>>()
        pointDir.walkTopDown().filter { it.isFile }.forEach { file ->
            currentFiles[file.absolutePath] = Pair(file.lastModified(), file.length())
        }
        Log.i(TAG, "Found ${currentFiles.size} point files on filesystem")

        // Step 2: Compute diff
        onProgress(IndexProgress("Computing diff", 0, 0))
        val diff = index.computeDiff(currentFiles)
        val filesToProcess = diff.newFiles + diff.modifiedFiles
        val total = filesToProcess.size
        Log.i(TAG, "Diff: ${diff.newFiles.size} new, ${diff.modifiedFiles.size} modified, ${diff.deletedPaths.size} deleted")

        // Step 3: Bind HWR service (AC4.6: graceful handling if unavailable)
        var hwrAvailable = hwr.bind()
        Log.i(TAG, "HWR service available: $hwrAvailable")
        try {
            // Step 4: Discover user ID and cache note metadata
            val userId = noteMetadata.discoverUserId()
            Log.i(TAG, "User ID: $userId")
            val noteMetadataMap: Map<String, NoteMetadata> = if (userId != null) {
                noteMetadata.getNoteMetadata(userId).associateBy { it.documentId }
            } else emptyMap()
            Log.i(TAG, "Loaded ${noteMetadataMap.size} note metadata entries")

            // Cache handwriting shapes per document to avoid repeated DB reads
            val handwritingShapeCache = mutableMapOf<String, Set<String>>()

            // Step 5: Process new/modified files
            val modifiedSet = diff.modifiedFiles.toSet()
            var consecutiveEmpty = 0
            val MAX_CONSECUTIVE_EMPTY = 20
            for ((i, pointFile) in filesToProcess.withIndex()) {
                onProgress(IndexProgress("Indexing", i + 1, total))
                try {
                    // For modified files, remove old shapes before re-indexing to prevent stale entries
                    if (pointFile in modifiedSet) {
                        index.deleteByPointFile(pointFile.absolutePath)
                    }
                    val gotText = processPointFile(pointFile, userId, hwrAvailable, noteMetadataMap, handwritingShapeCache)
                    processed++

                    // Track consecutive empty HWR results to detect stale service
                    if (gotText) {
                        consecutiveEmpty = 0
                    } else if (hwrAvailable) {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY) {
                            Log.w(TAG, "$MAX_CONSECUTIVE_EMPTY consecutive empty HWR results — rebinding service")
                            hwr.unbind()
                            hwrAvailable = hwr.bind()
                            Log.i(TAG, "HWR rebind result: $hwrAvailable")
                            consecutiveEmpty = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process ${pointFile.name}: ${e.message}", e)
                    failed++
                }
            }

            // Step 6: Remove deleted files
            for (path in diff.deletedPaths) {
                index.deleteByPointFile(path)
                deleted++
            }
        } finally {
            // Step 7: Unbind HWR (always, even if earlier steps threw)
            hwr.unbind()
        }

        val error = if (!hwrAvailable) "HWR service unavailable — shapes indexed without recognition text" else null
        Log.i(TAG, "Reindex complete: processed=$processed, failed=$failed, deleted=$deleted, error=$error")
        return IndexResult(processed, failed, deleted, error)
    }

    /** Returns true if HWR produced non-empty text for this page. */
    private suspend fun processPointFile(
        pointFile: File,
        userId: String?,
        hwrAvailable: Boolean,
        noteMetadataMap: Map<String, NoteMetadata>,
        handwritingShapeCache: MutableMap<String, Set<String>>
    ): Boolean {
        // Extract documentId and pageId from path:
        // /sdcard/.ksync/point/{documentId}/{pageId}/{revisionId}
        val parts = pointFile.absolutePath.split("/")
        val pointIdx = parts.indexOf("point")
        if (pointIdx < 0 || pointIdx + 3 >= parts.size) return false
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
        Log.d(TAG, "File $documentId/$pageId: ${xref.size} xref entries")

        // Get handwriting shape filter from cache (per-document, loaded once)
        // null = no filter (skip filtering when no per-note DB exists)
        // non-empty set = only allow these shape UUIDs
        val handwritingShapeIds: Set<String>? = if (userId != null) {
            val cached = handwritingShapeCache.getOrPut(documentId) {
                noteMetadata.getHandwritingShapes(userId, documentId)
                    .map { it.uniqueId }
                    .toSet()
            }
            cached.ifEmpty { null } // Empty = no per-note DB, don't filter
        } else null
        Log.d(TAG, "Handwriting filter for $documentId: ${handwritingShapeIds?.size ?: "disabled (no metadata)"} shapes")

        // Collect all handwriting strokes for this page (batch for HWR)
        val allStrokes = mutableListOf<HWRStroke>()
        var filtered = 0
        for (entry in xref) {
            if (handwritingShapeIds != null && entry.shapeUuid !in handwritingShapeIds) {
                filtered++
                continue
            }
            val stroke = strokeData.readStrokesForShape(pointFile, entry) ?: continue
            allStrokes.add(stroke)
        }

        if (allStrokes.isEmpty()) {
            Log.d(TAG, "File $documentId/$pageId: 0 strokes (${xref.size} xref, $filtered filtered)")
            return false
        }

        // One HWR call per page with all strokes batched
        val recognizedText = if (hwrAvailable) {
            try {
                hwr.recognizeStrokes(allStrokes, viewWidth, viewHeight) ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "HWR failed for $documentId/$pageId: ${e.message}")
                ""
            }
        } else ""

        // Store one IndexedShape per page (keyed by pointFilePath)
        val shape = IndexedShape(
            shapeId = "${documentId}_${pageId}_${pointFile.name}",
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
        Log.d(TAG, "File $documentId/$pageId: ${allStrokes.size} strokes → '${recognizedText.take(60)}'")
        return recognizedText.isNotEmpty()
    }
}
