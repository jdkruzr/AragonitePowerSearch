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
            // Adaptive throttle: increase delay when HWR drops results
            var throttleMs = 0L
            var recentTotal = 0
            var recentHits = 0
            val WINDOW_SIZE = 20
            for ((i, pointFile) in filesToProcess.withIndex()) {
                onProgress(IndexProgress("Indexing", i + 1, total))
                try {
                    if (pointFile in modifiedSet) {
                        index.deleteByPointFile(pointFile.absolutePath)
                    }
                    val gotText = processPointFile(pointFile, userId, hwrAvailable, noteMetadataMap, handwritingShapeCache)
                    processed++

                    // Adaptive throttle: track rolling hit rate
                    recentTotal++
                    if (gotText) recentHits++
                    if (recentTotal >= WINDOW_SIZE) {
                        val hitRate = recentHits.toFloat() / recentTotal
                        val oldThrottle = throttleMs
                        throttleMs = when {
                            hitRate < 0.5 -> 1000L   // < 50% — heavy throttle
                            hitRate < 0.7 -> 500L    // < 70% — moderate throttle
                            hitRate < 0.85 -> 200L   // < 85% — light throttle
                            hitRate > 0.95 && throttleMs > 0 -> (throttleMs - 100).coerceAtLeast(0) // > 95% — ease off
                            else -> throttleMs
                        }
                        if (throttleMs != oldThrottle) {
                            Log.i(TAG, "Adaptive throttle: hit rate ${(hitRate * 100).toInt()}% over last $WINDOW_SIZE pages → ${throttleMs}ms delay")
                        }
                        recentTotal = 0
                        recentHits = 0
                    }

                    if (throttleMs > 0 && hwrAvailable) {
                        kotlinx.coroutines.delay(throttleMs)
                    }

                    // Track consecutive empty for rebind
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
                            throttleMs = 500L // Start with moderate throttle after rebind
                            Log.i(TAG, "Post-rebind throttle: ${throttleMs}ms")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process ${pointFile.name}: ${e.message}", e)
                    failed++
                }
            }

            // Step 6: Clean up empty pages so they get re-processed on next run
            if (hwrAvailable) {
                val emptyCount = index.deleteEmptyPages()
                if (emptyCount > 0) {
                    Log.i(TAG, "Removed $emptyCount pages with empty recognizedText for re-processing")
                }
            }

            // Step 7: Remove deleted files
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
        // Filter out single-point strokes: they cause MyScript to hang (DOWN with no UP)
        val allStrokes = mutableListOf<HWRStroke>()
        var filtered = 0
        var singlePointDropped = 0
        for (entry in xref) {
            if (handwritingShapeIds != null && entry.shapeUuid !in handwritingShapeIds) {
                filtered++
                continue
            }
            val stroke = strokeData.readStrokesForShape(pointFile, entry) ?: continue
            if (stroke.points.size < 2) {
                singlePointDropped++
                continue
            }
            allStrokes.add(stroke)
        }
        if (singlePointDropped > 0) {
            Log.d(TAG, "Dropped $singlePointDropped single-point strokes (MyScript poison)")
        }

        if (allStrokes.isEmpty()) {
            Log.d(TAG, "File $documentId/$pageId: 0 strokes (${xref.size} xref, $filtered filtered)")
            return false
        }

        // One HWR call per page with all strokes batched
        // Retry once if HWR returns empty (service may need time to stabilize)
        var recognizedText = ""
        if (hwrAvailable) {
            for (attempt in 1..2) {
                try {
                    recognizedText = hwr.recognizeStrokes(allStrokes, viewWidth, viewHeight) ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "HWR failed for $documentId/$pageId (attempt $attempt): ${e.message}")
                }
                if (recognizedText.isNotEmpty()) break
                if (attempt == 1) {
                    Log.d(TAG, "HWR returned empty for $documentId/$pageId with ${allStrokes.size} strokes, retrying after delay")
                    kotlinx.coroutines.delay(500)
                }
            }
        }

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
