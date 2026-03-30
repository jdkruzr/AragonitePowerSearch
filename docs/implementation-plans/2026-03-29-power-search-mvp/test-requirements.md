# Test Requirements — Aragonite Power Search MVP

Maps each acceptance criterion from the [design plan](../../design-plans/2026-03-29-power-search-mvp.md) to either an automated test or a documented human verification procedure. Rationalized against implementation decisions in Phases 1-6.

---

## Legend

| Column | Meaning |
|--------|---------|
| **AC** | Acceptance criterion identifier |
| **Test type** | `unit` (JVM, src/test/), `integration` (Android instrumented, src/androidTest/), `compose` (Compose UI test, src/androidTest/), `human` (manual on-device verification) |
| **Module** | `:fleece` or `:app` |

---

## AC1: App reads note data from .ksync filesystem

### AC1.1 — Fleece decoder extracts title and parentUniqueId from NOTE_TREE BLOB

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Decode real NOTE_TREE BLOB fixture; assert `title` is non-empty string, `parentUniqueId` is string | unit | :fleece | `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` |
| `getNoteMetadata()` on real device NOTE_TREE database returns results with non-empty `title` and `documentId` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/NoteMetadataRepositoryTest.kt` |

**Rationale:** Phase 2 tests the Fleece decoder in isolation with binary fixture files (real BLOBs pulled via adb, or synthetic byte arrays per the Fleece spec). Phase 3 tests the full NoteMetadataRepository on-device because it depends on `android.database.sqlite.SQLiteDatabase`, which is unavailable in JVM tests. Together they cover both the decoder and the repository integration.

---

### AC1.2 — Fleece decoder extracts shapeType, uniqueId, revisionId from per-note BLOB

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Decode real per-note BLOB fixture; assert `shapeType` is int, `uniqueId` is non-empty string, `revisionId` is non-empty string | unit | :fleece | `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` |

**Rationale:** Phase 2 Task 4 explicitly tests per-note BLOB decoding. The integration path through `getHandwritingShapes()` is tested under AC1.4.

---

### AC1.3 — Fleece decoder returns error/empty for malformed or empty BLOBs without crashing

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `decodeAsDict(ByteArray(0))` returns null | unit | :fleece | `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` |
| `decodeAsDict(byteArrayOf(0x42))` (1-byte array) returns null | unit | :fleece | `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` |
| `decodeAsDict(randomGarbageBytes)` returns null or value, no exception thrown | unit | :fleece | `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` |
| `decodeAsDict(truncatedBlob)` (first 4 bytes of valid BLOB) returns null or partial, no exception | unit | :fleece | `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` |

**Rationale:** All edge cases are pure decoder logic with no Android dependencies, so they run as JVM unit tests. Phase 2 Task 4 specifies wrapping these in `assertDoesNotThrow`.

---

### AC1.4 — NoteMetadataRepository filters shapes to handwriting types only (2,3,4,5,15,21,22,47,60,61)

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `HandwritingShapeTypes.TYPES` contains exactly {2,3,4,5,15,21,22,47,60,61}, has 10 elements, excludes common non-handwriting types (0,1,6,10) | unit | :app | `app/src/test/java/dev/aragonite/powersearch/data/HandwritingShapeTypesTest.kt` |
| `getHandwritingShapes()` on real device returns only shapes with `shapeType` in `HandwritingShapeTypes.TYPES` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/NoteMetadataRepositoryTest.kt` |

**Rationale:** Phase 3 Task 3 splits this into a pure JVM unit test for the constant set (no device needed) and an instrumented test for the filtering behavior against real Couchbase data.

---

### AC1.5 — Point file parser reads xref table and extracts TinyPoint records with correct big-endian decoding

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `readXref()` returns non-empty list; each entry has 36-char UUID, non-negative offset, positive length | unit | :app | `app/src/test/java/dev/aragonite/powersearch/data/PointFileParserTest.kt` |
| `readShapePoints()` for first xref entry returns non-empty `TinyPoint` list; x,y in plausible range (0-3000); pressure in 0-4095 | unit | :app | `app/src/test/java/dev/aragonite/powersearch/data/PointFileParserTest.kt` |

**Rationale:** Phase 4 Task 3 uses a real point file pulled from device as a test resource in `app/src/test/resources/sample_point_file.bin`. `PointFileParser` uses only `java.io.RandomAccessFile` (no Android APIs), so it runs as a JVM unit test.

---

