// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import jdk.incubator.vector.*
import java.nio.ByteOrder

/**
 * Stateless SIMD kernels using the incubating Vector API for 2-byte sequences (UTF-16 and char[]).
 */
internal object ShortVectorScan {
    private val SPECIES: VectorSpecies<Short?> = ShortVector.SPECIES_PREFERRED

    fun indexOfCharClass(chars: CharArray, offset: Int, length: Int, ranges: IntArray, start: Int): Int {
        return indexOfCharClass(SPECIES, chars, offset, length, ranges, start)
    }

    @JvmStatic
    fun indexOfCharClass(
        species: VectorSpecies<Short?>, chars: CharArray, offset: Int, length: Int, ranges: IntArray, start: Int
    ): Int {
        if (!Swar.supportsBmpCodeUnitRanges(ranges, 4)) {
            return VectorScanProvider.UNSUPPORTED
        }
        var position = Math.max(0, start)
        val vectorLen = species.length()
        val limit = length - vectorLen

        while (position <= limit) {
            val values = ShortVector.fromCharArray(species, chars, offset + position)
            val matches = matches(values, ranges)
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += vectorLen
        }

        while (position < length) {
            val ch: Char = chars[offset + position]
            if (matches(ch, ranges)) {
                return position
            }
            position++
        }
        return -1
    }

    fun indexOfCharClassUtf16(bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int): Int {
        return indexOfCharClassUtf16(SPECIES, bytes, offset, length, ranges, start)
    }

    fun indexOfCharClassUtf16(
        species: VectorSpecies<Short?>, bytes: ByteArray, offset: Int, length: Int, ranges: IntArray, start: Int
    ): Int {
        if (!Swar.supportsBmpCodeUnitRanges(ranges, 4) || ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            return VectorScanProvider.UNSUPPORTED
        }
        var position = Math.max(0, start)
        val vectorLen = species.length()
        val limit = length - vectorLen
        val byteSpecies = species.withLanes<Byte?>(Byte::class.javaPrimitiveType)

        while (position <= limit) {
            val values =
                ByteVector.fromArray(byteSpecies, bytes, offset + (position shl 1)).reinterpretAsShorts()
            val matches = matches(values, ranges)
            if (matches.anyTrue()) {
                return position + matches.firstTrue()
            }
            position += vectorLen
        }

        while (position < length) {
            val ch =
                ((bytes[offset + (position shl 1)].toInt() and 0xFF)
                        or ((bytes[offset + (position shl 1) + 1].toInt() and 0xFF) shl 8)).toChar()
            if (matches(ch, ranges)) {
                return position
            }
            position++
        }
        return -1
    }

    fun indexOfIgnoreCase(chars: CharArray, offset: Int, length: Int, prefix: String, start: Int): Int {
        return indexOfIgnoreCase(SPECIES, chars, offset, length, prefix, start)
    }

    fun indexOfIgnoreCase(
        species: VectorSpecies<Short?>,
        chars: CharArray,
        offset: Int,
        length: Int,
        prefix: String,
        start: Int
    ): Int {
        val prefixLen: Int = prefix.length
        if (prefixLen == 0) {
            return Math.min(Math.max(0, start), length)
        }
        for (i in 0 until prefixLen) {
            if (prefix[i].code > 127) {
                return VectorScanProvider.UNSUPPORTED
            }
        }
        if (prefixLen > 1) {
            return Utf16.indexOfIgnoreCase(chars, offset, length, prefix, start)
        }

        var pos = Math.max(0, start)
        val vectorLen = species.length()
        val limit = length - vectorLen

        val first: Char = prefix[0]
        val low = Ascii.toLowerCase(first).code.toShort()
        val high = Ascii.toUpperCase(first).code.toShort()
        val lowVec = ShortVector.broadcast(species, low)
        val highVec = ShortVector.broadcast(species, high)

        while (pos <= limit) {
            val inputVec = ShortVector.fromCharArray(species, chars, offset + pos)
            val matchMask =
                inputVec
                    .compare(VectorOperators.EQ, lowVec)
                    .or(inputVec.compare(VectorOperators.EQ, highVec))

            if (matchMask.anyTrue()) {
                var activeLanes = matchMask.toLong()
                while (activeLanes != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
                    val candidatePos = pos + bit
                    if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
                        && Utf16.regionMatchesIgnoreCase(chars, offset + candidatePos, prefix, prefixLen)
                    ) {
                        return candidatePos
                    }
                    activeLanes = activeLanes and (activeLanes - 1)
                }
            }
            pos += vectorLen
        }

