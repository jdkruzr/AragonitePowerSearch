package dev.aragonite.fleece

// pattern: Functional Core

/**
 * Entry point for decoding Fleece-encoded data.
 *
 * Root finding algorithm:
 * 1. If data is empty or less than 2 bytes, return null
 * 2. Read last 2 bytes as a FleeceValue
 * 3. If it's a pointer, dereference it (narrow)
 * 4. If the dereferenced value is itself a pointer (wide), dereference again
 * 5. Return the resolved root value
 */
object FleeceDecoder {

    /**
     * Decode raw Fleece data and return the root value, or null if data is invalid.
     *
     * Root finding algorithm:
     * 1. Read last 2 bytes as a FleeceValue
     * 2. If it's a pointer, dereference it (narrow)
     * 3. If the dereferenced value is itself a pointer (wide), dereference again
     * 4. Return the resolved root value, or null if dereferencing fails (malformed pointer)
     */
    fun decode(data: ByteArray): FleeceValue? {
        if (data.size < 2) return null

        // Root is the last 2 bytes
        val rootOffset = data.size - 2
        var value: FleeceValue? = FleeceValue(data, rootOffset)

        // Dereference pointers (single-step at a time)
        if (value != null && value.isPointer) {
            value = value.deref(wide = false)  // Try narrow first
            if (value != null && value.isPointer) {
                value = value.deref(wide = true)  // Then wide if needed
            }
        }

        return value
    }

    /**
     * Decode raw Fleece data and return the root value as a dict, or null if data is invalid
     * or the root is not a dict.
     *
     * This is the primary API for this project — all Couchbase BLOBs we need to read are top-level dicts.
     */
    fun decodeAsDict(data: ByteArray, sharedKeys: List<String> = emptyList()): FleeceDict? {
        val root = decode(data) ?: return null
        if (root.tag != FleeceValue.TAG_DICT) return null
        return FleeceDict(root, sharedKeys)
    }

    /**
     * Parse the SharedKeys blob from the kv_info table.
     * The blob is a Fleece-encoded value containing an array of strings.
     * Returns the list of key name strings indexed by their shared key ID.
     */
    fun parseSharedKeys(data: ByteArray): List<String> {
        if (data.size < 2) return emptyList()
        val result = mutableListOf<String>()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            val tag = (b shr 4) and 0xF
            if (tag == FleeceValue.TAG_STRING) {
                val lengthNibble = b and 0x0F
                val stringStart: Int
                val stringLength: Int
                if (lengthNibble == 15) {
                    // Varint follows at i+1
                    var varintValue = 0
                    var shift = 0
                    var pos = i + 1
                    while (pos < data.size) {
                        val vb = data[pos].toInt() and 0xFF
                        pos++
                        varintValue = varintValue or ((vb and 0x7F) shl shift)
                        if ((vb and 0x80) == 0) break
                        shift += 7
                    }
                    stringStart = pos
                    stringLength = varintValue
                } else {
                    stringStart = i + 1
                    stringLength = lengthNibble
                }
                if (stringStart + stringLength <= data.size) {
                    result.add(data.decodeToString(stringStart, stringStart + stringLength))
                }
                i = stringStart + stringLength
                // Fleece requires 2-byte alignment
                if (i % 2 != 0) i++
            } else {
                i += 2 // skip non-string values (the trailing pointer array)
            }
        }
        return result
    }
}
