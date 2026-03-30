# Persistent Handwriting Search for BOOX Devices

A proposal for built-in handwriting search indexing on Onyx BOOX e-ink tablets.

## The Problem

BOOX devices ship with a search feature in the Notes app that re-runs OCR from scratch on every query. For a user with 50+ notebooks, this means:

- **30-60 seconds per search** — OCR runs against every page every time
- **No incremental benefit** — searching for the same word tomorrow re-does all the work
- **Battery drain** — repeated full-OCR passes consume significant power
- **Users stop searching** — the friction means handwritten notes become write-only

## The Solution: Persistent Search Index

Cache handwriting recognition results in a local database. Recognize each page once, store the text, search instantly.

### How It Works

```
Write a note → Save triggers indexing of changed pages
               → HWR runs once per page
               → Text stored in full-text search database
               → Subsequent searches are instant (<100ms)
```

### Architecture (Firmware Integration)

```
┌─────────────────────────────────────────────────┐
│  Note Save Path (ScribbleActivity)              │
│  on page close → notify IndexService            │
└────────────────────┬────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────┐
│  IndexService (system service, privileged)       │
│                                                  │
│  • Receives page-change notifications            │
│  • Queues pages for recognition                  │
│  • Runs on AC power only (initial index)         │
│  • Immediate for single-page updates             │
│  • Checkpoints progress for resume               │
└────────────────────┬────────────────────────────┘
                     │
            ┌────────┴────────┐
            ▼                 ▼
   ┌──────────────┐  ┌──────────────┐
   │  KHwrService  │  │  Search Index │
   │  (on-device)  │  │  (SQLite)     │
   └──────────────┘  └──────────────┘
            │                 │
            └────────┬────────┘
                     ▼
         ┌─────────────────────┐
         │  Search UI           │
         │  <100ms full-text queries  │
         │  Deep-link to note   │
         └─────────────────────┘
```

### Three Operating Modes

**1. Incremental (real-time)**
When a user saves or closes a page, only that page is re-indexed. This is a single HWR call (~0.5-1s) and is imperceptible to the user. No battery concern.

**2. Initial index (power-aware)**
First-time setup or firmware upgrade. Must process every page in the library. For a user with 1,000+ pages, this takes 1-2 hours. Should run **only while plugged into power**:

- Register as a system `JobScheduler` task with `setRequiresCharging(true)`
- Checkpoint after each notebook so progress survives interruptions
- Pause on unplug, resume on next charge
- Show progress in system notification: "Indexing handwriting: 847/2,304 pages"
- Complete index builds silently over 1-3 charging sessions

**3. Diff reindex (periodic)**
Catches edge cases (files synced from cloud, restored from backup). Runs daily while charging. Compares file timestamps against index, processes only changes. Typically completes in seconds.

### Why This Belongs in Firmware

| Capability | Third-Party App | Firmware Integration |
|-----------|----------------|---------------------|
| Trigger on note save | Poll filesystem (wasteful) | Direct callback from save path |
| Background execution | Foreground service + notification | System service, no restrictions |
| Power-aware scheduling | WorkManager (10-min chunks) | JobScheduler (unlimited, system) |
| HWR access | IPC to KHwrService | Direct in-process call |
| Search from Notes app | Separate app, context switch | Integrated into existing search UI |
| Survive reboot mid-index | Must re-checkpoint | System service auto-restarts |

### Performance Characteristics

Based on testing with AragoniteHWR on a BOOX Tab Ultra C Pro:

| Metric | Value |
|--------|-------|
| HWR per page (batch) | ~0.5-1.5s |
| full-text search query | <50ms |
| Index size per 1000 pages | ~5-10 MB |
| Initial index, 1000 pages | ~20 min on charger |
| Incremental (1 page) | <2s, imperceptible |
| Diff reindex (no changes) | <3s |

### Data Model

One row per page revision in an full-text-search-enabled SQLite table:

```sql
CREATE TABLE indexed_pages (
    page_id TEXT PRIMARY KEY,     -- documentId + pageId + revision
    document_id TEXT,
    page_number INTEGER,
    note_title TEXT,
    parent_folder_id TEXT,
    recognized_text TEXT,         -- full page HWR output
    point_file_path TEXT,
    point_file_modified INTEGER,
    indexed_at INTEGER
);

-- full-text search index for instant search
CREATE VIRTUAL TABLE indexed_pages_fts USING fts4(
    content="indexed_pages",
    recognized_text,
    note_title
);
```

### Search UX

The search experience should be:

1. **Instant** — results appear as you type, debounced ~300ms
2. **Contextual** — show note title + matched text snippet with highlights
3. **Actionable** — tap a result to jump directly to that page in the note
4. **Integrated** — accessible from the Notes app search bar, not a separate app

