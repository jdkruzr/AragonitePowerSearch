package dev.aragonite.fleece

// pattern: Functional Core

/**
 * Wraps a ByteArray at a specific offset and provides access to the Fleece-encoded value there.
 *
 * Fleece values begin with a 2-byte header. The high 4 bits of byte 0 are the type tag.
 * If the high bit of byte 0 is set, the value is a pointer.
 */
class FleeceValue(val data: ByteArray, val offset: Int) {

    companion object {
        const val TAG_SHORT_INT = 0x0
        const val TAG_INT = 0x1
        const val TAG_SPECIAL = 0x3
        const val TAG_STRING = 0x4
        const val TAG_ARRAY = 0x6
        const val TAG_DICT = 0x7
    }

    /**
     * Extracts the type tag from the high 4 bits of byte 0.
     */
    val tag: Int
        get() = (data[offset].toInt() and 0xF0) shr 4

    /**
     * Checks if the high bit of byte 0 is set (indicates a pointer).
     */
    val isPointer: Boolean
        get() = (data[offset].toInt() and 0x80) != 0

    /**
     * Dereference a pointer value. Handles both narrow (2-byte) and wide (4-byte) pointers.
     * Narrow pointer: 14-bit offset in big-endian 2 bytes.
     * Wide pointer: 30-bit offset in big-endian 4 bytes.
     * Single-step dereference only (does not recurse). Caller handles pointer chains.
     * Returns null if pointer is malformed (out of bounds).
     */
    fun deref(wide: Boolean): FleeceValue? {
        if (!isPointer) return this

        // Bounds check: ensure we can read the pointer header
        val pointerSize = if (wide) 4 else 2
        if (offset + pointerSize > data.size) return null

        val offsetInTwoByteUnits = if (wide) {
            // Wide pointer: 4 bytes, offset in low 30 bits
            val be = (data[offset].toInt() and 0xFF) shl 24 or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            (be and 0x3FFFFFFF)
        } else {
            // Narrow pointer: 2 bytes, offset in low 14 bits
            val be = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            (be and 0x3FFF)
        }

        val byteOffset = offsetInTwoByteUnits * 2
        val targetOffset = offset - byteOffset

        // Bounds check: ensure target offset is valid (within 2-byte header at minimum)
        if (targetOffset < 0 || targetOffset + 1 >= data.size) return null

        return FleeceValue(data, targetOffset)
    }

    /**
     * Decode as an integer (tag 0 for small int, tag 1 for long int).
     * Returns null if bounds checking fails (malformed value).
     */
    fun asInt(): Int? {
        // Bounds check: at least 2 bytes for header
        if (offset + 1 >= data.size) return null

        return when (tag) {
            TAG_SHORT_INT -> {
                // 12-bit signed value in low 12 bits of 2-byte header
                val value = ((data[offset].toInt() and 0x0F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                // Sign-extend from 12 bits
                if ((value and 0x800) != 0) {
                    value or 0xFFFFF000.toInt()
                } else {
                    value
                }
            }
            TAG_INT -> {
                // Long int: tag 1, next byte encodes length, followed by LE bytes
                if (offset + 2 > data.size) return null
                val lengthByte = data[offset + 1].toInt() and 0xFF
                val length = lengthByte and 0x0F  // Low 4 bits = length
                // Bounds check: ensure all length bytes are available
                if (offset + 2 + length > data.size) return null
                var result = 0
                for (i in 0 until length) {
                    val b = data[offset + 2 + i].toInt() and 0xFF
                    result = result or (b shl (i * 8))
                }
                result
            }
            else -> null
        }
    }

    /**
     * Decode as a boolean (tag 3, special values: 4 = false, 8 = true).
     * Returns null if bounds checking fails or value is not a valid boolean.
     */
    fun asBool(): Boolean? {
        // Bounds check: at least 2 bytes for header
        if (offset + 1 >= data.size) return null
        if (tag != TAG_SPECIAL) return null
        val specialValue = data[offset + 1].toInt() and 0xFF
        return when (specialValue) {
            4 -> false
            8 -> true
            else -> null  // Invalid special value
        }
    }

    /**
     * Decode as a string (tag 4).
     * Length is in low 4 bits of byte 0. If 15, a varint follows, then UTF-8 bytes.
     * Returns null if bounds checking fails (malformed value).
     */
    fun asString(): String? {
        // Bounds check: at least 2 bytes for header
        if (offset + 1 >= data.size) return null
        if (tag != TAG_STRING) return null

        var byteOffset = offset + 1
        val lengthNibble = data[offset].toInt() and 0x0F

        val length = if (lengthNibble == 15) {
            // Varint-encoded length follows
            var varintValue = 0
            var shift = 0
            while (true) {
                if (byteOffset >= data.size) return null  // Bounds check during varint decode
                val b = data[byteOffset].toInt() and 0xFF
                byteOffset++
                varintValue = varintValue or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            varintValue
        } else {
            lengthNibble
        }

        // Bounds check: ensure all string bytes are available
        if (byteOffset + length > data.size) return null

        return data.decodeToString(byteOffset, byteOffset + length)
    }
}
