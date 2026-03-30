package dev.aragonite.powersearch.data

import android.database.sqlite.SQLiteDatabase
import dev.aragonite.fleece.FleeceDecoder
import java.io.File

class NoteMetadataRepository(private val ksyncRoot: File = File("/sdcard/.ksync")) {

    fun discoverUserId(): String? {
        val couchDir = File(ksyncRoot, "couch")
        if (!couchDir.exists()) return null
        val noteTreeDir = couchDir.listFiles()
            ?.firstOrNull { it.name.endsWith("-NOTE_TREE.cblite2") }
            ?: return null
        return noteTreeDir.name.removeSuffix("-NOTE_TREE.cblite2")
    }

    fun getNoteMetadata(userId: String): List<NoteMetadata> {
        val dbPath = File(ksyncRoot, "couch/$userId-NOTE_TREE.cblite2/db.sqlite3")
        if (!dbPath.exists()) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val results = mutableListOf<NoteMetadata>()
            val cursor = db.rawQuery("SELECT key, body FROM kv_default", null)
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val body = it.getBlob(1) ?: continue
                    val dict = FleeceDecoder.decodeAsDict(body) ?: continue
                    val title = dict.getString("title") ?: continue
                    val parentUniqueId = dict.getString("parentUniqueId") ?: ""
                    results.add(NoteMetadata(documentId = key, title = title, parentUniqueId = parentUniqueId))
                }
            }
            results
        } finally {
            db.close()
        }
    }

    fun getHandwritingShapes(userId: String, documentId: String): List<ShapeMetadata> {
        val dbPath = File(ksyncRoot, "couch/$userId-$documentId.cblite2/db.sqlite3")
        if (!dbPath.exists()) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val results = mutableListOf<ShapeMetadata>()
            val cursor = db.rawQuery("SELECT key, body FROM kv_default", null)
            cursor.use {
                while (it.moveToNext()) {
                    val body = it.getBlob(1) ?: continue
                    val dict = FleeceDecoder.decodeAsDict(body) ?: continue
                    val shapeType = dict.getInt("shapeType") ?: continue
                    if (shapeType !in HandwritingShapeTypes.TYPES) continue
                    val uniqueId = dict.getString("uniqueId") ?: continue
                    val revisionId = dict.getString("revisionId") ?: ""
                    results.add(ShapeMetadata(uniqueId = uniqueId, shapeType = shapeType, revisionId = revisionId))
                }
            }
            results
        } finally {
            db.close()
        }
    }
}
