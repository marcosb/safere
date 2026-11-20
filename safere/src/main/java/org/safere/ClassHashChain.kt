// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.min

/**
 * Precomputed 2-gram shift table (Hash-Chain scanner) for case-insensitive literal and prefix
 * acceleration on [CharSequence] and [String] inputs.
 * 
 * <h2>Algorithm & Design</h2>
 * 
 * 
 * Based on the 2-gram Hash-Chain exact string matching technique (Palmer, SEA 2024), this class
 * constructs a direct-mapped 1024-entry shift table (`byte[1024]`, 1 KB) mapping adjacent
 * 16-bit code unit pairs `(c0, c1)` to candidate shifts.
 * 
 * 
 * For a pattern literal of length `M`, each adjacent pair in the case-folded character
 * sequence is mapped to a shift of `M - 2 - i`. The terminal pair at `[M - 2, M - 1]`
 * is assigned a shift of `0`. Non-matching pairs shift forward by up to `M - 1`
 * characters. Full Unicode case folding (including Cyrillic, Greek, accented Latin, and surrogate
 * pairs) is expanded during compile time via [Inst.simpleFold].
 * 
 * <h2>Why Hash-Chain is used exclusively for [CharSequence] / [String]</h2>
 * 
 * 
 * During empirical performance evaluations across both UTF-8 (`byte[]`) and UTF-16 (`CharSequence`) input pipelines:
 * 
 * 
 *  * **UTF-8 / Byte Streams:** On `byte[]` buffers, 64-bit SWAR ([ByteSwarScan])
 * and 256-bit Vector API ([ByteVectorScan]) vectorized scanning kernels inspect 8 to 32
 * bytes per cycle, while bounded Boyer-Moore-Horspool bad-character skip loops achieve rapid
 * skips with zero memory indirection. Scalar 2-gram hash chains on raw byte streams added
 * table lookup indirection without beating vectorized SWAR/SIMD filters. Therefore, UTF-8
 * literal scanning relies directly on SWAR/Vector filters with Horspool fallbacks.
 *  * **UTF-16 / CharSequence Strings:** HotSpot C2 provides highly optimized vector
 * intrinsics for single-character anchor searches (such as [String.indexOf]).
 * However, full multi-character case-folded matching lacks dedicated SIMD intrinsics in Java.
 * In inputs with dense false-anchor matches (e.g. searching `(?i)keyword` in text
 * flooded with isolated `'k'` characters), naive anchor scanning degrades towards
 * quadratic $O(N \cdot M)$ execution.
 *  * **Hybrid Fast-Path Acceleration:** Combining fast C2 SIMD `indexOf` dead-space
 * sweeps with [.shiftAt] 2-gram terminal checks allows SafeRE to
 * verify candidates in $O(1)$ time and skip forward by up to $M - 1$ characters upon false
 * anchor hits. This completely eliminates false-anchor candidate storms and delivers
 * 4.5x–5.0x speedups on case-insensitive string searches.
 * 
 */
