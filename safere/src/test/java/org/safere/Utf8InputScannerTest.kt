// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.lang.Byte
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import kotlin.IllegalArgumentException
import kotlin.IndexOutOfBoundsException
import kotlin.arrayOf
import kotlin.math.max
import kotlin.math.min

@DisabledForCrosscheck("package-private UTF-8 scanner tests exercise SafeRE internals")
internal class Utf8InputScannerTest {
    @Test
    fun decodesEveryUnicodeScalarInBothDirections() {
        for (expected in 0..Character.MAX_CODE_POINT) {
            if (expected >= Character.MIN_SURROGATE.code && expected <= Character.MAX_SURROGATE.code) {
                continue
            }
            val bytes = String(Character.toChars(expected)).toByteArray(StandardCharsets.UTF_8)
            val scanner = Utf8InputScanner(bytes, 0, bytes.size)

            val forward = scanner.decodeForward(0)
            val backward = scanner.decodeBackward(bytes.size)
            if (InputScanner.codePoint(forward) != expected || InputScanner.position(forward) != bytes.size || InputScanner.codePoint(
                    backward
                ) != expected || InputScanner.position(backward) != 0
            ) {
                Assertions.fail<Any?>("Incorrect UTF-8 decode for U+%04X", expected)
            }
        }
    }

    @Test
    fun malformedBytesRecoverOneByteInBothDirections() {
        for (malformed in malformedSequences()) {
            val scanner = Utf8InputScanner(malformed, 0, malformed.size)
            var position = 0
            while (position < malformed.size) {
                val decoded = scanner.decodeForward(position)
                Assertions.assertThat(InputScanner.codePoint(decoded)).isEqualTo(0xFFFD)
                Assertions.assertThat(InputScanner.position(decoded)).isEqualTo(position + 1)
                position++
            }

            position = malformed.size
            while (position > 0) {
                val decoded = scanner.decodeBackward(position)
                Assertions.assertThat(InputScanner.codePoint(decoded)).isEqualTo(0xFFFD)
                Assertions.assertThat(InputScanner.position(decoded)).isEqualTo(position - 1)
                position--
            }
        }
    }

