// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.jvm.JvmOverloads
import kotlin.math.max
import kotlin.math.min

/**
 * A resizable array of primitive `int` values to avoid boxing and object allocation overhead.
 */
internal class IntArrayList @JvmOverloads constructor(capacity: Int = 16) {
    private lateinit var data: IntArray
    var size = 0

    fun add(value: Int) {
        if (size == data.size) {
            data = (data).copyOf(data.size * 2)
        }
        data[size++] = value
    }

    fun size(): Int {
        return size
    }

    fun get(index: Int): Int {
        return data[index]
    }

    fun clear() {
        size = 0
    }

    fun isEmpty(): Boolean = size == 0

    fun removeLast(): Int {
        return data[--size]
    }

    fun toArray(): IntArray {
        return (data).copyOf(size)
    }

    /**
     * Returns the distinct values in ascending order.
     * 
     * 
     * Callers often add values as a concatenation of a few long ascending runs (for example, the
     * range boundaries of each character class in a program). Those are merged pairwise in linear
     * passes, which is much cheaper than a general sort; ranged `IntArray.sort` only looks for runs
     * in arrays with thousands of elements. Small inputs and inputs with many short runs are sorted
     * directly.
     */
    fun toSortedUniqueArray(): IntArray {
        if (size == 0) {
            return IntArray(0)
        }
        var a = data
        var runs = 1
        for (i in 1..<size) {
            if (a[i] < a[i - 1]) {
                runs++
            }
        }
        if (runs > 1) {
            if (size < MIN_MERGE_SIZE || runs > MAX_MERGED_RUNS) {
                a.sort(0, size)
            } else {
                a = mergeRuns(a, size, runs)
            }
        }
        var unique = 1
        for (i in 1..<size) {
            if (a[i] != a[unique - 1]) {
                a[unique++] = a[i]
            }
        }
        return (a).copyOf(unique)
    }

    init {
        data = IntArray(max(4, capacity))
    }

    companion object {
        /** Below this size, a general sort is as cheap as merging and allocates nothing.  */
        private const val MIN_MERGE_SIZE = 256

        /** Above this many runs, a general sort is cheaper than repeated merge passes.  */
        private const val MAX_MERGED_RUNS = 64

        /** Merges adjacent runs pairwise until one remains, and returns the array holding the result.  */
        private fun mergeRuns(a: IntArray, size: Int, runs: Int): IntArray {
            var a = a
            var runs = runs
            var b = IntArray(size)
            val starts = IntArray(runs + 1)
            var run = 1
            for (i in 1..<size) {
                if (a[i] < a[i - 1]) {
                    starts[run++] = i
                }
            }
            starts[runs] = size
            while (runs > 1) {
                var out = 0
                var merged = 0
                var r = 0
                while (r < runs) {
                    val lo = starts[r]
                    val mid = starts[min(r + 1, runs)]
                    val hi = starts[min(r + 2, runs)]
                    starts[merged++] = out
                    var i = lo
                    var j = mid
                    while (i < mid && j < hi) {
                        b[out++] = if (a[i] <= a[j]) a[i++] else a[j++]
                    }
                    while (i < mid) {
                        b[out++] = a[i++]
                    }
                    while (j < hi) {
                        b[out++] = a[j++]
                    }
                    r += 2
                }
                starts[merged] = size
                runs = merged
                val t = a
                a = b
                b = t
            }
            return a
        }
    }
}
