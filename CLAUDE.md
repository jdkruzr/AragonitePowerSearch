# CLAUDE.md

Last verified: 2026-04-12
<!-- Freshness: reviewed against commits up to dc1b680 + retry-N-days feature -->

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Aragonite Power Search is an Android app for Onyx Boox e-ink tablets that builds a persistent, incrementally-updated handwriting search index. The built-in Boox search re-runs OCR from scratch on every query; this app caches recognition results in a Room + FTS database for instant search.

**Status:** v0.1.0 release. Design plan at `docs/design-plans/2026-03-29-power-search-mvp.md`. Implementation plans at `docs/implementation-plans/2026-03-29-power-search-mvp/`. Research notes in `ARAGONITE_POWER_SEARCH.md`. CI builds release APKs on version tags via GitHub Actions.

## Target Platform

- Android 15 (API 35), targeting Onyx Boox devices with firmware 4.1.1+
- Kotlin, Jetpack Compose for UI
- Room with SQLite FTS4 for the search index (content-sync FTS table on `indexed_shapes`)
- Depends on the sibling [AragoniteHWR](../AragoniteHWR) library for handwriting recognition via on-device IPC
- Custom Fleece decoder (`:fleece` module, pure Kotlin, no Android deps) for reading Couchbase metadata
- No DI framework -- manual construction via `SearchViewModelFactory`, matching AragoniteHWR conventions
- Build config: compile SDK 35, min SDK 30, Java 17, Kotlin 2.0.21
  - minSdk 30: Environment.isExternalStorageManager() (for "All Files Access" permission check) requires API 30. All target Boox devices are API 30+.

## Commands

- `./gradlew build` -- Build all modules
- `./gradlew :app:connectedAndroidTest` -- Run instrumented tests (requires device/emulator)
- `./gradlew :fleece:test` -- Run Fleece module unit tests
- `./gradlew :app:testDebugUnitTest` -- Run app unit tests

## Project Structure

- `app/` -- Android app module (Compose UI, Room database, repositories, indexer, foreground service)
- `fleece/` -- Pure Kotlin Fleece decoder (no Android deps). See `fleece/CLAUDE.md`.
- `docs/design-plans/` -- Validated design documents
- `docs/implementation-plans/` -- Phase-by-phase implementation plans

## Key Domain Concepts

