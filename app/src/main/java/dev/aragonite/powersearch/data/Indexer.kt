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
    private val storageChecker: () -> Boolean = { Environment.isExternalStorageManager() },
    private val titleSlotIndex: Int = -1
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
            // Step 4: Discover user ID and scan NOTE_TREE for metadata + folders + deleted notes
            val userId = noteMetadata.discoverUserId()
            Log.i(TAG, "User ID: $userId")
            val noteTreeInfo: NoteTreeInfo = if (userId != null) {
                noteMetadata.scanNoteTree(userId, titleSlotIndex)
            } else NoteTreeInfo(emptyMap(), emptyMap(), emptySet(), emptySet())
            val noteMetadataMap = noteTreeInfo.notes
            Log.i(TAG, "Loaded ${noteMetadataMap.size} note metadata, ${noteTreeInfo.deletedNoteIds.size} deleted notes, ${noteTreeInfo.folders.size} folders")

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
            var skippedDeleted = 0
            for ((i, pointFile) in filesToProcess.withIndex()) {
                onProgress(IndexProgress("Indexing", i + 1, total))
                try {
                    // Skip deleted notes — extract documentId from path
                    val pathParts = pointFile.absolutePath.split("/")
                    val ptIdx = pathParts.indexOf("point")
                    if (ptIdx >= 0 && ptIdx + 1 < pathParts.size) {
                        val docId = pathParts[ptIdx + 1]
                        if (docId in noteTreeInfo.deletedNoteIds) {
                            skippedDeleted++
                            continue
                        }
                        // Skip files with no NOTE_TREE entry (reader annotations, orphans)
                        if (noteMetadataMap.isNotEmpty() && docId !in noteMetadataMap) {
                            skippedDeleted++
                            continue
                        }
                    }

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

            if (skippedDeleted > 0) {
                Log.i(TAG, "Skipped $skippedDeleted point files from deleted notes")
            }

            // Note: empty pages (no handwriting strokes) are stored with empty recognizedText
            // and filtered out in search queries. No cleanup needed — keeps diff stable.

            // Step 7: Refresh titles for any untitled pages
            onProgress(IndexProgress("Refreshing titles", 0, 0))
            refreshTitles(noteMetadataMap)

            // Step 8: Remove deleted files
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
            // Store with empty text so diff doesn't re-process this file every run.
            // Search query filters these out via length(recognizedText) > 0.
            val shape = IndexedShape(
                shapeId = "${documentId}_${pageId}_${pointFile.name}",
                documentId = documentId,
                pageId = pageId,
                parentUniqueId = note?.folderName ?: "",
                noteTitle = noteTitle,
                recognizedText = "",
                pointFilePath = pointFile.absolutePath,
                pointFileModified = pointFile.lastModified(),
                pointFileSize = pointFile.length(),
                indexedAt = System.currentTimeMillis()
            )
            index.upsertShape(shape)
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

    private suspend fun refreshTitles(noteMetadataMap: Map<String, NoteMetadata>) {
        if (noteMetadataMap.isEmpty()) {
            Log.w(TAG, "No metadata available — skipping title/folder refresh")
            return
        }

        // Refresh titles for untitled documents
        val untitled = index.getUntitledDocumentIds()
        var titleUpdated = 0
        for (docId in untitled) {
            val note = noteMetadataMap[docId] ?: continue
            index.updateTitlesForDocument(docId, note.title, note.folderName)
            titleUpdated++
        }
        if (titleUpdated > 0) {
            Log.i(TAG, "Refreshed titles for $titleUpdated / ${untitled.size} untitled documents")
        }

        // Refresh folder names for documents with empty parentUniqueId
        val unfoldered = index.getUnfolderedDocumentIds()
        var folderUpdated = 0
        for (docId in unfoldered) {
            val note = noteMetadataMap[docId] ?: continue
            if (note.folderName.isNotEmpty()) {
                index.updateTitlesForDocument(docId, note.title, note.folderName)
                folderUpdated++
            }
        }
        if (folderUpdated > 0) {
            Log.i(TAG, "Refreshed folders for $folderUpdated / ${unfoldered.size} unfoldered documents")
        }
    }
}