### AC1.6 — Page dimensions extracted from virtual/page/pb protobuf JSON bounds (right=width, bottom=height)

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `getPageDimensions()` for known note/page returns non-null `PageDimensions` with width > 0 and height > 0; values are plausible screen dimensions (~1404x1872) | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/StrokeDataRepositoryTest.kt` |

**Rationale:** Phase 4 uses `org.json.JSONObject` (Android built-in), and the protobuf files are on the device filesystem, so this must be an instrumented test.

---

## AC2: HWR processes shapes and stores results

### AC2.1 — Strokes converted to HWRPoint with pressure normalized (short/4095f) and passed to recognizeStrokes()

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| After `reindex()` on device, at least one indexed shape has non-empty `recognizedText` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` |
| `readStrokesForShape()` returns `HWRStroke` with each `HWRPoint.pressure` in 0.0-1.0 range | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/StrokeDataRepositoryTest.kt` |

**Rationale:** Pressure normalization is tested at two levels: StrokeDataRepository verifies the conversion math, and IndexerTest proves the full pipeline produces recognized text.

---

### AC2.2 — Recognized text stored in Room database keyed by shapeId, with note metadata

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| After `reindex()`, indexed shapes have populated `shapeId`, `documentId`, `pageId`, `noteTitle`, `recognizedText` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` |
| `upsertShape()` followed by `search()` returns the shape via FTS | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexRepositoryTest.kt` |

---

### AC2.3 — HWR returning empty/null for a shape does not block indexing of remaining shapes

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `reindex()` completes without throwing; `IndexResult.processed > 0` even if some shapes fail | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` |
| Create Indexer with stub `HWRRepository` whose `bind()` returns false; `reindex()` completes, shapes indexed with empty `recognizedText` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` |

---

## AC3: Search UI returns results and opens notes

### AC3.1 — Typing a query returns matching results from FTS index within ~300ms debounce

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Type query matching pre-populated data; assert results appear (using `waitUntil` for debounce) | compose | :app | `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt` |

---

### AC3.2 — Search results display note title and matched recognized text

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| After search, verify result cards display note title and recognized text content | compose | :app | `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt` |

---

### AC3.3 — Tapping a result launches ScribbleActivity Intent with correct documentId and parentUniqueId

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `buildNoteIntent(shape)` returns Intent with correct component, `documentId` extra, `parentUniqueId` extra | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/ui/DeepLinkTest.kt` |

**Implementation note:** Phase 6 Task 6 calls for extracting Intent construction from `openNote()` into a testable `buildNoteIntent()` function.

---

### AC3.4 — Empty query shows no results (not all results)

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| With empty text field, assert no result cards are displayed | compose | :app | `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt` |

---

### AC3.5 — Query matching no indexed text shows 'no results' empty state

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Type query matching nothing; assert "No results" text is displayed | compose | :app | `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt` |

---

## AC4: Manual reindex with diff

### AC4.1 — New point files (not in index) are processed and added

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `computeDiff()` with file path not in index returns it in `FileDiff.newFiles` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexRepositoryTest.kt` |

---

### AC4.2 — Modified point files (changed timestamp or size) are re-processed and updated

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Insert `IndexedShape` with known `pointFileModified`/`pointFileSize`; `computeDiff()` with different timestamp returns `FileDiff.modifiedFiles` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexRepositoryTest.kt` |

---

### AC4.3 — Deleted point files (in index but not on filesystem) are removed from index

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Insert `IndexedShape`; `computeDiff()` with empty map returns indexed path in `FileDiff.deletedPaths` | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexRepositoryTest.kt` |

---

### AC4.4 — Reindex button shows progress indicator and disables during indexing

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Tap reindex button; assert button becomes disabled; assert `LinearProgressIndicator` is displayed | compose | :app | `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt` |

---

### AC4.5 — Reindex with no storage permission shows informative error, not crash

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| `Indexer.reindex()` returns `IndexResult` with error containing "Storage permission", zero counts | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` |

**Testing constraint:** Requires `MANAGE_EXTERNAL_STORAGE` to NOT be granted, which conflicts with other tests. May need a separate test run.

---

### AC4.6 — Reindex when HWR service unavailable reports error and skips recognition gracefully

| Test | Type | Module | File Path |
|------|------|--------|-----------|
| Indexer with stub `HWRRepository` (`bind()` returns false); `reindex()` completes, error contains "HWR service unavailable", shapes have empty text | integration | :app | `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` |

---

## Human Verification Requirements

### HV-1: App installs and shows scaffold (Phase 1)

**What:** App installs on BOOX device, requests MANAGE_EXTERNAL_STORAGE via Settings page, shows Compose scaffold.

**Justification:** MANAGE_EXTERNAL_STORAGE triggers a system Settings page. Cross-app navigation cannot be automated.

**Verification:**
1. `adb install app/build/outputs/apk/debug/app-debug.apk`
2. Launch, verify permission screen, grant via Settings, verify scaffold appears
3. Check logcat for crashes

