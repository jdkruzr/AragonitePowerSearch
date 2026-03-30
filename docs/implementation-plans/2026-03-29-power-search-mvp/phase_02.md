# Power Search MVP Implementation Plan — Phase 2: Fleece Decoder

**Goal:** A read-only Kotlin decoder for the Fleece binary format that extracts structured data from Couchbase Lite BLOB columns.

**Architecture:** Pure Kotlin library in the `:fleece` module with zero Android dependencies. Operates on raw `ByteArray` input. Decodes dicts, arrays, strings, integers, booleans. No encoding, no mutation.

**Tech Stack:** Kotlin (JVM), JUnit for testing

**Scope:** Phase 2 of 6 from original design

**Codebase verified:** 2026-03-29. The `fleece/` module will exist after Phase 1 execution as a pure Kotlin library (`org.jetbrains.kotlin.jvm` plugin). No Fleece code exists yet. Format spec verified from https://github.com/couchbase/fleece @ 12b3725a.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### power-search-mvp.AC1: App reads note data from .ksync filesystem
- **power-search-mvp.AC1.1 Success:** Fleece decoder extracts title and parentUniqueId from NOTE_TREE BLOB
- **power-search-mvp.AC1.2 Success:** Fleece decoder extracts shapeType, uniqueId, revisionId from per-note BLOB
- **power-search-mvp.AC1.3 Failure:** Fleece decoder returns error/empty for malformed or empty BLOBs without crashing

---

## Fleece Format Reference

The Fleece binary format (Apache 2.0, Couchbase) encodes structured data as a sequence of 2-byte-aligned values. Key details for the decoder:

**Value header:** Every value starts with 2 bytes. High 4 bits of byte 0 = type tag:
- `0x0` = small integer (12-bit signed, inline)
- `0x1` = long integer (variable-length, little-endian data follows)
- `0x3` = special (null=0, false=4, true=8 in low nibble)
- `0x4` = string (length in low nibble; if 15, varint follows; then UTF-8 bytes)
- `0x6` = array (bit 3 = wide flag, 11-bit count, items follow)
- `0x7` = dict (same as array, count = pairs, items are key0,val0,key1,val1...)
- `0x8`-`0xF` = pointer (high bit set; 14-bit backward offset in 2-byte units)

**Pointers:** If high bit of byte 0 is set, it's a pointer. Narrow (2 bytes): offset = `(be_u16 & 0x3FFF) * 2` bytes backward. Wide (4 bytes): offset = `(be_u32 & 0x3FFFFFFF) * 2` bytes backward.

**Root:** Last 2 bytes of the data. Dereference if pointer (may chain through one narrow then one wide pointer).

**Strings:** Tag 4. Length = low 4 bits of byte 0. If 15, varint follows. UTF-8 data follows inline.

**Dicts:** Tag 7. Keys and values interleaved: [key0, val0, key1, val1, ...]. Keys are sorted. Each slot is 2 bytes (narrow) or 4 bytes (wide, if bit 3 of header byte 0 is set).

---

<!-- START_SUBCOMPONENT_A (tasks 1-4) -->

<!-- START_TASK_1 -->
### Task 1: FleeceValue core types and pointer dereferencing

**Files:**
- Create: `fleece/src/main/java/dev/aragonite/fleece/FleeceValue.kt`

**Implementation:**

Create a `FleeceValue` class that wraps a `ByteArray` + offset position and can identify its type and dereference pointers. This is the foundation everything else builds on.

The class needs:
- Constructor taking `data: ByteArray` and `offset: Int`
- `tag` property: extracts high 4 bits of `data[offset]` (the type tag)
- `isPointer` property: checks if high bit of `data[offset]` is set
- `deref(wide: Boolean)` method: if this is a pointer, compute backward offset and return new `FleeceValue` at the target position. Narrow pointer: `(bigEndianU16(offset) and 0x3FFF) * 2` bytes backward. Wide pointer: `(bigEndianU32(offset) and 0x3FFFFFFF) * 2` bytes backward. Chain through pointers recursively until a non-pointer value is found.
- `asInt()`: decode small int (tag 0, 12-bit signed inline) and long int (tag 1, variable-length LE)
- `asString()`: decode string (tag 4) — length from low nibble, varint if 15, then UTF-8 bytes
- `asBool()`: decode special (tag 3) — false if low nibble is 4, true if 8
- Type constants: `TAG_SHORT_INT = 0`, `TAG_INT = 1`, `TAG_SPECIAL = 3`, `TAG_STRING = 4`, `TAG_ARRAY = 6`, `TAG_DICT = 7`

Key byte-order details:
- 2-byte value headers are big-endian (for bitfield extraction)
- Inline integer data (long int tag 1) is little-endian
- Pointer offsets are big-endian

**Verification:**

Run: `./gradlew :fleece:test`
Expected: No tests yet, but module compiles

**Commit:** `feat(fleece): add FleeceValue core types and pointer dereferencing`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: FleeceDict and FleeceArray collection traversal

**Files:**
- Create: `fleece/src/main/java/dev/aragonite/fleece/FleeceDict.kt`
- Create: `fleece/src/main/java/dev/aragonite/fleece/FleeceArray.kt`

**Implementation:**

`FleeceDict` wraps a `FleeceValue` with tag 7 (dict) and provides key-value lookup.

Dict structure in memory: 2-byte header, then interleaved slots [key0, val0, key1, val1, ...]. Each slot is 2 bytes (narrow) or 4 bytes (wide). The wide flag is bit 3 of header byte 0. Count (number of pairs) is the lower 11 bits of the 2-byte header (masking out the tag and wide bit).