- **Point files** -- binary files at `/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}` containing handwriting stroke data. 76-byte header, xref table at end of file, 16-byte big-endian TinyPoint records per shape. Each shape has 4 bytes of attributes (2x short) before its TinyPoint data.
- **TinyPoint** -- packed record: `float x, float y, short size, short pressure, int time` (16 bytes, big-endian).
- **Xref entries** -- 44 bytes each: 36-byte UUID string + 4-byte offset + 4-byte length. Located via the last 4 bytes of the file.
- **HWR** -- Handwriting Recognition. Pressure normalizes from 0-4095 (12-bit EMR) to 0.0-1.0 for AragoniteHWR.
- **Handwriting shape types** -- Only specific `shapeType` values are handwriting (pen tools). Defined in `HandwritingShapeTypes.TYPES`: 2 (pencil), 3 (oily pen), 4 (fountain pen), 5 (brush), 15 (marker), 21 (neo brush), 22 (charcoal), 47 (square pen), 60/61 (calligraphy).
- **NOTE_TREE** -- Couchbase database at `.ksync/couch/{userId}-NOTE_TREE.cblite2/db.sqlite3`. Contains note metadata (titles, folder structure, deletion status). Accessed via SQLite + custom Fleece decoder. User ID discovered by scanning for `*-NOTE_TREE.cblite2` directory.
- **NoteTreeInfo** -- Single-pass scan result from `scanNoteTree()`: active notes with folder assignments, active folder map (UUID to name), deleted note IDs (status=0), deleted folder IDs. Used by Indexer to skip deleted notes and assign folder names.
- **Folder extraction** -- Folder names are extracted in priority order: (1) title from shared keys or slot-index read, if `isNotBlank` and not a known keyword; (2) fallback scanner that parses Fleece inline strings from the BLOB and picks the best short, non-UUID, non-keyword candidate. Folders are NOTE_TREE entries with `type=0`; notes have `type=1`. Folder assignment uses UUID substring matching in BLOB bytes.
- **Per-note databases** -- Couchbase databases at `.ksync/couch/{userId}-{documentId}.cblite2/db.sqlite3`. Contain shape metadata (uniqueId, shapeType, revisionId) in Fleece-encoded BLOBs.
- **Fleece** -- Couchbase's binary encoding format for document bodies in `kv_default.body` BLOB column. See `fleece/CLAUDE.md`.
- **Fleece wrapper prefix** -- Some devices (Palma-series) wrap Fleece BLOBs in a 10-byte dict envelope. Detected by checking `body[0] & 0xF0 == 0x70` when `body.size > 10`; stripped to `body[10:]` before decoding. Applied in both `scanNoteTree()` and `loadSampleSlotValues()`.
- **Shared key mismatch** -- Couchbase shared key tables can become inconsistent with BLOB contents across firmware updates or device migrations. The app handles this via: (1) automatic title detection by scanning for date-pattern strings (`20YYMMDD ...`) in Fleece BLOBs, (2) user-guided key mapping onboarding, (3) direct slot-index reads that bypass shared key lookup entirely.
- **Key mapping** -- User-guided onboarding flow where the user identifies which Fleece dict slot contains the note title and folder name. Slot indices are saved to SharedPreferences (`key_mapping` prefs, keys: `title_slot_index`, `folder_slot_index`, `mapping_done`). The `titleSlotIndex` is passed through `Indexer` to `scanNoteTree()` for `readStringAtSlot()` bypass.
- **Page dimensions** -- stored in protobuf at `/sdcard/.ksync/document/{noteId}/virtual/page/pb/{pageId}`, JSON bounds field (`right`=width, `bottom`=height). Default fallback: 1404x1872.

## Architecture

Multi-module Gradle project: `:app` (Android app), `:fleece` (pure Kotlin Fleece decoder), plus `includeBuild("../AragoniteHWR")` with dependency substitution (`dev.aragonite:hwr` maps to AragoniteHWR `:lib`).

### Data Flow

Scan `.ksync/point/` files -> diff against Room index -> read Couchbase metadata via Fleece decoder (titles, shape types) -> filter to handwriting shapes -> parse point files (binary TinyPoint records) -> read page dimensions from protobuf -> AragoniteHWR recognition -> store in Room + FTS4 -> search UI with 300ms debounce -> Intent deep-link to ScribbleActivity via OpenNoteBean JSON.

### Service Layer

Package: `dev.aragonite.powersearch.service`

- `IndexingService` -- Android foreground service (`dataSync` type) for long-running indexing. Exposes static `state: StateFlow<IndexingState>` for UI observation. Actions: start, pause, resume, stop, clearAndReindex. Uses `SupervisorJob` + `Dispatchers.IO`. Constructs its own `Indexer` and repositories per run. `START_NOT_STICKY` -- does not restart on process death.
- `IndexingState` -- data class: `isRunning`, `isPaused`, `phase`, `current`, `total`, `error`, `pagesIndexed`.

### Repository Layer

Package: `dev.aragonite.powersearch.data`

