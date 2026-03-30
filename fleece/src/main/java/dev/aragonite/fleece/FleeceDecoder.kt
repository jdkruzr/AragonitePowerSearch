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
    fun decodeAsDict(data: ByteArray): FleeceDict? {
        val root = decode(data) ?: return null
        if (root.tag != FleeceValue.TAG_DICT) return null
        return FleeceDict(root)
    }
}
