// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Test
import org.safere.Matcher
import org.safere.Pattern
import org.safere.Utf8Input
import org.safere.Utf8Matcher
import java.lang.Byte
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.List
import java.util.stream.IntStream
import kotlin.AssertionError
import kotlin.Int
import kotlin.IntArray
import kotlin.String
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.contentToString
import kotlin.collections.first
import kotlin.collections.indices
import kotlin.collections.last
import kotlin.intArrayOf

()
import kotlin.text.toByteArray

/** ICU4J-based Unicode grapheme conformance; the historical name preserves saved fuzz seeds.  */
class EmojiZwjGraphemeFuzzer {
    @FuzzTest(maxDuration = "30s")
    fun emojiZwjGrapheme(data: FuzzedDataProvider) {
        val choices = data.consumeBytes(128)
        val codePoints = IntArray(choices.size)
        for (i in choices.indices) {
            codePoints[i] = ALPHABET[Byte.toUnsignedInt(choices[i]) % ALPHABET.size]
        }
        assertSegmentation(codePoints)
    }

    @Test
    fun exhaustiveShortSequences() {
        // Check rule ordering deterministically as well as fuzzing longer combinations.
        assertSegmentation(IntArray(0))
        val alphabet = IcuGraphemes.representatives()
        for (first in alphabet) {
            assertSegmentation(intArrayOf(first))
            for (second in alphabet) {
                assertSegmentation(intArrayOf(first, second))
                for (third in alphabet) {
                    assertSegmentation(intArrayOf(first, second, third))
                    for (fourth in alphabet) {
                        assertSegmentation(intArrayOf(first, second, third, fourth))
                    }
                }
            }
        }
    }

    @Test
    fun generatedLongerSequences() {
        val random = Random(954)
        for (sample in 0..2000 - 1) {
            val cps = IntArray(random.nextInt(4, 129))
            for (i in cps.indices) {
                cps[i] = ALPHABET[random.nextInt(ALPHABET.size)]
            }
            assertSegmentation(cps)
        }
    }

