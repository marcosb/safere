// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.stream.Stream

/** Compatibility coverage for CRLF-sensitive multiline anchors in cached DFA transitions.  */
internal class MultilineCrLfDfaCachingTest {
    @Test
    fun reusedDollarStopsBeforeWholeCrLfTerminator() {
        // JDK 26 Pattern defines CRLF as one line terminator. Refs #713.
        val pattern = Pattern.compile("\\W*(?m:$)")
        pattern.matcher(" \na").find()
        val matcher = pattern.matcher("xb\r\n" + "a".repeat(30))
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)
        Assertions.assertThat(matcher.end()).isEqualTo(2)
    }

    @ParameterizedTest
    @MethodSource("reusedDollarCases")
    fun reusedDollarPreservesLineTerminatorBounds(
        regex: String, warmTerminator: String?, targetTerminator: String, suffixLength: Int
    ) {
        val pattern = Pattern.compile(regex)
        pattern.matcher(" " + warmTerminator + "a").find()
        val input = "xb" + targetTerminator + "a".repeat(suffixLength)
        assertFindSequence(pattern.matcher(input), regex, input)
    }

    @ParameterizedTest
    @MethodSource("reusedDollarCases")
    @DisabledForCrosscheck("UTF-8 input API is SafeRE-specific")
    fun reusedUtf8DollarPreservesLineTerminatorBounds(
        regex: String, warmTerminator: String?, targetTerminator: String, suffixLength: Int
    ) {
        val pattern = Pattern.compile(regex)
        pattern.matcher(Utf8Input.validated((" " + warmTerminator + "a").toByteArray(StandardCharsets.UTF_8))).find()
        val input = "xb" + targetTerminator + "a".repeat(suffixLength)
        val actual = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
        val expected = java.util.regex.Pattern.compile(regex).matcher(input)
        while (expected.find()) {
            Assertions.assertThat(actual.find()).isTrue()
            Assertions.assertThat(actual.start())
                .isEqualTo(input.substring(0, expected.start()).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(actual.end())
                .isEqualTo(input.substring(0, expected.end()).toByteArray(StandardCharsets.UTF_8).size)
            for (group in 0..expected.groupCount()) {
                Assertions.assertThat(actual.start(group))
                    .isEqualTo(input.substring(0, expected.start(group)).toByteArray(StandardCharsets.UTF_8).size)
                Assertions.assertThat(actual.end(group))
                    .isEqualTo(input.substring(0, expected.end(group)).toByteArray(StandardCharsets.UTF_8).size)
            }
        }
        Assertions.assertThat(actual.find()).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mixedCarriageReturnCases")
    @DisplayName("multiline ^ distinguishes CRLF from standalone carriage returns")
    fun multilineBeginningAnchorDistinguishesCrLfFromStandaloneCarriageReturns(
        description: String?, regex: String, input: String, expected: Boolean
    ) {
        val jdkResult = java.util.regex.Pattern.compile(regex).matcher(input).find()
        Assertions.assertThat(jdkResult).`as`("JDK result for %s", description).isEqualTo(expected)

        val pattern = org.safere.Pattern.compile(regex)
        Assertions.assertThat(pattern.matcher(input).find())
            .`as`("String result for %s", description)
            .isEqualTo(jdkResult)
        Assertions.assertThat(pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))).find())
            .`as`("UTF-8 result for %s", description)
            .isEqualTo(jdkResult)
    }

    companion object {
        private val LONG_PREFIX = "a".repeat(500)

        private fun assertFindSequence(actual: org.safere.Matcher, regex: String, input: String) {
            val expected = java.util.regex.Pattern.compile(regex).matcher(input)
            while (expected.find()) {
                Assertions.assertThat(actual.find()).isTrue()
                Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                for (group in 0..expected.groupCount()) {
                    Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                }
            }
            Assertions.assertThat(actual.find()).isFalse()
        }

        @JvmStatic
        private fun reusedDollarCases(): Stream<Arguments?>? {
            return Stream.of<String?>("\\W*(?m:$)", "\\s*(?m:$)", "(\\W*)(?m:$)", "\\W*?(?m:$)")
                .flatMap<Arguments?> { regex: String? ->
                    Stream.of<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")
                        .flatMap<Arguments?> { warm: String? ->
                            Stream.of<String?>("\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029")
                                .flatMap<Arguments?> { target: String? ->
                                    Stream.of<Int?>(30, 500)
                                        .map<Arguments?> { length: Int? -> Arguments.of(regex, warm, target, length) }
                                }
                        }
                }
        }

        @JvmStatic
        private fun mixedCarriageReturnCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(
                    "CRLF before standalone CR must not suppress a line start",
                    "(?m)^.X",
                    LONG_PREFIX + "\r\nqq\rqX",
                    true
                ),
                Arguments.of(
                    "standalone CR before CRLF must not create a line start inside CRLF",
                    "(?m)^\\nX",
                    LONG_PREFIX + "\rqY\r\nX",
                    false
                ),
                Arguments.of(
                    "repeated CRLF before standalone CR must preserve the standalone line start",
                    "(?m)^qX",
                    LONG_PREFIX + "\r\nqY\r\nqY\rqX",
                    true
                ),
                Arguments.of(
                    "repeated standalone CR before CRLF must not split the CRLF pair",
                    "(?m)^\\nX",
                    LONG_PREFIX + "\rqY\rqY\r\nX",
                    false
                )
            )
        }
    }
}
