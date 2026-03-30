# Human Test Plan — Aragonite Power Search MVP

Generated from test-requirements.md. All automated acceptance criteria have corresponding tests. This plan covers the human verification procedures that cannot be automated.

---

## Prerequisites

- Onyx BOOX device with firmware 4.1.1+ (Android 11/API 30+)
- At least one handwritten note with known content (for search verification)
- AragoniteHWR (KHwrService) installed and functional on device
- Debug APK built: `./gradlew :app:assembleDebug`

---

## HV-1: App installs and shows scaffold

**AC Coverage:** Infrastructure verification (Phase 1)

**Steps:**
1. Build and install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Launch app: `adb shell am start -n dev.aragonite.powersearch/.MainActivity`
3. Verify the permission request screen appears (if MANAGE_EXTERNAL_STORAGE not granted)
4. Tap "Grant Permission" — system Settings page opens
5. Toggle "All Files Access" on, press back
6. Verify the search screen appears (search bar, reindex button, "0 shapes indexed")
7. Check logcat for crashes: `adb logcat -s ActivityManager:I *:E | head -50`

**Pass criteria:** App installs, permission flow works, search screen displays without crashes.

---

## HV-2: Deep link opens correct note in BOOX Notes (AC3.3 supplement)

**AC Coverage:** power-search-mvp.AC3.3

**Steps:**
1. Ensure at least one note is indexed (run Reindex if needed)
2. Search for a word known to be in a specific note
3. Tap a search result
4. Verify BOOX Notes opens to the correct note
5. Press back to return to Power Search
6. Repeat with a note in a subfolder (tests parentUniqueId handling)

**Pass criteria:** Tapping a result opens the correct note in BOOX Notes. Notes in subfolders open correctly.

---

## HV-3: End-to-end search accuracy on real handwriting (AC2.1 + AC3.1)

**AC Coverage:** power-search-mvp.AC2.1, AC3.1

**Steps:**
1. Create a new note with clearly written words: "meeting", "groceries", "important"
2. Return to Power Search and tap "Reindex"
3. Wait for indexing to complete
4. Search for "meeting" — verify results appear
5. Search for "groceries" — verify results appear
6. Verify recognized text in results is reasonable (may not be perfect)
7. Search for a word NOT in any note — verify "No results" message

**Pass criteria:** Known handwritten words are searchable. Recognition quality is acceptable for the handwriting style used.

---

## HV-4: Reindex performance on realistic data volume

**AC Coverage:** Non-functional performance check

**Steps:**
1. Have 50+ handwritten notes on device
2. Clear the index: uninstall and reinstall the app
3. Tap "Reindex" and time the operation
4. Note the shape count when complete
5. Tap "Reindex" again (second run — no changes expected)
6. Verify second run completes near-instantly (< 5 seconds)

**Pass criteria:**
- Initial index of 50+ notes completes in under 5 minutes
- Second reindex (no changes) completes in under 5 seconds
- Progress indicator updates during indexing

---

## HV-5: E-ink display rendering

**AC Coverage:** Non-functional display quality

**Steps:**
1. Launch app on BOOX device
2. Verify search text field is legible and responsive
3. Type a query — verify text is readable on e-ink
4. Verify result cards have sufficient contrast (title and body text)
5. Trigger reindex — verify progress indicator renders without flickering artifacts
6. Verify "No results" and "No indexed notes" empty states are readable

**Pass criteria:** All UI elements are legible on e-ink display. No animation artifacts or contrast issues.

---

## Automated Test Summary

For reference, the automated test suites covering all 20 acceptance criteria:

| Test Suite | Location | ACs |
|-----------|----------|-----|
| FleeceDecoderRealDataTest | `fleece/src/test/.../FleeceDecoderRealDataTest.kt` | AC1.1, AC1.2, AC1.3 |
| FleeceDecoderTest | `fleece/src/test/.../FleeceDecoderTest.kt` | AC1.3 |
| HandwritingShapeTypesTest | `app/src/test/.../HandwritingShapeTypesTest.kt` | AC1.4 |
| PointFileParserTest | `app/src/test/.../PointFileParserTest.kt` | AC1.5 |
| NoteMetadataRepositoryInstrumentedTest | `app/src/androidTest/.../NoteMetadataRepositoryInstrumentedTest.kt` | AC1.1, AC1.4 |
| StrokeDataRepositoryTest | `app/src/androidTest/.../StrokeDataRepositoryTest.kt` | AC1.6, AC2.1 |
| IndexRepositoryTest | `app/src/androidTest/.../IndexRepositoryTest.kt` | AC2.2, AC4.1, AC4.2, AC4.3 |
| IndexerTest | `app/src/androidTest/.../IndexerTest.kt` | AC2.1, AC2.2, AC2.3, AC4.5, AC4.6 |
| SearchScreenTest | `app/src/androidTest/.../SearchScreenTest.kt` | AC3.1, AC3.2, AC3.4, AC3.5, AC4.4 |
| DeepLinkTest | `app/src/androidTest/.../DeepLinkTest.kt` | AC3.3 |
