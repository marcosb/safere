// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*

/** Utilities for regex case-folded literal and range expansion.  */
internal object UnicodeCaseFolding {
    private val CASE_FOLD_NOT_FOUND = Integer.MIN_VALUE

    fun asciiFoldRune(r: Int): Int {
        return Ascii.toLowerCase(r)
    }

    fun addAsciiFoldedRange(ccb: CharClassBuilder, lo: Int, hi: Int) {
        ccb.addRange(lo, hi)
        val upperLo = Math.max(lo, 'A'.code)
        val upperHi = Math.min(hi, 'Z'.code)
        if (upperLo <= upperHi) {
            ccb.addRange(Ascii.toLowerCase(upperLo), Ascii.toLowerCase(upperHi))
        }
        val lowerLo = Math.max(lo, 'a'.code)
        val lowerHi = Math.min(hi, 'z'.code)
        if (lowerLo <= lowerHi) {
            ccb.addRange(Ascii.toUpperCase(lowerLo), Ascii.toUpperCase(lowerHi))
        }
    }

    fun addUnicodeFoldedRange(ccb: CharClassBuilder, lo: Int, hi: Int) {
        ccb.addRange(lo, hi)
        UnicodeCaseClosureIndex.addSourcesForTargetsInRange(ccb, lo, hi)
    }

    fun hasUnicodeCaseVariant(cp: Int): Boolean {
        return Character.toUpperCase(cp) != cp || Character.toLowerCase(cp) != cp || Character.toTitleCase(cp) != cp || Character.toLowerCase(
            Character.toUpperCase(cp)
        ) != cp || Character.toUpperCase(Character.toLowerCase(cp)) != cp
    }

    fun cycleFoldRune(r: Int): Int {
        val cf = UnicodeTables.CASE_FOLD
        val idx = lookupCaseFold(r)
        if (idx < 0) {
            return r
        }
        return UnicodeCaseFolding.applyFold(cf[idx]!!, r)
    }

    private fun lookupCaseFold(r: Int): Int {
        val cf = UnicodeTables.CASE_FOLD
        var lo = 0
        var hi: Int = cf.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cf[mid]!![0] <= r && r <= cf[mid]!![1]) {
                return mid
            }
            if (r < cf[mid]!![0]) {
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return if (lo < cf.size) -(lo + 1) else CASE_FOLD_NOT_FOUND
    }

    private fun applyFold(entry: IntArray, r: Int): Int {
        var delta = entry[2]
        if (delta == UnicodeTables.EVEN_ODD_SKIP) {
            if ((r - entry[0]) % 2 != 0) return r
            delta = UnicodeTables.EVEN_ODD
        }
        if (delta == UnicodeTables.ODD_EVEN_SKIP) {
            if ((r - entry[0]) % 2 != 0) return r
            delta = UnicodeTables.ODD_EVEN
        }
        if (delta == UnicodeTables.EVEN_ODD) {
            return if (r % 2 == 0) r + 1 else r - 1
        }
        if (delta == UnicodeTables.ODD_EVEN) {
            return if (r % 2 == 1) r + 1 else r - 1
        }
        return r + delta
    }

    private object UnicodeCaseClosureIndex {
        val TARGET_TO_SOURCE: LongArray = buildTargetToSourcePairs()

        fun addSourcesForTargetsInRange(ccb: CharClassBuilder, lo: Int, hi: Int) {
            var index: Int = lowerBoundTarget(lo)
            while (index < TARGET_TO_SOURCE.size) {
                val pair: Long = TARGET_TO_SOURCE[index]
                val target: Int = target(pair)
                if (target > hi) {
                    return
                }
                ccb.addRune(source(pair))
                index++
            }
        }

        fun lowerBoundTarget(target: Int): Int {
            val key: Long = pack(target, 0)
            var lo = 0
            var hi: Int = TARGET_TO_SOURCE.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (TARGET_TO_SOURCE[mid] < key) {
                    lo = mid + 1
                } else {
                    hi = mid
                }
            }
            return lo
        }

        fun buildTargetToSourcePairs(): LongArray {
            // Gather only nontrivial single-code-point casing and simple-fold links. Most Unicode
            // code points have no links and need no entry in the component arrays.
            val links = LongArrayBuilder()
            for (cp in 0..Utils.MAX_RUNE) {
                addLink(links, cp, Character.toUpperCase(cp))
                addLink(links, cp, Character.toLowerCase(cp))
                addLink(links, cp, Character.toTitleCase(cp))
                addLink(links, cp, cycleFoldRune(cp))
            }
            val edges = links.toArray()
            val codePoints = IntArray(edges.size * 2)
            for (i in 0 until edges.size) {
                codePoints[2 * i] = target(edges[i])
                codePoints[2 * i + 1] = source(edges[i])
            }
            Arrays.sort(codePoints)
            var count = 0
            for (cp in codePoints) {
                if (count == 0 || codePoints[count - 1] != cp) {
                    codePoints[count++] = cp
                }
            }

            // Sorted code points give each participant a compact index; the least index in a
            // component also represents its least code point.
            val parent = IntArray(count)
            val next = IntArray(count)
            Arrays.fill(next, -1)
            for (i in 0 until count) {
                parent[i] = i
            }
            for (edge in edges) {
                val first = Arrays.binarySearch(codePoints, 0, count, target(edge))
                val second = Arrays.binarySearch(codePoints, 0, count, source(edge))
                union(parent, first, second)
            }
            // Each component is a linked list starting at its least code point.
            for (i in 0 until count) {
                val root = find(parent, i)
                if (root != i) {
                    next[i] = next[root]
                    next[root] = i
                }
            }
            val pairs = LongArrayBuilder()
            for (targetIndex in 0 until count) {
                val root = find(parent, targetIndex)
                if (next[root] == -1) {
                    continue
                }
                var sourceIndex = root
                while (sourceIndex != -1) {
                    if (sourceIndex != targetIndex) {
                        pairs.add(pack(codePoints[targetIndex], codePoints[sourceIndex]))
                    }
                    sourceIndex = next[sourceIndex]
                }
            }
            // Every member indexes every other member exactly once. One range lookup therefore
            // gives the complete symmetric/transitive closure, with no iterative parser expansion.
            val sorted = pairs.toArray()
            Arrays.sort(sorted)
            return sorted
        }

        fun addLink(links: LongArrayBuilder, first: Int, second: Int) {
            if (first != second) {
                links.add(pack(first, second))
            }
        }

        fun find(parent: IntArray, cp: Int): Int {
            var cp = cp
            while (parent[cp] != cp) {
                parent[cp] = parent[parent[cp]]
                cp = parent[cp]
            }
            return cp
        }

        fun union(parent: IntArray, a: Int, b: Int) {
            val first: Int = find(parent, a)
            val second: Int = find(parent, b)
            parent[Math.max(first, second)] = Math.min(first, second)
        }

        fun pack(target: Int, source: Int): Long {
            return (target.toLong() shl Integer.SIZE) or Integer.toUnsignedLong(source)
        }

        fun target(pair: Long): Int {
            return (pair ushr Integer.SIZE).toInt()
        }

        fun source(pair: Long): Int {
            return pair.toInt()
        }
    }

    private class LongArrayBuilder {
        private var values = LongArray(4096)
        private var size = 0

        fun add(value: Long) {
            if (size == values.size) {
                values = Arrays.copyOf(values, values.size * 2)
            }
            values[size++] = value
        }

        fun toArray(): LongArray {
            return Arrays.copyOf(values, size)
        }
    }
}
