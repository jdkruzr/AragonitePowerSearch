# Fleece Module

Last verified: 2026-04-01

## Purpose

Read-only decoder for Couchbase Lite's Fleece binary encoding format. Extracts note metadata (titles, shape types, UUIDs) from `kv_default.body` BLOB columns in on-device Couchbase databases. Pure Kotlin with no Android dependencies.

## Contracts

- **Exposes**: `FleeceDecoder.decode(ByteArray) -> FleeceValue?`, `FleeceDecoder.decodeAsDict(ByteArray, sharedKeys) -> FleeceDict?`, `FleeceDecoder.parseSharedKeys(ByteArray) -> List<String>`
- **Guarantees**: Returns null on invalid/malformed data (never throws). Bounds-checked at every read.
- **Expects**: Raw BLOB bytes from Couchbase `kv_default.body` column.

## Dependencies

- **Uses**: Nothing (pure Kotlin, no external deps)
- **Used by**: `NoteMetadataRepository` in `:app` module (via `FleeceDecoder.decodeAsDict`)
- **Boundary**: Must remain pure Kotlin. No Android framework imports.

## Key Decisions

- Linear scan for dict key lookup (binary search deferred as TODO for large dicts)
- Root finding: last 2 bytes, dereference narrow then wide pointer
- Read-only: no encoding/writing support needed

## Key Files

- `FleeceDecoder.kt` -- Entry point. `decodeAsDict` is the primary API.
- `FleeceValue.kt` -- Core value type. Handles tags, pointer dereferencing, scalar decoding (int, string, bool).
- `FleeceDict.kt` -- Dict traversal with `get`, `getString`, `getInt`. Supports narrow/wide slot widths.
- `FleeceArray.kt` -- Array traversal (indexed access, iteration).

## Gotchas

- Pointer dereferencing is single-step. Caller (FleeceDecoder) handles pointer chains (narrow then wide).
- Dict `count` is in lower 11 bits of header (not 12). Wide flag is bit 3 of header byte 0.
- String length nibble of 15 triggers varint-encoded length (not literal 15).
- Shared keys: Couchbase stores frequently-used dict keys as integer indices into a shared key table. `FleeceDict` resolves `TAG_SHORT_INT` keys via the `sharedKeys` list. Parse shared keys from the `info` blob of the `kvmeta` table using `FleeceDecoder.parseSharedKeys()`.
- **Wrapper prefix**: Some devices (Palma-series) wrap Fleece BLOBs in a 10-byte dict envelope (top nibble `0x7`). Callers must strip this before passing to `FleeceDecoder`. Detection: `body[0] & 0xF0 == 0x70 && body.size > 10` then use `body[10:]`. This stripping is done in the `:app` module (`NoteMetadataRepository`, `KeyMappingScreen`), not in FleeceDecoder itself.