- `NoteMetadataRepository` -- reads Couchbase/Fleece databases. Discovers userId. `scanNoteTree(userId, titleSlotIndex)` performs a single-pass scan returning `NoteTreeInfo` (active notes with folder names, deleted note/folder IDs). When `titleSlotIndex >= 0`, reads titles via `readStringAtSlot()` bypassing shared key lookup. Strips 10-byte wrapper prefix from BLOBs when detected. Falls back to automatic `detectTitleKeyIndex()` when titles are missing or most notes share a keyword title (e.g., "Local"). Reads per-note DBs for shape metadata. Filters to `HandwritingShapeTypes.TYPES`.
- `StrokeDataRepository` -- reads point files via `PointFileParser`, converts TinyPoints to `HWRStroke`/`HWRPoint`, reads page dimensions from protobuf JSON.
- `IndexRepository` -- Room DAO wrapper. Computes `FileDiff` (new/modified/deleted) by comparing filesystem state against `indexed_shapes` table. FTS4 search via content-sync join. Prefix search: appends `*` to each whitespace-delimited word for search-as-you-type. `clearIndex()` deletes all indexed data. `deleteEmptyPages()` removes pages with empty recognizedText so they get retried on next run. `deleteEmptyPagesModifiedSince(cutoffMs)` removes only empty-text rows whose point file was modified on or after the cutoff -- used by the "retry last N days" recovery path so deleted rows flow back through `computeDiff()` as `new` without stomping on successfully-OCR'd rows. `checkpoint()` runs WAL checkpoint (used before export). `getDistinctFolders()` returns unique folder names for filter UI. `getUnfolderedDocumentIds()` finds documents with empty or stale ('Local') folder assignments for refresh. `getUntitledDocumentIds()` finds documents with empty, null, or stale 'Local' titles for refresh.
- `HWRRepository` -- wraps `AragoniteHWR` static API. Bind/unbind lifecycle. Returns null if not bound. Class is `open` for test overrides.
- `Indexer` -- orchestrates full reindex pipeline. Constructor takes `titleSlotIndex` (from SharedPreferences key mapping, default -1). Reports `IndexProgress`. Returns `IndexResult` with counts. Checks storage permission before filesystem access. Gracefully handles missing HWR service (indexes without recognition text). Skips deleted notes (status=0) and orphan documents (no NOTE_TREE entry). Stores empty-stroke pages with empty text (phantom page fix -- keeps diff stable, no cleanup pass needed). Refreshes both folder names and titles for previously-unfoldered/untitled documents (including stale 'Local' values). Includes HWR resilience: single-point stroke filter, retry on empty, adaptive throttle, service rebind after 20 consecutive empties.

### Database Schema

Database: `power_search.db` (Room, version 1). `SearchDatabase.close()` static method allows closing for import/overwrite.

- `indexed_shapes` table -- primary key `shapeId` (UUID). Columns: `documentId`, `pageId`, `parentUniqueId` (stores resolved folder name, empty string for unfiled), `noteTitle`, `recognizedText`, `pointFilePath`, `pointFileModified`, `pointFileSize`, `indexedAt`.
- `indexed_shapes_fts` -- FTS4 content-sync table indexing `recognizedText` and `noteTitle`.
- Search queries additionally filter `length(recognizedText) > 0` to exclude empty-text entries.

### UI Layer

Package: `dev.aragonite.powersearch.ui`

- `SearchViewModel` -- takes `IndexRepository` + `Context`. Delegates indexing to `IndexingService` (start/pause/resume/clearAndReindex). Observes `IndexingService.state` StateFlow for progress. Exposes `SearchUiState` (results, isIndexing, isPaused, progress, count, error, folders, selectedFolder). Search is explicit via `executeSearch()` with client-side folder filtering. `selectFolder()` sets folder filter. Also provides `exportIndex()` and `importIndex()` for database portability. `retryEmptyPagesSince(days)` deletes empty-text rows whose point file was modified in the last N days, then kicks `IndexingService.start()` so the diff re-picks them up as "new".
- `SearchScreen` -- Compose UI with search field, folder filter chips (horizontally scrollable), result list, "Update Index" and "Rebuild from Scratch" buttons (with confirmation dialog), animated/static progress bar, "X of Y pages indexed" label, and a "Retry Last N Days" control (numeric field + button, shown when not indexing) that calls `SearchViewModel.retryEmptyPagesSince()` for recovery from runs where HWR was unavailable. All user-visible strings extracted to `res/values/strings.xml`.
- `KeyMappingScreen` -- Onboarding Compose UI shown on first launch (before search). Reads a sample NOTE_TREE BLOB, decodes all dict slot values, and presents them for the user to identify the title and folder name slots. Saves slot indices to SharedPreferences (`key_mapping` prefs). Two-step flow: pick title slot, then pick folder slot (or "None of these"). Strips wrapper prefix before decoding. Checks `isKeyMappingDone()` / `clearKeyMapping()` for state management.
- `SearchViewModelFactory` -- manual DI wiring. Creates `IndexRepository` only (indexing delegated to service).