### Proof of Concept

Aragonite Power Search demonstrates this architecture as a third-party Android app:

- Reads `.ksync/point/` files and Couchbase metadata via Fleece decoder
- Batches strokes per page for HWR via AragoniteHWR (KHwrService wrapper)
- Stores results in Room + full-text search database
- Compose UI with debounced search and deep-linking to ScribbleActivity
- Full source available for evaluation

The third-party implementation works but is constrained by Android's background execution limits and the inability to hook into the note save path. A firmware integration would eliminate these constraints and provide a seamless user experience.

## Multi-Device Index Distribution

BOOX devices already sync note data between devices via Couchbase Lite (`wss://cb.boox.com/neocloud/_blipsync`). The search index can piggyback on this existing infrastructure.

### Recommended: Sync recognized text as a document field

Add `recognizedText` and `recognizedAt` fields to the existing per-page Couchbase documents that already sync between devices:

```
Device A writes a note
  → Page saved → HWR runs → recognizedText field added to page document
  → Couchbase syncs document to cloud
  → Device B receives document with recognizedText already populated
  → Device B builds local full-text search index from synced text (no HWR needed)
```

**Benefits:**
- **HWR runs once across all devices** — not once per device. A 2-hour initial index on one device means zero wait on every other device.
- **Zero additional infrastructure** — uses the existing Couchbase sync channel
- **Conflict resolution is free** — Couchbase handles the case where two devices recognize the same page simultaneously
- **New device setup is instant** — after note sync completes, the search index builds from already-recognized text in seconds (no HWR pass needed)
- **Privacy preserved** — recognition happens on-device, only text travels through the existing encrypted sync path

### Why not sync the full-text search database directly?

Shipping the SQLite file between devices is simpler but fragile. Schema changes break sync, the file isn't diff-friendly, and it bypasses Couchbase's conflict resolution. Storing recognized text as a document field lets each device build its own full-text search index locally — which is fast since it's just inserting text, not running HWR.

### Why not server-side HWR?

Running recognition in the cloud during sync would eliminate all device-side cost. But it requires cloud compute infrastructure, adds latency to sync, and raises privacy concerns for handwritten personal notes. On-device HWR with synced results gives the same "recognize once" benefit without the privacy tradeoff.

## The Missing Line

The infrastructure for persistent handwriting search already exists in the BOOX firmware. It is nearly complete. Through reverse engineering of `knote2-release.apk`, we found:

1. **Every shape record in Couchbase has a `text` field** (shared key index 37 in `kv_info`). It is always empty.

2. **The "auto handwriting recognition in the background" feature** (`HWRecognizeInBackgroundAction`) runs MyScript iink, produces recognized text shapes, and calls `savePage()` to persist them.

3. **`savePage()` writes shape records to Couchbase** via `NewShapeDataProvider.saveShapeList()`. The Couchbase sync layer then replicates these records to the cloud and other devices.

4. **But `ShapeFactory.newShapeModelFromShape()` does not map the recognized text to the `text` field.** The HWR output is saved to the protobuf shape snapshots (`.ksync/document/{id}/shape/*.zip`) but the Couchbase record's `text` field is written as an empty string.

The entire pipeline — recognition, persistence, sync, and a database field waiting to hold the result — is built. The recognized text is produced, the `text` field exists, the save path runs, and the sync would carry it to every device. The text just isn't copied into the field before the record is written.

This is the gap:

```
Shape (has recognized text)
    ↓
ShapeFactory.newShapeModelFromShape(shape)
    ↓
NewShapeModel (text field = "")  ← recognized text not mapped
    ↓
NewShapeDataProvider.saveShapeList()
    ↓
Couchbase kv_default (text = "")
    ↓
Couchbase Sync → other devices (text = "")
```

Fixing this is a one-line change in the shape-to-model mapping. Once the `text` field is populated:

- **Cross-note search becomes a SQL query** — `SELECT * FROM kv_default WHERE body MATCH 'query'` across all per-note databases, or a single full-text-search index built from the synced `text` fields.
- **Multi-device sync carries recognition results for free** — recognize on one device, search on all of them.
- **The "auto background recognition" feature becomes useful beyond the current note** — right now it runs, produces text, and then effectively throws it away when the note is closed.

We confirmed this gap by examining the live Couchbase databases on a BOOX Tab Ultra C Pro running firmware 4.1.1. All 1,068 shape records in a test note had `text = ""` despite having run full-page HWR through the Notes app.

## Summary

Handwriting search should be instant. The recognition work needs to happen once per page, not once per query. A persistent index with power-aware background processing turns handwritten notes from write-only archives into a searchable knowledge base — which is the entire reason users choose handwriting on an e-ink tablet over typing on a laptop.
