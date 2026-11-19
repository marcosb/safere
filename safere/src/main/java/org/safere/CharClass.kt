// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/**
 * An immutable character class, represented as a sorted, non-overlapping list of Unicode code point
 * ranges. Each range is an inclusive `[lo, hi]` pair.
 * 
 * 
 * Internally, ranges are stored in a flat `int[]` where even indices are lo values and odd
 * indices are hi values. This is compact and cache-friendly.
 * 
 * 
 * Use [CharClassBuilder] to construct a CharClass incrementally.
 */
internal class CharClass(// Flat array: [lo0, hi0, lo1, hi1, ...]. Always sorted and non-overlapping.
    private val ranges: IntArray, private val nrunes: Int
) {
    /** Returns the number of ranges in this character class.  */
    fun numRanges(): Int {
        return ranges.size / 2
    }

    /** Returns the total number of code points matched by this character class.  */
    fun numRunes(): Int {
        return nrunes
    }

    fun isEmpty(): Boolean = ranges.size == 0

    /** Returns the lo value of the i-th range.  */
    fun lo(i: Int): Int {
        return ranges[2 * i]
    }

    /** Returns the hi value of the i-th range.  */
    fun hi(i: Int): Int {
        return ranges[2 * i + 1]
    }

    /** Returns true if this character class contains the given code point.  */
    fun contains(r: Int): Boolean {
        // Binary search over ranges.
        var lo = 0
        var hi = numRanges() - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (r < lo(mid)) {
                hi = mid - 1
            } else if (r > hi(mid)) {
                lo = mid + 1
            } else {
                return true
            }
        }
        return false
    }

    /**
     * Returns a new CharClass that is the negation of this one (matches all valid Unicode code points
     * not in this class).
     */
    fun negate(): CharClass {
        return CharClassBuilder(this).negate().build()
    }

    /**
     * Returns the underlying flat ranges array directly (no defensive copy). Callers must not modify
     * the returned array.
     */
    fun flatRanges(): IntArray {
        return ranges
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0..<numRanges()) {
            if (i > 0) {
                sb.append(' ')
            }
            val lo = lo(i)
            val hi = hi(i)
            if (lo == hi) {
                sb.append(String.format(Locale.ROOT, "0x%X", lo))
            } else {
                sb.append(String.format(Locale.ROOT, "0x%X-0x%X", lo, hi))
            }
        }
        sb.append(']')
        return sb.toString()
    }

    companion object {
        /** An empty character class that matches nothing.  */
        val EMPTY: CharClass = CharClass(IntArray(0), 0)
    }
}
