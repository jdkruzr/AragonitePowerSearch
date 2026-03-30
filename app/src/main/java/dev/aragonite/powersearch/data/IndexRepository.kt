package dev.aragonite.powersearch.data

// pattern: Imperative Shell

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
        // Escape FTS4 special characters to prevent query syntax errors
        // FTS4 operators: * " ( ) - etc. Safest approach: escape double quotes by doubling them
        val sanitizedQuery = query.replace("\"", "\"\"")
        return dao.search(sanitizedQuery)
    }

    suspend fun getIndexedShapeCount(): Int = dao.getIndexedShapeCount()
}
