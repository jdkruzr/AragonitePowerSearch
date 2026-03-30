# CLAUDE.md

Last verified: 2026-03-29

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Aragonite Power Search is an Android app for Onyx Boox e-ink tablets that builds a persistent, incrementally-updated handwriting search index. The built-in Boox search re-runs OCR from scratch on every query; this app caches recognition results in a Room + FTS database for instant search.

**Status:** MVP implemented. Design plan at `docs/design-plans/2026-03-29-power-search-mvp.md`. Implementation plans at `docs/implementation-plans/2026-03-29-power-search-mvp/`. Research notes in `ARAGONITE_POWER_SEARCH.md`.

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

- `app/` -- Android app module (Compose UI, Room database, repositories, indexer)
- `fleece/` -- Pure Kotlin Fleece decoder (no Android deps). See `fleece/CLAUDE.md`.
- `docs/design-plans/` -- Validated design documents
- `docs/implementation-plans/` -- Phase-by-phase implementation plans

## Key Domain Concepts

- **Point files** -- binary files at `/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}` containing handwriting stroke data. 76-byte header, xref table at end of file, 16-byte big-endian TinyPoint records per shape. Each shape has 4 bytes of attributes (2x short) before its TinyPoint data.
- **TinyPoint** -- packed record: `float x, float y, short size, short pressure, int time` (16 bytes, big-endian).
- **Xref entries** -- 44 bytes each: 36-byte UUID string + 4-byte offset + 4-byte length. Located via the last 4 bytes of the file.
- **HWR** -- Handwriting Recognition. Pressure normalizes from 0-4095 (12-bit EMR) to 0.0-1.0 for AragoniteHWR.
- **Handwriting shape types** -- Only specific `shapeType` values are handwriting (pen tools). Defined in `HandwritingShapeTypes.TYPES`: 2 (pencil), 3 (oily pen), 4 (fountain pen), 5 (brush), 15 (marker), 21 (neo brush), 22 (charcoal), 47 (square pen), 60/61 (calligraphy).
- **NOTE_TREE** -- Couchbase database at `.ksync/couch/{userId}-NOTE_TREE.cblite2/db.sqlite3`. Contains note metadata (titles, folder structure). Accessed via SQLite + custom Fleece decoder. User ID discovered by scanning for `*-NOTE_TREE.cblite2` directory.
- **Per-note databases** -- Couchbase databases at `.ksync/couch/{userId}-{documentId}.cblite2/db.sqlite3`. Contain shape metadata (uniqueId, shapeType, revisionId) in Fleece-encoded BLOBs.
- **Fleece** -- Couchbase's binary encoding format for document bodies in `kv_default.body` BLOB column. See `fleece/CLAUDE.md`.
- **Page dimensions** -- stored in protobuf at `/sdcard/.ksync/document/{noteId}/virtual/page/pb/{pageId}`, JSON bounds field (`right`=width, `bottom`=height). Default fallback: 1404x1872.

## Architecture

Multi-module Gradle project: `:app` (Android app), `:fleece` (pure Kotlin Fleece decoder), plus `includeBuild("../AragoniteHWR")` with dependency substitution (`dev.aragonite:hwr` maps to AragoniteHWR `:lib`).

### Data Flow

Scan `.ksync/point/` files -> diff against Room index -> read Couchbase metadata via Fleece decoder (titles, shape types) -> filter to handwriting shapes -> parse point files (binary TinyPoint records) -> read page dimensions from protobuf -> AragoniteHWR recognition -> store in Room + FTS4 -> search UI with 300ms debounce -> Intent deep-link to ScribbleActivity.

### Repository Layer

Package: `dev.aragonite.powersearch.data`

- `NoteMetadataRepository` -- reads Couchbase/Fleece databases. Discovers userId, reads NOTE_TREE for titles, reads per-note DBs for shape metadata. Filters to `HandwritingShapeTypes.TYPES`.
- `StrokeDataRepository` -- reads point files via `PointFileParser`, converts TinyPoints to `HWRStroke`/`HWRPoint`, reads page dimensions from protobuf JSON.
- `IndexRepository` -- Room DAO wrapper. Computes `FileDiff` (new/modified/deleted) by comparing filesystem state against `indexed_shapes` table. FTS4 search via content-sync join.
- `HWRRepository` -- wraps `AragoniteHWR` static API. Bind/unbind lifecycle. Returns null if not bound. Class is `open` for test overrides.
- `Indexer` -- orchestrates full reindex pipeline. Reports `IndexProgress`. Returns `IndexResult` with counts. Checks storage permission before filesystem access. Gracefully handles missing HWR service (indexes without recognition text).

### Database Schema

Database: `power_search.db` (Room, version 1)

- `indexed_shapes` table -- primary key `shapeId` (UUID). Columns: `documentId`, `pageId`, `parentUniqueId`, `noteTitle`, `recognizedText`, `pointFilePath`, `pointFileModified`, `pointFileSize`, `indexedAt`.
- `indexed_shapes_fts` -- FTS4 content-sync table indexing `recognizedText` and `noteTitle`.

### UI Layer

Package: `dev.aragonite.powersearch.ui`

- `SearchViewModel` -- exposes `SearchUiState` (results, isIndexing, progress, count, error). Query debounced at 300ms via Flow. Reindex triggered manually.
- `SearchScreen` -- Compose UI with search field, result list, reindex button, progress display.
- `SearchViewModelFactory` -- manual DI wiring. Creates all repositories and the Indexer.

## Deep-Link to Notes

Notes open via explicit Intent to `com.onyx.android.note/.note.ui.ScribbleActivity` with extras: `documentId`, `parentUniqueId`, `jump_from_document_path`.

## Invariants

- Indexer always unbinds HWR in a `finally` block, even on failure.
- Modified files are deleted from index before re-indexing (prevents stale entries).
- FTS4 table is content-sync with `indexed_shapes` -- Room manages sync automatically.
- `PointFileParser` returns empty list (never throws) on malformed files.
- `FleeceDecoder` returns null (never throws) on invalid data.