internal class ClassHashChain private constructor(
    private val literal: String,
    private val shifts: ByteArray,
    private val length: Int
) {
    /**
     * Returns the shift distance at the specified candidate position, or 0 if the terminal 2-gram
     * matches.
     */
    fun shiftAt(text: CharSequence, candidatePos: Int): Int {
        val inPos = candidatePos + (length - 1)
        if (inPos >= text.length) {
            return 0
        }
        val c0: Char = text[inPos - 1]
        val c1: Char = text[inPos]
        val h: Int = hash(c0, c1)
        return shifts[h].toInt() and 0xFF
    }

    /** Searches for the case-insensitive literal in `text` starting from `start`.  */
    fun search(text: CharSequence, start: Int, workLimit: Long): Int {
        val textLength: Int = text.length
        if (textLength - start < length) {
            return -1
        }
        val last = length - 1
        var position = start + last
        var work: Long = 0

        while (position < textLength) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val c0: Char = text[position - 1]
            val c1: Char = text[position]
            val h: Int = hash(c0, c1)
            val shift = shifts[h].toInt() and 0xFF

            if (shift == 0) {
                val candidate = position - last
                if (Utf16.regionMatchesUnicodeIgnoreCase(text, candidate, literal)) {
                    return candidate
                }
                work += length.toLong()
                if (WorkLimit.isExhausted(work, workLimit)) {
                    return -2
                }
                position++
            } else {
                position += shift
                work++
                if (WorkLimit.isExhausted(work, workLimit)) {
                    return -2
                }
            }
        }
        return -1
    }

    companion object {
        private const val TABLE_SIZE = 1024
        private const val TABLE_MASK = 0x3FF

        fun hash(c0: Char, c1: Char): Int {
            return ((c0.code * 31 + c1.code) xor (c0.code ushr 5)) and TABLE_MASK
        }

        /**
         * Compiles a precomputed 2-gram shift table for a case-insensitive literal string.
         * 
         * @param literal the literal string to compile (must be at least 4 code units)
         * @return a compiled [ClassHashChain], or `null` if literal is too short or has
         * unstable case folding widths
         */
        fun compileCaseInsensitive(literal: String?): ClassHashChain? {
            if (literal == null || literal.length < 4) {
                return null
            }
            val m: Int = literal.length
            val codePointVariants = ArrayList<Array<CharArray>>()
            var i = 0
            while (i < literal.length) {
                val codePoint = CodePoints.codePointAt(literal, i)
                val original = CodePoints.toChars(codePoint)
                val variants = ArrayList<CharArray>()
                var folded = codePoint
                do {
                    val foldedChars = CodePoints.toChars(folded)
                    if (foldedChars.size != original.size) {
                        return null
                    }
                    var seen = false
                    for (existing in variants) {
                        if (existing.contentEquals(foldedChars)) {
                            seen = true
                            break
                        }
                    }
                    if (!seen) {
                        variants.add(foldedChars)
                    }
                    folded = Inst.simpleFold(folded)
                } while (folded != codePoint)
                codePointVariants.add(variants.toTypedArray())
                i += original.size
            }

            val codePointIndexForChar = IntArray(m)
            val intraCharOffset = IntArray(m)
            var charIndex = 0
            for (codePointIndex in codePointVariants.indices) {
                val charCount = codePointVariants[codePointIndex][0].size
                for (charOffset in 0..<charCount) {
                    codePointIndexForChar[charIndex] = codePointIndex
                    intraCharOffset[charIndex] = charOffset
                    charIndex++
                }
            }

            val shifts = ByteArray(TABLE_SIZE)
            val defaultShift = min(127, m - 1)
            shifts.fill(defaultShift.toByte())

            for (pairIndex in 0..<m - 2) {
                val shiftVal = m - 2 - pairIndex
                val cp0 = codePointIndexForChar[pairIndex]
                val off0 = intraCharOffset[pairIndex]
                val cp1 = codePointIndexForChar[pairIndex + 1]
                val off1 = intraCharOffset[pairIndex + 1]
                if (cp0 == cp1) {
                    for (variant in codePointVariants[cp0]) {
                        val h = hash(variant[off0], variant[off1])
                        shifts[h] = min(shifts[h].toInt() and 0xFF, shiftVal).toByte()
                    }
                } else {
                    for (variant0 in codePointVariants[cp0]) {
                        for (variant1 in codePointVariants[cp1]) {
                            val h = hash(variant0[off0], variant1[off1])
                            shifts[h] = min(shifts[h].toInt() and 0xFF, shiftVal).toByte()
                        }
                    }
                }
            }

            val cpLast0 = codePointIndexForChar[m - 2]
            val offLast0 = intraCharOffset[m - 2]
            val cpLast1 = codePointIndexForChar[m - 1]
            val offLast1 = intraCharOffset[m - 1]
            if (cpLast0 == cpLast1) {
                for (variant in codePointVariants[cpLast0]) {
                    shifts[hash(variant[offLast0], variant[offLast1])] = 0
                }
            } else {
                for (variant0 in codePointVariants[cpLast0]) {
                    for (variant1 in codePointVariants[cpLast1]) {
                        shifts[hash(variant0[offLast0], variant1[offLast1])] = 0
                    }
                }
            }

            return ClassHashChain(literal, shifts, m)
        }
    }
}
