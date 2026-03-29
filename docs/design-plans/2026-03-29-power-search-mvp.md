# Aragonite Power Search MVP Design

## Summary

Aragonite Power Search is an Android app for BOOX e-ink tablets that makes handwritten notes full-text searchable. BOOX Notes stores strokes as proprietary binary files and note metadata in embedded Couchbase databases; neither is exposed through any search interface. This app reads those files directly from the device filesystem, runs on-device handwriting recognition (HWR) on every stroke shape it finds, and stores the resulting text in a local search index. The user can then type any word or phrase and instantly see which notes contain it, with a tap to open the matching note in the BOOX Notes app.

The approach is deliberately read-only and offline. A custom Fleece decoder extracts shape UUIDs and note titles from Couchbase's binary blob format. A binary point-file parser extracts raw stroke coordinates. Those strokes are passed to AragoniteHWR, a sibling library that wraps BOOX's on-device KHwrService. Recognized text is stored in a Room database with an FTS5 virtual table for fast queries. Indexing is triggered manually for the MVP and is designed as a plain, injectable class so that automated triggers (WorkManager, FileObserver) can be added later without restructuring the pipeline.

## Definition of Done

1. **An Android app** (Kotlin, Material Design) that reads stroke data from `/sdcard/.ksync/point/` files and note metadata (titles, folders) from the `.ksync` Couchbase databases.
2. **Runs on-device HWR** via AragoniteHWR on all shapes found in point files, storing recognized text in a persistent Room + FTS search index.
3. **Provides a search UI** where the user types a query, sees matching results with note title and recognized text, and can tap a result to open that note in the Boox Notes app.
4. **Manual reindex trigger** (button) that scans the filesystem, diffs against indexed state, and processes new/modified/deleted notes — with architecture that accommodates future automated sync.

## Acceptance Criteria

### power-search-mvp.AC1: App reads note data from .ksync filesystem
- **power-search-mvp.AC1.1 Success:** Fleece decoder extracts title and parentUniqueId from NOTE_TREE BLOB
- **power-search-mvp.AC1.2 Success:** Fleece decoder extracts shapeType, uniqueId, revisionId from per-note BLOB
- **power-search-mvp.AC1.3 Failure:** Fleece decoder returns error/empty for malformed or empty BLOBs without crashing
- **power-search-mvp.AC1.4 Success:** NoteMetadataRepository filters shapes to handwriting types only (2,3,4,5,15,21,22,47,60,61)
- **power-search-mvp.AC1.5 Success:** Point file parser reads xref table and extracts TinyPoint records with correct big-endian decoding
- **power-search-mvp.AC1.6 Success:** Page dimensions extracted from virtual/page/pb protobuf JSON bounds (right=width, bottom=height)

### power-search-mvp.AC2: HWR processes shapes and stores results
- **power-search-mvp.AC2.1 Success:** Strokes converted to HWRPoint with pressure normalized (short/4095f) and passed to recognizeStrokes()
- **power-search-mvp.AC2.2 Success:** Recognized text stored in Room database keyed by shapeId, with note metadata (title, documentId, pageId, parentUniqueId)
- **power-search-mvp.AC2.3 Failure:** HWR returning empty/null for a shape does not block indexing of remaining shapes

### power-search-mvp.AC3: Search UI returns results and opens notes
- **power-search-mvp.AC3.1 Success:** Typing a query returns matching results from FTS index within ~300ms debounce
- **power-search-mvp.AC3.2 Success:** Search results display note title and matched recognized text
- **power-search-mvp.AC3.3 Success:** Tapping a result launches ScribbleActivity Intent with correct documentId and parentUniqueId
- **power-search-mvp.AC3.4 Edge:** Empty query shows no results (not all results)
- **power-search-mvp.AC3.5 Edge:** Query matching no indexed text shows 'no results' empty state

