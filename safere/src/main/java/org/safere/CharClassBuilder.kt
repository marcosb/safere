// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import kotlin.math.*
/**
 * A mutable builder for [CharClass]. Maintains a set of Unicode code point ranges and
 * supports incremental construction via [.addRange], [.addCharClass], and [ ][.negate].
 * 
 * 
 * Ranges are stored in an array, each packed into a `long` as `lo << 32 | hi`. While
 * ranges arrive in ascending order, which is the common case when copying a class or a Unicode
 * table, each one is appended or merged into the last range directly. An out-of-order range is
 * appended unsorted, and the array is sorted and merged once, the next time an operation needs the
 * normalized form. This keeps construction O(n log n) for any insertion order.
 * 
 * 
 * Once construction is complete, call [.build] to produce an immutable [ ].
 */
internal class CharClassBuilder {
    private var ranges = LongArray(8)
    private var size = 0

    /**
     * Whether [.ranges] is sorted by `lo` with no overlapping or adjacent ranges. When
     * false, [.nrunes] is stale until [.normalize] runs.
     */
    private var normalized = true

    private var nrunes = 0

    /** Creates an empty builder.  */
    constructor()

    /** Creates a builder initialized with the contents of the given CharClass.  */
    constructor(cc: CharClass) {
        addCharClass(cc)
    }

    private fun append(lo: Int, hi: Int) {
        if (size == ranges.size) {
            ranges = (ranges).copyOf(size * 2)
        }
        ranges[size++] = pack(lo, hi)
    }

    /** Sorts the ranges and merges overlapping or adjacent ones.  */
    private fun normalize() {
        if (normalized) {
            return
        }
        // Code points are non-negative, so sorting the packed values sorts by lo, then hi.
        Arrays.sort(ranges, 0, size)
        var out = 0
        var count = 0
        var i = 0
        while (i < size) {
            val lo: Int = lo(ranges[i])
            var hi: Int = hi(ranges[i])
            i++
            while (i < size && lo(ranges[i]) <= hi.toLong() + 1) {
                hi = max(hi, hi(ranges[i]))
                i++
            }
            ranges[out++] = pack(lo, hi)
            count += hi - lo + 1
        }
        size = out
        nrunes = count
        normalized = true
    }

    /**
     * Adds all code points in the inclusive range `[lo, hi]` to this builder.
     * 
     * @return this builder, for chaining
     */
    fun addRange(lo: Int, hi: Int): CharClassBuilder {
        if (lo > hi) {
            return this
        }
        if (normalized && size > 0) {
            val last = ranges[size - 1]
            val lastLo: Int = lo(last)
            val lastHi: Int = hi(last)
            if (lo > lastHi.toLong() + 1) {
                append(lo, hi)
                nrunes += hi - lo + 1
            } else if (lo >= lastLo) {
                if (hi > lastHi) {
                    ranges[size - 1] = pack(lastLo, hi)
                    nrunes += hi - lastHi
                }
            } else {
                append(lo, hi)
                normalized = false
            }
            return this
        }
        if (size == 0) {
            append(lo, hi)
            nrunes = hi - lo + 1
            normalized = true
            return this
        }
        append(lo, hi)
        return this
    }

    /**
     * Adds a single code point to this builder.
     * 
     * @return this builder, for chaining
     */
    fun addRune(r: Int): CharClassBuilder {
        return addRange(r, r)
    }

    /**
     * Adds all ranges from the given CharClass to this builder.
     * 
     * @return this builder, for chaining
     */
    fun addCharClass(cc: CharClass): CharClassBuilder {
        for (i in 0..<cc.numRanges()) {
            addRange(cc.lo(i), cc.hi(i))
        }
        return this
    }

    /**
     * Adds all ranges from the given builder to this builder.
     * 
     * @return this builder, for chaining
     */
    fun addCharClass(other: CharClassBuilder): CharClassBuilder {
        other.normalize()
        for (i in 0..<other.size) {
            addRange(lo(other.ranges[i]), hi(other.ranges[i]))
        }
        return this
    }

