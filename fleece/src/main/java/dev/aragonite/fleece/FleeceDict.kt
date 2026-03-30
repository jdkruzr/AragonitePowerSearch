package dev.aragonite.fleece

// pattern: Functional Core

/**
 * Wraps a FleeceValue with tag 7 (dict) and provides key-value lookup.
 *
 * Dict structure in memory: 2-byte header, then interleaved key-value slots.
 * Each slot is 2 bytes (narrow) or 4 bytes (wide). The wide flag is bit 3 of header byte 0.
 * Count (number of pairs) is in the lower 11 bits of the 2-byte header.
 */
class FleeceDict(val value: FleeceValue) {

    /**
     * Number of key-value pairs in the dict.
     */
    val count: Int
        get() {
            val header = (value.data[value.offset].toInt() and 0xFF) shl 8 or
                    (value.data[value.offset + 1].toInt() and 0xFF)
            // Mask off the tag (high 4 bits) and wide bit to get count from lower 11 bits
            return header and 0x07FF
        }

    /**
     * Whether slots are 4 bytes (true) or 2 bytes (false). Determined by bit 3 of header byte 0.
     */
    val isWide: Boolean
        get() = (value.data[value.offset].toInt() and 0x08) != 0

    /**
     * Width of each slot in bytes. 4 if wide, 2 if narrow.
     */
    val slotWidth: Int
        get() = if (isWide) 4 else 2

    /**
     * Get the raw FleeceValue for a key, or null if not found.
     *
     * Linear scan through keys (binary search is an optimization for later).
     * // TODO: binary search for large dicts
     *
     * For each key slot: resolve pointer if needed, check if it's a string matching key,
     * if so return the corresponding value slot. Key slots are at headerOffset + 2 + i * 2 * slotWidth,
     * value slots are at headerOffset + 2 + i * 2 * slotWidth + slotWidth.
     */
    fun get(key: String): FleeceValue? {
        val headerOffset = value.offset
        for (i in 0 until count) {
            val keySlotOffset = headerOffset + 2 + i * 2 * slotWidth
            val valueSlotOffset = keySlotOffset + slotWidth

            // Check bounds
            if (keySlotOffset + slotWidth > value.data.size ||
                valueSlotOffset + slotWidth > value.data.size) {
                return null
            }

            val keySlotValue = FleeceValue(value.data, keySlotOffset)
            val resolvedKey = if (keySlotValue.isPointer) {
                keySlotValue.deref(wide = isWide) ?: continue
            } else {
                keySlotValue
            }

            // Check if the key matches
            if (resolvedKey.asString() == key) {
                val valueSlotValue = FleeceValue(value.data, valueSlotOffset)
                return valueSlotValue
            }
        }
        return null
    }

    /**
     * Convenience method: get a string value by key.
     *
     * Resolves pointer if needed before reading the scalar value.
     * Returns null if key not found, pointer is malformed, or value is not a string.
     */
    fun getString(key: String): String? {
        val value = get(key) ?: return null
        val resolved = if (value.isPointer) value.deref(wide = isWide) ?: return null else value
        return resolved.asString()
    }

    /**
     * Convenience method: get an integer value by key.
     *
     * Resolves pointer if needed before reading the scalar value.
     * Returns null if key not found, pointer is malformed, or value is not an integer.
     */
    fun getInt(key: String): Int? {
        val value = get(key) ?: return null
        val resolved = if (value.isPointer) value.deref(wide = isWide) ?: return null else value
        return resolved.asInt()
    }
}
