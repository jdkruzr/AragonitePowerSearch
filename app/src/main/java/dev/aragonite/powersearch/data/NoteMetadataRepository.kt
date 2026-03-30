package dev.aragonite.powersearch.data

// pattern: Imperative Shell

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.aragonite.fleece.FleeceDecoder
import java.io.File

private const val TAG = "NoteMetadataRepo"

/**
 * Manages reading note metadata and shape information from Couchbase Lite databases.
 *
 * Note: This class uses hardcoded /sdcard/.ksync path, which is device-specific and intentional
 * for Onyx Boox devices. The .ksync directory is where Boox stores all note and configuration data.
 */
@Suppress("SdCardPath")
class NoteMetadataRepository(private val ksyncRoot: File = File("/sdcard/.ksync")) {

    fun discoverUserId(): String? {
        val couchDir = File(ksyncRoot, "couch")
        if (!couchDir.exists()) return null
        val noteTreeDirs = couchDir.listFiles()
            ?.filter { it.name.endsWith("-NOTE_TREE.cblite2") }
            ?: return null
        val dir = noteTreeDirs
            .firstOrNull { !it.name.startsWith("share_user") }
            ?: noteTreeDirs.firstOrNull()
            ?: return null
        val userId = dir.name.removeSuffix("-NOTE_TREE.cblite2")
        Log.i(TAG, "Discovered userId=$userId from ${noteTreeDirs.size} NOTE_TREE databases")
        return userId
    }

    /**
     * Read the SharedKeys blob from a Couchbase Lite database's kv_info table.
     * Returns the ordered list of shared key name strings.
     */
    private fun readSharedKeys(db: SQLiteDatabase): List<String> {
        val cursor = db.rawQuery(
            "SELECT body FROM kv_info WHERE key = 'SharedKeys'", null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val body = it.getBlob(0) ?: return@use emptyList()
                val keys = FleeceDecoder.parseSharedKeys(body)
                Log.i(TAG, "Loaded ${keys.size} shared keys: ${keys.take(10)}...")
                keys
            } else {
                Log.w(TAG, "No SharedKeys entry in kv_info")
                emptyList()
            }
        }
    }

    fun getNoteMetadata(userId: String): List<NoteMetadata> {
        val dbPath = File(ksyncRoot, "couch/$userId-NOTE_TREE.cblite2/db.sqlite3")
        if (!dbPath.exists()) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            val sharedKeys = readSharedKeys(db)
            val results = mutableListOf<NoteMetadata>()
            var logged = false
            val cursor = db.rawQuery("SELECT key, body FROM kv_default", null)
            Log.i(TAG, "NOTE_TREE query returned ${cursor.count} rows")
            cursor.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val body = it.getBlob(1) ?: continue
                    val dict = FleeceDecoder.decodeAsDict(body, sharedKeys) ?: continue
                    if (!logged) {
                        Log.i(TAG, "NOTE_TREE sample key=$key: keys=${dict.keys()}")
                        logged = true
                    }
                    val title = dict.getString("title") ?: continue
                    val parentUniqueId = dict.getString("parentUniqueId") ?: ""
                    results.add(NoteMetadata(documentId = key, title = title, parentUniqueId = parentUniqueId))
                }
            }
            Log.i(TAG, "Loaded ${results.size} note metadata entries")
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
            val sharedKeys = readSharedKeys(db)
            val results = mutableListOf<ShapeMetadata>()
            val cursor = db.rawQuery("SELECT key, body FROM kv_default", null)
            var logged = false
            cursor.use {
                while (it.moveToNext()) {
                    val body = it.getBlob(1) ?: continue
                    val dict = FleeceDecoder.decodeAsDict(body, sharedKeys) ?: continue
                    if (!logged) {
                        Log.d(TAG, "Per-note BLOB for doc=$documentId: keys=${dict.keys()}")
                        logged = true
                    }
                    // Try "type" first (shared key), fall back to "shapeType" (legacy)
                    val shapeType = dict.getInt("type") ?: dict.getInt("shapeType") ?: continue
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
