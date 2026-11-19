// Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.crosscheck

import java.nio.charset.StandardCharsets
import java.util.*

/** Exact coordinate conversion at Unicode scalar boundaries.  */
internal class Utf8Coordinates private constructor(
    private val utf16ByUtf8Offset: IntArray,
    private val utf8ByUtf16Offset: IntArray
) {
    fun toUtf16(utf8Offset: Int): Int {
        check(!(utf8Offset < 0 || utf8Offset >= utf16ByUtf8Offset.size)) { "UTF-8 matcher returned out-of-range offset " + utf8Offset }
        val utf16Offset = utf16ByUtf8Offset[utf8Offset]
        check(utf16Offset >= 0) { "UTF-8 matcher returned a non-scalar-boundary offset " + utf8Offset }
        return utf16Offset
    }

    fun toUtf8(utf16Offset: Int): Int {
        require(isUtf16Boundary(utf16Offset)) { "UTF-16 offset is not a Unicode scalar boundary: " + utf16Offset }
        return utf8ByUtf16Offset[utf16Offset]
    }

    fun isUtf16Boundary(utf16Offset: Int): Boolean {
        return utf16Offset >= 0 && utf16Offset < utf8ByUtf16Offset.size && utf8ByUtf16Offset[utf16Offset] >= 0
    }

    fun describe(): String {
        val result = StringBuilder()
        for (utf16Offset in utf8ByUtf16Offset.indices) {
            val utf8Offset = utf8ByUtf16Offset[utf16Offset]
            if (utf8Offset >= 0) {
                if (!result.isEmpty()) {
                    result.append(", ")
                }
                result.append(utf16Offset).append("->").append(utf8Offset)
            }
        }
        return result.toString()
    }

    companion object {
        fun create(text: String): Utf8Coordinates? {
            val utf8 = text.toByteArray(StandardCharsets.UTF_8)
            val utf16ByUtf8Offset = IntArray(utf8.size + 1)
            val utf8ByUtf16Offset = IntArray(text.length + 1)
            Arrays.fill(utf16ByUtf8Offset, -1)
            Arrays.fill(utf8ByUtf16Offset, -1)
            var utf8Offset = 0
            var utf16Offset = 0
            while (utf16Offset < text.length) {
                val first = text.get(utf16Offset)
                if (Character.isSurrogate(first)
                    && !(Character.isHighSurrogate(first)
                            && utf16Offset + 1 < text.length && Character.isLowSurrogate(text.get(utf16Offset + 1)))
                ) {
                    return null
                }
                utf16ByUtf8Offset[utf8Offset] = utf16Offset
                utf8ByUtf16Offset[utf16Offset] = utf8Offset
                val codePoint = text.codePointAt(utf16Offset)
                utf8Offset += utf8Length(codePoint)
                utf16Offset += Character.charCount(codePoint)
            }
            utf16ByUtf8Offset[utf8Offset] = text.length
            utf8ByUtf16Offset[text.length] = utf8Offset
            check(utf8Offset == utf8.size) { "UTF-8 coordinate map length mismatch" }
            return Utf8Coordinates(utf16ByUtf8Offset, utf8ByUtf16Offset)
        }

        private fun utf8Length(codePoint: Int): Int {
            if (codePoint <= 0x7f) {
                return 1
            }
            if (codePoint <= 0x7ff) {
                return 2
            }
            if (codePoint <= 0xffff) {
                return 3
            }
            return 4
        }
    }
}
