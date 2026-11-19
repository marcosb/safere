// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

internal interface InputScanner {
    /** Returns the length of the input in index units (chars for String, bytes for byte[]).  */
    val length: Int
    /** Returns the ASCII value at `pos`, or `-1` if the unit is not ASCII.  */
    fun asciiAt(pos: Int): Int

    /**
     * Returns the code point at `pos` when it can be read directly from one index unit, or
     * `-1` when full decoding is required.
     */
    fun singleUnitCodePointAt(pos: Int): Int

    /**
     * Returns the code point before `pos` when it can be read directly from one index unit, or
     * `-1` when full decoding is required.
     */
    fun singleUnitCodePointBefore(pos: Int): Int

    /**
     * Returns the first position in `[start, limit)` belonging to the supplied code-point
     * class.
     */
    fun indexOfCodePointClass(ranges: IntArray?, bitmap0: Long, bitmap1: Long, start: Int, limit: Int): Int

    /**
     * Returns the first index in `[fromIndex, limit)` containing `ascii`, or `-1`
     * if none exists.
     */
    fun indexOfAscii(ascii: Int, fromIndex: Int, limit: Int): Int

    /**
     * Returns the first index in `[fromIndex, limit)` containing `ascii` or a non-ASCII
     * code unit, or `-1` if neither occurs.
     */
    fun indexOfAsciiOrNonAscii(ascii: Int, fromIndex: Int, limit: Int): Int

    /**
     * Returns the first index in `[fromIndex, limit)` containing `c1` or `c2`, or
     * `-1` if none exists.
     */
    fun indexOfAsciiPair(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int

    /**
     * Returns the first index in `[fromIndex, limit)` containing `c1`, `c2`, or a
     * non-ASCII code unit, or `-1` if none occurs.
     */
    fun indexOfAsciiPairOrNonAscii(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int

    /**
     * Returns the first index in `[fromIndex, limit)` containing `c1`, `c2`, or
     * `c3`, or `-1` if none exists.
     */
    fun indexOfAsciiTriple(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int

    /**
     * Returns the first index in `[fromIndex, limit)` containing `c1`, `c2`, `c3`, or a non-ASCII code unit, or `-1` if none occurs.
     */
    fun indexOfAsciiTripleOrNonAscii(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int

    /** Decodes the scalar at `pos` and packs it with the following logical position.  */
    fun decodeForward(pos: Int): Long

    /** Decodes within `limit`, treating a split encoding at the limit as unavailable.  */
    fun decodeForward(pos: Int, limit: Int): Long {
        val decoded = decodeForward(pos)
        return if (position(decoded) <= limit) decoded else decoded(END_OF_INPUT, limit)
    }

    /** Decodes the scalar ending at `pos` and packs it with its starting logical position.  */
    fun decodeBackward(pos: Int): Long

    /** Decodes backward within `start`, treating a split encoding as unavailable.  */
    fun decodeBackward(pos: Int, start: Int): Long {
        val decoded = decodeBackward(pos)
        return if (position(decoded) >= start) decoded else decoded(END_OF_INPUT, start)
    }

    /** Returns whether `pos` is a code-point boundary in this representation.  */
    fun isCodePointBoundary(pos: Int): Boolean

    /** Moves backward by at most `count` Unicode code points from `pos`.  */
    fun retreatByCodePoints(pos: Int, count: Int): Int {
        var result = pos
        var remaining = count
        while (remaining > 0 && result > 0) {
            result = position(decodeBackward(result))
            remaining--
        }
        return result
    }

    /** Returns the code point starting at the given index.  */
    fun codePointAt(pos: Int): Int {
        return codePoint(decodeForward(pos))
    }

    /** Returns the code point ending at the given index.  */
    fun codePointBefore(pos: Int): Int {
        return codePoint(decodeBackward(pos))
    }

    /** Finds the start of the trailing line terminator sequence.  */
    fun trailingLineTerminatorStart(unixLines: Boolean, logicalEndPos: Int): Int

    /**
     * Computes the index threshold beyond which transitions contain position-dependent emptyFlags.
     */
    fun positionDependentThreshold(dollarAnchorEnd: Boolean, unixLines: Boolean): Int

    fun indexOfCharClass(scanInfo: CharClassScanInfo?, start: Int): Int {
        return -1
    }

    companion object {
        fun decoded(codePoint: Int, position: Int): Long {
            return (codePoint.toLong() shl 32) or (position.toLong() and 0xFFFFFFFFL)
        }

        fun codePoint(decoded: Long): Int {
            return (decoded shr 32).toInt()
        }

        fun position(decoded: Long): Int {
            return decoded.toInt()
        }

        fun classContains(ranges: IntArray, bitmap0: Long, bitmap1: Long, codePoint: Int): Boolean {
            if (codePoint < 64) {
                return (bitmap0 and (1L shl codePoint)) != 0L
            }
            if (codePoint < 128) {
                return (bitmap1 and (1L shl (codePoint - 64))) != 0L
            }
            var low = 0
            var high: Int = ranges.size / 2 - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val rangeLow = ranges[middle * 2]
                val rangeHigh = ranges[middle * 2 + 1]
                if (codePoint < rangeLow) {
                    high = middle - 1
                } else if (codePoint > rangeHigh) {
                    low = middle + 1
                } else {
                    return true
                }
            }
            return false
        }

        val END_OF_INPUT: Int = -1
    }
}
