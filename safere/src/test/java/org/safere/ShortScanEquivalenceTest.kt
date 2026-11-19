// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import kotlin.math.max

/**
 * Comprehensive differential equivalence tests comparing 16-bit [ShortVectorScan] and [ ] kernels against 8-bit kernels and scalar reference models.
 */
@DisabledForCrosscheck("Internal SIMD/SWAR kernel test, not part of public regex API")
internal class ShortScanEquivalenceTest {
    @Test
    fun denseIgnoreCaseCandidatesFallBackBeforeReplayingLongPrefixes() {
        if (!isVectorApiAvailable) {
            return
        }
        val prefix = "z".repeat(64) + "y"
        val input = "z".repeat(4096).toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThat(
            ByteVectorScan.indexOfIgnoreCase(
                input, 0, input.size, prefix, prefix.length, 0, 'z'.code.toByte(), 'Z'.code.toByte(), 0
            )
        )
            .isEqualTo(VectorScanProvider.UNSUPPORTED)
    }

    @Test
    fun vectorCandidateBoundsDoNotOverflow() {
        Assertions.assertThat(WorkLimit.candidateInBounds(Int.Companion.MAX_VALUE - 4, 0, Int.Companion.MAX_VALUE, 8))
            .isFalse()
        Assertions.assertThat(WorkLimit.candidateInBounds(Int.Companion.MAX_VALUE - 8, 0, Int.Companion.MAX_VALUE, 8))
            .isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3, 7, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048])
    @DisplayName("1-byte Latin-1 char class scan equivalence across ByteSwarScan and scalar")
    fun testLatin1CharClassEquivalence(length: Int) {
        val random = Random((42 + length).toLong())
        val input = ByteArray(length)
        for (i in 0..<length) {
            input[i] = (random.nextInt(26) + 'a'.code).toByte() // random lowercase
        }

        for (ranges in ASCII_CHAR_CLASS_RANGES) {
            for (start in intArrayOf(0, 1, 7, 15, 31, max(0, length - 5))) {
                if (start > length) continue

                val expected: Int = scalarIndexOfAsciiClass(input, ranges, start)

                val byteSwar =
                    if (ranges.size <= 4)
                        ByteSwarScan.indexOfAsciiClass(input, 0, length, ranges, start)
                    else
                        expected

                Assertions.assertThat(byteSwar)
                    .`as`("ByteSwarScan at start=%d, len=%d", start, length)
                    .isEqualTo(expected)

                if (isVectorApiAvailable) {
                    val byteVector = ByteVectorScan.indexOfAsciiClass(input, 0, length, ranges, start)
                    Assertions.assertThat(byteVector)
                        .`as`("ByteVectorScan at start=%d, len=%d", start, length)
                        .isEqualTo(expected)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 3, 7, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048])
    @DisplayName("2-byte UTF-16 char class scan equivalence across char[], byte[], SWAR, and Vector")
    fun testUtf16CharClassEquivalence(charLength: Int) {
        val random = Random((100 + charLength).toLong())
        val chars = CharArray(charLength)
        for (i in 0..<charLength) {
            chars[i] = (random.nextInt(26) + 'a'.code).toChar()
        }
        // Inject some Cyrillic chars
        if (charLength > 10) {
            chars[charLength / 2] = '\u0416'
        }

        val str = String(chars)
        val utf16Bytes = str.toByteArray(StandardCharsets.UTF_16LE)

        for (ranges in UTF16_CHAR_CLASS_RANGES) {
            for (start in intArrayOf(0, 1, 7, 15, 31, max(0, charLength - 5))) {
                if (start > charLength) continue

                val expected: Int = scalarIndexOfCharClass(chars, ranges, start)

                // SWAR over byte[] (UTF-16) and char[]
                val shortSwarByte =
                    if (ranges.size <= 4)
                        ShortSwarScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start)
                    else
                        expected
                val shortSwarChar =
                    if (ranges.size <= 4)
                        ShortSwarScan.indexOfCharClass(chars, 0, charLength, ranges, start)
                    else
                        expected

                Assertions.assertThat(shortSwarByte)
                    .`as`("ShortSwarScan (byte[]) at start=%d, len=%d", start, charLength)
                    .isEqualTo(expected)
                Assertions.assertThat(shortSwarChar)
                    .`as`("ShortSwarScan (char[]) at start=%d, len=%d", start, charLength)
                    .isEqualTo(expected)

                // Vector API Kernels (When enabled on runtime)
                if (isVectorApiAvailable) {
                    val shortVectorByte =
                        ShortVectorScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start)
                    val shortVectorChar =
                        ShortVectorScan.indexOfCharClass(chars, 0, charLength, ranges, start)

                    Assertions.assertThat(shortVectorByte)
                        .`as`("ShortVectorScan (byte[]) at start=%d, len=%d", start, charLength)
                        .isEqualTo(expected)
                    Assertions.assertThat(shortVectorChar)
                        .`as`("ShortVectorScan (char[]) at start=%d, len=%d", start, charLength)
                        .isEqualTo(expected)
                }
            }
        }
    }

    @Test
    @DisplayName("Case-insensitive prefix scan equivalence for 16-bit ShortVectorScan and ShortSwarScan")
    fun testIgnoreCaseEquivalence() {
        val prefix = "hElLo"
        val haystackUtf16 =
            ("x".repeat(500)
                    + "\u0416\u0435\u043B\u043B\u043E"
                    + "x".repeat(200)
                    + "HELLO"
                    + "x".repeat(300))

        val chars = haystackUtf16.toCharArray()
        val utf16Bytes = haystackUtf16.toByteArray(StandardCharsets.UTF_16LE)

        // SWAR
        val utf16SwarByte =
            ShortSwarScan.indexOfIgnoreCaseUtf16(utf16Bytes, 0, haystackUtf16.length, prefix, 0)
        val utf16SwarChar =
            ShortSwarScan.indexOfIgnoreCase(chars, 0, haystackUtf16.length, prefix, 0)

        Assertions.assertThat(utf16SwarByte).isEqualTo(705)
        Assertions.assertThat(utf16SwarChar).isEqualTo(705)

        // Vector
        if (isVectorApiAvailable) {
            val utf16VecByte =
                ShortVectorScan.indexOfIgnoreCaseUtf16(utf16Bytes, 0, haystackUtf16.length, prefix, 0)
            val utf16VecChar =
                ShortVectorScan.indexOfIgnoreCase(chars, 0, haystackUtf16.length, prefix, 0)

            Assertions.assertThat(utf16VecByte).isEqualTo(705)
            Assertions.assertThat(utf16VecChar).isEqualTo(705)
        }
    }

    @Test
    @DisplayName("UTF-16 kernels handle unsigned ranges and reject non-BMP ranges")
    fun utf16RangeBoundaries() {
        val chars = CharArray(64)
        Arrays.fill(chars, '\uA000')
        chars[10] = '\u7500'
        val utf16Bytes = String(chars).toByteArray(StandardCharsets.UTF_16LE)
        val crossingSignedBoundary = intArrayOf('\u7000'.code, '\u9000'.code)

        Assertions.assertThat(ShortSwarScan.indexOfCharClass(chars, 0, chars.size, crossingSignedBoundary, 0))
            .isEqualTo(10)
        Assertions.assertThat(
            ShortSwarScan.indexOfCharClassUtf16(
                utf16Bytes, 0, chars.size, crossingSignedBoundary, 0
            )
        )
            .isEqualTo(10)
        if (isVectorApiAvailable) {
            Assertions.assertThat(
                ShortVectorScan.indexOfCharClass(chars, 0, chars.size, crossingSignedBoundary, 0)
            )
                .isEqualTo(10)
            Assertions.assertThat(
                ShortVectorScan.indexOfCharClassUtf16(
                    utf16Bytes, 0, chars.size, crossingSignedBoundary, 0
                )
            )
                .isEqualTo(10)
        }

        val supplementaryRange = intArrayOf(0x1F600, 0x1F64F)
        Assertions.assertThat(ShortSwarScan.indexOfCharClass(chars, 0, chars.size, supplementaryRange, 0))
            .isEqualTo(VectorScanProvider.UNSUPPORTED)
        Assertions.assertThat(
            ShortSwarScan.indexOfCharClassUtf16(utf16Bytes, 0, chars.size, supplementaryRange, 0)
        )
            .isEqualTo(VectorScanProvider.UNSUPPORTED)
        if (isVectorApiAvailable) {
            Assertions.assertThat(ShortVectorScan.indexOfCharClass(chars, 0, chars.size, supplementaryRange, 0))
                .isEqualTo(VectorScanProvider.UNSUPPORTED)
            Assertions.assertThat(
                ShortVectorScan.indexOfCharClassUtf16(
                    utf16Bytes, 0, chars.size, supplementaryRange, 0
                )
            )
                .isEqualTo(VectorScanProvider.UNSUPPORTED)
        }
    }

    @Test
    @DisplayName("UTF-16 vector kernels compare ranges within each signed half directly")
    fun utf16RangesWithinSignedHalves() {
        if (!isVectorApiAvailable) {
            return
        }
        val chars = CharArray(64)
        Arrays.fill(chars, '\uA000')
        chars[10] = '\u7500'
        chars[20] = '\u8500'
        val utf16Bytes = String(chars).toByteArray(StandardCharsets.UTF_16LE)

        for (ranges in arrayOf<IntArray?>(intArrayOf(0x7000, 0x7FFF), intArrayOf(0x8000, 0x9000))) {
            val expected: Int = Companion.scalarIndexOfCharClass(chars, ranges!!, 0)
            Assertions.assertThat(ShortVectorScan.indexOfCharClass(chars, 0, chars.size, ranges, 0))
                .isEqualTo(expected)
            Assertions.assertThat(ShortVectorScan.indexOfCharClassUtf16(utf16Bytes, 0, chars.size, ranges, 0))
                .isEqualTo(expected)
        }
    }

    @Test
    @DisplayName("Public array kernels handle empty prefixes and unsupported byte ranges")
    fun publicKernelEdgeInputs() {
        val chars = "abc".toCharArray()
        val utf16 = "abc".toByteArray(StandardCharsets.UTF_16LE)

        Assertions.assertThat(ShortSwarScan.indexOfIgnoreCase(chars, 0, chars.size, "", 1)).isEqualTo(1)
        Assertions.assertThat(ShortSwarScan.indexOfIgnoreCaseUtf16(utf16, 0, chars.size, "", 10))
            .isEqualTo(chars.size)
        if (isVectorApiAvailable) {
            Assertions.assertThat(ShortVectorScan.indexOfIgnoreCase(chars, 0, chars.size, "", -1)).isZero()
            Assertions.assertThat(ShortVectorScan.indexOfIgnoreCaseUtf16(utf16, 0, chars.size, "", 1))
                .isEqualTo(1)
        }

        val bytes = ByteArray(64)
        bytes[10] = 0xE9.toByte()
        Assertions.assertThat(ByteSwarScan.indexOfAsciiClass(bytes, 0, bytes.size, intArrayOf(233, 233), 0))
            .isEqualTo(VectorScanProvider.UNSUPPORTED)
        Assertions.assertThat(ByteSwarScan.indexOfAsciiClass(bytes, 0, bytes.size, intArrayOf('z'.code, 'a'.code), 0))
            .isEqualTo(VectorScanProvider.UNSUPPORTED)
    }

    @Test
    @DisplayName("Ignore-case prefix scans use a linear failure function")
    fun ignoreCasePrefixFailureFunction() {
        Assertions.assertThat(Ascii.ignoreCaseFailure("aaaaab")).containsExactly(0, 1, 2, 3, 4, 0)
    }

    companion object {
        private val ASCII_CHAR_CLASS_RANGES: MutableList<IntArray> = List.of<IntArray?>(
            intArrayOf('0'.code, '9'.code),
            intArrayOf('a'.code, 'z'.code),
            intArrayOf('a'.code, 'z'.code, 'A'.code, 'Z'.code),
            intArrayOf('0'.code, '9'.code, 'a'.code, 'z'.code, 'A'.code, 'Z'.code),
            intArrayOf('0'.code, '9'.code, 'a'.code, 'z'.code, 'A'.code, 'Z'.code, '_'.code, '_'.code)
        )

        private val UTF16_CHAR_CLASS_RANGES: MutableList<IntArray> = List.of<IntArray?>(
            intArrayOf('0'.code, '9'.code),
            intArrayOf('a'.code, 'z'.code, 'A'.code, 'Z'.code),
            intArrayOf(0x0400, 0x04FF),  // Cyrillic
            intArrayOf(0x3040, 0x309F),  // Hiragana
            intArrayOf('0'.code, '9'.code, 0x0400, 0x04FF)
        )

        private val isVectorApiAvailable: Boolean
            get() {
                try {
                    Class.forName("jdk.incubator.vector.ByteVector")
                    return true
                } catch (t: Throwable) {
                    return false
                }
            }

        private fun scalarIndexOfAsciiClass(input: ByteArray, ranges: IntArray, start: Int): Int {
            for (i in start..<input.size) {
                val b = input[i].toInt() and 0xFF
                var r = 0
                while (r < ranges.size) {
                    if (b >= ranges[r] && b <= ranges[r + 1]) {
                        return i
                    }
                    r += 2
                }
            }
            return -1
        }

        private fun scalarIndexOfCharClass(input: CharArray, ranges: IntArray, start: Int): Int {
            for (i in start..<input.size) {
                val c = input[i]
                var r = 0
                while (r < ranges.size) {
                    if (c.code >= ranges[r] && c.code <= ranges[r + 1]) {
                        return i
                    }
                    r += 2
                }
            }
            return -1
        }
    }
}
