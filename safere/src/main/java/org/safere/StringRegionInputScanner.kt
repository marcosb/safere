// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.safere.Pattern.CharClassMatchInfo
import java.util.*

/** An opaque, region-relative String view. Construction never copies the backing text.  */
internal class StringRegionInputScanner(text: String, start: Int, end: Int) : InputScanner, CharSequence {
    private val text: String
    private val base: Int
    private val length: Int

    init {
        this.text = text
        this.base = start
        this.length = end - start
    }

    override fun charAt(index: Int): Char {
        return text.charAt(base + Objects.checkIndex(index, length))
    }

    override fun subSequence(start: Int, end: Int): StringRegionInputScanner {
        Objects.checkFromToIndex(start, end, length)
        return StringRegionInputScanner(text, base + start, base + end)
    }

    override fun toString(): String {
        return text.substring(base, base + length)
    }

    fun startsWith(literal: String, from: Int, foldCase: Boolean): Boolean {
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(literal.length().toLong())
        }
        return from >= 0 && from <= length - literal.length() && (if (foldCase)
            Ascii.regionMatchesIgnoreCase(text, base + from, literal, literal.length())
        else
            text.startsWith(literal, base + from))
    }

    fun endsWith(suffix: String, wasDollar: Boolean, unixLines: Boolean, foldCase: Boolean): Boolean {
        val suffixLength: Int = suffix.length()
        if (length >= suffixLength
            && text.regionMatches(foldCase, base + length - suffixLength, suffix, 0, suffixLength)
        ) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record(suffixLength.toLong())
            }
            return true
        }
        if (!wasDollar) {
            return false
        }
        val trailing = trailingLineTerminatorStart(unixLines, length)
        if (trailing >= suffixLength
            && text.regionMatches(foldCase, base + trailing - suffixLength, suffix, 0, suffixLength)
        ) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record(suffixLength.toLong())
            }
            return true
        }
        return false
    }

    fun indexOf(literal: String, from: Int, limit: Int): Int {
        val start = Math.max(0, from)
        val end = Math.min(length, limit)
        if (start > end - literal.length()) {
            return -1
        }
        val found: Int = text.indexOf(literal, base + start, base + end)
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record((if (found < 0) end - start else found - base - start + literal.length()).toLong())
        }
        return if (found < 0) -1 else found - base
    }

    /** Bounded ASCII-folded search with an input-sized verification budget and KMP fallback.  */
    fun indexOfIgnoreCase(literal: String, anchorOffset: Int, low: Char, high: Char, from: Int): Int {
        var pos = Math.max(0, from)
        val literalLength: Int = literal.length()
        if (literalLength == 0) {
            return Math.min(pos, length)
        }
        val anchorEnd = length - literalLength + anchorOffset + 1
        var work: Long = 0
        val budget = WorkLimit.forRemaining(length - pos)
        while (pos <= length - literalLength) {
            val anchor = indexOfCaseAnchor(low, high, pos + anchorOffset, anchorEnd)
            if (anchor < 0) {
                return -1
            }
            val candidate = anchor - anchorOffset
            if (startsWith(literal, candidate, true)) {
                return candidate
            }
            pos = candidate + 1
            work += literalLength.toLong()
            if (WorkLimit.isExhausted(work, budget)) {
                val found =
                    Ascii.indexOfLinearIgnoreCase(
                        text, literal, Ascii.ignoreCaseFailure(literal), base + pos, base + length
                    )
                return if (found < 0) -1 else found - base
            }
        }
        return -1
    }

    private fun indexOfCaseAnchor(low: Char, high: Char, from: Int, limit: Int): Int {
        var pos = from
        // Preserve the existing case-insensitive String search's scalar prologue.
        val scalarEnd = pos + Math.min(32, limit - pos)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        // As in StringInputScanner's small-set search, cap and grow disjoint windows. Searching
        // both cases to the end of the region on every find would repeatedly rescan an absent case.
        var window = 64
        while (pos < limit) {
            val end = pos + Math.min(window, limit - pos)
            val first = indexOfAscii(low.code, pos, end)
            val second = if (low == high) -1 else indexOfAscii(high.code, pos, if (first >= 0) first else end)
            val found = Ascii.minNonNegative(first, second)
            if (found >= 0) {
                return found
            }
            pos = end
            window = Math.min(4096, window * 2)
        }
        return -1
    }

    fun matchesCharClass(info: CharClassMatchInfo): Boolean {
        if (length == 0) {
            return info.allowEmpty
        }
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return true
    }

    override fun length(): Int {
        return length
    }

    override fun asciiAt(pos: Int): Int {
        val c: Char = text.charAt(base + Objects.checkIndex(pos, length))
        return if (c.code < 128) c.code else -1
    }

    override fun singleUnitCodePointAt(pos: Int): Int {
        val c: Char = text.charAt(base + Objects.checkIndex(pos, length))
        return if (Character.isHighSurrogate(c)
            && pos + 1 < length && Character.isLowSurrogate(text.charAt(base + pos + 1))
        )
            -1
        else
            c.code
    }

    override fun singleUnitCodePointBefore(pos: Int): Int {
        val c: Char = text.charAt(base + Objects.checkIndex(pos - 1, length))
        return if (Character.isLowSurrogate(c)
            && pos >= 2 && Character.isHighSurrogate(text.charAt(base + pos - 2))
        )
            -1
        else
            c.code
    }

    override fun decodeForward(pos: Int): Long {
        if (pos >= length) {
            return InputScanner.Companion.decoded(InputScanner.Companion.END_OF_INPUT, length)
        }
        val cp = codePointAt(pos)
        return InputScanner.Companion.decoded(cp, pos + Character.charCount(cp))
    }

    override fun decodeForward(pos: Int, limit: Int): Long {
        val end = Math.min(length, limit)
        if (pos >= end) {
            return InputScanner.Companion.decoded(InputScanner.Companion.END_OF_INPUT, end)
        }
        val first: Char = text.charAt(base + Objects.checkIndex(pos, length))
        val cp =
            if (Character.isHighSurrogate(first)
                && pos + 1 < end && Character.isLowSurrogate(text.charAt(base + pos + 1))
            )
                Character.toCodePoint(first, text.charAt(base + pos + 1))
            else
                first.code
        return InputScanner.Companion.decoded(cp, pos + Character.charCount(cp))
    }

    override fun decodeBackward(pos: Int): Long {
        if (pos <= 0) {
            return InputScanner.Companion.decoded(InputScanner.Companion.END_OF_INPUT, 0)
        }
        val cp = codePointBefore(pos)
        return InputScanner.Companion.decoded(cp, pos - Character.charCount(cp))
    }

    override fun decodeBackward(pos: Int, start: Int): Long {
        val lower = Math.max(0, start)
        if (pos <= lower) {
            return InputScanner.Companion.decoded(InputScanner.Companion.END_OF_INPUT, lower)
        }
        val last: Char = text.charAt(base + Objects.checkIndex(pos - 1, length))
        val cp =
            if (Character.isLowSurrogate(last)
                && pos - 2 >= lower && Character.isHighSurrogate(text.charAt(base + pos - 2))
            )
                Character.toCodePoint(text.charAt(base + pos - 2), last)
            else
                last.code
        return InputScanner.Companion.decoded(cp, pos - Character.charCount(cp))
    }

    override fun codePointAt(pos: Int): Int {
        if (pos >= length) {
            return InputScanner.Companion.END_OF_INPUT
        }
        val c: Char = text.charAt(base + Objects.checkIndex(pos, length))
        if (Character.isHighSurrogate(c) && pos + 1 < length) {
            val next: Char = text.charAt(base + pos + 1)
            if (Character.isLowSurrogate(next)) {
                return Character.toCodePoint(c, next)
            }
        }
        return c.code
    }

    override fun codePointBefore(pos: Int): Int {
        if (pos <= 0) {
            return InputScanner.Companion.END_OF_INPUT
        }
        val c: Char = text.charAt(base + Objects.checkIndex(pos - 1, length))
        if (Character.isLowSurrogate(c) && pos >= 2) {
            val previous: Char = text.charAt(base + pos - 2)
            if (Character.isHighSurrogate(previous)) {
                return Character.toCodePoint(previous, c)
            }
        }
        return c.code
    }

    override fun isCodePointBoundary(pos: Int): Boolean {
        return pos >= 0 && pos <= length && (pos == 0 || pos == length || !Character.isLowSurrogate(text.charAt(base + pos)) || !Character.isHighSurrogate(
            text.charAt(base + pos - 1)
        ))
    }

    override fun indexOfCodePointClass(ranges: IntArray?, bitmap0: Long, bitmap1: Long, start: Int, limit: Int): Int {
        val bound = Math.min(limit, length)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    override fun indexOfCharClass(info: CharClassScanInfo, start: Int): Int {
        return indexOfCodePointClass(info.ranges(), info.bitmap0(), info.bitmap1(), start, length)
    }

    override fun indexOfAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val end = Math.min(limit, length)
        if (start >= end) {
            return -1
        }
        val found: Int = text.indexOf(ascii, base + start, base + end)
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record((if (found < 0) end - start else found - base - start + 1).toLong())
        }
        return if (found < 0) -1 else found - base
    }

    override fun indexOfAsciiOrNonAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        return indexOfAsciiSet(ascii, ascii, ascii, true, fromIndex, limit)
    }

    override fun indexOfAsciiPair(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        return indexOfAsciiSet(c1, c2, c2, false, fromIndex, limit)
    }

    override fun indexOfAsciiPairOrNonAscii(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        return indexOfAsciiSet(c1, c2, c2, true, fromIndex, limit)
    }

    override fun indexOfAsciiTriple(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        return indexOfAsciiSet(c1, c2, c3, false, fromIndex, limit)
    }

    override fun indexOfAsciiTripleOrNonAscii(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        return indexOfAsciiSet(c1, c2, c3, true, fromIndex, limit)
    }

    private fun indexOfAsciiSet(c1: Int, c2: Int, c3: Int, nonAscii: Boolean, from: Int, limit: Int): Int {
        val end = Math.min(limit, length)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    override fun trailingLineTerminatorStart(unixLines: Boolean, end: Int): Int {
        if (end <= 0 || end > length) {
            return -1
        }
        val c = asciiAt(end - 1)
        if (unixLines) {
            return if (c == '\n'.code) end - 1 else -1
        }
        val cp = codePointBefore(end)
        if (cp == '\n'.code) {
            return if (end >= 2 && asciiAt(end - 2) == '\r'.code) end - 2 else end - 1
        }
        return if (cp == '\r'.code || cp == 0x85 || cp == 0x2028 || cp == 0x2029) end - 1 else -1
    }

    override fun positionDependentThreshold(dollarAnchorEnd: Boolean, unixLines: Boolean): Int {
        if (!dollarAnchorEnd) {
            return Integer.MAX_VALUE
        }
        val trailing = trailingLineTerminatorStart(unixLines, length)
        return if (trailing >= 0) trailing else length
    }
}
