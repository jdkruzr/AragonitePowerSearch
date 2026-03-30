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
