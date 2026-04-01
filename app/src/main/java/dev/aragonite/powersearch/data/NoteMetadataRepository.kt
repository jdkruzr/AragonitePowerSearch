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
        if (!dbPath.exists()) {
            Log.w(TAG, "NOTE_TREE DB not found at: ${dbPath.absolutePath}")
            return emptyList()
        }
        val walFile = File(dbPath.path + "-wal")
        Log.i(TAG, "Opening NOTE_TREE at: ${dbPath.absolutePath} (${dbPath.length() / 1024}KB, WAL=${walFile.length() / 1024}KB)")
        val db = try {
            SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open NOTE_TREE: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        return try {
            val sharedKeys = readSharedKeys(db)
            var results = loadNotesWithKeys(db, sharedKeys)

            // If no titles found, the shared key table may not match the BLOBs.
            // Detect the correct "title" index by scanning BLOBs for date-pattern strings.
            if (results.isEmpty()) {
                Log.w(TAG, "No titles found with device shared keys (${sharedKeys.size} keys). Attempting key detection...")
                val detectedTitleKey = detectTitleKeyIndex(db)
                if (detectedTitleKey != null) {
                    Log.i(TAG, "Detected title at key '${detectedTitleKey.first}', parentUniqueId at '${detectedTitleKey.second}'")
                    // Build a remapped shared keys list where the detected indices map to "title" and "parentUniqueId"
                    val remappedKeys = sharedKeys.toMutableList()
                    // Ensure the list is large enough
                    while (remappedKeys.size <= maxOf(detectedTitleKey.first, detectedTitleKey.second)) {
                        remappedKeys.add("?${remappedKeys.size}")
                    }
                    remappedKeys[detectedTitleKey.first] = "title"
                    remappedKeys[detectedTitleKey.second] = "parentUniqueId"
                    results = loadNotesWithKeys(db, remappedKeys)
                }
            }

            Log.i(TAG, "Loaded ${results.size} note metadata entries")
            results
        } finally {
            db.close()
        }
    }

    private fun loadNotesWithKeys(db: SQLiteDatabase, sharedKeys: List<String>): List<NoteMetadata> {
        val results = mutableListOf<NoteMetadata>()
        val cursor = db.rawQuery("SELECT key, body FROM kv_default", null)
        Log.i(TAG, "NOTE_TREE query returned ${cursor.count} rows (keys: ${sharedKeys.size})")
        cursor.use {
            while (it.moveToNext()) {
                val key = it.getString(0)
                val body = it.getBlob(1) ?: continue
                val dict = FleeceDecoder.decodeAsDict(body, sharedKeys) ?: continue
                val title = dict.getString("title") ?: continue
                val parentUniqueId = dict.getString("parentUniqueId") ?: ""
                results.add(NoteMetadata(documentId = key, title = title, parentUniqueId = parentUniqueId))
            }
        }
        return results
    }

    /**
     * Detect the shared key index for "title" by scanning BLOBs for strings that look like note titles
     * (date patterns like "20250924 ..."). Returns pair of (titleIndex, parentUniqueIdIndex) or null.
     */
    private fun detectTitleKeyIndex(db: SQLiteDatabase): Pair<Int, Int>? {
        val cursor = db.rawQuery("SELECT body FROM kv_default LIMIT 200", null)
        // Track which key indices map to date-pattern strings
        val titleCandidates = mutableMapOf<Int, Int>() // keyIndex -> count of date-pattern matches

        cursor.use {
            while (it.moveToNext()) {
                val body = it.getBlob(0) ?: continue
                val dict = FleeceDecoder.decodeAsDict(body, emptyList()) ?: continue
                if (dict.count < 20) continue // Skip non-note entries

                // Scan all key-value pairs looking for string values matching "20YYMMDD"
                val headerOffset = dict.value.offset
                val sw = dict.slotWidth
                for (i in 0 until dict.count) {
                    val ko = headerOffset + 2 + i * 2 * sw
                    val vo = ko + sw
                    if (vo + sw > body.size) break

                    // Check key: must be a small int (shared key index)
                    val kb = (body[ko].toInt() and 0xF0) shr 4
                    if (kb != 0) continue
                    val keyIdx = ((body[ko].toInt() and 0xFF) shl 8 or (body[ko + 1].toInt() and 0xFF)) and 0x0FFF

                    // Check value: resolve pointer and check for date-pattern string
                    val vb0 = body[vo].toInt() and 0xFF
                    if (vb0 and 0x80 == 0) continue // not a pointer
                    val target = if (dict.isWide) {
                        val raw32 = ((body[vo].toInt() and 0xFF) shl 24) or
                                ((body[vo+1].toInt() and 0xFF) shl 16) or
                                ((body[vo+2].toInt() and 0xFF) shl 8) or
                                (body[vo+3].toInt() and 0xFF)
                        vo - ((raw32 and 0x3FFFFFFF) * 2)
                    } else {
                        val raw16 = ((body[vo].toInt() and 0xFF) shl 8) or (body[vo+1].toInt() and 0xFF)
                        vo - ((raw16 and 0x3FFF) * 2)
                    }
                    if (target < 0 || target >= body.size) continue
                    val targetTag = (body[target].toInt() and 0xF0) shr 4
                    if (targetTag != 4) continue // not a string

                    val strLen = body[target].toInt() and 0x0F
                    val strStart = if (strLen == 15) {
                        val l = body[target + 1].toInt() and 0xFF
                        if (l and 0x80 != 0) target + 3 else target + 2
                    } else target + 1
                    val actualLen = if (strLen == 15) {
                        val l = body[target + 1].toInt() and 0xFF
                        if (l and 0x80 != 0) (l and 0x7F) or ((body[target + 2].toInt() and 0xFF) shl 7)
                        else l
                    } else strLen

                    if (actualLen < 8 || strStart + actualLen > body.size) continue
                    val str = body.decodeToString(strStart, strStart + minOf(actualLen, 20))
                    if (str.matches(Regex("^20\\d{6}\\s.*"))) {
                        titleCandidates[keyIdx] = (titleCandidates[keyIdx] ?: 0) + 1
                    }
                }
            }
        }

        if (titleCandidates.isEmpty()) {
            Log.w(TAG, "Could not detect title key index")
            return null
        }

        val titleIdx = titleCandidates.maxByOrNull { it.value }!!.key
        Log.i(TAG, "Title key candidates: $titleCandidates, best=${titleIdx}")

        // parentUniqueId is typically titleIdx + 2 in the standard layout, but let's just
        // use the field right after title's typical position. For now, scan for small int values
        // near the title slot — parentUniqueId is usually a small int (folder reference).
        // This is a heuristic; we can refine later.
        val parentIdx = titleIdx + 1 // best guess — often adjacent

        return Pair(titleIdx, parentIdx)
    }

    fun getHandwritingShapes(userId: String, documentId: String): List<ShapeMetadata> {
        val dbPath = File(ksyncRoot, "couch/$userId-$documentId.cblite2/db.sqlite3")
        if (!dbPath.exists()) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
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
