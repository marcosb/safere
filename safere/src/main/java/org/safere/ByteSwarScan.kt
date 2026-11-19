// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder
import java.util.*
import kotlin.math.*
/** Shared 64-bit SWAR kernels for scanning bounded 1-byte sequences.  */
internal abstract class ByteSwarScan(bytes: ByteArray?, offset: Int, length: Int) {
    val bytes: ByteArray
    val offset: Int
    val length: Int
    init {
        this.bytes = Objects.requireNonNull<ByteArray>(bytes, "bytes")
        if (offset < 0 || length < 0 || offset > bytes!!.size - length) {
            throw IndexOutOfBoundsException(
                "offset=" + offset + ", length=" + length + ", arrayLength=" + bytes!!.size
            )
        }
        this.offset = offset
        this.length = length
    }

    fun indexOfByte(target: Byte, start: Int): Int {
        return indexOfByte(bytes, offset, length, target, start)
    }

    companion object {
        /**
         * Input sizes at which the SWAR candidate filter overtakes the skip loop. The filter always
         * advances eight positions per step, while the skip loop can advance as far as the literal
         * length, so the filter needs an input large enough to outweigh that per-step advantage. Both
         * bounds were measured; see [.indexOfFiltered].
         */
        const val MIN_FILTER_LENGTH: Int = 64

        const val FILTER_LENGTH_FACTOR: Int = 40

        private val LONG_VIEW: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.nativeOrder())

        fun filterThreshold(literalLength: Int): Long {
            return max(MIN_FILTER_LENGTH.toLong(), literalLength.toLong() * FILTER_LENGTH_FACTOR)
        }

