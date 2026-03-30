# Power Search MVP Implementation Plan — Phase 3: Note Metadata Repository

**Goal:** Read note titles, folder structure, and shape metadata from on-device Couchbase Lite databases using the Fleece decoder from Phase 2.

**Architecture:** `NoteMetadataRepository` in the `:app` module opens `.cblite2/db.sqlite3` files via Android's `SQLiteDatabase`, queries the `kv_default` table, and decodes Fleece BLOBs to extract note metadata and shape information.

**Tech Stack:** Kotlin, Android SQLiteDatabase API, Fleece decoder (`:fleece` module)

**Scope:** Phase 3 of 6 from original design

**Codebase verified:** 2026-03-29. After Phase 2, the `:fleece` module will contain `FleeceDecoder`, `FleeceValue`, `FleeceDict`, `FleeceArray`. The `:app` module will have build config with `implementation(project(":fleece"))`. No repository classes exist yet.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### power-search-mvp.AC1: App reads note data from .ksync filesystem
- **power-search-mvp.AC1.1 Success:** Fleece decoder extracts title and parentUniqueId from NOTE_TREE BLOB
- **power-search-mvp.AC1.4 Success:** NoteMetadataRepository filters shapes to handwriting types only (2,3,4,5,15,21,22,47,60,61)

---

<!-- START_SUBCOMPONENT_A (tasks 1-3) -->

<!-- START_TASK_1 -->
### Task 1: Data classes for note and shape metadata

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/NoteMetadata.kt`

**Implementation:**

Define the data classes that `NoteMetadataRepository` returns. These represent the decoded information from Couchbase BLOBs.

```kotlin
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
```

Also define the set of handwriting shape types:

```kotlin
package dev.aragonite.powersearch.data

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
```

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add NoteMetadata and ShapeMetadata data classes`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: NoteMetadataRepository

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/data/NoteMetadataRepository.kt`

**Implementation:**

`NoteMetadataRepository` reads Couchbase Lite databases from the device filesystem. It has three responsibilities:

1. **User ID discovery** — scan `/sdcard/.ksync/couch/` for `*-NOTE_TREE.cblite2` directories, extract the user ID prefix (everything before `-NOTE_TREE`).

2. **Note metadata extraction** — open `{userId}-NOTE_TREE.cblite2/db.sqlite3`, query all rows from `kv_default`, decode each `body` BLOB via `FleeceDecoder.decodeAsDict()`, extract `title` and `parentUniqueId` fields.

3. **Shape metadata extraction** — for a given note `documentId`, open `{userId}-{documentId}.cblite2/db.sqlite3`, query all rows from `kv_default`, decode each `body` BLOB, extract `shapeType`, `uniqueId`, and `revisionId`. Filter to handwriting types only using `HandwritingShapeTypes.TYPES`.

Key implementation details:

- Use `android.database.sqlite.SQLiteDatabase.openDatabase()` with `OPEN_READONLY` flag to open the `.cblite2/db.sqlite3` files. These are standard SQLite databases.
- The `kv_default` table has columns including `key` (text, the document ID) and `body` (BLOB, the Fleece-encoded data).
- The `body` column is a `ByteArray` retrieved via `cursor.getBlob(columnIndex)`.
- Close database and cursor in finally blocks to prevent leaks.
- The couch directory path: `/sdcard/.ksync/couch/`

```kotlin
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
```

**Verification:**

Run: `./gradlew :app:compileDebugKotlin`
Expected: Compiles

**Commit:** `feat: add NoteMetadataRepository for Couchbase database reading`
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: NoteMetadataRepository tests

**Verifies:** power-search-mvp.AC1.1, power-search-mvp.AC1.4

**Files:**
- Create: `app/src/test/java/dev/aragonite/powersearch/data/NoteMetadataRepositoryTest.kt`

**Implementation:**

Testing `NoteMetadataRepository` is challenging because it depends on `android.database.sqlite.SQLiteDatabase`, which is an Android API not available in plain JVM tests. There are two approaches:

**Option A (Preferred): Android instrumented tests on device**
Use `androidTest` source set with real `.cblite2` databases pushed to device. This tests the full stack including SQLiteDatabase and Fleece decoding.

**Option B: Unit tests with Robolectric**
Robolectric provides a shadow `SQLiteDatabase` that works in JVM tests. However, this adds a heavy dependency and may not perfectly match real SQLite behavior.

**Recommended approach:** Write instrumented tests (`androidTest`) that run on the BOOX device itself, using real Couchbase databases already on the device. This matches the design plan's "test using real device data" philosophy and avoids mocking the database layer.

For the test file at `app/src/androidTest/java/dev/aragonite/powersearch/data/NoteMetadataRepositoryTest.kt`:

**Testing:**

Tests must verify each AC listed above:
- **power-search-mvp.AC1.1:** Call `getNoteMetadata()` on a real NOTE_TREE database. Assert at least one result returned. Assert each result has a non-empty `title` and non-empty `documentId`.
- **power-search-mvp.AC1.4:** Call `getHandwritingShapes()` for a note known to have handwriting. Assert all returned shapes have `shapeType` in `HandwritingShapeTypes.TYPES`. Also call it for a note with non-handwriting shapes (if available) and assert those types are excluded.

Additional tests:
- `discoverUserId()` returns a non-null string matching the pattern of a hex ID
- `getNoteMetadata()` with a non-existent userId returns empty list
- `getHandwritingShapes()` with a non-existent documentId returns empty list

Also add a **plain JVM unit test** at `app/src/test/java/dev/aragonite/powersearch/data/HandwritingShapeTypesTest.kt` that verifies:
- `HandwritingShapeTypes.TYPES` contains exactly {2, 3, 4, 5, 15, 21, 22, 47, 60, 61}
- Common non-handwriting types (e.g., 0, 1, 6, 10) are NOT in the set
- The set has exactly 10 elements

This is pure logic with no Android dependencies and runs on JVM without a device.

Add the androidTest dependency to `app/build.gradle.kts`:
```kotlin
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("androidx.test:runner:1.6.2")
```

And add the test instrumentation runner in `defaultConfig`:
```kotlin
testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

**Verification:**

Run: `./gradlew :app:connectedDebugAndroidTest` (with device connected)
Expected: All tests pass

**Commit:** `test: add NoteMetadataRepository instrumented tests`
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_A -->
