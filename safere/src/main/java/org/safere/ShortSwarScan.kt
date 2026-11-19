// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/** Shared 64-bit SWAR kernels for scanning bounded 2-byte sequences (UTF-16 and char[]).  */
internal object ShortSwarScan {
    private val LONG_VIEW: VarHandle =
        MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

    fun indexOfCharClass(chars: CharArray, offset: Int, length: Int, ranges: IntArray, start: Int): Int {
        if (!Swar.supportsBmpCodeUnitRanges(ranges, 2)) {
            return VectorScanProvider.Companion.UNSUPPORTED
        }
        val numRanges: Int = ranges.length / 2
        if (requiresScalarRangeComparison(ranges)) {
            return scalarIndexOfCharClass(chars, offset, length, ranges, start)
        }

        var pos = Math.max(0, start)
        val wordEnd = length - 4 // 4 chars = 64 bits

        val low0 = (ranges[0].toLong() and 0xFFFFL) * Swar.SHORT_ONES
        val high0 = (ranges[1].toLong() and 0xFFFFL) * Swar.SHORT_ONES
        val low1 = if (numRanges > 1) (ranges[2].toLong() and 0xFFFFL) * Swar.SHORT_ONES else 0
        val high1 = if (numRanges > 1) (ranges[3].toLong() and 0xFFFFL) * Swar.SHORT_ONES else 0

        if (numRanges == 1) {
            while (pos <= wordEnd) {
                val word = loadLongFromChars(chars, offset + pos)
                val matches = Swar.exactShortRangeMask(word, low0, high0)
                if (matches != 0L) {
                    val limit = pos + 4
                    /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                }
                pos += 4
            }
        } else {
            while (pos <= wordEnd) {
                val word = loadLongFromChars(chars, offset + pos)
                val matches =
                    Swar.exactShortRangeMask(word, low0, high0) or Swar.exactShortRangeMask(word, low1, high1)
                if (matches != 0L) {
                    val limit = pos + 4
                    /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                }
                pos += 4
            }
        }

        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    fun indexOfCharClassUtf16(bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int): Int {
        if (!Swar.supportsBmpCodeUnitRanges(ranges, 2) || ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            return VectorScanProvider.Companion.UNSUPPORTED
        }
        val numRanges: Int = ranges.length / 2
        if (requiresScalarRangeComparison(ranges)) {
            return scalarIndexOfCharClassUtf16(bytes, offset, length, ranges, start)
        }

        var pos = Math.max(0, start)
        val wordEnd = length - 4 // 4 shorts = 8 bytes

        val low0 = (ranges[0].toLong() and 0xFFFFL) * Swar.SHORT_ONES
        val high0 = (ranges[1].toLong() and 0xFFFFL) * Swar.SHORT_ONES
        val low1 = if (numRanges > 1) (ranges[2].toLong() and 0xFFFFL) * Swar.SHORT_ONES else 0
        val high1 = if (numRanges > 1) (ranges[3].toLong() and 0xFFFFL) * Swar.SHORT_ONES else 0

        if (numRanges == 1) {
            while (pos <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + (pos shl 1)) as Long
                val matches = Swar.exactShortRangeMask(word, low0, high0)
                if (matches != 0L) {
                    val limit = pos + 4
                    /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                }
                pos += 4
            }
        } else {
            while (pos <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + (pos shl 1)) as Long
                val matches =
                    Swar.exactShortRangeMask(word, low0, high0) or Swar.exactShortRangeMask(word, low1, high1)
                if (matches != 0L) {
                    val limit = pos + 4
                    /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                }
                pos += 4
            }
        }

        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    fun indexOfIgnoreCase(chars: CharArray, offset: Int, length: Int, prefix: String, start: Int): Int {
        val prefixLen: Int = prefix.length()
        if (prefixLen == 0) {
            return Math.min(Math.max(0, start), length)
        }
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        if (prefixLen > 1) {
            return Utf16.indexOfIgnoreCase(chars, offset, length, prefix, start)
        }

        var pos = Math.max(0, start)
        val wordEnd = length - 4

        val first: Char = prefix.charAt(0)
        val low = Ascii.toLowerCase(first).code.toShort()
        val high = Ascii.toUpperCase(first).code.toShort()
        val repeatedLow = (low.toLong() and 0xFFFFL) * Swar.SHORT_ONES
        val repeatedHigh = (high.toLong() and 0xFFFFL) * Swar.SHORT_ONES

        while (pos <= wordEnd) {
            val word = loadLongFromChars(chars, offset + pos)
            val diffLow = word xor repeatedLow
            val diffHigh = word xor repeatedHigh
            val matches =
                ((((diffLow - Swar.SHORT_ONES) and diffLow.inv()) or ((diffHigh - Swar.SHORT_ONES) and diffHigh.inv()))
                        and Swar.SHORT_HIGH_BITS)

            if (matches != 0L) {
                val limit = pos + 4
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            }
            pos += 4
        }

        val scalarLimit = length - prefixLen
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    fun indexOfIgnoreCaseUtf16(
        bytes: ByteArray, offset: Int, length: Int, prefix: String, start: Int
    ): Int {
        val prefixLen: Int = prefix.length()
        if (prefixLen == 0) {
            return Math.min(Math.max(0, start), length)
        }
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        if (prefixLen > 1) {
            return Utf16.indexOfIgnoreCaseUtf16(bytes, offset, length, prefix, start)
        }
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            return VectorScanProvider.Companion.UNSUPPORTED
        }

        var pos = Math.max(0, start)
        val wordEnd = length - 4

        val first: Char = prefix.charAt(0)
        val low = Ascii.toLowerCase(first).code.toShort()
        val high = Ascii.toUpperCase(first).code.toShort()
        val repeatedLow = (low.toLong() and 0xFFFFL) * Swar.SHORT_ONES
        val repeatedHigh = (high.toLong() and 0xFFFFL) * Swar.SHORT_ONES

        while (pos <= wordEnd) {
            val word = LONG_VIEW.get(bytes, offset + (pos shl 1)) as Long
            val diffLow = word xor repeatedLow
            val diffHigh = word xor repeatedHigh
            val matches =
                ((((diffLow - Swar.SHORT_ONES) and diffLow.inv()) or ((diffHigh - Swar.SHORT_ONES) and diffHigh.inv()))
                        and Swar.SHORT_HIGH_BITS)

            if (matches != 0L) {
                val limit = pos + 4
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            }
            pos += 4
        }

        val scalarLimit = length - prefixLen
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    private fun requiresScalarRangeComparison(ranges: IntArray): Boolean {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return false
    }

    private fun scalarIndexOfCharClass(
        chars: CharArray, offset: Int, length: Int, ranges: IntArray, start: Int
    ): Int {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    private fun scalarIndexOfCharClassUtf16(
        bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int
    ): Int {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    private fun loadLongFromChars(chars: CharArray, offset: Int): Long {
        return ((chars[offset].code.toLong() and 0xFFFFL)
                or ((chars[offset + 1].code.toLong() and 0xFFFFL) shl 16)
                or ((chars[offset + 2].code.toLong() and 0xFFFFL) shl 32)
                or ((chars[offset + 3].code.toLong() and 0xFFFFL) shl 48))
    }
}