### power-search-mvp.AC4: Manual reindex with diff
- **power-search-mvp.AC4.1 Success:** New point files (not in index) are processed and added
- **power-search-mvp.AC4.2 Success:** Modified point files (changed timestamp or size) are re-processed and updated
- **power-search-mvp.AC4.3 Success:** Deleted point files (in index but not on filesystem) are removed from index
- **power-search-mvp.AC4.4 Success:** Reindex button shows progress indicator and disables during indexing
- **power-search-mvp.AC4.5 Failure:** Reindex with no storage permission shows informative error, not crash
- **power-search-mvp.AC4.6 Failure:** Reindex when HWR service unavailable reports error and skips recognition gracefully

## Glossary

- **AragoniteHWR**: A sibling Kotlin library in this workspace that wraps BOOX's on-device handwriting recognition service (`KHwrService`). Used here as a composite build dependency via `includeBuild`.
- **BOOX Notes / ScribbleActivity**: The native note-taking app on BOOX e-ink tablets. `ScribbleActivity` (`com.onyx.android.note/.note.ui.ScribbleActivity`) is the specific Android Activity launched to open a note.
- **Couchbase Lite / `.cblite2`**: An embedded NoSQL database used by BOOX Notes to store note metadata on-device. The app does not use the Couchbase SDK; it reads the underlying SQLite files directly.
- **Fleece**: A compact binary encoding format developed by Couchbase for storing structured data (dicts, arrays, scalars) in SQLite blob columns. This project includes a custom read-only Kotlin decoder for it.
- **FTS5**: SQLite's fifth-generation Full-Text Search extension. Used here as a Room virtual table to support fast keyword queries over recognized handwriting text.
- **HWR (Handwriting Recognition)**: The process of converting ink stroke coordinates into Unicode text. Done on-device by the BOOX KHwrService, accessed through AragoniteHWR.
- **HWRPoint / HWRStroke**: Data types defined by AragoniteHWR representing a single ink sample (x, y, pressure) and an ordered sequence of such samples forming one continuous stroke.
- **`includeBuild`**: A Gradle composite build directive that substitutes a local project for a published dependency at build time, used here to consume AragoniteHWR from a sibling directory without publishing it to Maven.
- **`kv_default` table**: The SQLite table inside a `.cblite2/db.sqlite3` file where Couchbase Lite stores all key-value documents as Fleece-encoded blobs in the `body` column.
- **KHwrService**: The BOOX system service that performs handwriting recognition. AragoniteHWR binds to it; this app inherits that binding indirectly.
- **Material 3**: Google's current Material Design component system, used for the app's UI theme and components (SearchBar, LinearProgressIndicator, etc.).
- **MVVM**: Model-View-ViewModel, an Android architecture pattern separating UI state (ViewModel) from data logic (repositories). Used here without a DI framework.
- **NOTE_TREE**: The specific Couchbase database (e.g., `{userId}-NOTE_TREE.cblite2`) that stores the top-level note hierarchy — titles, folder UUIDs (`parentUniqueId`), and note types.
- **Point file**: A proprietary binary file at `/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}` containing raw ink stroke data (TinyPoint records) for a single page revision.
- **Protobuf**: Protocol Buffers, Google's binary serialization format. Used by BOOX for page dimension files under `virtual/page/pb/`; this app parses the embedded JSON bounds field rather than the binary proto directly.
- **Room**: Android's SQLite ORM library (part of Jetpack). Used here to manage the local search index, with FTS5 support for full-text queries.
- **TinyPoint**: A 16-byte binary record within a point file encoding a single ink sample (x, y, pressure, and other fields) in big-endian byte order.
- **viewModelScope**: A Kotlin coroutine scope tied to the lifecycle of an Android ViewModel. Used in the MVP to launch the indexer; replaceable with WorkManager for background execution later.
- **WorkManager / FileObserver**: Android Jetpack APIs for scheduling background work and watching filesystem changes respectively. Not used in the MVP but called out as future sync trigger options.
- **xref table**: An index structure within a point file that maps shape UUIDs to their byte offsets, allowing the parser to locate individual shapes without scanning the entire file.

## Architecture

Single-activity Jetpack Compose Android app with MVVM architecture. Four layers: data sources, repositories, indexer orchestration, and UI.

### Data Sources (read-only, on-device filesystem)