### App Navigation Flow

`MainActivity` gates screens in order: (1) storage permission request, (2) `KeyMappingScreen` onboarding (first launch only, gated by `isKeyMappingDone()`), (3) `SearchScreen`. Key mapping must complete before search is accessible.

## Deep-Link to Notes

Notes open via explicit Intent to `com.onyx.android.note/.note.ui.ScribbleActivity` with a single `OPEN_NOTE_BEAN` string extra containing JSON: `{"documentId":"...","parentUniqueId":"...","title":"..."}`. Discovered via JADX decompilation of `knote2-release.apk`. The `buildNoteIntent()` function in `SearchScreen.kt` constructs this Intent.

## Invariants

- **Single-point strokes MUST be filtered before HWR.** Strokes with < 2 points (a DOWN event with no MOVE/UP) cause MyScript's KHwrService to hang indefinitely. The filter is in `Indexer.processPointFile()`. This is the most critical invariant for HWR reliability.
- Indexer always unbinds HWR in a `finally` block, even on failure.
- Modified files are deleted from index before re-indexing (prevents stale entries).
- FTS4 table is content-sync with `indexed_shapes` -- Room manages sync automatically.
- `PointFileParser` returns empty list (never throws) on malformed files.
- `FleeceDecoder` returns null (never throws) on invalid data.
- `IndexingService` is `START_NOT_STICKY` -- Android will not restart it after process death.
- `IndexingService.state` is a static `StateFlow` -- UI observes it without binding to the service.
- "Rebuild from Scratch" cancels any running job, clears the index, then starts a fresh reindex.
- Search queries are prefix-matched: each word gets a `*` suffix for search-as-you-type behavior.
- **Deleted notes MUST be skipped during indexing.** Notes with `status=0` in NOTE_TREE and documents with no NOTE_TREE entry (orphans, reader annotations) are excluded. Checked in `Indexer.reindex()` before processing each point file.
- **Phantom pages stored with empty text, not deleted.** Pages with no handwriting strokes are stored with `recognizedText=""` to keep the filesystem diff stable. Search queries filter them out via `length(recognizedText) > 0`. No post-indexing cleanup pass.
- **Folder names resolved via BLOB byte matching, not shared keys.** Folder UUID substring is searched in note BLOB bytes to assign folders, avoiding shared key table ordering mismatches.
- HWR empty result triggers retry (once, after 500ms delay) before accepting empty text.
- After 20 consecutive empty HWR results, the service is unbound and rebound (stale connection recovery).
- Adaptive throttle inserts delays (200-1000ms) when HWR hit rate drops below 85%, eases off above 95%.
- Stale 'Local' folder assignments are treated as unfoldered and refreshed on next index run.
- Stale 'Local' note titles are treated as untitled and refreshed on next index run.
- **"Retry Last N Days" is selective by design -- only empty-text rows are deleted, never rows with real recognized text.** Non-empty rows stay put regardless of age. This lets users recover from broken indexing runs (e.g., HWR unavailable) without invalidating successful OCR output. Implementation uses `pointFileModified` (filesystem mtime), which makes it firmware-agnostic -- works identically on 4.1.1 and 4.2.
- **Key mapping onboarding MUST complete before search.** `MainActivity` gates the `SearchScreen` behind `isKeyMappingDone()`. Without key mapping, title/folder extraction may fail on devices with shared key mismatches.
- **Wrapper prefix MUST be stripped before Fleece decoding.** BLOBs starting with `0x7_` (top nibble = 7) and size > 10 have a 10-byte dict envelope that must be removed. Both `scanNoteTree()` and `loadSampleSlotValues()` apply this check.
- **Title slot index flows from SharedPreferences through Indexer to scanNoteTree.** `IndexingService` reads `getSavedTitleSlotIndex()`, passes to `Indexer` constructor, which passes to `scanNoteTree(userId, titleSlotIndex)`. When >= 0, `readStringAtSlot()` bypasses shared key lookup entirely.
