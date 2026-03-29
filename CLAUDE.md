# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Aragonite Power Search is an Android app for Onyx Boox e-ink tablets that builds a persistent, incrementally-updated handwriting search index. The built-in Boox search re-runs OCR from scratch on every query; this app caches recognition results in a Room + FTS database for instant search.

**Status:** Pre-code design phase. See `docs/design-plans/2026-03-29-power-search-mvp.md` for the validated MVP design with acceptance criteria. `ARAGONITE_POWER_SEARCH.md` contains earlier research notes on data formats and scope/roadmap.

## Target Platform

- Android 15 (API 35), targeting Onyx Boox devices with firmware 4.1.1+
- Kotlin, Jetpack Compose for UI
- Room with SQLite FTS5 for the search index
- Depends on the sibling [AragoniteHWR](../AragoniteHWR) library for handwriting recognition via on-device IPC
- Custom Fleece decoder (`:fleece` module, pure Kotlin, no Android deps) for reading Couchbase metadata
- No DI framework — manual construction, matching AragoniteHWR conventions
- Build config: compile SDK 35, min SDK 29, Java 17, Kotlin 2.0.21

## Key Domain Concepts

- **Point files** — binary files at `/sdcard/.ksync/point/{noteId}/{pageId}/{revisionId}` containing handwriting stroke data. 76-byte header, xref table at end of file, 16-byte big-endian TinyPoint records per shape.
- **TinyPoint** — packed record: `float x, float y, short size, short pressure, int time` (16 bytes, big-endian).
- **Xref entries** — 44 bytes each: 36-byte UUID string + 4-byte offset + 4-byte length. Located via the last 4 bytes of the file.
- **HWR** — Handwriting Recognition. Pressure normalizes from 0-4095 (12-bit EMR) to 0.0-1.0 for AragoniteHWR.
- **NOTE_TREE** — Couchbase database containing note metadata (titles, folder structure). Accessed via SQLite + custom Fleece decoder.
- **Fleece** — Couchbase's binary encoding format for document bodies in `kv_default.body` BLOB column. Spec is Apache 2.0. This project has a custom read-only decoder.
- **Page dimensions** — stored in protobuf at `/sdcard/.ksync/document/{noteId}/virtual/page/pb/{pageId}`, JSON bounds field (`right`=width, `bottom`=height).

## Architecture

Multi-module Gradle project: `:app` (Android app), `:fleece` (pure Kotlin Fleece decoder), plus `includeBuild("../AragoniteHWR")`.

**Data flow:** Scan `.ksync/point/` files → diff against Room index → read Couchbase metadata via Fleece decoder (titles, shape types) → parse point files (binary TinyPoint records) → read page dimensions from protobuf → AragoniteHWR recognition → store in Room + FTS5 → search UI → Intent deep-link to ScribbleActivity.

**Repository layer:** `NoteMetadataRepository` (Couchbase/Fleece), `StrokeDataRepository` (point files + protobuf), `IndexRepository` (Room + FTS5), `HWRRepository` (AragoniteHWR wrapper). `Indexer` orchestrates the pipeline; called from ViewModel for MVP.

## Deep-Link to Notes

Notes open via explicit Intent to `com.onyx.android.note/.note.ui.ScribbleActivity` with extras: `documentId`, `parentUniqueId`, `jump_from_document_path`.
