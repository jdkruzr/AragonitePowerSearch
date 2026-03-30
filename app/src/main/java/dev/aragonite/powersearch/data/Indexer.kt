package dev.aragonite.powersearch.data

import android.os.Environment
import dev.aragonite.powersearch.data.db.IndexedShape
import java.io.File

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
        try {
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
        } finally {
            // Step 7: Unbind HWR (always, even if earlier steps threw)
            hwr.unbind()
        }

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
