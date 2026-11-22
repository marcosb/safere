// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import kotlin.math.max
import kotlin.math.min
import kotlin.jvm.JvmOverloads

internal class Utf8InputScanner(bytes: ByteArray?, offset: Int, length: Int) : ByteSwarScan(bytes, offset, length),
    InputScanner {
    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

    override fun supportsUtf8ByteSearch(): Boolean {
        return true
    }

    override fun indexOfAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        val start = max(0, fromIndex)
        val scanLen = min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            for (i in start until scanLen) {
                WorkCounter.record()
                if (unsignedByteAt(i) == ascii) {
                    return i
                }
            }
            return -1
        }
        val res = scanByte(scanLen, ascii.toByte(), start)
        return if (res >= 0 && res < limit) res else -1
    }

    override fun indexOfAsciiOrNonAscii(ascii: Int, fromIndex: Int, limit: Int): Int {
        val start = max(0, fromIndex)
        val scanLen = min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            for (position in start until scanLen) {
                WorkCounter.record()
                val value = unsignedByteAt(position)
                if (value >= 0x80 || value == ascii) {
                    return position
                }
            }
            return -1
        }
        return indexOfByteOrNonAscii(bytes, offset, scanLen, ascii.toByte(), start)
    }

    private fun scanByte(scanLen: Int, b0: Byte, start: Int): Int {
        val window = scanLen - start
        val byteProvider = VectorScanProviders.providerFor(ScanKind.BYTE, window)
        if (byteProvider != null) {
            val idx = byteProvider.indexOfByte(bytes, offset, scanLen, b0, start)
            if (idx != VectorScanProvider.UNSUPPORTED) {
                ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
        }
        ScanAudit.record(ScanKind.BYTE, ScanDirection.FORWARD, window, ScanPath.SWAR)
        return indexOfByte(bytes, offset, scanLen, b0, start)
    }

    override fun indexOfAsciiPair(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        val start = max(0, fromIndex)
        val scanLen = min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            for (i in start until scanLen) {
                WorkCounter.record()
                val value = unsignedByteAt(i)
                if (value == c1 || value == c2) {
                    return i
                }
            }
            return -1
        }
        val res = scanBytePair(scanLen, c1.toByte(), c2.toByte(), start)
        return if (res >= 0 && res < limit) res else -1
    }

    override fun indexOfAsciiPairOrNonAscii(c1: Int, c2: Int, fromIndex: Int, limit: Int): Int {
        val start = max(0, fromIndex)
        val scanLen = min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            for (position in start until scanLen) {
                WorkCounter.record()
                val value = unsignedByteAt(position)
                if (value >= 0x80 || value == c1 || value == c2) {
                    return position
                }
            }
            return -1
        }
        return indexOfBytePairOrNonAscii(
            bytes, offset, scanLen, c1.toByte(), c2.toByte(), start
        )
    }

    override fun indexOfAsciiTriple(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        val start = max(0, fromIndex)
        val scanLen = min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            for (i in start until scanLen) {
                WorkCounter.record()
                val value = unsignedByteAt(i)
                if (value == c1 || value == c2 || value == c3) {
                    return i
                }
            }
            return -1
        }
        val res = scanByteTriple(scanLen, c1.toByte(), c2.toByte(), c3.toByte(), start)
        return if (res >= 0 && res < limit) res else -1
    }

    override fun indexOfAsciiTripleOrNonAscii(c1: Int, c2: Int, c3: Int, fromIndex: Int, limit: Int): Int {
        val start = max(0, fromIndex)
        val scanLen = min(length, limit)
        if (start >= scanLen) {
            return -1
        }
        if (WorkCounterConfig.ENABLED) {
            for (position in start until scanLen) {
                WorkCounter.record()
                val value = unsignedByteAt(position)
                if (value >= 0x80 || value == c1 || value == c2 || value == c3) {
                    return position
                }
            }
            return -1
        }
        return indexOfByteTripleOrNonAscii(
            bytes, offset, scanLen, c1.toByte(), c2.toByte(), c3.toByte(), start
        )
    }

    private fun scanBytePair(scanLen: Int, b0: Byte, b1: Byte, start: Int): Int {
        val window = scanLen - start
        val pairProvider = VectorScanProviders.providerFor(ScanKind.PAIR, window)
        if (pairProvider != null) {
            val idx = pairProvider.indexOfBytePair(bytes, offset, scanLen, b0, b1, start)
            if (idx != VectorScanProvider.UNSUPPORTED) {
                ScanAudit.record(ScanKind.PAIR, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
        }
        ScanAudit.record(ScanKind.PAIR, ScanDirection.FORWARD, window, ScanPath.SWAR)
        return indexOfBytePair(bytes, offset, scanLen, b0, b1, start)
    }

    private fun scanByteTriple(scanLen: Int, b0: Byte, b1: Byte, b2: Byte, start: Int): Int {
        val window = scanLen - start
        val tripleProvider = VectorScanProviders.providerFor(ScanKind.TRIPLE, window)
        if (tripleProvider != null) {
            val idx = tripleProvider.indexOfByteTriple(bytes, offset, scanLen, b0, b1, b2, start)
            if (idx != VectorScanProvider.UNSUPPORTED) {
                ScanAudit.record(ScanKind.TRIPLE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return idx
            }
        }
        ScanAudit.record(ScanKind.TRIPLE, ScanDirection.FORWARD, window, ScanPath.SWAR)
        return indexOfByteTriple(bytes, offset, scanLen, b0, b1, b2, start)
    }

    override fun slice(start: Int, end: Int): Utf8InputScanner {
        return Utf8InputScanner(bytes, offset + start, end - start)
    }

    @JvmOverloads
    override fun startsWith(prefix: ByteArray, startPos: Int, foldCase: Boolean): Boolean {
        val prefixLen: Int = prefix.size
        if (startPos >= 0 && length - startPos >= prefixLen) {
            val start = offset + startPos
            if (if (foldCase)
                    equalsFoldCase(bytes, start, prefix, 0, prefixLen)
                else
                    ByteArrayRegions.regionEquals(bytes, start, prefix, 0, prefixLen)
            ) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record(prefixLen.toLong())
                }
                return true
            }
        }
        return false
    }

    override fun endsWith(suffix: ByteArray, wasDollar: Boolean, unixLines: Boolean, foldCase: Boolean): Boolean {
        val suffixLen: Int = suffix.size
        if (length >= suffixLen) {
            val start = offset + length - suffixLen
            if (if (foldCase)
                    equalsFoldCase(bytes, start, suffix, 0, suffixLen)
                else
                    ByteArrayRegions.regionEquals(bytes, start, suffix, 0, suffixLen)
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
                    ByteArrayRegions.regionEquals(bytes, start, suffix, 0, suffixLen)
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

    override fun indexOfCodePointClass(ranges: IntArray?, bitmap0: Long, bitmap1: Long, start: Int, limit: Int): Int {
        val rangeTable = ranges ?: return -1
        var position = max(0, start)
        val scanLen = min(length, limit)
        if (position >= scanLen) {
            return -1
        }
        if (!WorkCounterConfig.ENABLED) {
            val window = scanLen - position
            if (VectorScanProviders.providerForPolicy(
                    ScanKind.CLASS,
                    window
                ) == null && rangeTable.size >= 4 && rangeTable.size <= 8 && rangeTable[0] >= 0 && rangeTable[rangeTable.size - 1] < 0x80 && window >= MULTI_RANGE_SWAR_MINIMUM_LENGTH && !isAsciiPair(
                    rangeTable
                ) && !isAsciiTriple(rangeTable)
            ) {
                // No Vector kernel for this window, but SWAR still beats decoding code points one at a
                // time. Declining the Vector tier is not a reason to fall all the way back to scalar.
                ScanAudit.recordConsultation(ScanKind.CLASS, window)
                ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.SWAR)
                val scalarLimit = min(scanLen, position + MULTI_RANGE_SWAR_SCALAR_PROLOGUE_LENGTH)
                while (position < scalarLimit) {
                    val value = unsignedByteAt(position)
                    if ((value < 64 && (bitmap0 and (1L shl value)) != 0L)
                        || (value >= 64
                                && value < 128
                                && (bitmap1 and (1L shl (value - 64))) != 0L)
                    ) {
                        return position
                    }
                    position++
                }
                if (position >= scanLen) {
                    return -1
                }
                return indexOfMultipleByteRanges(
                    bytes, offset, scanLen, rangeTable, bitmap0, bitmap1, position
                )
            }
            val asciiResult = indexOfAsciiRanges(rangeTable, bitmap0, bitmap1, position, scanLen)
            if (asciiResult >= -1) {
                return asciiResult
            }
            if (bitmap0 == 0L && bitmap1 == 0L) {
                return indexOfNonAsciiCodePointClass(rangeTable, position, scanLen)
            }
        }
        while (position < scanLen) {
            val codePointPosition = position
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            var codePoint = asciiAt(position)
            if (codePoint >= 0) {
                position++
            } else {
                val decoded = decodeForward(position)
                codePoint = InputScanner.codePoint(decoded)
                position = InputScanner.position(decoded)
            }
            if (InputScanner.classContains(rangeTable, bitmap0, bitmap1, codePoint)) {
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
        if (ranges.size < 2 || ranges[0] < 0 || ranges[ranges.size - 1] >= 0x80) {
            return -2
        }
        when (ranges.size) {
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
            val scalarLimit = min(scanLen, position + VECTOR_SCALAR_PROLOGUE_LENGTH)
            while (position < scalarLimit) {
                if (InputScanner.classContains(ranges, bitmap0, bitmap1, unsignedByteAt(position))) {
                    ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                    return position
                }
                position++
            }
            val result = classProvider.indexOfAsciiClass(bytes, offset, scanLen, ranges, position)
            if (result != VectorScanProvider.UNSUPPORTED) {
                ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                return result
            }
        }
        if (ranges.size == 2) {
            ScanAudit.record(ScanKind.CLASS, ScanDirection.FORWARD, window, ScanPath.SWAR)
            return indexOfByteRange(bytes, offset, scanLen, ranges[0], ranges[1], start)
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
    override fun indexOfUtf8Sequence(sequence: ByteArray, start: Int, limit: Int): Int {
        val position = max(0, start)
        val scanLen = min(length, limit)
        val tail: Int = sequence.size - 1
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
            WorkCounter.record(((if (result >= 0) result + sequence.size else scanLen) - position).toLong())
        }
        return result
    }

    override fun indexOfMixedPair(ascii: Int, sequence: ByteArray, start: Int, limit: Int): Int {
        val position = max(0, start)
        val scanLen = min(length, limit)
        if (position >= scanLen) {
            return -1
        }
        val tail: Int = sequence.size - 1
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
        for (i in 0 until count) {
            if (bytes[offset + candidate + i] != sequence[i]) {
                return false
            }
        }
        return true
    }

    private fun indexOfNonAsciiCodePointClass(ranges: IntArray, start: Int, scanLen: Int): Int {
        var position = start
        val wordEnd = scanLen - ByteArrayLongView.LONG_BYTES
        while (position < scanLen) {
            if (position <= wordEnd) {
                val word = ByteArrayLongView.getNative(bytes, offset + position)
                if ((word and BYTE_HIGH_BITS) == 0L) {
                    position += ByteArrayLongView.LONG_BYTES
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
            val codePoint: Int = InputScanner.codePoint(decoded)
            position = InputScanner.position(decoded)
            if (InputScanner.classContains(ranges!!, 0, 0, codePoint)) {
                return if (codePointPosition < scanLen) codePointPosition else -1
            }
        }
        return -1
    }

    fun indexOf(literal: ByteArray, failure: IntArray, shifts: IntArray?): Int {
        return indexOf(literal, failure, shifts, 0)
    }

    override fun indexOf(literal: ByteArray, failure: IntArray, shifts: IntArray?, start: Int): Int {
        if (literal.size == 0) {
            return start
        }
        if (!WorkCounterConfig.ENABLED) {
            if (literal.size == 1) {
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
                    if (remaining(start) >= filterThreshold(literal.size))
                        indexOfFiltered(bytes, offset, length, literal, start)
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
    override fun indexOfInTail(literal: ByteArray, failure: IntArray, start: Int): Int {
        if (!WorkCounterConfig.ENABLED && literal.size > 1) {
            val result: Int = indexOfFiltered(bytes, offset, length, literal, start)
            if (result >= -1) {
                return result
            }
        }
        return indexOf(literal, failure, null, start)
    }

    override fun indexOf(literal: ByteArray, failure: IntArray, shifts: IntArray?, start: Int, rareByteOffset: Int): Int {
        if (rareByteOffset < 0 || WorkCounterConfig.ENABLED
            || remaining(start) < filterThreshold(literal.size)
        ) {
            return indexOf(literal, failure, shifts, start)
        }
        val result = indexOfRareByte(literal, rareByteOffset, start)
        return if (result >= -1) result else indexOfLinear(literal, failure, start)
    }

    private fun indexOfRareByte(literal: ByteArray, anchorOffset: Int, start: Int): Int {
        val lastStart: Int = length - literal.size
        if (start > lastStart) {
            return -1
        }
        val scanEnd = lastStart + anchorOffset + 1
        var searchFrom = start + anchorOffset
        var work: Long = 0
        val workLimit = WorkLimit.forRemaining(remaining(start))
        while (searchFrom < scanEnd) {
            val hit: Int = indexOfByte(bytes, offset, scanEnd, literal[anchorOffset], searchFrom)
            if (hit < 0) {
                return -1
            }
            val candidate = hit - anchorOffset
            if (matchesAt(bytes, offset, literal, candidate)) {
                return candidate
            }
            work += literal.size.toLong()
            if (WorkLimit.isExhausted(work, workLimit)) {
                return -2
            }
            searchFrom = hit + 1
        }
        return -1
    }

    override fun indexOfWithin(literal: ByteArray, failure: IntArray, start: Int, maxStart: Int): Int {
        if (literal.size == 0) {
            return if (start <= maxStart) start else -1
        }
        val lastStart = min(length - literal.size, maxStart)
        if (start > lastStart) {
            return -1
        }
        val limit: Int = lastStart + literal.size
        if (!WorkCounterConfig.ENABLED
            && limit - start >= filterThreshold(literal.size)
        ) {
            val result: Int = indexOfFiltered(bytes, offset, limit, literal, start)
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

    override fun indexOfIgnoreCase(
        prefix: String, failure: IntArray, anchorOffset: Int, anchorLow: Byte, anchorHigh: Byte, start: Int
    ): Int {
        val prefixLen: Int = prefix.length
        if (prefixLen == 0) {
            return start
        }
        if (!WorkCounterConfig.ENABLED) {
            val window = length - start
            val ignoreCaseProvider = VectorScanProviders.providerFor(ScanKind.IGNORE_CASE, window)
            if (ignoreCaseProvider != null) {
                val result =
                    ignoreCaseProvider.indexOfIgnoreCase(
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
                if (result != VectorScanProvider.UNSUPPORTED) {
                    ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                    return result
                }
            }
            val swarResult: Int =
                indexOfIgnoreCase(
                    bytes, offset, length, prefix, prefixLen, anchorOffset, anchorLow, anchorHigh, start
                )
            if (swarResult != VectorScanProvider.UNSUPPORTED) {
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
        val limit = min(length, maxStart + prefix.length)
        return indexOfLinearIgnoreCase(bytes, offset, limit, prefix, failure, start)
    }

    override fun indexOfPairIgnoreCase(
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
        val prefixLen: Int = prefix.length
        if (prefixLen == 0) {
            return start
        }
        if (!WorkCounterConfig.ENABLED) {
            val window = length - start
            val ignoreCaseProvider = VectorScanProviders.providerFor(ScanKind.IGNORE_CASE, window)
            if (ignoreCaseProvider != null) {
                val result =
                    ignoreCaseProvider.indexOfPairIgnoreCase(
                        bytes, offset, length, prefix, prefixLen, offset1, low1, high1, offset2, low2,
                        high2, start
                    )
                if (result != VectorScanProvider.UNSUPPORTED) {
                    ScanAudit.record(ScanKind.IGNORE_CASE, ScanDirection.FORWARD, window, ScanPath.VECTOR)
                    return result
                }
            }
            val swarResult: Int =
                indexOfPairIgnoreCase(
                    bytes, offset, length, prefix, prefixLen, offset1, low1, high1, offset2, low2, high2,
                    start
                )
            if (swarResult != VectorScanProvider.UNSUPPORTED) {
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
        for (value in 0 until 128) {
            if (asciiClass.contains(value)) {
                if (first < 0) {
                    first = value
                } else if (second < 0) {
                    second = value
                }
                contiguous = contiguous and (last < 0 || value == last + 1)
                last = value
            }
        }
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
            indexOfByteRange(bytes, offset, length, first, last, start)
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
        val last: Int = literal.size - 1
        var position = start + last
        var work: Long = 0
        val workLimit = WorkLimit.forRemaining(remaining(start))
        while (position < length) {
            // Keep two dependent skip steps under one outer backedge. C2 otherwise leaves this loop
            // scalar when it is compiled alongside the candidate-filter path, adding a backedge and
            // safepoint poll to every skip.
            for (step in 0 until BOYER_MOORE_HORSPOOL_BATCH_SIZE) {
                if (position >= length) {
                    break
                }
                var literalPosition = last
                var inputPosition = position
                while (literalPosition >= 0 && bytes[offset + inputPosition] == literal[literalPosition]) {
                    literalPosition--
                    inputPosition--
                }
                if (literalPosition < 0) {
                    return inputPosition + 1
                }
                work += (last - literalPosition + 1).toLong()
                if (WorkLimit.isExhausted(work, workLimit)) {
                    return -2
                }
                position += shifts[bytes[offset + position].toInt() and 0xFF]
            }
        }
        return -1
    }

    private fun indexOfAsciiClassScalar(asciiClass: AsciiBitmap, start: Int): Int {
        for (position in start until length) {
            if (WorkCounterConfig.ENABLED) {
                WorkCounter.record()
            }
            val value = bytes[offset + position].toInt() and 0xFF
            if (asciiClass.contains(value)) {
                return position
            }
        }
        return -1
    }

    override fun decodeForward(pos: Int): Long {
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record()
        }
        if (pos >= length) {
            return InputScanner.decoded(InputScanner.END_OF_INPUT, length)
        }
        val b1 = unsignedByteAt(pos)
        if (b1 < 0x80) {
            return InputScanner.decoded(b1, pos + 1)
        }
        if (b1 >= 0xC2 && b1 <= 0xDF && continuation(pos + 1)) {
            val codePoint = ((b1 and 0x1F) shl 6) or (unsignedByteAt(pos + 1) and 0x3F)
            return InputScanner.decoded(codePoint, pos + 2)
        }
        if (b1 >= 0xE0 && b1 <= 0xEF && validThreeByteSecond(b1, pos + 1) && continuation(pos + 2)) {
            val codePoint =
                (((b1 and 0x0F) shl 12)
                        or ((unsignedByteAt(pos + 1) and 0x3F) shl 6)
                        or (unsignedByteAt(pos + 2) and 0x3F))
            return InputScanner.decoded(codePoint, pos + 3)
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
            return InputScanner.decoded(codePoint, pos + 4)
        }
        return InputScanner.decoded(REPLACEMENT_CHARACTER, pos + 1)
    }

    override fun decodeBackward(pos: Int): Long {
        if (WorkCounterConfig.ENABLED) {
            WorkCounter.record()
        }
        if (pos <= 0) {
            return InputScanner.decoded(InputScanner.END_OF_INPUT, 0)
        }
        val last = unsignedByteAt(pos - 1)
        if (last < 0x80) {
            return InputScanner.decoded(last, pos - 1)
        }
        val earliest = max(0, pos - 4)
        for (start in pos - 2 downTo earliest) {
            val decoded = decodeForward(start)
            if (InputScanner.position(decoded) == pos) {
                return InputScanner.decoded(InputScanner.codePoint(decoded), start)
            }
        }
        return InputScanner.decoded(REPLACEMENT_CHARACTER, pos - 1)
    }

    override fun isCodePointBoundary(pos: Int): Boolean {
        if (pos < 0 || pos > length) {
            return false
        }
        if (pos == 0 || pos == length) {
            return true
        }
        val earliest = max(0, pos - 3)
        for (start in earliest until pos) {
            val decoded = decodeForward(start)
            if (InputScanner.position(decoded) > pos) {
                return false
            }
        }
        return true
    }

    override fun trailingLineTerminatorStart(unixLines: Boolean, logicalEndPos: Int): Int {
        if (logicalEndPos <= 0 || logicalEndPos > length) {
            return -1
        }
        val decoded = decodeBackward(logicalEndPos)
        val codePoint: Int = InputScanner.codePoint(decoded)
        val previous: Int = InputScanner.position(decoded)
        if (unixLines) {
            return if (codePoint == '\n'.code) previous else -1
        }
        if (codePoint == '\n'.code) {
            if (previous > 0) {
                val before = decodeBackward(previous)
                if (InputScanner.codePoint(before) == '\r'.code) {
                    return InputScanner.position(before)
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
            return Int.MAX_VALUE
        }
        val trailingTerminator = trailingLineTerminatorStart(unixLines, length)
        return if (trailingTerminator >= 0) trailingTerminator else length
    }

    override fun bytes(): ByteArray {
        return bytes
    }

    override fun offset(): Int {
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

    override fun indexOfCharClass(scanInfo: CharClassScanInfo?, start: Int): Int {
        val info = scanInfo!!
        return indexOfCodePointClass(
            info.ranges, info.bitmap0, info.bitmap1, start, length
        )
    }

    companion object {
        private const val REPLACEMENT_CHARACTER = 0xFFFD
        private const val BYTE_HIGH_BITS = -0x7f7f7f7f7f7f7f80L
        private const val BOYER_MOORE_HORSPOOL_BATCH_SIZE = 2
        private const val MULTI_RANGE_SWAR_MINIMUM_LENGTH = 64
        private val MULTI_RANGE_SWAR_SCALAR_PROLOGUE_LENGTH = ByteArrayLongView.LONG_BYTES
        private val VECTOR_SCALAR_PROLOGUE_LENGTH = 4

        fun validate(bytes: ByteArray?, offset: Int, length: Int) {
            val scanner = Utf8InputScanner(bytes, offset, length)
            var position = 0
            while (position < length) {
                val decoded = scanner.decodeForward(position)
                val next: Int = InputScanner.position(decoded)
                require(
                    !(InputScanner.codePoint(decoded) == REPLACEMENT_CHARACTER && next == position + 1 && scanner.unsignedByteAt(
                        position
                    ) >= 0x80)
                ) { "Malformed UTF-8 at byte " + position }
                position = next
            }
        }

        private fun equalsFoldCase(
            input: ByteArray, inputStart: Int, suffix: ByteArray, suffixStart: Int, len: Int
        ): Boolean {
            for (i in 0 until len) {
                val b1 = input[inputStart + i]
                val b2 = suffix[suffixStart + i]
                if (b1 != b2) {
                    val c1: Int =
                        if (b1 >= 'A'.code.toByte() && b1 <= 'Z'.code.toByte()) b1 + ('a'.code - 'A'.code) else b1.toInt()
                    val c2: Int =
                        if (b2 >= 'A'.code.toByte() && b2 <= 'Z'.code.toByte()) b2 + ('a'.code - 'A'.code) else b2.toInt()
                    if (c1 != c2) {
                        return false
                    }
                }
            }
            return true
        }

        private fun isAsciiPair(ranges: IntArray): Boolean {
            return ranges.size == 4 && ranges[0] == ranges[1] && ranges[2] == ranges[3]
        }

        fun isAsciiTriple(ranges: IntArray): Boolean {
            return ranges.size == 6 && ranges[0] == ranges[1] && ranges[2] == ranges[3] && ranges[4] == ranges[5]
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
            for (position in start until length) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val current = bytes[offset + position]
                while (matched > 0 && current != literal[matched]) {
                    matched = failure[matched - 1]
                }
                if (current == literal[matched]) {
                    matched++
                    if (matched == literal.size) {
                        return position - literal.size + 1
                    }
                }
            }
            return -1
        }

        fun indexOfLinearIgnoreCase(
            bytes: ByteArray, offset: Int, length: Int, prefix: String, failure: IntArray, start: Int
        ): Int {
            val prefixLen: Int = prefix.length
            var matched = 0
            for (position in start until length) {
                if (WorkCounterConfig.ENABLED) {
                    WorkCounter.record()
                }
                val current: Int = Ascii.toLowerCase(bytes[offset + position].toInt() and 0xFF)
                while (matched > 0 && current != prefix[matched].code) {
                    matched = failure[matched - 1]
                }
                if (current == prefix[matched].code) {
                    matched++
                    if (matched == prefixLen) {
                        return position - prefixLen + 1
                    }
                }
            }
            return -1
        }
    }
}