`FleeceDict` needs:
- `count` property: number of key-value pairs
- `isWide` property: whether slots are 4 bytes (bit 3 of header byte 0)
- `slotWidth` property: 4 if wide, 2 if narrow
- `get(key: String): FleeceValue?` — linear scan through keys (binary search is an optimization for later; the BLOBs we decode have <20 keys). Include a `// TODO: binary search for large dicts` comment in the implementation. For each key slot: resolve pointer if needed, check if it's a string matching `key`, if so return the corresponding value slot. Key slots are at `headerOffset + 2 + i * 2 * slotWidth`, value slots are at `headerOffset + 2 + i * 2 * slotWidth + slotWidth`.
- `getString(key: String): String?` — convenience: `get(key)?.resolveAndAsString(isWide)`
- `getInt(key: String): Int?` — convenience: `get(key)?.resolveAndAsInt(isWide)`

The "resolve" step means: if the value slot is itself a pointer, dereference it (using the dict's wide flag) before reading the scalar value.

`FleeceArray` wraps a `FleeceValue` with tag 6 (array) and provides indexed access:
- `count` property: number of items (lower 11 bits of header, same layout as dict)
- `isWide` property: bit 3
- `get(index: Int): FleeceValue?` — item at `headerOffset + 2 + index * slotWidth`, resolve pointer if needed

**Verification:**

Run: `./gradlew :fleece:test`
Expected: Compiles

**Commit:** `feat(fleece): add FleeceDict and FleeceArray collection traversal`
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: FleeceDecoder entry point (root finding)

**Files:**
- Create: `fleece/src/main/java/dev/aragonite/fleece/FleeceDecoder.kt`

**Implementation:**

`FleeceDecoder` is the public entry point. It takes a raw `ByteArray` (the `body` column from `kv_default` table) and returns the root value.

Root finding algorithm:
1. If data is empty or less than 2 bytes, return null
2. Read last 2 bytes as a `FleeceValue`
3. If it's a pointer, dereference it (narrow)
4. If the dereferenced value is itself a pointer (wide), dereference again
5. Return the resolved root value

Public API:
```kotlin
object FleeceDecoder {
    fun decode(data: ByteArray): FleeceValue?
    fun decodeAsDict(data: ByteArray): FleeceDict?
}
```

`decodeAsDict` is the primary API for this project — all Couchbase BLOBs we need to read are top-level dicts.

Edge cases:
- Empty `ByteArray` → return null
- `ByteArray` with only 1 byte → return null
- Data where root is not a dict → `decodeAsDict` returns null, `decode` returns the value

**Verification:**

Run: `./gradlew :fleece:test`
Expected: Compiles

**Commit:** `feat(fleece): add FleeceDecoder entry point with root finding`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Fleece decoder tests with real BLOB fixtures

**Verifies:** power-search-mvp.AC1.1, power-search-mvp.AC1.2, power-search-mvp.AC1.3

**Files:**
- Create: `fleece/src/test/resources/` (directory for test fixtures)
- Create: `fleece/src/test/java/dev/aragonite/fleece/FleeceDecoderTest.kt`

**Prerequisite: Capture test fixtures from device**

Before writing tests, pull real BLOB data from a BOOX device. Run via adb:

```bash
# Pull a NOTE_TREE BLOB (contains title, parentUniqueId, type)
adb shell sqlite3 /sdcard/.ksync/couch/*-NOTE_TREE.cblite2/db.sqlite3 \
    "SELECT hex(body) FROM kv_default LIMIT 1;" > /tmp/note_tree_blob.hex

# Pull a per-note BLOB (contains shapeType, uniqueId, revisionId)
# First find a note database:
adb shell ls /sdcard/.ksync/couch/ | grep -v NOTE_TREE | head -1
# Then pull a BLOB from it:
adb shell sqlite3 /sdcard/.ksync/couch/<userId>-<noteId>.cblite2/db.sqlite3 \
    "SELECT hex(body) FROM kv_default LIMIT 1;" > /tmp/per_note_blob.hex
```

Convert hex dumps to binary test fixtures at `fleece/src/test/resources/`:
- `note_tree_sample.bin` — a NOTE_TREE BLOB containing title, parentUniqueId, type
- `per_note_sample.bin` — a per-note BLOB containing shapeType, uniqueId, revisionId

If device access is not available during automated execution, construct synthetic Fleece fixtures by hand using the format spec. A minimal dict with string and int values can be built as a byte array literal in the test.

**Testing:**

Tests must verify each AC listed above:
- **power-search-mvp.AC1.1:** Decode a NOTE_TREE BLOB and assert `title` is a non-empty string, `parentUniqueId` is a string, `type` is an integer
- **power-search-mvp.AC1.2:** Decode a per-note BLOB and assert `shapeType` is an integer, `uniqueId` is a non-empty string, `revisionId` is a non-empty string
- **power-search-mvp.AC1.3:** Decode an empty `ByteArray` → returns null. Decode a 1-byte array → returns null. Decode random garbage bytes → returns null or a value (no exception thrown). Decode a truncated BLOB (first 4 bytes of a valid BLOB) → returns null or partial value (no exception thrown).

Test structure:
- Load fixture binary from resources via `javaClass.getResourceAsStream()`
- Call `FleeceDecoder.decodeAsDict(data)`
- Assert fields extracted match expected values
- Error cases: wrap in assertDoesNotThrow / catch and assert no crash

If synthetic fixtures are needed (no device), construct them as `byteArrayOf(...)` literals encoding known Fleece structures per the spec.

**Verification:**

Run: `./gradlew :fleece:test`
Expected: All tests pass

**Commit:** `test(fleece): add decoder tests with real BLOB fixtures`
<!-- END_TASK_4 -->

<!-- END_SUBCOMPONENT_A -->
