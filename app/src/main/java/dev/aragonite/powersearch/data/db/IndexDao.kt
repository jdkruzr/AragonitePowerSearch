package dev.aragonite.powersearch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery

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
        AND length(s.recognizedText) > 0
    """)
    suspend fun search(query: String): List<IndexedShape>

    @Query("SELECT COUNT(*) FROM indexed_shapes")
    suspend fun getIndexedShapeCount(): Int

    @Query("DELETE FROM indexed_shapes")
    suspend fun clearAll()

    @Query("DELETE FROM indexed_shapes WHERE length(recognizedText) = 0")
    suspend fun deleteEmptyPages(): Int

    @Query("SELECT DISTINCT documentId FROM indexed_shapes WHERE noteTitle = '' OR noteTitle IS NULL")
    suspend fun getUntitledDocumentIds(): List<String>

    @Query("UPDATE indexed_shapes SET noteTitle = :title, parentUniqueId = :parentUniqueId WHERE documentId = :documentId")
    suspend fun updateTitlesForDocument(documentId: String, title: String, parentUniqueId: String)

    @RawQuery
    fun rawQuery(query: androidx.sqlite.db.SupportSQLiteQuery): Int
}

data class IndexedFileInfo(
    val pointFilePath: String,
    val pointFileModified: Long,
    val pointFileSize: Long
)