        val limitScalar = length - prefixLen
        while (pos <= limitScalar) {
            if (Utf16.regionMatchesIgnoreCase(chars, offset + pos, prefix, prefixLen)) {
                return pos
            }
            pos++
        }
        return -1
    }

    fun indexOfIgnoreCaseUtf16(
        bytes: ByteArray, offset: Int, length: Int, prefix: String, start: Int
    ): Int {
        return indexOfIgnoreCaseUtf16(SPECIES, bytes, offset, length, prefix, start)
    }

    fun indexOfIgnoreCaseUtf16(
        species: VectorSpecies<Short?>,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        prefix: String,
        start: Int
    ): Int {
        val prefixLen: Int = prefix.length
        if (prefixLen == 0) {
            return Math.min(Math.max(0, start), length)
        }
        for (i in 0 until prefixLen) {
            if (prefix[i].code > 127) {
                return VectorScanProvider.UNSUPPORTED
            }
        }
        if (prefixLen > 1) {
            return Utf16.indexOfIgnoreCaseUtf16(bytes, offset, length, prefix, start)
        }
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            return VectorScanProvider.UNSUPPORTED
        }

        var pos = Math.max(0, start)
        val vectorLen = species.length()
        val limit = length - vectorLen
        val byteSpecies = species.withLanes<Byte?>(Byte::class.javaPrimitiveType)

        val first: Char = prefix[0]
        val low = Ascii.toLowerCase(first).code.toShort()
        val high = Ascii.toUpperCase(first).code.toShort()
        val lowVec = ShortVector.broadcast(species, low)
        val highVec = ShortVector.broadcast(species, high)

        while (pos <= limit) {
            val inputVec =
                ByteVector.fromArray(byteSpecies, bytes, offset + (pos shl 1)).reinterpretAsShorts()
            val matchMask =
                inputVec
                    .compare(VectorOperators.EQ, lowVec)
                    .or(inputVec.compare(VectorOperators.EQ, highVec))

            if (matchMask.anyTrue()) {
                var activeLanes = matchMask.toLong()
                while (activeLanes != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(activeLanes)
                    val candidatePos = pos + bit
                    if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
                        && Utf16.regionMatchesIgnoreCaseUtf16(
                            bytes, offset + (candidatePos shl 1), prefix, prefixLen
                        )
                    ) {
                        return candidatePos
                    }
                    activeLanes = activeLanes and (activeLanes - 1)
                }
            }
            pos += vectorLen
        }

        val limitScalar = length - prefixLen
        while (pos <= limitScalar) {
            if (Utf16.regionMatchesIgnoreCaseUtf16(bytes, offset + (pos shl 1), prefix, prefixLen)) {
                return pos
            }
            pos++
        }
        return -1
    }

    private fun matches(values: ShortVector, ranges: IntArray): VectorMask<Short?> {
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

    private fun matches(values: ShortVector, lowBound: Int, highBound: Int): VectorMask<Short?> {
        val low = lowBound.toShort()
        val high = highBound.toShort()
        if (low == high) {
            return values.eq(low)
        }
        if (highBound == lowBound + 1) {
            return values.eq(low).or(values.eq(high))
        }
        if (highBound <= 0x7FFF || lowBound >= 0x8000) {
            return values.compare(VectorOperators.GE, low).and(values.compare(VectorOperators.LE, high))
        }
        val biasedValues = values.lanewise(VectorOperators.XOR, java.lang.Short.MIN_VALUE)
        val biasedLow = (low.toInt() xor java.lang.Short.MIN_VALUE.toInt()).toShort()
        val biasedHigh = (high.toInt() xor java.lang.Short.MIN_VALUE.toInt()).toShort()
        return biasedValues.compare(VectorOperators.GE, biasedLow)
            .and(biasedValues.compare(VectorOperators.LE, biasedHigh))
    }

    private fun matches(value: Char, ranges: IntArray): Boolean {
        for (index in ranges.indices step 2) {
            if (value.code >= ranges[index] && value.code <= ranges[index + 1]) {
                return true
            }
        }
        return false
    }
}
