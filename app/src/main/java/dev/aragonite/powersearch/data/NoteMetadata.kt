package dev.aragonite.powersearch.data

data class NoteMetadata(
    val documentId: String,       // key from kv_default table (note UUID)
    val title: String,            // from NOTE_TREE BLOB "title" field
    val parentUniqueId: String,   // from NOTE_TREE BLOB "parentUniqueId" field
    val folderName: String = ""   // resolved folder name (empty = unfiled/root)
)

/**
 * Container for NOTE_TREE scan results: active notes with folder assignments,
 * plus sets of deleted/inactive document IDs to exclude from indexing.
 */
data class NoteTreeInfo(
    val notes: Map<String, NoteMetadata>,       // documentId -> metadata (active notes only)
    val folders: Map<String, String>,            // folderUuid -> folderName (active folders only)
    val deletedNoteIds: Set<String>,             // document IDs with status=0 (deleted notes)
    val deletedFolderIds: Set<String>            // folder UUIDs with status=0 (deleted folders)
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
