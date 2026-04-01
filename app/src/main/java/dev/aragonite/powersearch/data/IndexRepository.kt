package dev.aragonite.powersearch.data

// pattern: Imperative Shell

import androidx.sqlite.db.SimpleSQLiteQuery
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
        // Sanitize and add prefix matching for search-as-you-type.
        // Each word gets a * suffix so "hammer" matches "hammerspace".
        val sanitizedQuery = query.trim()
            .replace("\"", "\"\"")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
        if (sanitizedQuery.isBlank()) return emptyList()
        return dao.search(sanitizedQuery)
    }

    suspend fun getIndexedShapeCount(): Int = dao.getIndexedShapeCount()

    suspend fun clearIndex() = dao.clearAll()

    suspend fun deleteEmptyPages(): Int = dao.deleteEmptyPages()

    suspend fun getUntitledDocumentIds(): List<String> = dao.getUntitledDocumentIds()

    suspend fun updateTitlesForDocument(documentId: String, title: String, parentUniqueId: String) =
        dao.updateTitlesForDocument(documentId, title, parentUniqueId)

    fun checkpoint() {
        dao.rawQuery(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
    }
}
