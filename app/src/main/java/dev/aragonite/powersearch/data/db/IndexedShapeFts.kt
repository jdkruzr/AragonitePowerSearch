package dev.aragonite.powersearch.data.db

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = IndexedShape::class)
@Entity(tableName = "indexed_shapes_fts")
data class IndexedShapeFts(
    val recognizedText: String,
    val noteTitle: String
)