| Path | Contents | Format |
|------|----------|--------|
| `/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}` | Stroke coordinate data | Binary: 76-byte header, xref table, 16-byte TinyPoint records |
| `/sdcard/.ksync/couch/{userId}-NOTE_TREE.cblite2/db.sqlite3` | Note titles, folder UUIDs | SQLite with Fleece-encoded BLOBs |
| `/sdcard/.ksync/couch/{userId}-{noteId}.cblite2/db.sqlite3` | Shape metadata (shapeType, uniqueId, revisionId) | SQLite with Fleece-encoded BLOBs |
| `/sdcard/.ksync/document/{noteId}/virtual/page/pb/{pageId}` | Page dimensions (canvas bounds) | Protobuf with embedded JSON |

### Gradle Modules

```
AragonitePowerSearch/
├── app/       — Android app (Compose UI, ViewModel, repositories, indexer)
├── fleece/    — Pure Kotlin Fleece decoder (zero Android deps, extractable)
└── settings.gradle.kts — includes :app, :fleece, includeBuild("../AragoniteHWR")
```

The `fleece` module is deliberately isolated with no Android dependencies so it can later be extracted to a standalone library (like `AragoniteFleece`).

### Repository Layer

- **`NoteMetadataRepository`** — Opens `.cblite2/db.sqlite3` files via Android's `SQLiteDatabase`. Queries `kv_default` table, decodes `body` BLOBs via the Fleece decoder. Returns note titles, folder IDs, shape types, shape UUIDs, and revision IDs.
- **`StrokeDataRepository`** — Reads binary point files. Parses xref table to locate shapes, reads TinyPoint records, converts to `HWRStroke`/`HWRPoint`. Reads page dimension protobuf files and parses the JSON bounds field.
- **`IndexRepository`** — Manages the Room + FTS5 database. Provides insert/update/delete for indexed shapes, FTS search queries, and the diff logic comparing filesystem state to indexed state.
- **`HWRRepository`** — Wraps AragoniteHWR. Manages service binding lifecycle. Converts stroke data to `HWRStroke` format and calls `recognizeStrokes()`.

### Indexer Orchestration

The `Indexer` is a use case that coordinates the full reindex pipeline:

1. Scan `/sdcard/.ksync/point/` → build map of `{filePath → (lastModified, fileSize)}`
2. Query `IndexRepository` → build map of `{pointFilePath → (pointFileModified, pointFileSize)}`
3. Diff: identify new, modified, and deleted files
4. For new/modified files:
   a. `NoteMetadataRepository` → note title, folder ID, handwriting shape UUIDs
   b. For each handwriting shape: `StrokeDataRepository` → parse strokes, read page dimensions
   c. `HWRRepository` → recognize strokes, get text
   d. `IndexRepository` → store results
5. For deleted files: `IndexRepository` → remove entries

The indexer lives as a plain class with repository dependencies injected via constructor. For MVP, called from `viewModelScope` coroutines. Future versions can call the same indexer from WorkManager or FileObserver.

### UI Layer

Single-activity Compose app with Material 3. One screen:
- `SearchBar`/`TextField` at top, queries FTS on each keystroke (debounced ~300ms)
- `LazyColumn` of result cards showing note title + matched text + "Open" action
- "Reindex" button in top bar triggers manual reindex
- `LinearProgressIndicator` + status text during indexing
- Empty states for no results and first launch (no indexed notes)

Deep linking to Notes app via explicit Intent to `com.onyx.android.note/.note.ui.ScribbleActivity` with `documentId` and `parentUniqueId` extras.

### Custom Fleece Decoder

