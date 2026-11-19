// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder
import java.util.*

internal class Utf8InputScanner(bytes: ByteArray?, offset: Int, length: Int) : ByteSwarScan(bytes, offset, length),
    InputScanner {
    constructor(bytes: ByteArray) : this(bytes, 0, bytes.length)

    override fun length(): Int {
        return length
    }

    override fun indexOfAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val scanLen = Math.min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
        val res = scanByte(scanLen, ascii.toByte(), start)
        return if (res >= 0 && res < limit) res else -1
    }

    override fun indexOfAsciiOrNonAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val scanLen = Math.min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
        return ByteSwarScan.Companion.indexOfByteOrNonAscii(bytes, offset, scanLen, ascii.toByte(), start)
    }

    private fun scanByte(scanLen: Int, b0: Byte, start: Int): Int {
        val window = scanLen - start
        val byteProvider = VectorScanProviders.providerFor(ScanKind.BYTE, window)
        if (byteProvider != null) {
            val idx = byteProvider.indexOfByte(bytes, offset, scanLen, b0, start)
            if (idx != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
        }
        ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, window, ScanPath.SWAR)
        return ByteSwarScan.Companion.indexOfByte(bytes, offset, scanLen, b0, start)
    }

    override fun indexOfAsciiPair(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val scanLen = Math.min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
        val res = scanBytePair(scanLen, c1.toByte(), c2.toByte(), start)
        return if (res >= 0 && res < limit) res else -1
    }

    override fun indexOfAsciiPairOrNonAscii(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val scanLen = Math.min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
        return ByteSwarScan.Companion.indexOfBytePairOrNonAscii(
            bytes, offset, scanLen, c1.toByte(), c2.toByte(), start
        )
    }

    override fun indexOfAsciiTriple(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val scanLen = Math.min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
        val res = scanByteTriple(scanLen, c1.toByte(), c2.toByte(), c3.toByte(), start)
        return if (res >= 0 && res < limit) res else -1
    }

    override fun indexOfAsciiTripleOrNonAscii(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        val start = Math.max(0, fromIndex)
        val scanLen = Math.min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
        return ByteSwarScan.Companion.indexOfByteTripleOrNonAscii(
            bytes, offset, scanLen, c1.toByte(), c2.toByte(), c3.toByte(), start
        )
    }

    private fun scanBytePair(scanLen: Int, b0: Byte, b1: Byte, start: Int): Int {
        val window = scanLen - start
        val pairProvider = VectorScanProviders.providerFor(ScanKind.PAIR, window)
        if (pairProvider != null) {
            val idx = pairProvider.indexOfBytePair(bytes, offset, scanLen, b0, b1, start)
            if (idx != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.PAIR, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
        }
        ScanAudit.record(ScanKind.PAIR, ScanDirection.FORWARD, window, ScanPath.SWAR)
        return ByteSwarScan.Companion.indexOfBytePair(bytes, offset, scanLen, b0, b1, start)
    }

    private fun scanByteTriple(scanLen: Int, b0: Byte, b1: Byte, b2: Byte, start: Int): Int {
        val window = scanLen - start
        val tripleProvider = VectorScanProviders.providerFor(ScanKind.TRIPLE, window)
        if (tripleProvider != null) {
            val idx = tripleProvider.indexOfByteTriple(bytes, offset, scanLen, b0, b1, b2, start)
            if (idx != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.TRIPLE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
        }
        ScanAudit.record(ScanKind.TRIPLE, ScanDirection.FORWARD, window, ScanPath.SWAR)
        return ByteSwarScan.Companion.indexOfByteTriple(bytes, offset, scanLen, b0, b1, b2, start)
    }

    fun slice(start: Int, end: Int): Utf8InputScanner {
        return Utf8InputScanner(bytes, offset + start, end - start)
    }

    @JvmOverloads
    fun startsWith(prefix: ByteArray, startPos: Int, foldCase: Boolean = false): Boolean {
        val prefixLen: Int = prefix.length
        if (startPos >= 0 && length - startPos >= prefixLen) {
            val start = offset + startPos
            if (if (foldCase)
                    equalsFoldCase(bytes, start, prefix, 0, prefixLen)
                else
                    Arrays.equals(bytes, start, start + prefixLen, prefix, 0, prefixLen)
            ) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(prefixLen.toLong())
                }
                return true
            }
        }
        return false
    }

    fun endsWith(suffix: ByteArray, wasDollar: Boolean, unixLines: Boolean, foldCase: Boolean): Boolean {
        val suffixLen: Int = suffix.length
        if (length >= suffixLen) {
            val start = offset + length - suffixLen
            if (if (foldCase)
                    equalsFoldCase(bytes, start, suffix, 0, suffixLen)
                else
                    Arrays.equals(bytes, start, start + suffixLen, suffix, 0, suffixLen)
            ) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(suffixLen.toLong())
                }
                return true
            }
        }
        if (!wasDollar || length == 0) {
            return false
        }
        val effectiveLen = trailingLineTerminatorStart(unixLines, length)
        if (effectiveLen >= suffixLen) {
            val start = offset + effectiveLen - suffixLen
            if (if (foldCase)
                    equalsFoldCase(bytes, start, suffix, 0, suffixLen)
                else
                    Arrays.equals(bytes, start, start + suffixLen, suffix, 0, suffixLen)
            ) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(suffixLen.toLong())
                }
                return true
            }
        }
        return false
    }

    override fun asciiAt(pos: Int): Int {
        val value = unsignedByteAt(pos)
        return if (value < 0x80) value else -1
    }

    override fun singleUnitCodePointAt(pos: Int): Int {
        return asciiAt(pos)
    }

    override fun singleUnitCodePointBefore(pos: Int): Int {
        return asciiAt(pos - 1)
    }

    override fun indexOfCodePointClass(ranges: IntArray, bitmap0: Long, bitmap1: Long, start: Int, limit: Int): Int {
        var position = Math.max(0, start)
        val scanLen = Math.min(length, limit)
        if (position >= scanLen) {
            return -1
        }
        if (!WorkCounterConfig.ENABLED) {
            val window = scanLen - position
            if (VectorScanProviders.providerForPolicy(
                    ScanKind.CLASS,
                    window
                ) == null && ranges.length >= 4 && ranges.length <= 8 && ranges[0] >= 0 && ranges[ranges.length - 1] < 0x80 && window >= MULTI_RANGE_SWAR_MINIMUM_LENGTH && !isAsciiPair(
                    ranges
                ) && !isAsciiTriple(ranges)
            ) {
                // No Vector kernel for this window, but SWAR still beats decoding code points one at a
                // time. Declining the Vector tier is not a reason to fall all the way back to scalar.
                ScanAudit.recordConsultation(ScanKind.CLASS, window)
                ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.SWAR)
                val scalarLimit = Math.min(scanLen, position + MULTI_RANGE_SWAR_SCALAR_PROLOGUE_LENGTH)
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                if (position >= scanLen) {
                    return -1
                }
                return ByteSwarScan.Companion.indexOfMultipleByteRanges(
                    bytes, offset, scanLen, ranges, bitmap0, bitmap1, position
                )
            }
            val asciiResult = indexOfAsciiRanges(ranges, bitmap0, bitmap1, position, scanLen)
            if (asciiResult >= -1) {
                return asciiResult
            }
            if (bitmap0 == 0L && bitmap1 == 0L) {
                return indexOfNonAsciiCodePointClass(ranges, position, scanLen)
            }
        }
        while (position < scanLen) {
            val codePointPosition = position
            if (WorkCounterConfig.ENABLED) {
                record()
            }
            var codePoint = asciiAt(position)
            if (codePoint >= 0) {
                position++
            } else {
                val decoded = decodeForward(position)
                codePoint = InputScanner.Companion.codePoint(decoded)
                position = InputScanner.Companion.position(decoded)
            }
            if (InputScanner.Companion.classContains(ranges, bitmap0, bitmap1, codePoint)) {
                return if (codePointPosition < scanLen) codePointPosition else -1
            }
        }
        return -1
    }

    /**
     * Searches classes that can be represented by the specialized ASCII scanners.
     * 
     * @return the match position, `-1` when the class is absent, or `-2` when the class
     * requires the general code-point scan
     */
    private fun indexOfAsciiRanges(ranges: IntArray, bitmap0: Long, bitmap1: Long, start: Int, scanLen: Int): Int {
        if (ranges.length < 2 || ranges[0] < 0 || ranges[ranges.length - 1] >= 0x80) {
            return -2
        }
        when (ranges.length) {
            2 -> {
                val low = ranges[0]
                val high = ranges[1]
                if (low == high) {
                    val res = indexOfByte(low.toByte(), start)
                    return if (res >= 0 && res < scanLen) res else -1
                }
                if (high == low + 1) {
                    return scanBytePair(scanLen, low.toByte(), high.toByte(), start)
                }
            }

            4 -> {
                if (ranges[0] == ranges[1] && ranges[2] == ranges[3]) {
                    return scanBytePair(scanLen, ranges[0].toByte(), ranges[2].toByte(), start)
                }
            }

            6 -> {
                if (useSpecializedAsciiTriple(ranges, scanLen - start)) {
                    return scanByteTriple(
                        scanLen, ranges[0].toByte(), ranges[2].toByte(), ranges[4].toByte(), start
                    )
                }
            }

            else -> {}
        }
        val window = scanLen - start
        val classProvider = VectorScanProviders.providerFor(ScanKind.CLASS, window)
        if (classProvider != null) {
            var position = start
            val scalarLimit = Math.min(scanLen, position + VECTOR_SCALAR_PROLOGUE_LENGTH)
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            val result = classProvider.indexOfAsciiClass(bytes, offset, scanLen, ranges, position)
            if (result != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return result
            }
        }
        if (ranges.length == 2) {
            ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.SWAR)
            return ByteSwarScan.Companion.indexOfByteRange(bytes, offset, scanLen, ranges[0], ranges[1], start)
        }
        ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.DECLINED)
        return -2
    }

    /**
     * Returns the position of the first occurrence of `sequence`, the UTF-8 encoding of one
     * non-ASCII code point, starting in `[start, limit)`, or `-1`.
     * 
     * 
     * This searches for the sequence's last byte with the byte search kernel and then compares the
     * bytes before it, so it never decodes. In valid UTF-8 a match is at a code point boundary,
     * because the sequence starts with a lead byte. The last byte is a continuation byte, which
     * varies more than lead bytes do in text from one script, so fewer hits fail the comparison.
     */
    fun indexOfUtf8Sequence(sequence: ByteArray, start: Int, limit: Int): Int {
        val position = Math.max(0, start)
        val scanLen = Math.min(length, limit)
        val tail: Int = sequence.length - 1
        val last = sequence[tail]
        var from = position + tail
        var result = -1
        while (from < scanLen) {
            val hit = scanByte(scanLen, last, from)
            if (hit < 0 || hit >= scanLen) {
                break
            }
            val candidate = hit - tail
            if (precedingBytesMatch(sequence, candidate, tail)) {
                result = candidate
                break
            }
            from = hit + 1
        }
        if (WorkCounterConfig.ENABLED && position < scanLen) {
            WorkCounter.record(((if (result >= 0) result + sequence.length else scanLen) - position).toLong())
        }
        return result
    }

    fun indexOfMixedPair(ascii: Int, sequence: ByteArray, start: Int, limit: Int): Int {
        val position = Math.max(0, start)
        val scanLen = Math.min(length, limit)
        if (position >= scanLen) {
            return -1
        }
        val tail: Int = sequence.length - 1
        val asciiByte = ascii.toByte()
        val last = sequence[tail]
        var from = position
        var result = -1
        var matchEnd = scanLen
        while (from < scanLen) {
            val hit = scanBytePair(scanLen, asciiByte, last, from)
            if (hit < 0 || hit >= scanLen) {
                break
            }
            if (bytes[offset + hit] == asciiByte) {
                result = hit
                matchEnd = hit + 1
                break
            }
            val candidate = hit - tail
            if (candidate >= position && precedingBytesMatch(sequence, candidate, tail)) {
                result = candidate
                matchEnd = hit + 1
                break
            }
            from = hit + 1
        }
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record((matchEnd - position).toLong())
        }
        return result
    }

    private fun precedingBytesMatch(sequence: ByteArray, candidate: Int, count: Int): Boolean {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return true
    }

    private fun indexOfNonAsciiCodePointClass(ranges: IntArray?, start: Int, scanLen: Int): Int {
        var position = start
        val wordEnd = scanLen - java.lang.Long.BYTES
        while (position < scanLen) {
            if (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                if ((word and BYTE_HIGH_BITS) == 0L) {
                    position += java.lang.Long.BYTES
                    continue
                }
            }
            val value = unsignedByteAt(position)
            if (value < 0x80) {
                position++
                continue
            }
            val codePointPosition = position
            val decoded = decodeForward(position)
            val codePoint: Int = InputScanner.Companion.codePoint(decoded)
            position = InputScanner.Companion.position(decoded)
            if (InputScanner.Companion.classContains(ranges, 0, 0, codePoint)) {
                return if (codePointPosition < scanLen) codePointPosition else -1
            }
        }
        return -1
    }

    fun indexOf(literal: ByteArray, failure: IntArray, shifts: IntArray?): Int {
        return indexOf(literal, failure, shifts, 0)
    }

    fun indexOf(literal: ByteArray, failure: IntArray, shifts: IntArray?, start: Int): Int {
        if (literal.length == 0) {
            return start
        }
        if (!WorkCounterConfig.ENABLED) {
            if (literal.length == 1) {
                return indexOfByte(literal[0], start)
            }
            if (shifts != null) {
                // Both searches beat the linear scan, but they win over different ranges. The skip loop
                // reaches the end of a short input in a handful of steps, while the candidate filter has
                // to pay for its wider setup and finish with a scalar tail. Once the input is long enough
                // for the filter's eight-positions-per-step throughput to dominate that fixed cost, it
                // wins by a growing margin. The crossover scales with the literal length, since a longer
                // literal lets the skip loop advance further per step.
                val result =
                    if (remaining(start) >= ByteSwarScan.Companion.filterThreshold(literal.length))
                        ByteSwarScan.Companion.indexOfFiltered(bytes, offset, length, literal, start)
                    else
                        boundedBoyerMooreHorspool(literal, shifts, start)
                // A match index or a trusted -1; only the -2 "work budget exhausted" sentinel falls
                // through to the linear-time scan below.
                if (result >= -1) {
                    return result
                }
            }
        }
        return indexOfLinear(literal, failure, start)
    }

    /**
     * Like [.indexOf], but uses the candidate filter at every input
     * length.
     * 
     * 
     * The skip loop's advantage on short inputs depends on the input bytes being absent from the
     * literal. A short literal made of common letters, such as `</block>` in English text,
     * skips only a few bytes per step, and its dependent table loads then cost several times the
     * filter's fixed setup. Callers that search a short tail for a literal that is often present use
     * this instead.
     */
    fun indexOfInTail(literal: ByteArray, failure: IntArray, start: Int): Int {
        if (!WorkCounterConfig.ENABLED && literal.length > 1) {
            val result: Int = ByteSwarScan.Companion.indexOfFiltered(bytes, offset, length, literal, start)
            if (result >= -1) {
                return result
            }
        }
        return indexOf(literal, failure, null, start)
    }

    fun indexOf(literal: ByteArray, failure: IntArray, shifts: IntArray?, start: Int, rareByteOffset: Int): Int {
        if (rareByteOffset < 0 || WorkCounterConfig.ENABLED
            || remaining(start) < ByteSwarScan.Companion.filterThreshold(literal.length)
        ) {
            return indexOf(literal, failure, shifts, start)
        }
        val result = indexOfRareByte(literal, rareByteOffset, start)
        return if (result >= -1) result else indexOfLinear(literal, failure, start)
    }

    private fun indexOfRareByte(literal: ByteArray, anchorOffset: Int, start: Int): Int {
        val lastStart: Int = length - literal.length
        if (start > lastStart) {
            return -1
        }
        val scanEnd = lastStart + anchorOffset + 1
        var searchFrom = start + anchorOffset
        var work: Long = 0
        val workLimit = WorkLimit.forRemaining(remaining(start))
        while (searchFrom < scanEnd) {
            val hit: Int = ByteSwarScan.Companion.indexOfByte(bytes, offset, scanEnd, literal[anchorOffset], searchFrom)
            if (hit < 0) {
                return -1
            }
            val candidate = hit - anchorOffset
            if (ByteSwarScan.Companion.matchesAt(bytes, offset, literal, candidate)) {
                return candidate
            }
            work += literal.length.toLong()
            if (WorkLimit.isExhausted(work, workLimit)) {
                return -2
            }
            searchFrom = hit + 1
        }
        return -1
    }

    fun indexOfWithin(literal: ByteArray, failure: IntArray, start: Int, maxStart: Int): Int {
        if (literal.length == 0) {
            return if (start <= maxStart) start else -1
        }
        val lastStart = Math.min(length - literal.length, maxStart)
        if (start > lastStart) {
            return -1
        }
        val limit: Int = lastStart + literal.length
        if (!WorkCounterConfig.ENABLED
            && limit - start >= ByteSwarScan.Companion.filterThreshold(literal.length)
        ) {
            val result: Int = ByteSwarScan.Companion.indexOfFiltered(bytes, offset, limit, literal, start)
            if (result >= -1) {
                return result
            }
        }
        return indexOfLinear(bytes, offset, limit, literal, failure, start)
    }

    private fun remaining(start: Int): Int {
        return length - start
    }

    /** Knuth-Morris-Pratt scan, linear in the input length regardless of the literal.  */
    private fun indexOfLinear(literal: ByteArray, failure: IntArray, start: Int): Int {
        return indexOfLinear(bytes, offset, length, literal, failure, start)
    }

    fun indexOfIgnoreCase(
        prefix: String, failure: IntArray, anchorOffset: Int, anchorLow: Byte, anchorHigh: Byte, start: Int
    ): Int {
        val prefixLen: Int = prefix.length()
        if (prefixLen == 0) {
            return start
        }
        if (!WorkCounterConfig.ENABLED) {
            val window = length - start
            if (VectorScanProviders.providerFor(ScanKind.IGNORE_CASE, window) != null) {
                val result =
                    ByteVectorScan.indexOfIgnoreCase(
                        bytes,
                        offset,
                        length,
                        prefix,
                        prefixLen,
                        anchorOffset,
                        anchorLow,
                        anchorHigh,
                        start
                    )
                if (result != VectorScanProvider.Companion.UNSUPPORTED) {
                    ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                    return result
                }
            }
            val swarResult: Int =
                ByteSwarScan.Companion.indexOfIgnoreCase(
                    bytes, offset, length, prefix, prefixLen, anchorOffset, anchorLow, anchorHigh, start
                )
            if (swarResult != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.SWAR)
                return swarResult
            }
            ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.SCALAR)
        }
        return indexOfLinearIgnoreCase(bytes, offset, length, prefix, failure, start)
    }

    fun indexOfIgnoreCaseWithin(prefix: String, failure: IntArray, start: Int, maxStart: Int): Int {
        if (prefix.isEmpty()) {
            return if (start <= maxStart) start else -1
        }
        val limit = Math.min(length, maxStart + prefix.length())
        return indexOfLinearIgnoreCase(bytes, offset, limit, prefix, failure, start)
    }

    fun indexOfPairIgnoreCase(
        prefix: String,
        failure: IntArray,
        offset1: Int,
        low1: Byte,
        high1: Byte,
        offset2: Int,
        low2: Byte,
        high2: Byte,
        start: Int
    ): Int {
        val prefixLen: Int = prefix.length()
        if (prefixLen == 0) {
            return start
        }
        if (!WorkCounterConfig.ENABLED) {
            val window = length - start
            if (VectorScanProviders.providerFor(ScanKind.IGNORE_CASE, window) != null) {
                val result =
                    ByteVectorScan.indexOfPairIgnoreCase(
                        bytes, offset, length, prefix, prefixLen, offset1, low1, high1, offset2, low2,
                        high2, start
                    )
                if (result != VectorScanProvider.Companion.UNSUPPORTED) {
                    ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                    return result
                }
            }
            val swarResult: Int =
                ByteSwarScan.Companion.indexOfPairIgnoreCase(
                    bytes, offset, length, prefix, prefixLen, offset1, low1, high1, offset2, low2, high2,
                    start
                )
            if (swarResult != VectorScanProvider.Companion.UNSUPPORTED) {
                ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.SWAR)
                return swarResult
            }
            ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.SCALAR)
        }
        return indexOfLinearIgnoreCase(bytes, offset, length, prefix, failure, start)
    }

    fun indexOfAsciiClass(asciiClass: AsciiBitmap?, start: Int): Int {
        if (asciiClass == null || asciiClass.isEmpty()) {
            return -1
        }
        var first = -1
        var second = -1
        var last = -1
        var contiguous = true
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        if (first < 0) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            return indexOfAsciiClassScalar(asciiClass, start)
        }
        if (second < 0) {
            return indexOfByte(first.toByte(), start)
        }
        if (last == second) {
            return scanBytePair(length, first.toByte(), second.toByte(), start)
        }
        return if (contiguous)
            ByteSwarScan.Companion.indexOfByteRange(bytes, offset, length, first, last, start)
        else
            indexOfAsciiClassScalar(asciiClass, start)
    }

    /**
     * Boyer-Moore-Horspool with a bad-character skip table, used for inputs too short to amortize the
     * candidate filter's setup.
     * 
     * @return the index of the first match, `-1` if the literal is absent, or `-2` if the
     * work budget was exhausted before either could be established
     */
    fun boundedBoyerMooreHorspool(literal: ByteArray, shifts: IntArray, start: Int): Int {
        val last: Int = literal.length - 1
        var position = start + last
        var work: Long = 0
        val workLimit = WorkLimit.forRemaining(remaining(start))
        while (position < length) {
            // Keep two dependent skip steps under one outer backedge. C2 otherwise leaves this loop
            // scalar when it is compiled alongside the candidate-filter path, adding a backedge and
            // safepoint poll to every skip.
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        }
        return -1
    }

    private fun indexOfAsciiClassScalar(asciiClass: AsciiBitmap, start: Int): Int {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return -1
    }

    override fun decodeForward(pos: Int): Long {
        if (WorkCounterConfig.ENABLED) {
            record()
        }
        if (pos >= length) {
            return InputScanner.Companion.decoded(InputScanner.Companion.END_OF_INPUT, length)
        }
        val b1 = unsignedByteAt(pos)
        if (b1 < 0x80) {
            return InputScanner.Companion.decoded(b1, pos + 1)
        }
        if (b1 >= 0xC2 && b1 <= 0xDF && continuation(pos + 1)) {
            val codePoint = ((b1 and 0x1F) shl 6) or (unsignedByteAt(pos + 1) and 0x3F)
            return InputScanner.Companion.decoded(codePoint, pos + 2)
        }
        if (b1 >= 0xE0 && b1 <= 0xEF && validThreeByteSecond(b1, pos + 1) && continuation(pos + 2)) {
            val codePoint =
                (((b1 and 0x0F) shl 12)
                        or ((unsignedByteAt(pos + 1) and 0x3F) shl 6)
                        or (unsignedByteAt(pos + 2) and 0x3F))
            return InputScanner.Companion.decoded(codePoint, pos + 3)
        }
        if (b1 >= 0xF0 && b1 <= 0xF4 && validFourByteSecond(b1, pos + 1)
            && continuation(pos + 2)
            && continuation(pos + 3)
        ) {
            val codePoint =
                (((b1 and 0x07) shl 18)
                        or ((unsignedByteAt(pos + 1) and 0x3F) shl 12)
                        or ((unsignedByteAt(pos + 2) and 0x3F) shl 6)
                        or (unsignedByteAt(pos + 3) and 0x3F))
            return InputScanner.Companion.decoded(codePoint, pos + 4)
        }
        return InputScanner.Companion.decoded(REPLACEMENT_CHARACTER, pos + 1)
    }

    override fun decodeBackward(pos: Int): Long {
        if (WorkCounterConfig.ENABLED) {
            record()
        }
        if (pos <= 0) {
            return InputScanner.Companion.decoded(InputScanner.Companion.END_OF_INPUT, 0)
        }
        val last = unsignedByteAt(pos - 1)
        if (last < 0x80) {
            return InputScanner.Companion.decoded(last, pos - 1)
        }
        val earliest = Math.max(0, pos - 4)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return InputScanner.Companion.decoded(REPLACEMENT_CHARACTER, pos - 1)
    }

    override fun isCodePointBoundary(pos: Int): Boolean {
        if (pos < 0 || pos > length) {
            return false
        }
        if (pos == 0 || pos == length) {
            return true
        }
        val earliest = Math.max(0, pos - 3)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return true
    }

    override fun trailingLineTerminatorStart(unixLines: Boolean, logicalEndPos: Int): Int {
        if (logicalEndPos <= 0 || logicalEndPos > length) {
            return -1
        }
        val decoded = decodeBackward(logicalEndPos)
        val codePoint: Int = InputScanner.Companion.codePoint(decoded)
        val previous: Int = InputScanner.Companion.position(decoded)
        if (unixLines) {
            return if (codePoint == '\n'.code) previous else -1
        }
        if (codePoint == '\n'.code) {
            if (previous > 0) {
                val before = decodeBackward(previous)
                if (InputScanner.Companion.codePoint(before) == '\r'.code) {
                    return InputScanner.Companion.position(before)
                }
            }
            return previous
        }
        if (codePoint == '\r'.code || codePoint == '\u0085'.code || codePoint == '\u2028'.code || codePoint == '\u2029'.code) {
            return previous
        }
        return -1
    }

    override fun positionDependentThreshold(dollarAnchorEnd: Boolean, unixLines: Boolean): Int {
        if (!dollarAnchorEnd) {
            return Integer.MAX_VALUE
        }
        val trailingTerminator = trailingLineTerminatorStart(unixLines, length)
        return if (trailingTerminator >= 0) trailingTerminator else length
    }

    fun bytes(): ByteArray? {
        return bytes
    }

    fun offset(): Int {
        return offset
    }

    private fun unsignedByteAt(pos: Int): Int {
        return bytes[offset + pos].toInt() and 0xFF
    }

    private fun continuation(pos: Int): Boolean {
        return pos < length && (unsignedByteAt(pos) and 0xC0) == 0x80
    }

    private fun validThreeByteSecond(first: Int, secondPos: Int): Boolean {
        if (!continuation(secondPos)) {
            return false
        }
        val second = unsignedByteAt(secondPos)
        if (first == 0xE0) {
            return second >= 0xA0
        }
        if (first == 0xED) {
            return second <= 0x9F
        }
        return true
    }

    private fun validFourByteSecond(first: Int, secondPos: Int): Boolean {
        if (!continuation(secondPos)) {
            return false
        }
        val second = unsignedByteAt(secondPos)
        if (first == 0xF0) {
            return second >= 0x90
        }
        if (first == 0xF4) {
            return second <= 0x8F
        }
        return true
    }

    override fun indexOfCharClass(scanInfo: CharClassScanInfo, start: Int): Int {
        return indexOfCodePointClass(
            scanInfo.ranges(), scanInfo.bitmap0(), scanInfo.bitmap1(), start, length
        )
    }

    companion object {
        private const val REPLACEMENT_CHARACTER = 0xFFFD
        private const val BYTE_HIGH_BITS = -0x7f7f7f7f7f7f7f80L
        private const val BOYER_MOORE_HORSPOOL_BATCH_SIZE = 2
        private const val MULTI_RANGE_SWAR_MINIMUM_LENGTH = 64
        private val MULTI_RANGE_SWAR_SCALAR_PROLOGUE_LENGTH = java.lang.Long.BYTES
        private val VECTOR_SCALAR_PROLOGUE_LENGTH = Integer.BYTES

        private val LONG_VIEW: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())

        fun validate(bytes: ByteArray?, offset: Int, length: Int) {
            val scanner = Utf8InputScanner(bytes, offset, length)
            var position = 0
            while (position < length) {
                val decoded = scanner.decodeForward(position)
                val next: Int = InputScanner.Companion.position(decoded)
                require(
                    !(InputScanner.Companion.codePoint(decoded) == REPLACEMENT_CHARACTER && next == position + 1 && scanner.unsignedByteAt(
                        position
                    ) >= 0x80)
                ) { "Malformed UTF-8 at byte " + position }
                position = next
            }
        }

        private fun equalsFoldCase(
            input: ByteArray, inputStart: Int, suffix: ByteArray, suffixStart: Int, len: Int
        ): Boolean {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return true
        }

        private fun isAsciiPair(ranges: IntArray): Boolean {
            return ranges.length == 4 && ranges[0] == ranges[1] && ranges[2] == ranges[3]
        }

        fun isAsciiTriple(ranges: IntArray): Boolean {
            return ranges.length == 6 && ranges[0] == ranges[1] && ranges[2] == ranges[3] && ranges[4] == ranges[5]
        }

        /**
         * Returns whether the specialized ASCII triple scan should handle `ranges` over a window of
         * `remaining` bytes: either the triple kernel is available, or the general class kernel the
         * caller would otherwise reach is not.
         */
        fun useSpecializedAsciiTriple(ranges: IntArray, remaining: Int): Boolean {
            return isAsciiTriple(ranges)
                    && (VectorScanProviders.providerForPolicy(ScanKind.TRIPLE, remaining) != null
                    || VectorScanProviders.providerForPolicy(ScanKind.CLASS, remaining) == null)
        }

        fun indexOfLinear(
            bytes: ByteArray, offset: Int, length: Int, literal: ByteArray, failure: IntArray, start: Int
        ): Int {
            var matched = 0
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }

        fun indexOfLinearIgnoreCase(
            bytes: ByteArray, offset: Int, length: Int, prefix: String, failure: IntArray, start: Int
        ): Int {
            val prefixLen: Int = prefix.length()
            var matched = 0
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return -1
        }
    }
}
