<p align="center">
  <img src="powersearch.png" width="400" alt="PowerSearch logo" />
</p>

# Aragonite PowerSearch

Persistent handwriting search for Onyx BOOX e-ink tablets. Index once, search instantly.

## The Problem

BOOX devices re-run OCR from scratch on every search query. For a library of hundreds of handwritten notes, this means 30-60 seconds per search, every time, with no caching. Users stop searching and handwritten notes become write-only.

## The Solution

PowerSearch builds a persistent full-text search index of your handwriting. Each page is recognized once via the on-device MyScript engine, and the results are stored in a local database. Subsequent searches return results in milliseconds.

### What It Does

- Scans all handwritten notes from `.ksync` storage
- Batches strokes per page and sends them to KHwrService (MyScript iink) for recognition
- Stores recognized text in a Room + FTS4 full-text search database
- Provides instant search with prefix matching ("hammer" finds "Hammerspace")
- Deep-links search results directly into the BOOX Notes app
- Runs indexing as a foreground service with pause/resume support
- Exports/imports the search index between devices

### Performance

| Device | SoC | Pages/min | Full library (12K pages) |
|--------|-----|-----------|--------------------------|
| Note Max | Snapdragon 855 | ~84 | ~2.5 hours |
| Palma 2 Pro | Snapdragon 750G | ~50 | ~4 hours |
| Go 10.3 Gen 2 Lumi | Snapdragon 690 | ~39 | ~5.5 hours |

Recognition hit rate: **100%** after single-point stroke filter fix.
Index size: **~8MB** for 12,000 pages.

### Key Discovery

A single-point stroke (one point with no move/up event) causes MyScript's KHwrService to hang indefinitely, killing all subsequent recognition calls. Filtering strokes with fewer than 2 points before sending to HWR eliminates all recognition failures.

## Architecture

Multi-module Gradle project:

- **`:app`** — Android app (Kotlin, Jetpack Compose, Room, FTS4)
- **`:fleece`** — Pure Kotlin decoder for Couchbase Lite's Fleece binary format

Data flow:
```
Scan .ksync/point/ files
  → Diff against Room index
  → Read Couchbase metadata via Fleece decoder
  → Parse binary point files (TinyPoint records)
  → Batch strokes per page → KHwrService (MyScript)
  → Store in Room + FTS4
  → Search UI → Deep-link to ScribbleActivity
```

### Dependencies

- [AragoniteHWR](https://github.com/jtdLab/AragoniteHWR) — Kotlin wrapper for BOOX KHwrService
- Jetpack Compose, Room, Material 3
- Targets Onyx BOOX devices with firmware 4.1.1+, Android 11+ (API 30)

## Building

```bash
# Requires AragoniteHWR sibling directory
git clone https://github.com/jtdLab/AragoniteHWR.git ../AragoniteHWR

# Build
./gradlew :app:assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Grant "All Files Access" storage permission when prompted
2. Tap **Update Index** to start indexing (or **Rebuild from Scratch** for a fresh start)
3. Indexing runs as a foreground service — you can leave the app
4. Search by typing and tapping **Search**
5. Tap a result to open the note in BOOX Notes

### Multi-Device

Index on your fastest device, then share:
1. Tap **Export Index** on the source device
2. Copy `/sdcard/PowerSearch/power_search.db` to the target device
3. Tap **Import Index** on the target device

## Documentation

- [`docs/onyx-integration-proposal.md`](docs/onyx-integration-proposal.md) — Proposal for firmware-level integration
- [`docs/design-plans/`](docs/design-plans/) — Original MVP design
- [`docs/implementation-plans/`](docs/implementation-plans/) — Phase-by-phase implementation plans

## License

[Apache 2.0](LICENSE)
