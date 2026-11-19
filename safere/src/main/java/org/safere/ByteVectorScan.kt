// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies

/**
 * Stateless SIMD kernels using the incubating Vector API for 1-byte sequences (UTF-8 and Latin-1).
 */
internal object ByteVectorScan {
    private val SPECIES: VectorSpecies<Byte?> = ByteVector.SPECIES_PREFERRED

    fun indexOfAsciiClass(bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int): Int {
        return indexOfAsciiClass(SPECIES, bytes, offset, length, ranges, start)
    }

    @JvmStatic
    fun indexOfAsciiClass(
        species: VectorSpecies<Byte?>, bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int
    ): Int {
        if (!Swar.supportsAsciiRanges(ranges, 4)) {
            return VectorScanProvider.Companion.UNSUPPORTED
        }
        var position = Math.max(0, start)
        val limit = position + species.loopBound(length - position)
        while (position < limit) {
            val values = ByteVector.fromArray(species, bytes, offset + position)
            val matches = matches(values, ranges)
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += species.length()
        }
        while (position < length) {
            if (matches(bytes[offset + position], ranges)) {
                return position
            }
            position++
        }
        return -1
    }

    fun indexOfByte(bytes: ByteArray, offset: Int, length: Int, target: Byte, start: Int): Int {
        return indexOfByte(SPECIES, bytes, offset, length, target, start)
    }

    fun indexOfByte(
        species: VectorSpecies<Byte?>, bytes: ByteArray, offset: Int, length: Int, target: Byte, start: Int
    ): Int {
        var position = Math.max(0, start)
        val limit = position + species.loopBound(length - position)
        val targetVec = ByteVector.broadcast(species, target)
        while (position < limit) {
            val values = ByteVector.fromArray(species, bytes, offset + position)
            val matches = values.compare(VectorOperators.EQ, targetVec)
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += species.length()
        }
        while (position < length) {
            if (bytes[offset + position] == target) {
                return position
            }
            position++
        }
        return -1
    }

    fun indexOfBytePair(bytes: ByteArray, offset: Int, length: Int, b0: Byte, b1: Byte, start: Int): Int {
        var position = Math.max(0, start)
        val limit = position + SPECIES.loopBound(length - position)
        val v0 = ByteVector.broadcast(SPECIES, b0)
        val v1 = ByteVector.broadcast(SPECIES, b1)
        while (position < limit) {
            val values = ByteVector.fromArray(SPECIES, bytes, offset + position)
            val matches = values.compare(VectorOperators.EQ, v0).or(values.compare(VectorOperators.EQ, v1))
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += SPECIES.length()
        }
        while (position < length) {
            val `val` = bytes[offset + position]
            if (`val` == b0 || `val` == b1) {
                return position
            }
            position++
        }
        return -1
    }

    fun indexOfByteTriple(
        bytes: ByteArray, offset: Int, length: Int, b0: Byte, b1: Byte, b2: Byte, start: Int
    ): Int {
        var position = Math.max(0, start)
        val limit = position + SPECIES.loopBound(length - position)
        val v0 = ByteVector.broadcast(SPECIES, b0)
        val v1 = ByteVector.broadcast(SPECIES, b1)
        val v2 = ByteVector.broadcast(SPECIES, b2)
        while (position < limit) {
            val values = ByteVector.fromArray(SPECIES, bytes, offset + position)
            val matches =
                values.compare(VectorOperators.EQ, v0).or(values.compare(VectorOperators.EQ, v1))
                    .or(values.compare(VectorOperators.EQ, v2))
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += SPECIES.length()
        }
        while (position < length) {
            val `val` = bytes[offset + position]
            if (`val` == b0 || `val` == b1 || `val` == b2) {
                return position
            }
            position++
        }
        return -1
    }

    private fun matches(values: ByteVector, ranges: IntArray): VectorMask<Byte?> {
        var matches = matches(values, ranges[0], ranges[1])
        if (ranges.size >= 4) {
            matches = matches.or(matches(values, ranges[2], ranges[3]))
        }
        if (ranges.size >= 6) {
            matches = matches.or(matches(values, ranges[4], ranges[5]))
        }
        if (ranges.size == 8) {
            matches = matches.or(matches(values, ranges[6], ranges[7]))
        }
        return matches
    }

