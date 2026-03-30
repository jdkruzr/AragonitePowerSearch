package dev.aragonite.powersearch.data

data class NoteMetadata(
    val documentId: String,       // key from kv_default table (note UUID)
    val title: String,            // from NOTE_TREE BLOB "title" field
    val parentUniqueId: String    // from NOTE_TREE BLOB "parentUniqueId" field
)

data class ShapeMetadata(
    val uniqueId: String,         // from per-note BLOB "uniqueId" field
    val shapeType: Int,           // from per-note BLOB "shapeType" field
    val revisionId: String        // from per-note BLOB "revisionId" field
)

object HandwritingShapeTypes {
    val TYPES = setOf(
        2,   // pencil
        3,   // oily pen
        4,   // fountain pen
        5,   // brush
        15,  // marker
        21,  // neo brush
        22,  // charcoal
        47,  // square pen
        60,  // latin calligraphy
        61   // asian calligraphy
    )
}