Read-only Kotlin implementation of the [Fleece binary format](https://github.com/couchbase/fleece/blob/master/Fleece.md) (Apache 2.0 spec). Operates on raw `ByteArray` from SQLite `body` BLOB column.

**Scope:** Decode dicts, arrays, strings, integers, booleans. No encoding, no mutation, no delta handling.

**Extracted fields from NOTE_TREE:** `title` (string), `parentUniqueId` (string), `type` (int).

**Extracted fields from per-note databases:** `shapeType` (int), `uniqueId` (string), `revisionId` (string).

**Shape type filtering:** Only process handwriting shapes: types 2 (pencil), 3 (oily pen), 4 (fountain pen), 5 (brush), 15 (marker), 21 (neo brush), 22 (charcoal), 47 (square pen), 60 (latin calligraphy), 61 (asian calligraphy).

### Search Index Schema

Room database with two tables:

**`indexed_shapes`** — one row per recognized shape:
- `shapeId` (PK) — shape UUID from xref/Couchbase
- `documentId` — note UUID
- `pageId` — page UUID
- `parentUniqueId` — folder UUID
- `noteTitle` — denormalized for display
- `recognizedText` — HWR output
- `pointFilePath` — source point file path
- `pointFileModified` — last modified timestamp
- `pointFileSize` — file size (for change detection)
- `indexedAt` — when indexed

**`indexed_shapes_fts`** — FTS5 virtual table on `recognizedText` and `noteTitle`.

### Key Dependencies

| Dependency | Purpose |
|------------|---------|
| Jetpack Compose + Material 3 | UI |
| Room + FTS5 | Search index persistence |
| kotlinx-coroutines-android 1.9.0 | Async pipeline (matches AragoniteHWR) |
| AragoniteHWR via `includeBuild` | Handwriting recognition |

No DI framework — manual construction. Build config matches AragoniteHWR: compile SDK 35, min SDK 29, Java 17, Kotlin 2.0.21. Namespace: `dev.aragonite.powersearch` (app), `dev.aragonite.fleece` (decoder).

## Existing Patterns

Investigation of the sibling AragoniteHWR project revealed these patterns:

- **Singleton object pattern** — AragoniteHWR uses `object` for its public API. This app uses manual construction with constructor injection instead (the object graph is larger), but follows the same spirit of no DI framework.
- **Coroutine-based async** — AragoniteHWR uses `suspend` functions and `suspendCancellableCoroutine`. This app follows the same coroutine patterns for indexing and HWR calls.
- **Namespace convention** — `dev.aragonite.*` namespace. This app uses `dev.aragonite.powersearch` and `dev.aragonite.fleece`.
- **Build config** — compile SDK 35, min SDK 29, Java 17, Kotlin 2.0.21. Matched exactly.
- **Separate module for reusable library** — AragoniteHWR's `lib` module pattern is followed for the `fleece` module.

No existing UI or database patterns exist in the workspace (AragoniteHWR is a headless library).

## Implementation Phases

<!-- START_PHASE_1 -->
### Phase 1: Project Scaffolding
**Goal:** Android app project with multi-module Gradle setup, builds and runs on device.

**Components:**
- Root `build.gradle.kts` and `settings.gradle.kts` with `:app`, `:fleece`, and `includeBuild("../AragoniteHWR")`
- `app/build.gradle.kts` with Compose, Room, Material 3 dependencies
- `fleece/build.gradle.kts` as pure Kotlin library (no Android plugin)
- `app/src/main/AndroidManifest.xml` with `MANAGE_EXTERNAL_STORAGE` permission
- Minimal `MainActivity` with Compose scaffold
- Storage permission request flow on first launch

**Dependencies:** None (first phase)

**Done when:** App installs on Boox device, requests storage permission, shows empty Compose scaffold
<!-- END_PHASE_1 -->

<!-- START_PHASE_2 -->
### Phase 2: Fleece Decoder
**Goal:** Read-only Fleece binary decoder that extracts structured data from Couchbase BLOBs.

**Components:**
- Fleece decoder in `fleece/` module — parses Fleece binary format into Kotlin maps/lists/primitives
- Test fixtures using real BLOBs pulled from device via adb

**Dependencies:** Phase 1 (project structure)

**Covers:** power-search-mvp.AC1.1, power-search-mvp.AC1.2, power-search-mvp.AC1.3

**Done when:** Decoder correctly extracts title, parentUniqueId, type from NOTE_TREE BLOBs and shapeType, uniqueId, revisionId from per-note BLOBs. All tests pass.
<!-- END_PHASE_2 -->

<!-- START_PHASE_3 -->
### Phase 3: Note Metadata Repository
**Goal:** Read note titles, folder structure, and shape metadata from on-device Couchbase databases.

**Components:**
- `NoteMetadataRepository` in `app/` — opens `.cblite2/db.sqlite3`, queries `kv_default`, decodes via Fleece decoder
- User ID discovery (scan `/sdcard/.ksync/couch/` for `*-NOTE_TREE.cblite2` directories)
- Shape type filtering logic (handwriting types set)

**Dependencies:** Phase 2 (Fleece decoder)

**Covers:** power-search-mvp.AC1.1, power-search-mvp.AC1.4

**Done when:** Repository returns note titles and filtered handwriting shape metadata from real device databases. All tests pass.
<!-- END_PHASE_3 -->

<!-- START_PHASE_4 -->
### Phase 4: Stroke Data & Page Dimensions
**Goal:** Read binary point files and page dimension protobuf files.

**Components:**
- `StrokeDataRepository` in `app/` — point file parser (header, xref table, TinyPoint records), protobuf page bounds parser
- TinyPoint → HWRPoint conversion (pressure normalization: `short / 4095f`)
- Page dimensions from `virtual/page/pb/{pageId}` protobuf (parse JSON bounds: `right` = width, `bottom` = height)

**Dependencies:** Phase 1 (project structure)

**Covers:** power-search-mvp.AC1.5, power-search-mvp.AC1.6

**Done when:** Parser reads point files from device, converts to HWRStroke/HWRPoint, extracts page dimensions. All tests pass using real device data as fixtures.
<!-- END_PHASE_4 -->

<!-- START_PHASE_5 -->
### Phase 5: Search Index & HWR Integration
**Goal:** Room + FTS5 database, HWR wrapper, and full indexing pipeline.

**Components:**
- `IndexRepository` — Room database with `indexed_shapes` entity and `indexed_shapes_fts` FTS5 table, diff logic
- `HWRRepository` — wraps AragoniteHWR `bindAndAwait()` and `recognizeStrokes()`
- `Indexer` — orchestrates scan → diff → metadata → strokes → HWR → store pipeline

**Dependencies:** Phase 3 (metadata), Phase 4 (strokes)

**Covers:** power-search-mvp.AC2.1, power-search-mvp.AC2.2, power-search-mvp.AC2.3, power-search-mvp.AC4.1, power-search-mvp.AC4.2, power-search-mvp.AC4.3

**Done when:** Manual reindex processes notes from device, stores recognized text in Room database, FTS queries return correct results. Diff correctly identifies new/modified/deleted notes. All tests pass.
<!-- END_PHASE_5 -->

<!-- START_PHASE_6 -->
### Phase 6: Search UI & Deep Linking
**Goal:** Complete user-facing search interface with deep linking to Notes app.

**Components:**
- `SearchViewModel` — exposes search query, results (via FTS), and indexing state as Flows
- Compose search screen — SearchBar, LazyColumn results, reindex button, progress indicator, empty states
- Deep link Intent construction to `ScribbleActivity`
- Material 3 theming

**Dependencies:** Phase 5 (index and HWR)

**Covers:** power-search-mvp.AC3.1, power-search-mvp.AC3.2, power-search-mvp.AC3.3, power-search-mvp.AC3.4, power-search-mvp.AC3.5, power-search-mvp.AC4.4

**Done when:** User can search handwritten notes by text, see results with note titles, tap to open in Notes app. Reindex button works with progress feedback. All tests pass.
<!-- END_PHASE_6 -->

## Additional Considerations

**Storage permission:** `MANAGE_EXTERNAL_STORAGE` is required on Android 11+ to access `/sdcard/.ksync/`. This triggers a special permission flow (Settings screen, not a runtime dialog). The app must handle the case where permission is denied.

**User ID discovery:** The Couchbase database paths include a user ID (e.g., `66d7d5d9828c9855ceb1578e`). The app discovers this by scanning `/sdcard/.ksync/couch/` for `*-NOTE_TREE.cblite2` directories and extracting the prefix. Multiple user IDs are theoretically possible but unlikely on a single device.

**HWR service availability:** AragoniteHWR's `bindAndAwait()` returns `false` if the KHwrService isn't available. The indexer must handle this gracefully — skip HWR and report the error rather than crashing.

**Future sync triggers:** The indexer is a plain class with constructor-injected repositories. Adding FileObserver or WorkManager triggers in v0.2 means creating new callers of the same indexer, not restructuring the pipeline.
