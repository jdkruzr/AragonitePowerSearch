package dev.aragonite.fleece

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
     * Recursively dereferences until a non-pointer is found.
     */
    fun deref(wide: Boolean): FleeceValue {
        if (!isPointer) return this

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
        val targetValue = FleeceValue(data, targetOffset)

        // Recursively dereference if target is also a pointer
        return if (targetValue.isPointer) {
            // After narrow, try wide
            targetValue.deref(wide = !wide)
        } else {
            targetValue
        }
    }

    /**
     * Decode as an integer (tag 0 for small int, tag 1 for long int).
     */
    fun asInt(): Int {
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
                val lengthByte = data[offset + 1].toInt() and 0xFF
                val length = lengthByte and 0x0F  // Low 4 bits = length
                var result = 0
                for (i in 0 until length) {
                    val b = data[offset + 2 + i].toInt() and 0xFF
                    result = result or (b shl (i * 8))
                }
                result
            }
            else -> 0
        }
    }

    /**
     * Decode as a boolean (tag 3, special values: 4 = false, 8 = true).
     */
    fun asBool(): Boolean {
        if (tag != TAG_SPECIAL) return false
        val specialValue = data[offset + 1].toInt() and 0xFF
        return specialValue == 8
    }

    /**
     * Decode as a string (tag 4).
     * Length is in low 4 bits of byte 0. If 15, a varint follows, then UTF-8 bytes.
     */
    fun asString(): String {
        if (tag != TAG_STRING) return ""

        var byteOffset = offset + 1
        val lengthNibble = data[offset].toInt() and 0x0F

        val length = if (lengthNibble == 15) {
            // Varint-encoded length follows
            var varintValue = 0
            var shift = 0
            while (true) {
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

        return data.decodeToString(byteOffset, byteOffset + length)
    }
}