    @Test
    fun validationRejectsFirstMalformedRelativeOffset() {
        val bytes = byteArrayOf(
            'x'.code.toByte(),
            0xF0.toByte(),
            0x80.toByte(),
            0x80.toByte(),
            0x80.toByte(),
            'y'.code.toByte()
        )

        Assertions.assertThatThrownBy(ThrowingCallable { Utf8InputScanner.validate(bytes, 0, bytes.size) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("byte 1")
    }

    @Test
    fun validationAcceptsEveryBoundaryScalar() {
        val bytes =
            ("\u0000\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF" + String(Character.toChars(0x10000)) + String(
                Character.toChars(
                    0x10FFFF
                )
            ))
                .toByteArray(StandardCharsets.UTF_8)

        Utf8InputScanner.validate(bytes, 0, bytes.size)
    }

    @Test
    fun windowCoordinatesAreRelativeAndCannotReadSentinels() {
        val logical = "aé😀z".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logical.size + 4)
        storage[0] = 0x80.toByte()
        storage[1] = 0x80.toByte()
        System.arraycopy(logical, 0, storage, 2, logical.size)
        storage[storage.size - 2] = 0x80.toByte()
        storage[storage.size - 1] = 0x80.toByte()
        val scanner = Utf8InputScanner(storage, 2, logical.size)

        Assertions.assertThat(scanner.length).isEqualTo(logical.size)
        Assertions.assertThat<Int?>(traceForward(scanner)).containsExactly('a'.code, 'é'.code, 0x1F600, 'z'.code)
        Assertions.assertThat<Int?>(traceBackward(scanner)).containsExactly('z'.code, 0x1F600, 'é'.code, 'a'.code)
        Assertions.assertThat(scanner.decodeForward(scanner.length))
            .isEqualTo(InputScanner.decoded(-1, scanner.length))
        Assertions.assertThat(scanner.decodeBackward(0)).isEqualTo(InputScanner.decoded(-1, 0))
    }

    @Test
    fun singleByteLiteralSearchCoversEveryWindowAlignmentAndPosition() {
        for (offset in 0..<java.lang.Long.BYTES * 2) {
            for (expected in 0..39) {
                val storage = ByteArray(offset + 40 + java.lang.Long.BYTES)
                Arrays.fill(storage, 'a'.code.toByte())
                storage[offset + expected] = 'z'.code.toByte()
                val scanner = Utf8InputScanner(storage, offset, 40)

                Assertions.assertThat(scanner.indexOf(byteArrayOf('z'.code.toByte()), intArrayOf(0), null))
                    .`as`("offset %s, position %s", offset, expected)
                    .isEqualTo(expected)
            }
        }

        val absent = Utf8InputScanner("aaaaaaaa".toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(absent.indexOf(byteArrayOf('z'.code.toByte()), intArrayOf(0), null)).isEqualTo(-1)
    }

    @Test
    fun asciiCodePointClassSearchCoversOptimizedShapesAlignmentsAndPositions() {
        for (ranges in List.of<IntArray?>(
            intArrayOf('x'.code, 'x'.code),
            intArrayOf('x'.code, 'x'.code, 'z'.code, 'z'.code),
            intArrayOf('0'.code, '9'.code)
        )) {
            val matchingByte =
                if (ranges.size == 2 && ranges[0] != ranges[1]) '5'.code else ranges[ranges.size - 1]
            val bitmap0: Long = asciiBitmap(ranges, 0, 63)
            val bitmap1: Long = asciiBitmap(ranges, 64, 127)
            for (offset in 0..<java.lang.Long.BYTES * 2) {
                for (expected in 0..39) {
                    val storage = ByteArray(offset + 40 + java.lang.Long.BYTES)
                    Arrays.fill(storage, 'a'.code.toByte())
                    storage[offset + expected] = matchingByte.toByte()
                    val scanner = Utf8InputScanner(storage, offset, 40)

                    Assertions.assertThat(scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, 0, scanner.length))
                        .`as`("ranges %s, offset %s, position %s", ranges.contentToString(), offset, expected)
                        .isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun asciiCodePointClassSearchCoversMultiRangeProviderAndDeclinePaths() {
        val classes =
            List.of<IntArray?>(
                intArrayOf('X'.code, 'X'.code, 'Z'.code, 'Z'.code, '_'.code, '_'.code),
                intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code),
                intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code, 'a'.code, 'z'.code),
                intArrayOf('!'.code, '!'.code, '#'.code, '#'.code, '0'.code, '9'.code, 'A'.code, 'Z'.code),
                intArrayOf(
                    '!'.code,
                    '!'.code,
                    '#'.code,
                    '#'.code,
                    '0'.code,
                    '9'.code,
                    'A'.code,
                    'Z'.code,
                    'a'.code,
                    'z'.code
                )
            )
        val length = 2048
        val positions = intArrayOf(0, 1, 3, 4, 15, 16, 17, 1023, 1024, 2031, 2047)

        for (ranges in classes) {
            val bitmap0: Long = asciiBitmap(ranges, 0, 63)
            val bitmap1: Long = asciiBitmap(ranges, 64, 127)
            for (offset in 0..<java.lang.Long.BYTES * 2) {
                for (expected in positions) {
                    val storage = ByteArray(offset + length + java.lang.Long.BYTES)
                    Arrays.fill(storage, '~'.code.toByte())
                    storage[offset + expected] = ranges[ranges.size - 1].toByte()
                    val scanner = Utf8InputScanner(storage, offset, length)

                    Assertions.assertThat(scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, 0, scanner.length))
                        .`as`("ranges %s, offset %s, position %s", ranges.contentToString(), offset, expected)
                        .isEqualTo(expected)
                    Assertions.assertThat(
                        scanner.indexOfCodePointClass(
                            ranges, bitmap0, bitmap1, expected + 1, scanner.length
                        )
                    )
                        .`as`("absent tail for ranges %s, offset %s", ranges.contentToString(), offset)
                        .isEqualTo(-1)
                }
            }
        }
    }

    @Test
    fun exactAsciiRangeMaskAgreesForEveryRangeAndByteValue() {
        val byteOnes = 0x0101010101010101L
        val byteHighBits = -0x7f7f7f7f7f7f7f80L

        for (low in 0..127) {
            for (high in low..127) {
                for (value in 0..255) {
                    val word = value * byteOnes
                    val expected = if (value >= low && value <= high) byteHighBits else 0
                    val actual = ByteSwarScan.exactAsciiRangeMask(word, low, high)
                    if (actual != expected) {
                        Assertions.fail<Any?>(
                            "Incorrect range mask for [%s, %s] and byte %s: expected %s, actual %s",
                            low, high, value, java.lang.Long.toHexString(expected), java.lang.Long.toHexString(actual)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun exactAsciiRangeMaskKeepsByteLanesIndependent() {
        val random = Random(20260805)
        for (trial in 0..100000 - 1) {
            val low = random.nextInt(128)
            val high = low + random.nextInt(128 - low)
            var word: Long = 0
            var expected: Long = 0
            for (lane in 0..<java.lang.Long.BYTES) {
                val value = random.nextInt(256)
                word = word or (value.toLong() shl (lane * Byte.SIZE))
                if (value >= low && value <= high) {
                    expected = expected or (0x80L shl (lane * Byte.SIZE))
                }
            }

            Assertions.assertThat(ByteSwarScan.exactAsciiRangeMask(word, low, high))
                .`as`("range [%s, %s], word %s", low, high, java.lang.Long.toHexString(word))
                .isEqualTo(expected)
        }
    }

    @Test
    fun asciiCodePointClassSearchHonorsStartsAndSkipsNonAsciiScalars() {
        val bytes = "5é😀a7".toByteArray(StandardCharsets.UTF_8)
        val digits = intArrayOf('0'.code, '9'.code)
        val bitmap0: Long = asciiBitmap(digits, 0, 63)

        val scanner = Utf8InputScanner(bytes)

        Assertions.assertThat(scanner.indexOfCodePointClass(digits, bitmap0, 0, -4, scanner.length))
            .isEqualTo(0)
        Assertions.assertThat(scanner.indexOfCodePointClass(digits, bitmap0, 0, 1, scanner.length))
            .isEqualTo(bytes.size - 1)
        Assertions.assertThat(scanner.indexOfCodePointClass(digits, bitmap0, 0, bytes.size, scanner.length))
            .isEqualTo(-1)
    }

    @Test
    fun asciiCodePointClassSearchAgreesForEveryRangeAndWindowAlignment() {
        for (offset in 0..<java.lang.Long.BYTES * 2) {
            val storage = ByteArray(offset + 128 + java.lang.Long.BYTES)
            Arrays.fill(storage, 0x7F.toByte())
            for (value in 0..127) {
                storage[offset + value] = value.toByte()
            }
            val scanner = Utf8InputScanner(storage, offset, 128)

            for (low in 0..127) {
                for (high in low..127) {
                    val ranges = intArrayOf(low, high)
                    val bitmap0: Long = asciiBitmap(ranges, 0, 63)
                    val bitmap1: Long = asciiBitmap(ranges, 64, 127)
                    for (start in intArrayOf(-1, 0, low, high, high + 1, 127, 128)) {
                        val clampedStart = max(0, start)
                        val expected = if (clampedStart <= high) max(clampedStart, low) else -1

                        Assertions.assertThat(
                            scanner.indexOfCodePointClass(
                                ranges, bitmap0, bitmap1, start, scanner.length
                            )
                        )
                            .`as`("offset %s, range [%s, %s], start %s", offset, low, high, start)
                            .isEqualTo(expected)
                    }
                }
            }
        }
    }

    @Test
    fun asciiPrefixClassSearchCoversSingletonPairAndRange() {
        for (members in List.of<IntArray?>(
            intArrayOf('x'.code),
            intArrayOf('x'.code, 'z'.code),
            asciiRange('0'.code, '9'.code)
        )) {
            val builder = AsciiBitmap.Builder()
            for (member in members) {
                builder.add(member)
            }
            val asciiClass = builder.build()
            val matchingByte = members[members.size - 1].toByte()
            val bytes = "a".repeat(40).toByteArray(StandardCharsets.UTF_8)
            bytes[31] = matchingByte
            val scanner = Utf8InputScanner(bytes)

            Assertions.assertThat(scanner.indexOfAsciiClass(asciiClass, 0)).isEqualTo(31)
            Assertions.assertThat(scanner.indexOfAsciiClass(asciiClass, 32)).isEqualTo(-1)
        }
    }

    @Test
    fun captureFreePrefixSearchCanSkipAFailingCandidateAndFindALaterMatch() {
        val pattern = Pattern.compile("ab+c")

        Assertions.assertThat(pattern.find(Utf8Input.trusted("xxabxabbc".toByteArray(StandardCharsets.UTF_8)))).isTrue()
        Assertions.assertThat(pattern.find(Utf8Input.trusted("xxabx".toByteArray(StandardCharsets.UTF_8)))).isFalse()
    }

    @Test
    fun optimizedLiteralSearchAgreesWithStringIndexOf() {
        val random = Random(0x5AFE09L)
        for (trial in 0..10000 - 1) {
            val text: String = randomAscii(random, random.nextInt(80))
            val literal: String = randomAscii(random, 1 + random.nextInt(12))
            val expected = text.indexOf(literal)
            val pattern = Pattern.compile(literal)
            val input = Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8))

            Assertions.assertThat(pattern.find(input)).`as`("%s in %s", literal, text).isEqualTo(expected >= 0)
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.find()).`as`("%s in %s", literal, text).isEqualTo(expected >= 0)
            if (expected >= 0) {
                Assertions.assertThat(matcher.start()).isEqualTo(expected)
                Assertions.assertThat(matcher.end()).isEqualTo(expected + literal.size)
            }
        }
    }

    @Test
    fun literalSearchSizeArithmeticDoesNotOverflowForLargeArrays() {
        Assertions.assertThat(ByteSwarScan.filterThreshold(Int.Companion.MAX_VALUE)).isEqualTo(85899345880L)
        Assertions.assertThat(WorkLimit.forRemaining(Int.Companion.MAX_VALUE)).isEqualTo(4294967294L)
    }

    @Test
    fun candidateVerificationWorkDoesNotWrapPastIntegerMaximum() {
        Assertions.assertThat(WorkLimit.addCandidateWork(2100000000L, 8, 20000000)).isEqualTo(2260000008L)
    }

    @Test
    fun multiByteLiteralSearchCoversEveryWindowAlignmentAndPosition() {
        // The candidate filter reads eight positions at a time and hands the remainder to a linear
        // tail, so every combination of window alignment and match position must agree.
        for (literal in mutableListOf<String?>("zy", "zqy", "zabcdefy", "zabcdefghijy")) {
            val needle = literal!!.toByteArray(StandardCharsets.UTF_8)
            for (window in intArrayOf(40, filterThreshold(needle.size) + 40)) {
                for (offset in 0..<java.lang.Long.BYTES * 2) {
                    for (expected in 0..window - needle.size) {
                        val storage = ByteArray(offset + window + java.lang.Long.BYTES)
                        Arrays.fill(storage, 'a'.code.toByte())
                        System.arraycopy(needle, 0, storage, offset + expected, needle.size)
                        val scanner = Utf8InputScanner(storage, offset, window)

                        Assertions.assertThat(scanner.indexOf(needle, literalFailure(needle), literalShifts(needle)))
                            .`as`(
                                "literal %s, window %s, offset %s, position %s",
                                literal, window, offset, expected
                            )
                            .isEqualTo(expected)
                    }
                }
            }
        }
    }

    @Test
    fun multiByteLiteralSearchFindsMatchesFromEveryStartPosition() {
        val needle = "cab".toByteArray(StandardCharsets.UTF_8)
        val failure: IntArray = literalFailure(needle)
        val shifts: IntArray? = literalShifts(needle)
        // Long enough that early start positions select the filter and late ones fall back to the
        // skip loop as the remaining input shrinks past the threshold.
        for (repeats in intArrayOf(11, filterThreshold(needle.size))) {
            val haystack = "abc".repeat(repeats).toByteArray(StandardCharsets.UTF_8)
            val text = String(haystack, StandardCharsets.UTF_8)
            val scanner = Utf8InputScanner(haystack)

            for (start in 0..haystack.size) {
                Assertions.assertThat(scanner.indexOf(needle, failure, shifts, start))
                    .`as`("repeats %s, start %s", repeats, start)
                    .isEqualTo(text.indexOf("cab", start))
            }
        }
    }

    @Test
    fun multiByteLiteralSearchAgreesWithStringIndexOfOnDenseCandidateInput() {
        // A tiny alphabet makes nearly every position pass the first/last byte filter, which is the
        // input shape that exhausts the work budget and falls back to the linear-time scan. Texts run
        // well past the filter threshold so the budget is actually reachable.
        val random = Random(0x5AFE5EAL)
        for (trial in 0..20000 - 1) {
            val alphabet = 1 + random.nextInt(3)
            val text: String = randomFromAlphabet(random, random.nextInt(1200), alphabet)
            val literal: String = randomFromAlphabet(random, 2 + random.nextInt(10), alphabet)
            val needle = literal.toByteArray(StandardCharsets.UTF_8)
            val scanner = Utf8InputScanner(text.toByteArray(StandardCharsets.UTF_8))
            val start = if (text.isEmpty()) 0 else random.nextInt(text.length)

            Assertions.assertThat(scanner.indexOf(needle, literalFailure(needle), literalShifts(needle), start))
                .`as`("%s in %s from %s", literal, text, start)
                .isEqualTo(text.indexOf(literal, start))
        }
    }

    @Test
    fun tailLiteralSearchAgreesWithStringIndexOf() {
        // Short and long texts, so the candidate filter runs below the skip loop's threshold too, and
        // a tiny alphabet so the work budget can run out and fall back to the linear-time scan.
        val random = Random(0x7A11L)
        for (trial in 0..20000 - 1) {
            val alphabet = 1 + random.nextInt(4)
            val text: String =
                randomFromAlphabet(random, random.nextInt(if (random.nextBoolean()) 160 else 1200), alphabet)
            val literal: String = randomFromAlphabet(random, 1 + random.nextInt(10), alphabet)
            val needle = literal.toByteArray(StandardCharsets.UTF_8)
            val scanner = Utf8InputScanner(text.toByteArray(StandardCharsets.UTF_8))
            val start = random.nextInt(text.length + 1)

            Assertions.assertThat(scanner.indexOfInTail(needle, literalFailure(needle), start))
                .`as`("%s in %s from %s", literal, text, start)
                .isEqualTo(text.indexOf(literal, start))
        }
    }

    @Test
    fun multiByteLiteralSearchHandlesMatchesAtTheVeryEndOfTheInput() {
        for (length in 2..20) {
            val literal = StringBuilder()
            for (index in 0..<length) {
                literal.append(('a'.code + (index % 5)).toChar())
            }
            val needle = literal.toString().toByteArray(StandardCharsets.UTF_8)
            // Prefix lengths straddle the threshold so the match lands in the filter's tail as well as
            // in the skip loop.
            for (prefix in intArrayOf(0, 19, filterThreshold(needle.size), filterThreshold(needle.size) + 7)) {
                val text = "z".repeat(prefix) + literal
                val scanner = Utf8InputScanner(text.toByteArray(StandardCharsets.UTF_8))

                Assertions.assertThat(scanner.indexOf(needle, literalFailure(needle), literalShifts(needle)))
                    .`as`("length %s, prefix %s", length, prefix)
                    .isEqualTo(prefix)
            }
        }
    }

    @Test
    fun multiByteLiteralSearchAgreesWithStringIndexOfOnNonAsciiLiterals() {
        val literals: MutableList<String> = mutableListOf<String?>("é", "café", "日本語", "😀x", "aé😀")
        val texts =
            List.of<String?>("", "café", "le café est chaud", "日本語のテキスト", "😀😀x", "aé😀aé😀", "z".repeat(50))
        for (literal in literals) {
            val needle = literal.toByteArray(StandardCharsets.UTF_8)
            val failure: IntArray = literalFailure(needle)
            val shifts: IntArray? = literalShifts(needle)
            for (text in texts) {
                // Padding pushes the same case past the threshold so both paths see non-ASCII literals.
                for (padded in List.of<String>(text, "\u00e9\u65e5".repeat(filterThreshold(needle.size)) + text)) {
                    val haystack = padded.toByteArray(StandardCharsets.UTF_8)

                    Assertions.assertThat(Utf8InputScanner(haystack).indexOf(needle, failure, shifts))
                        .`as`("%s in %s", literal, padded)
                        .isEqualTo(naiveIndexOf(haystack, needle, 0))
                }
            }
        }
    }

    @Test
    fun rareInteriorByteSearchRespectsViewsAndStartOffsets() {
        val needle = "Шерлок Холмс".toByteArray(StandardCharsets.UTF_8)
        val failure: IntArray = literalFailure(needle)
        val shifts: IntArray? = literalShifts(needle)
        val content = "xШерлок Холмс y Шерлок Холмс z".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(content.size + 4)
        System.arraycopy(content, 0, storage, 2, content.size)
        val scanner = Utf8InputScanner(storage, 2, content.size)
        val second = "xШерлок Холмс y ".toByteArray(StandardCharsets.UTF_8).size

        Assertions.assertThat(scanner.indexOf(needle, failure, shifts, 0, 1)).isEqualTo(1)
        Assertions.assertThat(scanner.indexOf(needle, failure, shifts, 2, 1)).isEqualTo(second)
        Assertions.assertThat(scanner.indexOf(needle, failure, shifts, second + 1, 1)).isEqualTo(-1)
    }

    @Test
    fun rareInteriorByteSearchFallsBackOnDenseFalseCandidates() {
        val needle = "abacus".toByteArray(StandardCharsets.UTF_8)
        val haystack = ("x".repeat(10000) + "abacus").toByteArray(StandardCharsets.UTF_8)
        Arrays.fill(haystack, 0, haystack.size - needle.size, 'c'.code.toByte())
        val scanner = Utf8InputScanner(haystack)

        Assertions.assertThat(scanner.indexOf(needle, literalFailure(needle), literalShifts(needle), 0, 3))
            .isEqualTo(haystack.size - needle.size)
    }

    @Test
    fun rareInteriorByteSearchAgreesWithLinearSearchAcrossWindows() {
        val random = Random(0x9170B17EL)
        val alphabet = arrayOf<String?>("Ш", "ш", "е", "л", "о", "к", " ", "Х", "м", "с", "x")
        val needle = "Шерлок Холмс".toByteArray(StandardCharsets.UTF_8)
        val failure: IntArray = literalFailure(needle)
        val shifts: IntArray? = literalShifts(needle)
        val anchorOffset = RarityOracle.rarestUtf8LiteralByteOffset(needle)
        Assertions.assertThat(anchorOffset).isEqualTo(1)

        for (trial in 0..2000 - 1) {
            val text = StringBuilder()
            val chunks = 1 + random.nextInt(600)
            for (chunk in 0..<chunks) {
                text.append(alphabet[random.nextInt(alphabet.size)])
            }
            if (random.nextBoolean()) {
                text.insert(random.nextInt(text.length + 1), "Шерлок Холмс")
            }
            val haystack = text.toString().toByteArray(StandardCharsets.UTF_8)
            val viewOffset = random.nextInt(8)
            val storage = ByteArray(viewOffset + haystack.size + 8)
            System.arraycopy(haystack, 0, storage, viewOffset, haystack.size)
            val scanner = Utf8InputScanner(storage, viewOffset, haystack.size)
            val start = random.nextInt(haystack.size + 1)

            val expected =
                Utf8InputScanner.indexOfLinear(haystack, 0, haystack.size, needle, failure, start)
            Assertions.assertThat(scanner.indexOf(needle, failure, shifts, start, anchorOffset))
                .`as`("trial %s, start %s", trial, start)
                .isEqualTo(expected)
        }
    }

    @Test
    fun emptySingleByteAndFourByteWindowsStayBounded() {
        val empty = Utf8InputScanner(byteArrayOf('x'.code.toByte()), 1, 0)
        Assertions.assertThat(empty.decodeForward(0)).isEqualTo(InputScanner.decoded(-1, 0))
        Assertions.assertThat(empty.decodeBackward(0)).isEqualTo(InputScanner.decoded(-1, 0))

        val single = Utf8InputScanner(byteArrayOf('x'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), 1, 1)
        Assertions.assertThat(single.decodeForward(0)).isEqualTo(InputScanner.decoded('a'.code, 1))
        Assertions.assertThat(single.decodeBackward(1)).isEqualTo(InputScanner.decoded('a'.code, 0))

        val scalar = "😀".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(scalar.size + 2)
        System.arraycopy(scalar, 0, storage, 1, scalar.size)
        val fourByte = Utf8InputScanner(storage, 1, scalar.size)
        Assertions.assertThat(fourByte.decodeForward(0)).isEqualTo(InputScanner.decoded(0x1F600, 4))
        Assertions.assertThat(fourByte.decodeBackward(4)).isEqualTo(InputScanner.decoded(0x1F600, 0))
    }

    @Test
    fun windowBoundsRejectOverflowAndPartialScalars() {
        val bytes = "xéy".toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThatThrownBy(ThrowingCallable { Utf8InputScanner(bytes, -1, 1) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { Utf8InputScanner(bytes, 0, Int.Companion.MAX_VALUE) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { Utf8InputScanner.validate(bytes, 2, 1) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("byte 0")
    }

    @Test
    fun codePointBoundariesRecognizeOnlyScalarEdges() {
        val bytes = "aé😀z".toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(bytes, 0, bytes.size)

        Assertions.assertThat(scanner.isCodePointBoundary(0)).isTrue()
        Assertions.assertThat(scanner.isCodePointBoundary(1)).isTrue()
        Assertions.assertThat(scanner.isCodePointBoundary(2)).isFalse()
        Assertions.assertThat(scanner.isCodePointBoundary(3)).isTrue()
        Assertions.assertThat(scanner.isCodePointBoundary(4)).isFalse()
        Assertions.assertThat(scanner.isCodePointBoundary(5)).isFalse()
        Assertions.assertThat(scanner.isCodePointBoundary(6)).isFalse()
        Assertions.assertThat(scanner.isCodePointBoundary(7)).isTrue()
        Assertions.assertThat(scanner.isCodePointBoundary(8)).isTrue()
    }

    @Test
    fun malformedRecoveryMakesEveryRecoveredByteABoundary() {
        val bytes = byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
        val scanner = Utf8InputScanner(bytes)

        for (position in 0..bytes.size) {
            Assertions.assertThat(scanner.isCodePointBoundary(position)).`as`("position %s", position).isTrue()
        }
    }

    @Test
    fun strictValidationAgreesWithJdkDecoderAndReportsFirstErrorOffset() {
        for (value in 0..0xFFFF) {
            assertValidationAgreement(byteArrayOf((value ushr 8).toByte(), value.toByte()), 0, 2)
        }

        val random = Random(0x5AFE516L)
        for (trial in 0..20000 - 1) {
            val length = random.nextInt(9)
            val logical = ByteArray(length)
            random.nextBytes(logical)
            val window = ByteArray(length + 4)
            Arrays.fill(window, 0x80.toByte())
            System.arraycopy(logical, 0, window, 2, length)
            assertValidationAgreement(window, 2, length)
        }
    }

    @Test
    fun boyerMooreHorspoolReturnsExhaustionSentinelOnAdversarialInput() {
        val needle = "baaaaa".toByteArray(StandardCharsets.UTF_8)
        val shifts: IntArray? = literalShifts(needle)
        // 100 "a"s is below the filter threshold so Horspool is selected
        val haystack = "a".repeat(100).toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(haystack)

        // Tests that boundedBoyerMooreHorspool hits the WorkLimit on repetitive mismatches and returns
        // -2
        Assertions.assertThat(scanner.boundedBoyerMooreHorspool(needle, shifts, 0)).isEqualTo(-2)

        // Tests that the public indexOf catches -2, falls back to linear KMP, and correctly completes
        val failure: IntArray = literalFailure(needle)
        Assertions.assertThat(scanner.indexOf(needle, failure, shifts, 0)).isEqualTo(-1)
    }

    @Test
    fun boyerMooreHorspoolFindsMatchWithinBudgetWithoutExhaustion() {
        val needle = "xyz".toByteArray(StandardCharsets.UTF_8)
        val shifts: IntArray? = literalShifts(needle)
        val haystack = "abcdefghijklmnopqrstuvwxyz".toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(haystack)

        Assertions.assertThat(scanner.boundedBoyerMooreHorspool(needle, shifts, 0)).isEqualTo(23)
    }

    @Test
    fun boyerMooreHorspoolCompletesMatchWithoutExhaustionWhenWinningCandidateMatches() {
        val needle = "baa".toByteArray(StandardCharsets.UTF_8)
        val shifts: IntArray? = literalShifts(needle)
        // The failed candidates consume the work budget before the final candidate. With hoisted work
        // checking, the inner loop completes the true match without aborting partway through it.
        val haystack = "aaaaaaaaabaa".toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(haystack)

        Assertions.assertThat(scanner.boundedBoyerMooreHorspool(needle, shifts, 0)).isEqualTo(9)
    }

    @Test
    fun indexOfUtf8SequenceFindsOnlyWholeEncodedCodePoints() {
        val sequence = "\uFF3D".toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThat(utf8SequenceIndex("\uFF3D", sequence, 0)).isEqualTo(0)
        Assertions.assertThat(utf8SequenceIndex("ab\uFF3D", sequence, 0)).isEqualTo(2)
        Assertions.assertThat(utf8SequenceIndex("\u4E00\uFF3Dx", sequence, 0)).isEqualTo(3)
        Assertions.assertThat(utf8SequenceIndex("\uFF3D\u4E00\uFF3D", sequence, 1)).isEqualTo(6)
        Assertions.assertThat(utf8SequenceIndex("no closing bracket \uFF3B", sequence, 0)).isEqualTo(-1)
        Assertions.assertThat(utf8SequenceIndex("", sequence, 0)).isEqualTo(-1)
        // U+00BD (C2 BD) and U+FF7D (EF BD BD) end in the same byte as U+FF3D (EF BC BD).
        Assertions.assertThat(utf8SequenceIndex("\u00BD\uFF7D\u00BD", sequence, 0)).isEqualTo(-1)
        Assertions.assertThat(utf8SequenceIndex("\u00BD\uFF7D\uFF3D", sequence, 0)).isEqualTo(5)
        // Long inputs reach the vector byte kernel, if one is available.
        val longText = "\u4E00\u00BD".repeat(200)
        Assertions.assertThat(utf8SequenceIndex(longText, sequence, 0)).isEqualTo(-1)
        Assertions.assertThat(utf8SequenceIndex(longText + "\uFF3D", sequence, 0))
            .isEqualTo(longText.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun indexOfUtf8SequenceRespectsScannerBounds() {
        val sequence = "\uFF3D".toByteArray(StandardCharsets.UTF_8)
        val bytes = "\uFF3Dab\uFF3Dcd\uFF3D".toByteArray(StandardCharsets.UTF_8)

        // A window that starts after the first sequence's lead byte does not see that sequence.
        val straddlesStart = Utf8InputScanner(bytes, 1, bytes.size - 1)
        Assertions.assertThat(straddlesStart.indexOfUtf8Sequence(sequence, 0, straddlesStart.length))
            .isEqualTo(4)

        // A window that ends inside the last sequence does not see that sequence.
        val straddlesEnd = Utf8InputScanner(bytes, 3, bytes.size - 4)
        Assertions.assertThat(straddlesEnd.indexOfUtf8Sequence(sequence, 0, straddlesEnd.length)).isEqualTo(2)
        Assertions.assertThat(straddlesEnd.indexOfUtf8Sequence(sequence, 3, straddlesEnd.length)).isEqualTo(-1)

        // The limit excludes a sequence that would end past it.
        val whole = Utf8InputScanner(bytes, 0, bytes.size)
        Assertions.assertThat(whole.indexOfUtf8Sequence(sequence, 1, 7)).isEqualTo(-1)
        Assertions.assertThat(whole.indexOfUtf8Sequence(sequence, 1, 8)).isEqualTo(5)
    }

    @Test
    fun indexOfMixedPairFindsEarliestAsciiOrEncodedSequence() {
        val sequence = "\uFF3D".toByteArray(StandardCharsets.UTF_8)

        Assertions.assertThat(utf8MixedPairIndex("]", ']'.code, sequence, 0)).isEqualTo(0)
        Assertions.assertThat(utf8MixedPairIndex("\uFF3D", ']'.code, sequence, 0)).isEqualTo(0)
        Assertions.assertThat(utf8MixedPairIndex("\u4E00]\uFF3D", ']'.code, sequence, 0)).isEqualTo(3)
        Assertions.assertThat(utf8MixedPairIndex("\u4E00\uFF3D]", ']'.code, sequence, 0)).isEqualTo(3)
        Assertions.assertThat(utf8MixedPairIndex("\u4E00\uFF3D]", ']'.code, sequence, 4)).isEqualTo(6)
        Assertions.assertThat(utf8MixedPairIndex("no closing bracket \uFF3B", ']'.code, sequence, 0)).isEqualTo(-1)
        // U+00BD (C2 BD) and U+FF7D (EF BD BD) share the trailing byte 0xBD with U+FF3D (EF BC BD).
        Assertions.assertThat(utf8MixedPairIndex("\u00BD\uFF7D\u00BD", ']'.code, sequence, 0)).isEqualTo(-1)
        Assertions.assertThat(utf8MixedPairIndex("\u00BD\uFF7D\uFF3D", ']'.code, sequence, 0)).isEqualTo(5)
        Assertions.assertThat(utf8MixedPairIndex("\u00BD\uFF7D]", ']'.code, sequence, 0)).isEqualTo(5)

        // Slice bounds: a sequence starting before start or ending past limit is ignored.
        val bytes = "\uFF3Dab\uFF3Dcd]".toByteArray(StandardCharsets.UTF_8)
        val straddlesStart = Utf8InputScanner(bytes, 1, bytes.size - 1)
        Assertions.assertThat(straddlesStart.indexOfMixedPair(']'.code, sequence, 0, straddlesStart.length))
            .isEqualTo(4)
        val whole = Utf8InputScanner(bytes, 0, bytes.size)
        Assertions.assertThat(whole.indexOfMixedPair(']'.code, sequence, 1, 7)).isEqualTo(-1)
        Assertions.assertThat(whole.indexOfMixedPair(']'.code, sequence, 1, 8)).isEqualTo(5)
    }

    companion object {
        /**
         * Smallest input that selects the SWAR candidate filter over the skip loop, mirroring `Utf8InputScanner.MIN_FILTER_LENGTH` and `FILTER_LENGTH_FACTOR`. Tests run each scenario
         * both below and above this bound so that both search paths are covered.
         */
        private fun filterThreshold(literalLength: Int): Int {
            return max(64, literalLength * 40)
        }

        private fun assertValidationAgreement(bytes: ByteArray, offset: Int, length: Int) {
            val jdkError: Int = firstJdkError(bytes, offset, length)
            try {
                Utf8InputScanner.validate(bytes, offset, length)
                Assertions.assertThat(jdkError).`as`("JDK error for %s", bytes.contentToString()).isEqualTo(-1)
            } catch (e: IllegalArgumentException) {
                Assertions.assertThat(jdkError).`as`("JDK accepted %s", bytes.contentToString()).isNotEqualTo(-1)
                Assertions.assertThat<IllegalArgumentException?>(e).hasMessageContaining("byte " + jdkError)
            }
        }

        /** Builds the KMP failure function, mirroring what `Pattern` precomputes per literal.  */
        private fun literalFailure(literal: ByteArray): IntArray {
            val failure = IntArray(literal.size)
            var matched = 0
            for (index in 1..<literal.size) {
                while (matched > 0 && literal[index] != literal[matched]) {
                    matched = failure[matched - 1]
                }
                if (literal[index] == literal[matched]) {
                    matched++
                }
                failure[index] = matched
            }
            return failure
        }

        /** Builds the bad-character skip table, mirroring what `Pattern` precomputes.  */
        private fun literalShifts(literal: ByteArray): IntArray? {
            if (literal.size < 2) {
                return null
            }
            val shifts = IntArray(256)
            Arrays.fill(shifts, literal.size)
            for (index in 0..<literal.size - 1) {
                shifts[literal[index].toInt() and 0xFF] = literal.size - index - 1
            }
            return shifts
        }

        private fun naiveIndexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int {
            var position = start
            outer@ while (position + needle.size <= haystack.size) {
                for (index in needle.indices) {
                    if (haystack[position + index] != needle[index]) {
                        position++
                        continue@outer
                    }
                }
                return position
                position++
            }
            return -1
        }

        private fun asciiBitmap(ranges: IntArray, first: Int, last: Int): Long {
            var bitmap: Long = 0
            var index = 0
            while (index < ranges.size) {
                val low = max(first, ranges[index])
                val high = min(last, ranges[index + 1])
                for (value in low..high) {
                    bitmap = bitmap or (1L shl (value - first))
                }
                index += 2
            }
            return bitmap
        }

        private fun asciiRange(low: Int, high: Int): IntArray {
            val result = IntArray(high - low + 1)
            for (value in low..high) {
                result[value - low] = value
            }
            return result
        }

        private fun randomFromAlphabet(random: Random, length: Int, alphabet: Int): String {
            val result = StringBuilder(length)
            for (index in 0..<length) {
                result.append(('a'.code + random.nextInt(alphabet)).toChar())
            }
            return result.toString()
        }

        private fun randomAscii(random: Random, length: Int): String {
            val result = StringBuilder(length)
            for (index in 0..<length) {
                result.append(('a'.code + random.nextInt(4)).toChar())
            }
            return result.toString()
        }

        private fun firstJdkError(bytes: ByteArray, offset: Int, length: Int): Int {
            val input = ByteBuffer.wrap(bytes, offset, length)
            val decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                decoder.decode(input)
                return -1
            } catch (e: CharacterCodingException) {
                return input.position() - offset
            }
        }

        private fun traceForward(scanner: Utf8InputScanner): MutableList<Int?> {
            val result = ArrayList<Int?>()
            var position = 0
            while (position < scanner.length) {
                val decoded = scanner.decodeForward(position)
                result.add(InputScanner.codePoint(decoded))
                position = InputScanner.position(decoded)
            }
            return result
        }

        private fun traceBackward(scanner: Utf8InputScanner): MutableList<Int?> {
            val result = ArrayList<Int?>()
            var position = scanner.length
            while (position > 0) {
                val decoded = scanner.decodeBackward(position)
                result.add(InputScanner.codePoint(decoded))
                position = InputScanner.position(decoded)
            }
            return result
        }

        private fun malformedSequences(): MutableList<ByteArray> {
            return List.of<ByteArray?>(
                byteArrayOf(0x80.toByte()),
                byteArrayOf(0xC0.toByte()),
                byteArrayOf(0xC1.toByte()),
                byteArrayOf(0xC2.toByte()),
                byteArrayOf(0xE0.toByte()),
                byteArrayOf(0xF0.toByte()),
                byteArrayOf(0xF5.toByte()),
                byteArrayOf(0xFF.toByte()),
                byteArrayOf(0xC0.toByte(), 0x80.toByte()),
                byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
                byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())
            )
        }

        private fun utf8SequenceIndex(text: String, sequence: ByteArray, start: Int): Int {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            return Utf8InputScanner(bytes, 0, bytes.size)
                .indexOfUtf8Sequence(sequence, start, bytes.size)
        }

        private fun utf8MixedPairIndex(text: String, ascii: Int, sequence: ByteArray?, start: Int): Int {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            return Utf8InputScanner(bytes, 0, bytes.size)
                .indexOfMixedPair(ascii, sequence, start, bytes.size)
        }
    }
}
