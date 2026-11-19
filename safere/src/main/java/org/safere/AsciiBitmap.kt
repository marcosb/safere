// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.util.*
import kotlin.jvm.JvmRecord
import kotlin.math.*
/**
 * Immutable 128-bit bitmap representing a set of ASCII code points (0–127).
 * 
 * 
 * Bits 0–63 are stored in `bitmap0`; bits 64–127 are stored in `bitmap1`.
 */
@JvmRecord
internal data class AsciiBitmap(val bitmap0: Long, val bitmap1: Long) {
    fun contains(cp: Int): Boolean {
        return (cp ushr 7 == 0) && containsAscii(cp)
    }

    /**
     * Unchecked ASCII membership test.
     * 
     * 
     * Only safe when the caller has already verified that `0 <= cp <= 127`.
     */
    fun containsAscii(cp: Int): Boolean {
        return ((if ((cp and 64) == 0) bitmap0 else bitmap1) and (1L shl cp)) != 0L
    }

    fun cardinality(): Int {
        return java.lang.Long.bitCount(bitmap0) + java.lang.Long.bitCount(bitmap1)
    }

    fun isEmpty(): Boolean = bitmap0 == 0L && bitmap1 == 0L

    fun union(other: AsciiBitmap?): AsciiBitmap {
        if (other == null || other.isEmpty()) {
            return this
        }
        return AsciiBitmap(this.bitmap0 or other.bitmap0, this.bitmap1 or other.bitmap1)
    }

    /** Returns a 128-element boolean lookup array for maximum scalar loop throughput.  */
    fun toBooleanArray(): BooleanArray {
        val array = BooleanArray(128)
        for (i in 0..63) {
            if ((bitmap0 and (1L shl i)) != 0L) {
                array[i] = true
            }
        }
        for (i in 0..63) {
            if ((bitmap1 and (1L shl i)) != 0L) {
                array[64 + i] = true
            }
        }
        return array
    }

    /** Returns inclusive [low0, high0, low1, high1, ...] range pairs representing this ASCII set.  */
    fun toRanges(): IntArray {
        val temp = IntArray(256)
        var count = 0
        var start = -1
        for (i in 0..127) {
            if (containsAscii(i)) {
                if (start == -1) {
                    start = i
                }
            } else if (start != -1) {
                temp[count++] = start
                temp[count++] = i - 1
                start = -1
            }
        }
        if (start != -1) {
            temp[count++] = start
            temp[count++] = 127
        }
        return (temp).copyOf(count)
    }

    internal class Builder {
        private var b0: Long = 0
        private var b1: Long = 0

        fun add(cp: Int): Builder {
            if (cp >= 0 && cp < 64) {
                b0 = b0 or (1L shl cp)
            } else if (cp >= 64 && cp < 128) {
                b1 = b1 or (1L shl (cp - 64))
            }
            return this
        }

        fun addRange(lo: Int, hi: Int): Builder {
            for (cp in max(0, lo)..Math.min(127, hi)) {
                add(cp)
            }
            return this
        }

        fun build(): AsciiBitmap {
            return if (b0 == 0L && b1 == 0L) EMPTY else AsciiBitmap(b0, b1)
        }
    }

    companion object {
        val EMPTY: AsciiBitmap = AsciiBitmap(0L, 0L)

        fun of(cp: Int): AsciiBitmap {
            if (cp >= 0 && cp < 64) {
                return AsciiBitmap(1L shl cp, 0L)
            }
            if (cp >= 64 && cp < 128) {
                return AsciiBitmap(0L, 1L shl (cp - 64))
            }
            return EMPTY
        }
    }
}