    companion object {
        // Preserve the original choices first, then add every GCB/InCB/ExtPict overlap.
        private val ALPHABET: IntArray = alphabet()

        private fun alphabet(): IntArray {
            val original = intArrayOf(
                0x1F469, 0x1F44D, 0x0301, 0xFE0F, 0x1F3FD, 0x0903, 0x200D,
                0x0600, 0x110BD, 0x1F1E6, 0x1F1E7, 0x000D, 0x000A, 0x0061
            )
            return IntStream.concat(Arrays.stream(original), Arrays.stream(IcuGraphemes.representatives()))
                .distinct()
                .toArray()
        }

        private val PATTERNS: MutableList<Pattern> = List.of<Pattern?>(
            Pattern.compile("\\X"),
            Pattern.compile("\\X\\b{g}"),
            Pattern.compile("(\\X)"),
            Pattern.compile("\\b{g}\\X\\b{g}")
        )
        private val QUANTIFIED: Pattern = Pattern.compile("\\A(\\X)*\\z")
        private val FIRST_AND_REST: Pattern = Pattern.compile("\\A(\\X)((?:\\X)*)\\z")
        private val BOUNDARY: Pattern = Pattern.compile("\\b{g}")
        private val SCALAR_THEN_BOUNDARY: Pattern = Pattern.compile("(?s).\\b{g}")
        private val BOUNDARY_THEN_SCALAR: Pattern = Pattern.compile("(?s)\\b{g}.")

        private fun assertSegmentation(codePoints: IntArray) {
            val input = String(codePoints, 0, codePoints.size)
            val expected = IcuGraphemes.clusters(codePoints)
            for (pattern in PATTERNS) {
                val matcher = pattern.matcher(input)
                val actual: MutableList<String?> = ArrayList<String?>()
                while (matcher.find()) {
                    actual.add(matcher.group())
                }
                check(pattern.pattern(), codePoints, actual, expected)
                val bytes = input.toByteArray(StandardCharsets.UTF_8)
                val utf8 = pattern.matcher(Utf8Input.validated(bytes))
                actual.clear()
                while (utf8.find()) {
                    actual.add(String(bytes, utf8.start(), utf8.end() - utf8.start(), StandardCharsets.UTF_8))
                }
                check("UTF-8 " + pattern.pattern(), codePoints, actual, expected)
            }
            val lastScalars =
                expected.stream()
                    .map<String?> { cluster: String? -> Character.toString(cluster!!.codePointBefore(cluster.length)) }
                    .toList()
            val firstScalars =
                expected.stream().map<String?> { cluster: String? -> Character.toString(cluster!!.codePointAt(0)) }
                    .toList()
            checkFinds(SCALAR_THEN_BOUNDARY, input, codePoints, lastScalars)
            checkFinds(BOUNDARY_THEN_SCALAR, input, codePoints, firstScalars)
            val expectedBoundaries: MutableList<Int?> = ArrayList<Int?>()
            expectedBoundaries.add(
                0
            ) // Regex boundary convention also includes position zero on empty input.
            var end = 0
            for (cluster in expected) {
                end += cluster.length
                expectedBoundaries.add(end)
            }
            val boundaries: MutableList<Int?> = ArrayList<Int?>()
            val boundary: Matcher = BOUNDARY.matcher(input)
            while (boundary.find()) {
                boundaries.add(boundary.start())
            }
            if (boundaries != expectedBoundaries) {
                throw AssertionError(
                    ("Boundary trace on "
                            + codePoints.contentToString() + ": expected "
                            + expectedBoundaries
                            + ", actual "
                            + boundaries)
                )
            }
            val quantified: Matcher = QUANTIFIED.matcher(input)
            if (!quantified.matches()
                || quantified.group(1) != if (expected.isEmpty()) null else expected.last()
            ) {
                throw AssertionError("Quantified grapheme capture on " + codePoints.contentToString())
            }
            if (!expected.isEmpty()) {
                val composed: Matcher = FIRST_AND_REST.matcher(input)
                if (!composed.matches() || (composed.group(1) != expected.first()) || (composed.group(2) != String.join(
                        "",
                        expected.subList(1, expected.size)
                    ))
                ) {
                    throw AssertionError("Consecutive grapheme captures on " + codePoints.contentToString())
                }
            }
            val encoded = input.toByteArray(StandardCharsets.UTF_8)
            val utf8Quantified: Utf8Matcher = QUANTIFIED.matcher(Utf8Input.validated(encoded))
            if (!utf8Quantified.matches()) {
                throw AssertionError("UTF-8 quantified clusters on " + codePoints.contentToString())
            }
            val expectedLastStart =
                if (expected.isEmpty()) -1 else encoded.size - expected.last()
                    .toByteArray(StandardCharsets.UTF_8).size
            if (utf8Quantified.start(1) != expectedLastStart
                || utf8Quantified.end(1) != (if (expected.isEmpty()) -1 else encoded.size)
            ) {
                throw AssertionError("UTF-8 quantified capture on " + codePoints.contentToString())
            }
            if (!expected.isEmpty()) {
                val utf8Composed: Utf8Matcher = FIRST_AND_REST.matcher(Utf8Input.validated(encoded))
                val firstEnd = expected.first().toByteArray(StandardCharsets.UTF_8).size
                if (!utf8Composed.matches() || utf8Composed.start(1) != 0 || utf8Composed.end(1) != firstEnd || utf8Composed.start(
                        2
                    ) != firstEnd || utf8Composed.end(2) != encoded.size
                ) {
                    throw AssertionError("UTF-8 consecutive captures on " + codePoints.contentToString())
                }
            }
            val byteBoundaries: MutableList<Int?> = ArrayList<Int?>()
            var byteEnd = 0
            byteBoundaries.add(byteEnd)
            for (cluster in expected) {
                byteEnd += cluster.toByteArray(StandardCharsets.UTF_8).size
                byteBoundaries.add(byteEnd)
            }
            val utf8Boundary: Utf8Matcher = BOUNDARY.matcher(Utf8Input.validated(encoded))
            boundaries.clear()
            while (utf8Boundary.find()) {
                boundaries.add(utf8Boundary.start())
            }
            if (boundaries != byteBoundaries) {
                throw AssertionError(
                    ("UTF-8 boundary trace on "
                            + codePoints.contentToString() + ": expected "
                            + byteBoundaries
                            + ", actual "
                            + boundaries)
                )
            }
            // split("") returns one empty string; there are no clusters in empty text.
            if (!input.isEmpty()) {
                check("split", codePoints, Arrays.asList<String?>(*BOUNDARY.split(input)), expected)
            }
        }

        private fun checkFinds(
            pattern: Pattern, input: String, codePoints: IntArray?, expected: MutableList<String>?
        ) {
            val actual: MutableList<String?> = ArrayList<String?>()
            val scalar = pattern.matcher(input)
            while (scalar.find()) {
                actual.add(scalar.group())
            }
            check(pattern.pattern(), codePoints, actual, expected)
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val utf8 = pattern.matcher(Utf8Input.validated(bytes))
            actual.clear()
            while (utf8.find()) {
                actual.add(String(bytes, utf8.start(), utf8.end() - utf8.start(), StandardCharsets.UTF_8))
            }
            check("UTF-8 " + pattern.pattern(), codePoints, actual, expected)
        }

        private fun check(
            operation: String?,
            codePoints: IntArray?,
            actual: MutableList<String?>,
            expected: MutableList<String>?
        ) {
            if (actual != expected) {
                throw AssertionError(
                    ("UAX #29 mismatch for "
                            + operation
                            + " on "
                            + codePoints.contentToString() + ": expected "
                            + expected
                            + ", actual "
                            + actual)
                )
            }
        }
    }
}
