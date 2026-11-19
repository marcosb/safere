// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.io.Serializable
import java.util.*

/**
 * Metadata for small multi-literal alternation acceleration (2  N  4) using multi-vector
 * SIMD matching on distinct initial prefix characters.
 * 
 * 
 * All literals anchor on their first character (offset 0) to strictly preserve leftmost-first
 * matching semantics during left-to-right vector scanning.
 * 
 * 
 * The upper bound of 4 literals and requirement for distinct prefix characters is chosen based
 * on empirical vector performance and hardware constraints:
 * 
 * 
 *  * **Register allocation &amp; unrolling:** AVX2 provides 16 YMM vector registers. 2–4
 * literals require 2–4 broadcast registers plus input and comparison masks (6 registers
 * total), leaving 10+ free YMM registers for HotSpot C2 to unroll the vector loop across
 * 64–128 byte strides without stack spilling.
 *  * **Candidate false-positive density:** For 2–4 literals with distinct prefixes, the
 * probability of false-positive candidate hits remains low (&lt; 5% in natural text),
 * allowing the scanner to stay on the fast SIMD path &gt; 95% of the time. When literals
 * share common prefixes or exceed 4 keywords, dedicated multi-pattern algorithms (such as
 * Teddy with 4-bit nibble bucket hashing) are used instead.
 * 
 */
// The arrays are immutable, privately owned scanner metadata; array identity is never observed.
@JvmRecord
internal data class MultiLiteralInfo(
    literals: Array<String?>?,
    anchorChars: CharArray?,
    anchorOffsets: IntArray?,
    anchorRanges: IntArray?,
    minLength: Int
) : Serializable {
    val literals: Array<String?>?
    val anchorChars: CharArray?
    val anchorOffsets: IntArray?
    val anchorRanges: IntArray?
    val minLength: Int

    init {
        this.literals = literals
        this.anchorChars = anchorChars
        this.anchorOffsets = anchorOffsets
        this.anchorRanges = anchorRanges
        this.minLength = minLength
    }

    companion object {
        fun create(literals: Array<String?>?): MultiLiteralInfo? {
            if (literals == null || literals.length < 2 || literals.length > 4) {
                return null
            }
            var minLen = Integer.MAX_VALUE
            val anchorChars = CharArray(literals.length)
            val anchorOffsets = IntArray(literals.length)
            var seen0 = 0L
            var seen1 = 0L
            val anchorBitmap = AsciiBitmap.Builder()

            for (i in literals.indices) {
                val lit = literals[i]
                if (lit == null || lit.isEmpty()) {
                    return null
                }
                for (j in 0..<lit.length()) {
                    if (lit.charAt(j).code > 127) {
                        return null
                    }
                }
                val firstChar: Char = lit.charAt(0)
                val bit = 1L shl (firstChar.code and 63)
                if (firstChar.code < 64) {
                    if ((seen0 and bit) != 0L) {
                        return null // Duplicate anchor character; defer to Teddy
                    }
                    seen0 = seen0 or bit
                } else {
                    if ((seen1 and bit) != 0L) {
                        return null // Duplicate anchor character; defer to Teddy
                    }
                    seen1 = seen1 or bit
                }
                anchorOffsets[i] = 0
                anchorChars[i] = firstChar
                anchorBitmap.add(anchorChars[i].code)
                minLen = Math.min(minLen, lit.length())
            }

            return MultiLiteralInfo(
                Arrays.copyOf<String?>(literals, literals.length),
                anchorChars,
                anchorOffsets,
                anchorBitmap.build().toRanges(),
                minLen
            )
        }
    }
}