    /**
     * Adds all code points described by the given range table. Each row is `{lo, hi}`.
     * 
     * @return this builder, for chaining
     */
    fun addTable(table: Array<IntArray>): CharClassBuilder {
        for (row in table) {
            addRange(row[0], row[1])
        }
        return this
    }

    /**
     * Negates this character class in place, so it matches all Java string code points not previously
     * matched, and vice versa.
     * 
     * @return this builder, for chaining
     */
    fun negate(): CharClassBuilder {
        normalize()
        val negated = LongArray(size + 1)
        var out = 0
        var count = 0
        var next = 0
        for (i in 0..<size) {
            val lo: Int = lo(ranges[i])
            if (next < lo) {
                negated[out++] = pack(next, lo - 1)
                count += lo - next
            }
            next = hi(ranges[i]) + 1
        }
        if (next <= Utils.MAX_RUNE) {
            negated[out++] = pack(next, Utils.MAX_RUNE)
            count += Utils.MAX_RUNE - next + 1
        }
        ranges = if (negated.size >= 8) negated else (negated).copyOf(8)
        size = out
        nrunes = count
        return this
    }

    /**
     * Removes all code points in the inclusive range `[lo, hi]` from this builder.
     * 
     * @return this builder, for chaining
     */
    fun removeRange(lo: Int, hi: Int): CharClassBuilder {
        if (lo > hi) {
            return this
        }
        normalize()
        // Removing a range from the middle of an existing range can split it in two.
        val result = LongArray(size + 1)
        var out = 0
        var count = 0
        for (i in 0..<size) {
            val rlo: Int = lo(ranges[i])
            val rhi: Int = hi(ranges[i])
            if (rhi < lo || rlo > hi) {
                result[out++] = ranges[i]
                count += rhi - rlo + 1
                continue
            }
            if (rlo < lo) {
                result[out++] = pack(rlo, lo - 1)
                count += lo - rlo
            }
            if (rhi > hi) {
                result[out++] = pack(hi + 1, rhi)
                count += rhi - hi
            }
        }
        ranges = if (result.size >= 8) result else (result).copyOf(8)
        size = out
        nrunes = count
        return this
    }

    /** Returns true if this builder contains the given code point.  */
    fun contains(r: Int): Boolean {
        normalize()
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val range = ranges[mid]
            if (r < lo(range)) {
                high = mid - 1
            } else if (r > hi(range)) {
                low = mid + 1
            } else {
                return true
            }
        }
        return false
    }

    /** Returns the total number of code points in this builder.  */
    fun numRunes(): Int {
        normalize()
        return nrunes
    }

    fun isEmpty(): Boolean = size == 0

    /**
     * Intersects this builder with another, keeping only code points present in both. Replaces the
     * contents of this builder with the intersection.
     * 
     * @return this builder, for chaining
     */
    fun intersect(other: CharClassBuilder): CharClassBuilder {
        normalize()
        other.normalize()
        val result = LongArray(max(8, size + other.size))
        var out = 0
        var count = 0
        var a = 0
        var b = 0
        while (a < size && b < other.size) {
            val ra = ranges[a]
            val rb = other.ranges[b]
            val lo: Int = max(lo(ra), lo(rb))
            val hi: Int = min(hi(ra), hi(rb))
            if (lo <= hi) {
                result[out++] = pack(lo, hi)
                count += hi - lo + 1
            }
            if (hi(ra) < hi(rb)) {
                a++
            } else {
                b++
            }
        }
        ranges = result
        size = out
        nrunes = count
        return this
    }

    /** Builds an immutable [CharClass] from the current state of this builder.  */
    fun build(): CharClass {
        normalize()
        val flat = IntArray(size * 2)
        for (i in 0..<size) {
            flat[2 * i] = lo(ranges[i])
            flat[2 * i + 1] = hi(ranges[i])
        }
        return CharClass(flat, nrunes)
    }

    companion object {
        private fun pack(lo: Int, hi: Int): Long {
            return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
        }

        private fun lo(range: Long): Int {
            return (range ushr 32).toInt()
        }

        private fun hi(range: Long): Int {
            return range.toInt()
        }
    }
}
