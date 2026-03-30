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