---

### HV-2: Deep link opens correct note in BOOX Notes (AC3.3 supplement)

**What:** Tapping a search result opens the corresponding note in BOOX Notes.

**Justification:** Intent extras are reverse-engineered and undocumented. Only real device testing confirms they work.

**Verification:**
1. Search for a word in a known note, tap result
2. Verify BOOX Notes opens to correct note
3. Test with note in subfolder (parentUniqueId handling)

---

### HV-3: End-to-end search accuracy on real handwriting (AC2.1 + AC3.1)

**What:** HWR output is accurate enough for meaningful search.

**Justification:** HWR accuracy depends on handwriting style and KHwrService model quality. Qualitative judgment required.

**Verification:**
1. Write a note with known words, reindex, search for those words
2. Verify results appear and recognized text is reasonable

---

### HV-4: Reindex performance on realistic data volume (non-functional)

**What:** Reindex completes in reasonable time with 50-200 notes.

**Verification:**
1. Run reindex on device with 50+ notes
2. Target: under 5 minutes for initial index
3. Second reindex should be near-instant (no changes)

---

### HV-5: E-ink display rendering (non-functional)

**What:** UI renders legibly on e-ink display (contrast, text sizes, no animation artifacts).

**Verification:**
1. Launch app on BOOX device
2. Verify text field, result cards, and progress indicator are readable
3. Check contrast is sufficient on e-ink screen

---

## Test File Summary

| File Path | Test Type | ACs Covered |
|-----------|-----------|-------------|
| `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt` | unit (JVM) | AC1.1, AC1.2, AC1.3 |
| `app/src/test/java/dev/aragonite/powersearch/data/HandwritingShapeTypesTest.kt` | unit (JVM) | AC1.4 |
| `app/src/test/java/dev/aragonite/powersearch/data/PointFileParserTest.kt` | unit (JVM) | AC1.5 |
| `app/src/androidTest/java/dev/aragonite/powersearch/data/NoteMetadataRepositoryTest.kt` | integration | AC1.1, AC1.4 |
| `app/src/androidTest/java/dev/aragonite/powersearch/data/StrokeDataRepositoryTest.kt` | integration | AC1.6, AC2.1 |
| `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexRepositoryTest.kt` | integration | AC2.2, AC4.1, AC4.2, AC4.3 |
| `app/src/androidTest/java/dev/aragonite/powersearch/data/IndexerTest.kt` | integration | AC2.1, AC2.2, AC2.3, AC4.5, AC4.6 |
| `app/src/androidTest/java/dev/aragonite/powersearch/ui/SearchScreenTest.kt` | compose | AC3.1, AC3.2, AC3.4, AC3.5, AC4.4 |
| `app/src/androidTest/java/dev/aragonite/powersearch/ui/DeepLinkTest.kt` | integration | AC3.3 |

## Coverage Matrix

| AC | Automated | Human |
|----|-----------|-------|
| AC1.1 | FleeceDecoderTest + NoteMetadataRepositoryTest | -- |
| AC1.2 | FleeceDecoderTest | -- |
| AC1.3 | FleeceDecoderTest (4 edge cases) | -- |
| AC1.4 | HandwritingShapeTypesTest + NoteMetadataRepositoryTest | -- |
| AC1.5 | PointFileParserTest | -- |
| AC1.6 | StrokeDataRepositoryTest | -- |
| AC2.1 | IndexerTest + StrokeDataRepositoryTest | HV-3 |
| AC2.2 | IndexerTest + IndexRepositoryTest | -- |
| AC2.3 | IndexerTest (2 approaches) | -- |
| AC3.1 | SearchScreenTest | -- |
| AC3.2 | SearchScreenTest | -- |
| AC3.3 | DeepLinkTest | HV-2 |
| AC3.4 | SearchScreenTest | -- |
| AC3.5 | SearchScreenTest | -- |
| AC4.1 | IndexRepositoryTest | -- |
| AC4.2 | IndexRepositoryTest | -- |
| AC4.3 | IndexRepositoryTest | -- |
| AC4.4 | SearchScreenTest | -- |
| AC4.5 | IndexerTest | -- |
| AC4.6 | IndexerTest | -- |

## Design Deviation Notes

1. **FTS4 vs FTS5:** Room only provides `@Fts4` annotation. FTS4 supports the same `MATCH` queries needed for keyword search.
2. **Test fixture strategy:** Phases 2 and 4 require binary fixtures from device via adb. Phase 2 has a synthetic fallback; Phase 4 requires real point files.
3. **`buildNoteIntent()` extraction:** Phase 6 Task 6 requires extracting Intent construction into a testable function.
4. **AC4.5 testing constraint:** Requires separate test run without storage permission granted.