        fun indexOfByte(bytes: ByteArray, offset: Int, length: Int, target: Byte, start: Int): Int {
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            val repeatedTarget = (target.toLong() and 0xFFL) * Swar.BYTE_ONES
            while (position <= wordEnd) {
                val difference = LONG_VIEW.get(bytes, offset + position) as Long xor repeatedTarget
                if (((difference - Swar.BYTE_ONES) and difference.inv() and Swar.BYTE_HIGH_BITS) != 0L) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        if (bytes[offset + position + index] == target) {
                            return position + index
                        }
                    }
                }
                position += java.lang.Long.BYTES
            }
            while (position < length) {
                if (bytes[offset + position] == target) {
                    return position
                }
                position++
            }
            return -1
        }

        fun indexOfByteOrNonAscii(bytes: ByteArray, offset: Int, length: Int, target: Byte, start: Int): Int {
            return indexOfBytesOrNonAscii(bytes, offset, length, target, target, target, start, 1)
        }

        fun indexOfBytePairOrNonAscii(
            bytes: ByteArray, offset: Int, length: Int, first: Byte, second: Byte, start: Int
        ): Int {
            return indexOfBytesOrNonAscii(bytes, offset, length, first, second, second, start, 2)
        }

        fun indexOfByteTripleOrNonAscii(
            bytes: ByteArray, offset: Int, length: Int, first: Byte, second: Byte, third: Byte, start: Int
        ): Int {
            return indexOfBytesOrNonAscii(bytes, offset, length, first, second, third, start, 3)
        }

        private fun indexOfBytesOrNonAscii(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            first: Byte,
            second: Byte,
            third: Byte,
            start: Int,
            targetCount: Int
        ): Int {
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            val repeatedFirst = (first.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedSecond = (second.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedThird = (third.toLong() and 0xFFL) * Swar.BYTE_ONES
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val firstDifference = word xor repeatedFirst
                var candidates = (firstDifference - Swar.BYTE_ONES) and firstDifference.inv()
                if (targetCount >= 2) {
                    val secondDifference = word xor repeatedSecond
                    candidates = candidates or ((secondDifference - Swar.BYTE_ONES) and secondDifference.inv())
                }
                if (targetCount == 3) {
                    val thirdDifference = word xor repeatedThird
                    candidates = candidates or ((thirdDifference - Swar.BYTE_ONES) and thirdDifference.inv())
                }
                if (((candidates or word) and Swar.BYTE_HIGH_BITS) != 0L) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        val value = bytes[offset + position + index]
                        if (value < 0 || value == first || (targetCount >= 2 && value == second)
                            || (targetCount == 3 && value == third)
                        ) {
                            return position + index
                        }
                    }
                }
                position += java.lang.Long.BYTES
            }
            while (position < length) {
                val value = bytes[offset + position]
                if (value < 0 || value == first || (targetCount >= 2 && value == second)
                    || (targetCount == 3 && value == third)
                ) {
                    return position
                }
                position++
            }
            return -1
        }

        fun indexOfIgnoreCase(bytes: ByteArray, offset: Int, length: Int, prefix: String, start: Int): Int {
            val prefixLen: Int = prefix.length
            if (prefixLen == 0) {
                return min(max(0, start), length)
            }
            for (i in 0..<prefixLen) {
                if (prefix[i].code > 127) {
                    return -2
                }
            }
            if (prefixLen >= 2) {
                val pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefixLen)
                if (pair != null) {
                    return indexOfPairIgnoreCase(
                        bytes,
                        offset,
                        length,
                        prefix,
                        prefixLen,
                        pair.offset1,
                        pair.low1,
                        pair.high1,
                        pair.offset2,
                        pair.low2,
                        pair.high2,
                        start
                    )
                }
            }
            val anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefixLen, true)
            val anchor: Char = prefix[anchorOffset]
            val low = Ascii.toLowerCase(anchor).code.toByte()
            val high = Ascii.toUpperCase(anchor).code.toByte()
            return indexOfIgnoreCase(
                bytes, offset, length, prefix, prefixLen, anchorOffset, low, high, start
            )
        }

        fun indexOfPairIgnoreCase(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            prefix: String,
            prefixLen: Int,
            offset1: Int,
            low1: Byte,
            high1: Byte,
            offset2: Int,
            low2: Byte,
            high2: Byte,
            start: Int
        ): Int {
            if (prefixLen == 0) {
                return min(max(0, start), length)
            }
            var pos = max(0, start)
            if (pos <= ByteScanBounds.lastMatchStart(length, prefixLen)
                && Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)
            ) {
                return pos
            }
            var verificationWork: Long = 0
            val workLimit = WorkLimit.forRemaining(length - pos)

            val maxAnchorOffset = max(offset1, offset2)
            // Both anchor word loads and the immediate full-prefix check must fit in the slice.
            val wordEnd = ByteScanBounds.lastWideScanStart(length, prefixLen, maxAnchorOffset, java.lang.Long.BYTES)
            val repeatedLow1 = (low1.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedHigh1 = (high1.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedLow2 = (low2.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedHigh2 = (high2.toLong() and 0xFFL) * Swar.BYTE_ONES
            val hasHigh1 = (low1 != high1)
            val hasHigh2 = (low2 != high2)

            while (pos <= wordEnd) {
                val c1 = bytes[offset + pos + offset1]
                if (c1 == low1 || c1 == high1) {
                    val c2 = bytes[offset + pos + offset2]
                    if (c2 == low2 || c2 == high2) {
                        if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
                            return pos
                        }
                        verificationWork += prefixLen.toLong()
                        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                            return VectorScanProvider.Companion.UNSUPPORTED
                        }
                        pos++
                        continue
                    }
                }

                val word1 = LONG_VIEW.get(bytes, offset + pos + offset1) as Long
                val lowDiff1 = word1 xor repeatedLow1
                var match1 = (lowDiff1 - Swar.BYTE_ONES) and lowDiff1.inv()
                if (hasHigh1) {
                    val highDiff1 = word1 xor repeatedHigh1
                    match1 = match1 or ((highDiff1 - Swar.BYTE_ONES) and highDiff1.inv())
                }
                val highBits1 = match1 and Swar.BYTE_HIGH_BITS
                if (highBits1 == 0L) {
                    pos += java.lang.Long.BYTES
                    continue
                }

                val word2 = LONG_VIEW.get(bytes, offset + pos + offset2) as Long
                val lowDiff2 = word2 xor repeatedLow2
                var match2 = (lowDiff2 - Swar.BYTE_ONES) and lowDiff2.inv()
                if (hasHigh2) {
                    val highDiff2 = word2 xor repeatedHigh2
                    match2 = match2 or ((highDiff2 - Swar.BYTE_ONES) and highDiff2.inv())
                }

                val candidates = highBits1 and match2
                if (candidates != 0L) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        val candidatePos = pos + index
                        if (candidatePos <= ByteScanBounds.lastMatchStart(length, prefixLen)) {
                            val v1 = bytes[offset + candidatePos + offset1]
                            val v2 = bytes[offset + candidatePos + offset2]
                            if ((v1 == low1 || v1 == high1) && (v2 == low2 || v2 == high2)) {
                                if (Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
                                    return candidatePos
                                }
                                verificationWork += prefixLen.toLong()
                                if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                                    return VectorScanProvider.Companion.UNSUPPORTED
                                }
                            }
                        }
                    }
                }
                pos += java.lang.Long.BYTES
            }

            val limitScalar = ByteScanBounds.lastMatchStart(length, prefixLen)
            while (pos <= limitScalar) {
                val v1 = bytes[offset + pos + offset1]
                val v2 = bytes[offset + pos + offset2]
                if ((v1 == low1 || v1 == high1) && (v2 == low2 || v2 == high2)) {
                    if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
                        return pos
                    }
                    verificationWork += prefixLen.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        return VectorScanProvider.Companion.UNSUPPORTED
                    }
                }
                pos++
            }
            return -1
        }

        fun indexOfIgnoreCase(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            prefix: String,
            prefixLen: Int,
            anchorOffset: Int,
            low: Byte,
            high: Byte,
            start: Int
        ): Int {
            if (prefixLen == 0) {
                return min(max(0, start), length)
            }
            var pos = max(0, start)
            if (pos <= ByteScanBounds.lastMatchStart(length, prefixLen)
                && Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)
            ) {
                return pos
            }
            var verificationWork: Long = 0
            val workLimit = WorkLimit.forRemaining(length - pos)

            // Preserve a full match for every lane by bounding a load at the final prefix byte.
            val wordEnd = ByteScanBounds.lastLoadStart(length, prefixLen - 1, java.lang.Long.BYTES)
            val repeatedLow = (low.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedHigh = (high.toLong() and 0xFFL) * Swar.BYTE_ONES

            while (pos <= wordEnd) {
                // Tier 0: Immediate anchor check in 1 CPU cycle
                val currentAnchor = bytes[offset + pos + anchorOffset]
                if (currentAnchor == low || currentAnchor == high) {
                    if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
                        return pos
                    }
                    verificationWork += prefixLen.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        return VectorScanProvider.Companion.UNSUPPORTED
                    }
                    pos++
                    continue
                }

                val word = LONG_VIEW.get(bytes, offset + pos + anchorOffset) as Long
                val lowDiff = word xor repeatedLow
                val highDiff = word xor repeatedHigh
                if (((((lowDiff - Swar.BYTE_ONES) and lowDiff.inv()) or ((highDiff - Swar.BYTE_ONES) and highDiff.inv()))
                            and Swar.BYTE_HIGH_BITS)
                    != 0L
                ) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        val value = bytes[offset + pos + anchorOffset + index]
                        if (value == low || value == high) {
                            val candidatePos = pos + index
                            if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
                                if (Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
                                    return candidatePos
                                }
                                verificationWork += prefixLen.toLong()
                                if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                                    return VectorScanProvider.Companion.UNSUPPORTED
                                }
                            }
                        }
                    }
                }
                pos += java.lang.Long.BYTES
            }

            val limitScalar = ByteScanBounds.lastMatchStart(length, prefixLen)
            while (pos <= limitScalar) {
                val value = bytes[offset + pos + anchorOffset]
                if (value == low || value == high) {
                    if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
                        return pos
                    }
                    verificationWork += prefixLen.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        return VectorScanProvider.Companion.UNSUPPORTED
                    }
                }
                pos++
            }
            return -1
        }

        fun indexOfBytePair(
            bytes: ByteArray, offset: Int, length: Int, first: Byte, second: Byte, start: Int
        ): Int {
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            val repeatedFirst = (first.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedSecond = (second.toLong() and 0xFFL) * Swar.BYTE_ONES
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val firstDifference = word xor repeatedFirst
                val secondDifference = word xor repeatedSecond
                if (((((firstDifference - Swar.BYTE_ONES) and firstDifference.inv())
                            or ((secondDifference - Swar.BYTE_ONES) and secondDifference.inv()))
                            and Swar.BYTE_HIGH_BITS)
                    != 0L
                ) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        val value = bytes[offset + position + index]
                        if (value == first || value == second) {
                            return position + index
                        }
                    }
                }
                position += java.lang.Long.BYTES
            }
            while (position < length) {
                val value = bytes[offset + position]
                if (value == first || value == second) {
                    return position
                }
                position++
            }
            return -1
        }

        fun indexOfByteTriple(
            bytes: ByteArray, offset: Int, length: Int, first: Byte, second: Byte, third: Byte, start: Int
        ): Int {
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            val repeatedFirst = (first.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedSecond = (second.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedThird = (third.toLong() and 0xFFL) * Swar.BYTE_ONES
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val firstDifference = word xor repeatedFirst
                val secondDifference = word xor repeatedSecond
                val thirdDifference = word xor repeatedThird
                if (((((firstDifference - Swar.BYTE_ONES) and firstDifference.inv())
                            or ((secondDifference - Swar.BYTE_ONES) and secondDifference.inv())
                            or ((thirdDifference - Swar.BYTE_ONES) and thirdDifference.inv()))
                            and Swar.BYTE_HIGH_BITS)
                    != 0L
                ) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        val value = bytes[offset + position + index]
                        if (value == first || value == second || value == third) {
                            return position + index
                        }
                    }
                }
                position += java.lang.Long.BYTES
            }
            while (position < length) {
                val value = bytes[offset + position]
                if (value == first || value == second || value == third) {
                    return position
                }
                position++
            }
            return -1
        }

        /**
         * Searches for a byte in the inclusive ASCII range `[low, high]`.
         * 
         * 
         * The word filter compares the common binary prefix of the range endpoints across eight bytes
         * at once. Every byte in the range has that prefix, so the filter cannot miss a match. It may
         * admit bytes outside the range when the endpoints do not cover their entire binary-prefix
         * bucket; the scalar candidate check provides the exact answer.
         */
        fun indexOfByteRange(bytes: ByteArray, offset: Int, length: Int, low: Int, high: Int, start: Int): Int {
            val differingBit = Integer.highestOneBit(low xor high)
            val commonMask = ((differingBit shl 1) - 1).inv() and 0xFF
            val repeatedMask = (commonMask.toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedPrefix = (low and commonMask) * Swar.BYTE_ONES
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val difference = (word and repeatedMask) xor repeatedPrefix
                if (((difference - Swar.BYTE_ONES) and difference.inv() and Swar.BYTE_HIGH_BITS) != 0L) {
                    for (index in 0..<java.lang.Long.BYTES) {
                        val value = bytes[offset + position + index].toInt() and 0xFF
                        if (value >= low && value <= high) {
                            return position + index
                        }
                    }
                }
                position += java.lang.Long.BYTES
            }
            while (position < length) {
                val value = bytes[offset + position].toInt() and 0xFF
                if (value >= low && value <= high) {
                    return position
                }
                position++
            }
            return -1
        }

        /**
         * Searches for a multi-byte `literal` by locating candidate positions with a SWAR filter on
         * the literal's first and last bytes, then verifying each candidate in full.
         * 
         * 
         * Two words are loaded per step, one aligned with the literal's first byte and one with its
         * last byte. XOR-ing each against the corresponding broadcast byte turns matching positions into
         * zero bytes, so the standard zero-byte test identifies positions where both the first and last
         * byte agree. Requiring both bytes makes candidates far rarer than a single-byte filter would.
         * 
         * 
         * This examines eight positions per step with no data-dependent branching. A skip loop such as
         * Boyer-Moore-Horspool can advance further per step, but each of its steps is a serialized load,
         * table lookup, and add, which costs more than the wider branch-free step here.
         * 
         * 
         * The zero-byte test never misses a matching position, but it can flag a position that does
         * not match, so every candidate is verified against the whole literal rather than trusting the
         * filter for the first and last byte.
         * 
         * 
         * Verification is O(literal length) per candidate, so an adversarial input can drive this to
         * O(input length * literal length). A work budget bounds that: on exhaustion this returns `-2` and the caller falls back to linear-time KMP.
         * 
         * @return the index of the first match, `-1` if the literal is absent, or `-2` if the
         * work budget was exhausted before either could be established
         */
        fun indexOfFiltered(bytes: ByteArray, offset: Int, length: Int, literal: ByteArray, start: Int): Int {
            val last: Int = literal.size - 1
            val repeatedFirst = (literal[0].toLong() and 0xFFL) * Swar.BYTE_ONES
            val repeatedLast = (literal[last].toLong() and 0xFFL) * Swar.BYTE_ONES
            val wordEnd = ByteScanBounds.lastLoadStart(length, last, java.lang.Long.BYTES)
            var work: Long = 0
            val workLimit = WorkLimit.forRemaining(length - start)
            var position = start
            while (position <= wordEnd) {
                val firstDifference = LONG_VIEW.get(bytes, offset + position) as Long xor repeatedFirst
                val lastDifference = LONG_VIEW.get(bytes, offset + position + last) as Long xor repeatedLast
                val candidates =
                    ((firstDifference - Swar.BYTE_ONES)
                            and firstDifference.inv()
                            and (lastDifference - Swar.BYTE_ONES)
                            and lastDifference.inv()
                            and Swar.BYTE_HIGH_BITS)
                if (candidates != 0L) {
                    // Scanning the eight positions in address order keeps this independent of byte order and
                    // returns the leftmost match within the word.
                    var candidateCount = 0
                    for (index in 0..<java.lang.Long.BYTES) {
                        val candidate = position + index
                        if (bytes[offset + candidate] == literal[0]) {
                            if (matchesAt(bytes, offset, literal, candidate)) {
                                return candidate
                            }
                            candidateCount++
                        }
                    }
                    work = WorkLimit.addCandidateWork(work, candidateCount, literal.size)
                }
                position += java.lang.Long.BYTES
                work++
                if (WorkLimit.isExhausted(work, workLimit)) {
                    return -2
                }
            }
            return scalarTail(bytes, offset, length, literal, position)
        }

        /**
         * Scans the trailing bytes that the word loop could not cover (fewer than [Long.BYTES] plus
         * the literal length). Kept out of [.indexOfFiltered] so the hot word loop stays within
         * C2's inlining budget; merging this loop into it measurably deoptimizes the SWAR loop.
         */
        private fun scalarTail(
            bytes: ByteArray, offset: Int, length: Int, literal: ByteArray, position: Int
        ): Int {
            var position = position
            while (position <= ByteScanBounds.lastMatchStart(length, literal.size)) {
                if (matchesAt(bytes, offset, literal, position)) {
                    return position
                }
                position++
            }
            return -1
        }

        fun matchesAt(bytes: ByteArray, offset: Int, literal: ByteArray, position: Int): Boolean {
            for (index in literal.indices) {
                if (bytes[offset + position + index] != literal[index]) {
                    return false
                }
            }
            return true
        }

        fun exactAsciiRangeMask(word: Long, low: Int, high: Int): Long {
            val values = word and Swar.BYTE_HIGH_BITS.inv()
            val ascii = word.inv() and Swar.BYTE_HIGH_BITS
            return Swar.exactAsciiRangeMask(values, ascii, low * Swar.BYTE_ONES, high * Swar.BYTE_ONES)
        }

        fun indexOfMultipleByteRanges(
            bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, bitmap0: Long, bitmap1: Long, start: Int
        ): Int {
            return when (ranges.size) {
                4 -> indexOfTwoByteRanges(bytes, offset, length, ranges, bitmap0, bitmap1, start)
                6 -> indexOfThreeByteRanges(bytes, offset, length, ranges, bitmap0, bitmap1, start)
                8 -> indexOfFourByteRanges(bytes, offset, length, ranges, bitmap0, bitmap1, start)
                else -> throw AssertionError("unexpected range count")
            }
        }

        private fun indexOfTwoByteRanges(
            bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, bitmap0: Long, bitmap1: Long, start: Int
        ): Int {
            val low0 = ranges[0] * Swar.BYTE_ONES
            val high0 = ranges[1] * Swar.BYTE_ONES
            val low1 = ranges[2] * Swar.BYTE_ONES
            val high1 = ranges[3] * Swar.BYTE_ONES
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val values = word and Swar.BYTE_HIGH_BITS.inv()
                val ascii = word.inv() and Swar.BYTE_HIGH_BITS
                var matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0)
                matches = matches or Swar.exactAsciiRangeMask(values, ascii, low1, high1)
                if (matches != 0L) {
                    return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, position + java.lang.Long.BYTES)
                }
                position += java.lang.Long.BYTES
            }
            return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, length)
        }

        private fun indexOfThreeByteRanges(
            bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, bitmap0: Long, bitmap1: Long, start: Int
        ): Int {
            val low0 = ranges[0] * Swar.BYTE_ONES
            val high0 = ranges[1] * Swar.BYTE_ONES
            val low1 = ranges[2] * Swar.BYTE_ONES
            val high1 = ranges[3] * Swar.BYTE_ONES
            val low2 = ranges[4] * Swar.BYTE_ONES
            val high2 = ranges[5] * Swar.BYTE_ONES
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val values = word and Swar.BYTE_HIGH_BITS.inv()
                val ascii = word.inv() and Swar.BYTE_HIGH_BITS
                var matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0)
                matches = matches or Swar.exactAsciiRangeMask(values, ascii, low1, high1)
                matches = matches or Swar.exactAsciiRangeMask(values, ascii, low2, high2)
                if (matches != 0L) {
                    return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, position + java.lang.Long.BYTES)
                }
                position += java.lang.Long.BYTES
            }
            return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, length)
        }

        private fun indexOfFourByteRanges(
            bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, bitmap0: Long, bitmap1: Long, start: Int
        ): Int {
            val low0 = ranges[0] * Swar.BYTE_ONES
            val high0 = ranges[1] * Swar.BYTE_ONES
            val low1 = ranges[2] * Swar.BYTE_ONES
            val high1 = ranges[3] * Swar.BYTE_ONES
            val low2 = ranges[4] * Swar.BYTE_ONES
            val high2 = ranges[5] * Swar.BYTE_ONES
            val low3 = ranges[6] * Swar.BYTE_ONES
            val high3 = ranges[7] * Swar.BYTE_ONES
            var position = start
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)
            while (position <= wordEnd) {
                val word = LONG_VIEW.get(bytes, offset + position) as Long
                val values = word and Swar.BYTE_HIGH_BITS.inv()
                val ascii = word.inv() and Swar.BYTE_HIGH_BITS
                var matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0)
                matches = matches or Swar.exactAsciiRangeMask(values, ascii, low1, high1)
                matches = matches or Swar.exactAsciiRangeMask(values, ascii, low2, high2)
                matches = matches or Swar.exactAsciiRangeMask(values, ascii, low3, high3)
                if (matches != 0L) {
                    return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, position + java.lang.Long.BYTES)
                }
                position += java.lang.Long.BYTES
            }
            return scalarRangeCheck(bytes, offset, bitmap0, bitmap1, position, length)
        }

        fun indexOfAsciiClass(bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int): Int {
            if (!Swar.supportsAsciiRanges(ranges, 2)) {
                return VectorScanProvider.Companion.UNSUPPORTED
            }
            val numRanges: Int = ranges.size / 2

            var pos = max(0, start)
            val wordEnd = ByteScanBounds.lastLoadStart(length, 0, java.lang.Long.BYTES)

            val low0 = (ranges[0].toLong() and 0xFFL) * Swar.BYTE_ONES
            val high0 = (ranges[1].toLong() and 0xFFL) * Swar.BYTE_ONES
            val low1 = if (numRanges > 1) (ranges[2].toLong() and 0xFFL) * Swar.BYTE_ONES else 0
            val high1 = if (numRanges > 1) (ranges[3].toLong() and 0xFFL) * Swar.BYTE_ONES else 0

            if (numRanges == 1) {
                while (pos <= wordEnd) {
                    val word = LONG_VIEW.get(bytes, offset + pos) as Long
                    val values = word and Swar.BYTE_HIGH_BITS.inv()
                    val ascii = word.inv() and Swar.BYTE_HIGH_BITS
                    val matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0)

                    if (matches != 0L) {
                        val limit = pos + java.lang.Long.BYTES
                        for (i in pos..<limit) {
                            val b = bytes[offset + i].toInt() and 0xFF
                            if (b >= ranges[0] && b <= ranges[1]) {
                                return i
                            }
                        }
                    }
                    pos += java.lang.Long.BYTES
                }
            } else {
                while (pos <= wordEnd) {
                    val word = LONG_VIEW.get(bytes, offset + pos) as Long
                    val values = word and Swar.BYTE_HIGH_BITS.inv()
                    val ascii = word.inv() and Swar.BYTE_HIGH_BITS
                    val matches =
                        (Swar.exactAsciiRangeMask(values, ascii, low0, high0)
                                or Swar.exactAsciiRangeMask(values, ascii, low1, high1))

                    if (matches != 0L) {
                        val limit = pos + java.lang.Long.BYTES
                        for (i in pos..<limit) {
                            val b = bytes[offset + i].toInt() and 0xFF
                            if ((b >= ranges[0] && b <= ranges[1]) || (b >= ranges[2] && b <= ranges[3])) {
                                return i
                            }
                        }
                    }
                    pos += java.lang.Long.BYTES
                }
            }

            while (pos < length) {
                val b = bytes[offset + pos].toInt() and 0xFF
                for (r in 0..<numRanges) {
                    if (b >= ranges[r * 2] && b <= ranges[r * 2 + 1]) {
                        return pos
                    }
                }
                pos++
            }
            return -1
        }

        private fun scalarRangeCheck(
            bytes: ByteArray, offset: Int, bitmap0: Long, bitmap1: Long, position: Int, limit: Int
        ): Int {
            var position = position
            while (position < limit) {
                val value = bytes[offset + position].toInt() and 0xFF
                if ((value < java.lang.Long.SIZE && (bitmap0 and (1L shl value)) != 0L)
                    || (value >= java.lang.Long.SIZE && value < 128 && (bitmap1 and (1L shl (value - java.lang.Long.SIZE))) != 0L)
                ) {
                    return position
                }
                position++
            }
            return -1
        }
    }
}
