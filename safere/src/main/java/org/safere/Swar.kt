// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

/** Shared 64-bit SWAR (SIMD within a register) broadword constants and arithmetic operations.  */
internal object Swar {
    const val BYTE_ONES: Long = 0x0101010101010101L
    const val BYTE_HIGH_BITS: Long = -0x7f7f7f7f7f7f7f80L

    const val SHORT_ONES: Long = 0x0001000100010001L
    const val SHORT_HIGH_BITS: Long = -0x7fff7fff7fff8000L

    /** Returns whether the ranges can be matched one UTF-16 code unit at a time.  */
    fun supportsBmpCodeUnitRanges(ranges: IntArray, maximumRanges: Int): Boolean {
        if (ranges.size < 2 || ranges.size > maximumRanges * 2 || (ranges.size and 1) != 0) {
            return false
        }
        for (i in 0 until ranges.size step 2) {
            val low = ranges[i]
            val high = ranges[i + 1]
            if (low < 0
                || high > Character.MAX_VALUE.code
                || low > high
                || (low <= Character.MAX_SURROGATE.code && high >= Character.MIN_SURROGATE.code)
            ) {
                return false
            }
        }
        return true
    }

    /** Returns whether the ranges can be matched by an ASCII byte kernel.  */
    fun supportsAsciiRanges(ranges: IntArray, maximumRanges: Int): Boolean {
        if (ranges.size < 2 || ranges.size > maximumRanges * 2 || (ranges.size and 1) != 0) {
            return false
        }
        for (i in 0 until ranges.size step 2) {
            if (ranges[i] < 0 || ranges[i] > ranges[i + 1] || ranges[i + 1] > 0x7F) {
                return false
            }
        }
        return true
    }

    /** Computes a borrow-free 1-byte SWAR range mask for `[low, high]`.  */
    fun exactAsciiRangeMask(values: Long, ascii: Long, repeatedLow: Long, repeatedHigh: Long): Long {
        val atLeastLow = ((values or BYTE_HIGH_BITS) - repeatedLow) and BYTE_HIGH_BITS
        val atMostHigh = ((repeatedHigh or BYTE_HIGH_BITS) - values) and BYTE_HIGH_BITS
        return ascii and atLeastLow and atMostHigh
    }

    /** Computes a borrow-free 2-byte UTF-16 SWAR range mask for `[low, high]`.  */
    fun exactShortRangeMask(word: Long, repeatedLow: Long, repeatedHigh: Long): Long {
        val atLeastLow = ((word or SHORT_HIGH_BITS) - repeatedLow) and SHORT_HIGH_BITS
        val atMostHigh = ((repeatedHigh or SHORT_HIGH_BITS) - word) and SHORT_HIGH_BITS
        return atLeastLow and atMostHigh
    }
}
