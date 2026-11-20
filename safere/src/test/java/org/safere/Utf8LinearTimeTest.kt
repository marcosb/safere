// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Consumer
import kotlin.math.max

/** Work-bound tests for forward and reverse UTF-8 decoding.  */
@DisabledForCrosscheck("package-private UTF-8 decoder work accounting is SafeRE-internal")
@Tag("work-counter")
internal class Utf8LinearTimeTest {
    @Test
    fun forwardAndReverseDecodingScaleLinearlyForValidAndMalformedInput() {
        assertFourXInputStaysNearLinear(Consumer { size: Int? -> scanBothDirections(validInput(size!!)) })
        assertFourXInputStaysNearLinear(Consumer { size: Int? -> scanBothDirections(malformedInput(size!!)) })
    }

    @Test
    fun matchingBehaviorsScaleLinearlyOverUtf8Input() {
        assertLargeFourXInputStaysNearLinear(
            Consumer { size: Int? ->
                findAll(
                    "[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$", "a".repeat(size!!).toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            })
        val overlappingLiteral = "a".repeat(1000) + "b"
        assertLargeFourXInputStaysNearLinear(
            Consumer { size: Int? ->
                findAll(
                    overlappingLiteral,
                    "a".repeat(size!!).toByteArray(StandardCharsets.UTF_8)
                )
            })
        assertLargeFourXInputStaysNearLinear(Consumer { size: Int? ->
            findAll(
                "", "😀".repeat(size!!).toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        })
        assertLargeFourXInputStaysNearLinear(
            Consumer { size: Int? ->
                findAll(
                    "([a-z]+)([0-9]+)",
                    "a1".repeat(size!!).toByteArray(StandardCharsets.UTF_8)
                )
            })
        assertLargeFourXInputStaysNearLinear(
            Consumer { size: Int? ->
                findAll(
                    "\\bword\\b",
                    "word ".repeat(size!!).toByteArray(StandardCharsets.UTF_8)
                )
            })
        assertLargeFourXInputStaysNearLinear(
            Consumer { size: Int? ->
                findAll(
                    "\\d{3}/\\d{3}/\\d{4}",
                    "path /api ".repeat(size!!).toByteArray(StandardCharsets.UTF_8)
                )
            })
        assertLargeFourXInputStaysNearLinear(Consumer { size: Int? ->
            findAll(
                "\\X", "á".repeat(size!!).toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        })
        assertLargeFourXInputStaysNearLinear(Consumer { size: Int? -> findAll(".", malformedInput(size!!)) })
    }

    @Test
    fun singleValueAsciiPrefixScanAccountsForEveryExaminedByte() {
        val input = "a".repeat(10000).toByteArray(StandardCharsets.UTF_8)
        val scanner = Utf8InputScanner(input)
        val asciiClass = AsciiBitmap.of('z'.code)

        val work =
            WorkCounter.countForTesting({ Assertions.assertThat(scanner.indexOfAsciiClass(asciiClass, 0)).isEqualTo(-1) })

        Assertions.assertThat(work).isEqualTo(input.size.toLong())
    }

    @Test
    fun repeatedClassReplacementScalesLinearlyAcrossScalarsAndEmptyMatches() {
        for (regex in mutableListOf<String>("[aé😀]*", "[aé😀]+", "[^a]+")) {
            val pattern = Pattern.compile(regex)
            val replacement = Utf8Input.validated(byteArrayOf('x'.code.toByte()))
            assertFourXInputStaysNearLinear(
                Consumer { size: Int? ->
                    val input = Utf8Input.trusted("aé😀!".repeat(size!!).toByteArray(StandardCharsets.UTF_8))
                    pattern.matcher(input)
                        .replaceAll(Utf8Sink { bytes: ByteArray?, offset: Int, length: Int -> }, replacement)
                })
        }
    }

    @Test
    fun fullyAnchoredBooleanFindScalesLinearlyWithInteriorFailures() {
        for (terminal in mutableListOf<String>("END", "[0-9]")) {
            val pattern = Pattern.compile("^([a-z]+)" + terminal + "\\z")
            val suffix = if (terminal == "END") "END" else "5"
            assertLargeFourXInputStaysNearLinear(
                Consumer { size: Int? ->
                    val input =
                        Utf8Input.validated(("a".repeat(size!!) + "!a" + suffix).toByteArray(StandardCharsets.UTF_8))
                    Assertions.assertThat(pattern.find(input)).isFalse()
                })
        }
    }

    companion object {
        private fun assertFourXInputStaysNearLinear(task: Consumer<Int?>) {
            task.accept(10000)
            task.accept(40000)
            val smallerWork = WorkCounter.countForTesting({ task.accept(10000) })
            val largerWork = WorkCounter.countForTesting({ task.accept(40000) })

            Assertions.assertThat(smallerWork).isGreaterThanOrEqualTo(0)
            Assertions.assertThat(largerWork).isLessThanOrEqualTo(max(10, smallerWork * 5))
        }

        private fun assertLargeFourXInputStaysNearLinear(task: Consumer<Int?>) {
            task.accept(40000)
            task.accept(160000)
            val smallerWork = WorkCounter.countForTesting({ task.accept(40000) })
            val largerWork = WorkCounter.countForTesting({ task.accept(160000) })

            Assertions.assertThat(smallerWork).isGreaterThanOrEqualTo(0)
            Assertions.assertThat(largerWork).isLessThanOrEqualTo(max(10, smallerWork * 5))
        }

        private fun scanBothDirections(bytes: ByteArray?) {
            val scanner = Utf8InputScanner(bytes!!)
            var position = 0
            while (position < scanner.length) {
                position = InputScanner.position(scanner.decodeForward(position))
            }
            Assertions.assertThat(position).isEqualTo(scanner.length)

            while (position > 0) {
                position = InputScanner.position(scanner.decodeBackward(position))
            }
            Assertions.assertThat(position).isZero()
        }

        private fun findAll(regex: String?, bytes: ByteArray?) {
            val matcher = Pattern.compile(regex).matcher(Utf8Input.trusted(bytes))
            while (matcher.find()) {
                matcher.start()
                matcher.end()
            }
        }

        private fun validInput(scalars: Int): ByteArray {
            return "aé😀".repeat(scalars / 3).toByteArray(StandardCharsets.UTF_8)
        }

        private fun malformedInput(bytes: Int): ByteArray {
            val input = ByteArray(bytes)
            Arrays.fill(input, 0x80.toByte())
            return input
        }
    }
}
