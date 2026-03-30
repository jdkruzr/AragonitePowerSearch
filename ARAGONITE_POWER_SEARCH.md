# Aragonite Power Search

A persistent handwriting search index for Onyx Boox devices. Solves the problem
that Boox's built-in handwriting search re-runs OCR from scratch on every query
by maintaining a persistent, incrementally-updated search index.

## The Problem

Onyx Boox devices ship with handwriting search, but it's effectively unusable:

- Every search triggers a **full HWR pass** across all matching notes
- Recognition results are **never cached** — thrown away after each search
- On a device with hundreds of notes, a single search can take minutes
- The infrastructure for caching exists in the firmware (`HWRBatchRecognizeAction`,
  `HWRTextCompileAction`, `KSYNC_EMBED_HWR_TEXT_CACHE_DIR_NAME = "hwr_text"`)
  but was **never wired up**

## The Solution

An Android app that:

1. Watches the Boox note storage for changes
2. Reads stroke data directly from the device filesystem
3. Runs handwriting recognition via the on-device HWR engine
4. Builds and maintains a persistent full-text search index
5. Provides instant search with deep-linking back to the original note

## Why This Is Possible

Through reverse engineering of the Boox firmware (firmware 4.1.1, Android 15),
we discovered:

### Note data is world-readable on the SD card

Boox stores all note data at `/sdcard/.ksync/` — no root required. This was an
intentional design decision for Android 11+ compatibility (see `KSyncFilePaths.java`
line 167: `if (Build.VERSION.SDK_INT >= 30) return getKSyncExternalFilesPath()`).

### Stroke data is in a simple binary format

Point files at `/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}` contain
packed 16-byte records with no encryption, no compression, no intermediate encoding:

```
Per point (16 bytes, big-endian):
  float  x          — X coordinate (screen pixels)
  float  y          — Y coordinate (screen pixels)
  short  size       — pen width
  short  pressure   — pen pressure (0-4095)
  int    time       — timestamp (relative ms)
```

See `BOOX_STROKE_FORMAT.md` for the complete format specification.

### The HWR engine is accessible via IPC

The [AragoniteHWR](../AragoniteHWR) library provides a coroutine-based API to the
on-device handwriting recognition service. The stroke format maps directly:

| TinyPoint (file) | HWRPoint (library) | Conversion |
|---|---|---|
| x (float) | x (Float) | Direct |
| y (float) | y (Float) | Direct |
| time (int ms) | dt (Int ms) | Direct |
| pressure (short 0-4095) | pressure (Float 0-1) | `/ 4095f` |

### Notes can be opened via explicit Intent

The Notes app's `ScribbleActivity` is exported with `ACTION_VIEW` and accepts
a `documentId` extra to open a specific note:

```kotlin
val intent = Intent().apply {
    setComponent(ComponentName(
        "com.onyx.android.note",
        "com.onyx.android.note.note.ui.ScribbleActivity"
    ))
    putExtra("documentId", noteUniqueId)
    putExtra("parentUniqueId", parentFolderId)
    putExtra("jump_from_document_path", "/your/app/path")
}
startActivity(intent)
```

## Architecture

### Data Flow

```
/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}
    │
    ▼
┌──────────────────────┐
│  FileSystemWatcher   │  Monitors .ksync/point/ for new/modified files
│  (FileObserver)      │  Detects when notes are created, edited, or deleted
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  PointFileReader     │  Reads point files: 76-byte header, xref table,
│                      │  16-byte TinyPoint records per shape
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  AragoniteHWR        │  Converts TinyPoint → HWRPoint → HWRStroke
│  (recognition)       │  Runs on-device HWR, returns recognized text
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  SearchIndex         │  Stores recognized text keyed by:
│  (Room + FTS)        │  - documentId (note UUID)
│                      │  - pageId (page UUID within note)
│                      │  - shapeId (individual stroke UUID)
│                      │  - parentUniqueId (folder UUID)
│                      │  - note title, last modified time
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  Search UI           │  Text input → FTS query → results list
│                      │  Tap result → Intent → ScribbleActivity
└──────────────────────┘
```

### State Management

The indexer needs to know which notes have been indexed and at what version.
A simple approach:

- For each point file, store: `filePath`, `lastModified`, `fileSize`, `indexedAt`
- On scan: compare current filesystem state to indexed state
- New or modified files → re-recognize and update index
- Deleted files → remove from index

The scan trigger can be:
- **On app launch** — always do a full diff
- **FileObserver** — watch `/sdcard/.ksync/point/` for real-time updates
- **Periodic** — WorkManager job for background indexing

### Search Index Schema

Using Room with SQLite FTS4/FTS5:

```kotlin
@Entity(tableName = "indexed_shapes")
data class IndexedShape(
    @PrimaryKey val shapeId: String,      // shape UUID from xref
    val documentId: String,                // note UUID
    val pageId: String,                    // page UUID
    val parentUniqueId: String,            // folder UUID (from NOTE_TREE)
    val noteTitle: String,                 // human-readable title
    val recognizedText: String,            // HWR output
    val pointFilePath: String,             // path to source point file
    val pointFileModified: Long,           // last modified timestamp
    val indexedAt: Long                    // when we indexed this
)

@Fts4(contentEntity = IndexedShape::class)
@Entity(tableName = "indexed_shapes_fts")
data class IndexedShapeFts(
    val recognizedText: String
)
```

### Note Metadata

To get note titles and folder structure (for display in search results), read
the NOTE_TREE Couchbase database. This requires either:

1. **Fleece decoding** — to read the Couchbase Lite database directly
2. **Filesystem heuristics** — the directory structure under `.ksync/document/`
   and `.ksync/couch/` encodes the note ID, which can be correlated with the
   point file paths

Option 2 is simpler for an MVP. The note title could also be extracted from
the virtual doc protobuf at:
```
/sdcard/.ksync/document/{noteId}/virtual/doc/pb/{noteId}
```

## Requirements

- Onyx Boox device with firmware 4.1.1+ (Android 15)
- `com.onyx.android.ksync` service installed (standard on all Boox devices)
- Storage permission (`MANAGE_EXTERNAL_STORAGE` or scoped access to `.ksync`)
- AragoniteHWR library for handwriting recognition

## Scope

### MVP (v0.1)

- Read point files from `/sdcard/.ksync/point/`
- Run HWR on all scribble-type shapes
- Store results in Room + FTS database
- Basic search UI with text input
- Tap-to-open via Intent to ScribbleActivity
- Manual "reindex" button

### v0.2

- FileObserver for real-time index updates
- Background indexing via WorkManager
- Note title display in search results
- Progress indicator during indexing

### v0.3

- Page-level search results (show which page contains the match)
- Search result snippets with context
- Filter by notebook/folder
- Index statistics (notes indexed, time since last index)

### Future

- Cross-device search (if notes sync via Boox cloud, index could too)
- Export search index for backup
- Integration with other note apps that use the same storage format
- Handwriting preview thumbnails in search results

## Technical Notes

- Point files use **big-endian** byte order (Java `DataInputStream` convention)
- The file header is 76 bytes; the xref offset is the last 4 bytes of the file
- Each xref entry is 44 bytes: 36-byte UUID string + 4-byte offset + 4-byte length
- Each shape's point data starts with 4 bytes of attributes (`attrA`, `attrB`
  as shorts), then N × 16 bytes of TinyPoint data
- Shape types that contain handwriting strokes: 2 (pencil), 3 (oily pen),
  4 (fountain pen), 5 (brush), 15 (marker), 21 (neo brush), 22 (charcoal),
  47 (square pen), 60 (latin calligraphy), 61 (asian calligraphy)
- The `shapeType` is in the Couchbase/protobuf metadata, not in the point file
  itself. For MVP, recognize all shapes and let the HWR engine decide if
  the strokes are text or not.
- Pressure values are 0-4095 (12-bit EMR); normalize to 0-1 for AragoniteHWR
- The `size` field in TinyPoint is pen width, not needed for recognition
- Large notes (>20K points per shape) are downsampled during Boox's own
  rendering but the full data is stored. Consider downsampling for HWR too
  if performance is an issue.

## Naming

"Aragonite Power Search" — aragonite is a mineral form of calcium carbonate,
continuing the geological theme from AragoniteHWR. "Power Search" because
it does what the built-in search should have done from the start.
