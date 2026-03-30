package dev.aragonite.fleece

// pattern: Functional Core

/**
 * Wraps a FleeceValue with tag 6 (array) and provides indexed access.
 *
 * Array structure in memory: 2-byte header, then item slots.
 * Each slot is 2 bytes (narrow) or 4 bytes (wide). The wide flag is bit 3 of header byte 0.
 * Count is in the lower 11 bits of the 2-byte header.
 */
class FleeceArray(val value: FleeceValue) {

    /**
     * Number of items in the array.
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
     * Get the raw FleeceValue at the given index, or null if out of bounds.
     *
     * Item at index i is at headerOffset + 2 + i * slotWidth.
     * Resolves pointer if needed.
     */
    fun get(index: Int): FleeceValue? {
        if (index < 0 || index >= count) return null

        val headerOffset = value.offset
        val itemOffset = headerOffset + 2 + index * slotWidth

        // Check bounds
        if (itemOffset + slotWidth > value.data.size) {
            return null
        }

        val itemValue = FleeceValue(value.data, itemOffset)
        return itemValue
    }
}
