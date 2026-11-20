// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min

internal class StringInputScanner(text: String) : InputScanner {
    private val text: String = text

    fun text(): String {
        return text
    }

    override val length: Int
        get() {
            return text.length
        }

    override fun asciiAt(pos: Int): Int {
        val c: Char = text[pos]
        return if (c.code < 0x80) c.code else -1
    }

    override fun indexOfAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        return indexOfAscii(text, ascii, fromIndex, limit)
    }

    override fun indexOfAsciiOrNonAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        val end = min(limit, text.length)
        for (i in max(0, fromIndex) until end) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch = text[i]
            if (ch.code == ascii || ch.code >= 0x80) {
                return i
            }
        }
        return -1
    }

    override fun indexOfAsciiPair(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        val end = min(limit, text.length)
        for (i in max(0, fromIndex) until end) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch = text[i]
            if (ch.code == c1 || ch.code == c2) {
                return i
            }
        }
        return -1
    }

    override fun indexOfAsciiPairOrNonAscii(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        val end = min(limit, text.length)
        for (i in max(0, fromIndex) until end) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch = text[i]
            if (ch.code == c1 || ch.code == c2 || ch.code >= 0x80) {
                return i
            }
        }
        return -1
    }

    override fun indexOfAsciiTriple(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        val end = min(limit, text.length)
        for (i in max(0, fromIndex) until end) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch = text[i]
            if (ch.code == c1 || ch.code == c2 || ch.code == c3) {
                return i
            }
        }
        return -1
    }

    override fun indexOfAsciiTripleOrNonAscii(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        val end = min(limit, text.length)
        for (i in max(0, fromIndex) until end) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val ch = text[i]
            if (ch.code == c1 || ch.code == c2 || ch.code == c3 || ch.code >= 0x80) {
                return i
            }
        }
        return -1
    }

    override fun singleUnitCodePointAt(pos: Int): Int {
        val c: Char = text[pos]
        return if (c.isHighSurrogate()
            && pos + 1 < text.length && text[pos + 1].isLowSurrogate()
        )
            -1
        else
            c.code
    }

    override fun singleUnitCodePointBefore(pos: Int): Int {
        val c: Char = text[pos - 1]
        return if (c.isLowSurrogate()
            && pos >= 2 && text[pos - 2].isHighSurrogate()
        )
            -1
        else
            c.code
    }

    override fun indexOfCharClass(scanInfo: CharClassScanInfo?, start: Int): Int {
        if (scanInfo == null) {
            return -1
        }
        return indexOfCharClass(text, scanInfo, start)
    }

    override fun indexOfCodePointClass(ranges: IntArray?, bitmap0: Long, bitmap1: Long, start: Int, limit: Int): Int {
        return indexOfCodePointClass(text, ranges, bitmap0, bitmap1, start, limit)
    }

    override fun decodeForward(pos: Int): Long {
        if (pos >= text.length) {
            return InputScanner.decoded(InputScanner.END_OF_INPUT, text.length)
        }
        val codePoint = CodePoints.codePointAt(text, pos)
        return InputScanner.decoded(codePoint, pos + CodePoints.charCount(codePoint))
    }

    override fun decodeForward(pos: Int, limit: Int): Long {
        if (pos >= limit) {
            return InputScanner.decoded(InputScanner.END_OF_INPUT, limit)
        }
        val first: Char = text[pos]
        val codePoint =
            if (first.isHighSurrogate()
                && pos + 1 < limit && text[pos + 1].isLowSurrogate()
            )
                CodePoints.toCodePoint(first, text[pos + 1])
            else
                first.code
        return InputScanner.decoded(codePoint, pos + CodePoints.charCount(codePoint))
    }

    override fun decodeBackward(pos: Int): Long {
        if (pos <= 0) {
            return InputScanner.decoded(InputScanner.END_OF_INPUT, 0)
        }
        val codePoint = CodePoints.codePointBefore(text, pos)
        return InputScanner.decoded(codePoint, pos - CodePoints.charCount(codePoint))
    }

    override fun decodeBackward(pos: Int, start: Int): Long {
        if (pos <= start) {
            return InputScanner.decoded(InputScanner.END_OF_INPUT, start)
        }
        val last: Char = text[pos - 1]
        val codePoint =
            if (last.isLowSurrogate()
                && pos - 2 >= start && text[pos - 2].isHighSurrogate()
            )
                CodePoints.toCodePoint(text[pos - 2], last)
            else
                last.code
        return InputScanner.decoded(codePoint, pos - CodePoints.charCount(codePoint))
    }

    override fun codePointAt(pos: Int): Int {
        return if (pos >= text.length) InputScanner.END_OF_INPUT else CodePoints.codePointAt(text, pos)
    }

    override fun codePointBefore(pos: Int): Int {
        return if (pos <= 0) InputScanner.END_OF_INPUT else CodePoints.codePointBefore(text, pos)
    }

    override fun isCodePointBoundary(pos: Int): Boolean {
        if (pos < 0 || pos > text.length) {
            return false
        }
        return pos == 0 || pos == text.length || !text[pos].isLowSurrogate() || !text[pos - 1].isHighSurrogate()
    }

    override fun trailingLineTerminatorStart(unixLines: Boolean, logicalEndPos: Int): Int {
        return trailingLineTerminatorStart(text, unixLines, logicalEndPos)
    }

    override fun positionDependentThreshold(dollarAnchorEnd: Boolean, unixLines: Boolean): Int {
        var threshold = Int.MAX_VALUE
        // Note: the caller handles hasTextAnchor threshold (= 1).
        if (dollarAnchorEnd) {
            val trailingTermStart = trailingLineTerminatorStart(unixLines, text.length)
            if (trailingTermStart >= 0) {
                threshold = trailingTermStart
            } else {
                threshold = text.length
            }
        }
        return threshold
    }

    companion object {
        private const val SMALL_SET_SCALAR_PROLOGUE = 16
        private const val SMALL_SET_INITIAL_SEARCH_WINDOW = 64
        private const val SMALL_SET_SEARCH_WINDOW = 4096

        fun indexOfAscii(text: String, ascii: Int, fromIndex: Int, limit: Int): Int {
            if (WorkCounterConfig.ENABLED) {
                val end = min(limit, text.length)
                for (i in max(0, fromIndex) until end) {
                    WorkCounter.record()
                    if (text[i].code == ascii) {
                        return i
                    }
                }
                return -1
            }
            val idx: Int = text.indexOfCodePointBounded(ascii, fromIndex, limit)
            return if (idx >= 0) idx else -1
        }

        fun indexOfCharClass(text: String, scanInfo: CharClassScanInfo, start: Int): Int {
            var position = max(0, start)
            if (scanInfo is CharClassScanInfo.SmallSet) {
                return indexOfSmallSet(text, scanInfo.chars, position)
            }
            val ranges = scanInfo.ranges
            val bitmap0 = scanInfo.bitmap0
            val bitmap1 = scanInfo.bitmap1
            while (position < text.length) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val ch: Char = text[position]
                if (InputScanner.classContains(ranges, bitmap0, bitmap1, ch.code)) {
                    return position
                }
                position++
            }
            return -1
        }

        /**
         * Returns the first index at or after `start` of any of `chars`, or `-1`.
         * 
         * 
         * `chars` holds the one to three members of a [CharClassScanInfo.SmallSet]. None
         * is a surrogate, so a code-unit search finds exactly the code points in the set. This is the
         * small-set search for repeated `find()` calls on the `String` path: the scanner and
         * the start accelerator both use it, so both keep the work bound below.
         */
        fun indexOfSmallSet(text: String, chars: CharArray, start: Int): Int {
            val length: Int = text.length
            var position = max(0, start)
            if (position >= length) {
                return -1
            }
            val scalarEnd = position + min(SMALL_SET_SCALAR_PROLOGUE, length - position)
            while (position < scalarEnd) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                if (isMember(chars, text[position])) {
                    return position
                }
                position++
            }
            return indexOfSmallSetAfterPrologue(text, chars, position)
        }

        /**
         * The windowed part of [.indexOfSmallSet], kept out of line. The prologue above is inlined
         * into the DFA's start-acceleration call; inlining the whole search there as well doubled the
         * cost of a full-width citation `replaceAll` on 64 KiB of non-matching text, though the
         * windowed part never ran.
         */
        private fun indexOfSmallSetAfterPrologue(text: String, chars: CharArray, position: Int): Int {
            var position = position
            val length: Int = text.length
            if (position >= length) {
                return -1
            }
            if (chars.size == 1) {
                // A single member needs no bound: a hit advances past all scanned units; a miss ends search.
                val candidate: Int = text.indexOfCodePointBounded(chars[0].code, position, length)
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record((if (candidate >= 0) candidate - position + 1 else length - position).toLong())
                }
                return candidate
            }

            // Public String searches use the JVM's accelerated Latin-1 or UTF-16 kernels without copying
            // the input. Keep each search bounded: an absent member must not rescan the entire remaining
            // suffix on every find(). Grow from a short first window so nearby matches do not repeatedly
            // scan a full-sized window for an absent member. Reset to 64 on each call and double after
            // misses, up to the cap. Failed windows are disjoint; the final window is at most the distance
            // already scanned in this call plus 64. Thus overrun past a match is bounded by distance
            // advanced plus 64, giving at most k * (2n + 64m) work for k <= 3 members and m consuming
            // matches across n input units. Since m <= n, repeated find() work stays linear.
            var windowLength: Int = SMALL_SET_INITIAL_SEARCH_WINDOW
            while (position < length) {
                val windowEnd = position + min(windowLength, length - position)
                var searchEnd = windowEnd
                var best = -1
                for (target in chars) {
                    val candidate: Int = text.indexOfCodePointBounded(target.code, position, searchEnd)
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record((if (candidate >= 0) candidate - position + 1 else searchEnd - position).toLong())
                    }
                    if (candidate >= 0) {
                        best = candidate
                        searchEnd = candidate
                        if (candidate == position) {
                            return candidate
                        }
                    }
                }
                if (best >= 0) {
                    return best
                }
                position = windowEnd
                windowLength = min(SMALL_SET_SEARCH_WINDOW, windowLength * 2)
            }
            return -1
        }

        private fun isMember(chars: CharArray, ch: Char): Boolean {
            for (c in chars) {
                if (c == ch) {
                    return true
                }
            }
            return false
        }

        fun indexOfCodePointClass(
            text: String, ranges: IntArray?, bitmap0: Long, bitmap1: Long, start: Int, limit: Int
        ): Int {
            var position = max(0, start)
            val bound = min(limit, text.length)
            while (position < bound) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val codePoint = CodePoints.codePointAt(text, position)
                if (InputScanner.classContains(ranges!!, bitmap0, bitmap1, codePoint)) {
                    return position
                }
                position += CodePoints.charCount(codePoint)
            }
            return -1
        }

        fun trailingLineTerminatorStart(text: String, unixLines: Boolean, logicalEndPos: Int): Int {
            val len = logicalEndPos
            if (len <= 0 || len > text.length) {
                return -1
            }
            val ch: Char = text[len - 1]
            if (unixLines) {
                return if (ch == '\n') len - 1 else -1
            }
            if (ch == '\n') {
                return if (len >= 2 && text[len - 2] == '\r') len - 2 else len - 1
            }
            if (ch == '\r' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029') {
                return len - 1
            }
            return -1
        }
    }
}