    private fun matches(values: ByteVector, lowBound: Int, highBound: Int): VectorMask<Byte?> {
        val low = lowBound.toByte()
        val high = highBound.toByte()
        if (low == high) {
            return values.eq(low)
        }
        if (highBound == lowBound + 1) {
            return values.eq(low).or(values.eq(high))
        }
        return values.compare(VectorOperators.GE, low).and(values.compare(VectorOperators.LE, high))
    }

    private fun matches(value: Byte, ranges: IntArray): Boolean {
        var index = 0
        while (index < ranges.size) {
            if (value >= ranges[index].toByte() && value <= ranges[index + 1].toByte()) {
                return true
            }
            index += 2
        }
        return false
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
        return indexOfIgnoreCase(
            SPECIES, bytes, offset, length, prefix, prefixLen, anchorOffset, low, high, start
        )
    }

    @JvmStatic
    fun indexOfIgnoreCase(
        species: VectorSpecies<Byte?>,
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
            return Math.min(Math.max(0, start), length)
        }
        var pos = Math.max(0, start)
        var verificationWork: Long = 0
        val workLimit = WorkLimit.forRemaining(length - pos)

        // Fast scalar prologue to catch immediate matches without SIMD setup
        val scalarPrologueLimit = Math.min(ByteScanBounds.lastMatchStart(length, prefixLen) + 1, pos + Integer.BYTES)
        while (pos < scalarPrologueLimit) {
            val b = bytes[offset + pos + anchorOffset].toInt() and 0xFF
            if ((b == (low.toInt() and 0xFF) || b == (high.toInt() and 0xFF))
                && Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)
            ) {
                return pos
            }
            if (b == (low.toInt() and 0xFF) || b == (high.toInt() and 0xFF)) {
                verificationWork += prefixLen.toLong()
                if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                    return VectorScanProvider.Companion.UNSUPPORTED
                }
            }
            pos++
        }

        val vectorLen = species.length()
        val limit = ByteScanBounds.lastLoadStart(length, 0, vectorLen)
        if (pos > limit) {
            val limitScalar = ByteScanBounds.lastMatchStart(length, prefixLen)
            for (p in Math.max(start, pos - anchorOffset)..limitScalar) {
                val b = bytes[offset + p + anchorOffset].toInt() and 0xFF
                if (b != (low.toInt() and 0xFF) && b != (high.toInt() and 0xFF)) {
                    continue
                }
                if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
                    return p
                }
                verificationWork += prefixLen.toLong()
                if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                    return VectorScanProvider.Companion.UNSUPPORTED
                }
            }
            return -1
        }

        val lowVec = ByteVector.broadcast(species, low)
        val highVec = ByteVector.broadcast(species, high)

        while (pos <= limit) {
            val inputVec = ByteVector.fromArray(species, bytes, offset + pos)
            val matchMask =
                inputVec.compare(VectorOperators.EQ, lowVec).or(inputVec.compare(VectorOperators.EQ, highVec))

            if (matchMask.anyTrue()) {
                var activeLanes = matchMask.toLong()
                while (activeLanes != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
                    val candidatePos = pos + bit - anchorOffset
                    if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
                        && Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)
                    ) {
                        return candidatePos
                    }
                    if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
                        verificationWork += prefixLen.toLong()
                        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                            return VectorScanProvider.Companion.UNSUPPORTED
                        }
                    }
                    activeLanes = activeLanes and activeLanes - 1
                }
            }
            pos += vectorLen
        }

        val limitScalar = ByteScanBounds.lastMatchStart(length, prefixLen)
        for (p in Math.max(start, pos - anchorOffset)..limitScalar) {
            val b = bytes[offset + p + anchorOffset].toInt() and 0xFF
            if (b != (low.toInt() and 0xFF) && b != (high.toInt() and 0xFF)) {
                continue
            }
            if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
                return p
            }
            verificationWork += prefixLen.toLong()
            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                return VectorScanProvider.Companion.UNSUPPORTED
            }
        }
        return -1
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
            return Math.min(Math.max(0, start), length)
        }
        var pos = Math.max(0, start)
        var verificationWork: Long = 0
        val workLimit = WorkLimit.forRemaining(length - pos)

        val maxAnchorOffset = Math.max(offset1, offset2)
        val scalarPrologueLimit = Math.min(ByteScanBounds.lastMatchStart(length, prefixLen) + 1, pos + Integer.BYTES)
        while (pos < scalarPrologueLimit) {
            val b1 = bytes[offset + pos + offset1].toInt() and 0xFF
            if ((b1 == (low1.toInt() and 0xFF) || b1 == (high1.toInt() and 0xFF))) {
                val b2 = bytes[offset + pos + offset2].toInt() and 0xFF
                if ((b2 == (low2.toInt() and 0xFF) || b2 == (high2.toInt() and 0xFF))) {
                    if (Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
                        return pos
                    }
                    verificationWork += prefixLen.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        return VectorScanProvider.Companion.UNSUPPORTED
                    }
                }
            }
            pos++
        }

        val vectorLen = SPECIES.length()
        val limit = ByteScanBounds.lastLoadStart(length, maxAnchorOffset, vectorLen)
        if (pos > limit) {
            val limitScalar = ByteScanBounds.lastMatchStart(length, prefixLen)
            for (p in pos..limitScalar) {
                val b1 = bytes[offset + p + offset1].toInt() and 0xFF
                if (b1 == (low1.toInt() and 0xFF) || b1 == (high1.toInt() and 0xFF)) {
                    val b2 = bytes[offset + p + offset2].toInt() and 0xFF
                    if (b2 == (low2.toInt() and 0xFF) || b2 == (high2.toInt() and 0xFF)) {
                        if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
                            return p
                        }
                        verificationWork += prefixLen.toLong()
                        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                            return VectorScanProvider.Companion.UNSUPPORTED
                        }
                    }
                }
            }
            return -1
        }

        val lowVec1 = ByteVector.broadcast(SPECIES, low1)
        val highVec1 = ByteVector.broadcast(SPECIES, high1)
        val lowVec2 = ByteVector.broadcast(SPECIES, low2)
        val highVec2 = ByteVector.broadcast(SPECIES, high2)
        val baseOffset1 = offset + offset1
        val baseOffset2 = offset + offset2
        val hasHigh1 = (low1 != high1)
        val hasHigh2 = (low2 != high2)

        if (hasHigh1 && hasHigh2) {
            while (pos <= limit) {
                val inputVec1 = ByteVector.fromArray(SPECIES, bytes, baseOffset1 + pos)
                val mask1 =
                    inputVec1.compare(VectorOperators.EQ, lowVec1).or(inputVec1.compare(VectorOperators.EQ, highVec1))
                if (!mask1.anyTrue()) {
                    pos += vectorLen
                    continue
                }
                val inputVec2 = ByteVector.fromArray(SPECIES, bytes, baseOffset2 + pos)
                val mask2 =
                    inputVec2.compare(VectorOperators.EQ, lowVec2).or(inputVec2.compare(VectorOperators.EQ, highVec2))
                val matchMask = mask1.and(mask2)

                if (matchMask.anyTrue()) {
                    var activeLanes = matchMask.toLong()
                    while (activeLanes != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
                        val candidatePos = pos + bit
                        if (candidatePos <= ByteScanBounds.lastMatchStart(length, prefixLen)) {
                            if (Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
                                return candidatePos
                            }
                            verificationWork += prefixLen.toLong()
                            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                                return VectorScanProvider.Companion.UNSUPPORTED
                            }
                        }
                        activeLanes = activeLanes and activeLanes - 1
                    }
                }
                pos += vectorLen
            }
        } else {
            while (pos <= limit) {
                val inputVec1 = ByteVector.fromArray(SPECIES, bytes, baseOffset1 + pos)
                val mask1 =
                    if (hasHigh1)
                        inputVec1.compare(VectorOperators.EQ, lowVec1)
                            .or(inputVec1.compare(VectorOperators.EQ, highVec1))
                    else
                        inputVec1.compare(VectorOperators.EQ, lowVec1)
                if (!mask1.anyTrue()) {
                    pos += vectorLen
                    continue
                }
                val inputVec2 = ByteVector.fromArray(SPECIES, bytes, baseOffset2 + pos)
                val mask2 =
                    if (hasHigh2)
                        inputVec2.compare(VectorOperators.EQ, lowVec2)
                            .or(inputVec2.compare(VectorOperators.EQ, highVec2))
                    else
                        inputVec2.compare(VectorOperators.EQ, lowVec2)
                val matchMask = mask1.and(mask2)

                if (matchMask.anyTrue()) {
                    var activeLanes = matchMask.toLong()
                    while (activeLanes != 0L) {
                        val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
                        val candidatePos = pos + bit
                        if (candidatePos <= ByteScanBounds.lastMatchStart(length, prefixLen)) {
                            if (Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
                                return candidatePos
                            }
                            verificationWork += prefixLen.toLong()
                            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                                return VectorScanProvider.Companion.UNSUPPORTED
                            }
                        }
                        activeLanes = activeLanes and activeLanes - 1
                    }
                }
                pos += vectorLen
            }
        }

        val limitScalar = ByteScanBounds.lastMatchStart(length, prefixLen)
        for (p in pos..limitScalar) {
            val b1 = bytes[offset + p + offset1].toInt() and 0xFF
            if (b1 == (low1.toInt() and 0xFF) || b1 == (high1.toInt() and 0xFF)) {
                val b2 = bytes[offset + p + offset2].toInt() and 0xFF
                if (b2 == (low2.toInt() and 0xFF) || b2 == (high2.toInt() and 0xFF)) {
                    if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
                        return p
                    }
                    verificationWork += prefixLen.toLong()
                    if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                        return VectorScanProvider.Companion.UNSUPPORTED
                    }
                }
            }
        }
        return -1
    }

    fun indexOfMultiLiteral(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        literals: Array<String>,
        anchorChars: CharArray,
        anchorOffsets: IntArray,
        anchorRanges: IntArray,
        minLength: Int,
        start: Int
    ): Int {
        return indexOfMultiLiteral(
            bytes,
            offset,
            length,
            literals,
            anchorChars,
            anchorOffsets,
            anchorRanges,
            minLength,
            null,
            start
        )
    }

    fun indexOfMultiLiteral(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        literals: Array<String>,
        anchorChars: CharArray,
        anchorOffsets: IntArray,
        anchorRanges: IntArray,
        minLength: Int,
        teddyModel: TeddyModel?,
        start: Int
    ): Int {
        val numLits: Int = literals.size
        if (numLits == 0 || length < minLength) {
            return -1
        }
        var pos = Math.max(0, start)
        var verificationWork: Long = 0
        val workLimit = WorkLimit.forRemaining(length - pos)
        var observedBytes = 0
        var estimatedVerificationBytes: Long = 0
        val vectorLen = SPECIES.length()
        val limit = ByteScanBounds.lastLoadStart(length, 0, vectorLen)

        while (pos <= limit) {
            val inputVec = ByteVector.fromArray(SPECIES, bytes, offset + pos)
            val matchMask = matches(inputVec, anchorRanges)

            if (teddyModel != null && MultiLiteralSelectionPolicy.shouldObserve(observedBytes)) {
                observedBytes += vectorLen
                estimatedVerificationBytes += matchMask.trueCount().toLong() * minLength
                if (MultiLiteralSelectionPolicy.prefersTeddy(estimatedVerificationBytes, observedBytes)) {
                    return TeddyVectorScan.indexOfTeddyUtf8(bytes, offset, length, teddyModel, pos)
                }
            }

            if (matchMask.anyTrue()) {
                var activeLanes = matchMask.toLong()
                while (activeLanes != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
                    val matchIndex = pos + bit
                    for (i in 0..<numLits) {
                        val candidatePos = matchIndex - anchorOffsets[i]
                        val lit = literals[i]
                        if (candidatePos >= start && candidatePos + lit.length <= length && (bytes[offset + matchIndex].toInt() and 0xFF) == (anchorChars[i].code and 0xFF)) {
                            if (WorkCounterConfig.ENABLED) {
                                WorkCounter.record(lit.length.toLong())
                            }
                            if (Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length)) {
                                return candidatePos
                            }
                            verificationWork += lit.length.toLong()
                            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                                return VectorScanProvider.Companion.UNSUPPORTED
                            }
                        }
                    }
                    activeLanes = activeLanes and activeLanes - 1
                }
            }
            pos += vectorLen
        }

        val scalarLimit = ByteScanBounds.lastMatchStart(length, minLength)
        while (pos <= scalarLimit) {
            val value = bytes[offset + pos].toInt() and 0xFF
            for (i in 0..<numLits) {
                val lit = literals[i]
                if (value == anchorChars[i].code && pos + lit.length <= length) {
                    if (WorkCounterConfig.ENABLED) {
                        WorkCounter.record(lit.length.toLong())
                    }
                    if (Ascii.regionMatches(bytes, offset + pos, lit, lit.length)) {
                        return pos
                    }
                }
            }
            verificationWork += minLength.toLong()
            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
                return VectorScanProvider.Companion.UNSUPPORTED
            }
            pos++
        }
        return -1
    }
}
