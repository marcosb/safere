// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets
import java.util.regex.MatchResult
import java.util.regex.Pattern
import java.util.stream.Stream

/** Behavioral tests for patterns containing literal surrogate code points.  */
internal class SurrogateLiteralTest {
    @ParameterizedTest
    @MethodSource("nonMatchingValidUtf16Cases")
    fun surrogateLiteralsDoNotFalselyMatchOnStringOrUtf8(regex: String, input: String) {
        val jdkPattern = Pattern.compile(regex)
        Assertions.assertThat(jdkPattern.matcher(input).find()).isFalse()
        Assertions.assertThat(jdkPattern.matcher(input).matches()).isFalse()
        Assertions.assertThat(jdkPattern.matcher(input).lookingAt()).isFalse()

        val pattern = org.safere.Pattern.compile(regex)
        Assertions.assertThat(pattern.matcher(input).find()).isFalse()
        Assertions.assertThat(pattern.matcher(input).matches()).isFalse()
        Assertions.assertThat(pattern.matcher(input).lookingAt()).isFalse()

        val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
        Assertions.assertThat(pattern.matcher(utf8).find()).isFalse()
        Assertions.assertThat(pattern.matcher(utf8).matches()).isFalse()
        Assertions.assertThat(pattern.matcher(utf8).lookingAt()).isFalse()
        Assertions.assertThat(pattern.find(utf8)).isFalse()
    }

    @ParameterizedTest
    @MethodSource("matchingUnpairedSurrogateStringCases")
    fun unpairedSurrogateLiteralsMatchUnpairedSurrogatesInString(
        regex: String, input: String, expectedStart: Int, expectedEnd: Int
    ) {
        val expected = Pattern.compile(regex).matcher(input)
        val actual = org.safere.Pattern.compile(regex).matcher(input)

        Assertions.assertThat(actual.find()).isEqualTo(expected.find()).isTrue()
        Assertions.assertThat(actual.start()).isEqualTo(expected.start()).isEqualTo(expectedStart)
        Assertions.assertThat(actual.end()).isEqualTo(expected.end()).isEqualTo(expectedEnd)
        Assertions.assertThat(actual.group()).isEqualTo(expected.group())
    }

    @ParameterizedTest
    @MethodSource("entryPointCases")
    fun surrogateLiteralsAgreeWithJdkAcrossEntryPoints(regex: String, input: String) {
        val expected = Pattern.compile(regex)
        val actual = org.safere.Pattern.compile(regex)

        Assertions.assertThat(actual.matcher(input).replaceAll("_"))
            .isEqualTo(expected.matcher(input).replaceAll("_"))
        Assertions.assertThat(actual.matcher(input).replaceFirst("_"))
            .isEqualTo(expected.matcher(input).replaceFirst("_"))
        Assertions.assertThat<String?>(actual.split(input)).containsExactly(*expected.split(input))
        Assertions.assertThat<String?>(
            actual.matcher(input).results().map<String?> { r: MatchResult? -> r!!.start().toString() + "-" + r.end() }
                .toList())
            .isEqualTo(
                expected.matcher(input).results()
                    .map<String?> { r: MatchResult? -> r!!.start().toString() + "-" + r.end() }.toList()
            )
    }

    companion object {
        fun nonMatchingValidUtf16Cases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("\"\\uDBFB", "\"?"),
                Arguments.of("^\"\\uDBFB", "\"?"),
                Arguments.of("\\uDBFB", "\uDBFB\uDDBD"),
                Arguments.of("\\uDDBD", "\uDBFB\uDDBD"),
                Arguments.of("\\x{D800}\\x{DC00}", "\uD800\uDC00"),
                Arguments.of("\\uD800(?:\\uDC00)", "\uD800\uDC00"),
                Arguments.of("a\\x{D800}\\x{DC00}b", "a\uD800\uDC00b"),
                Arguments.of("\\uDC00[a-z]", "\uD800\uDC00a"),
                Arguments.of("a\\uDBFB[a-z]", "a?z"),
                Arguments.of("[a-z]+\\x{D800}\\x{DC00}[a-z]+", "a\uD800\uDC00b"),
                Arguments.of(".*a\\uDBFB$", "xa?"),
                Arguments.of(".*\\uDDBD$", "\uDBFB\uDDBD"),
                Arguments.of("(?:a\\x{D800}\\x{DC00}|banana)", "a\uD800\uDC00"),
                Arguments.of("(?:a\\uDBFB|banana)", "a?")
            )
        }

        fun matchingUnpairedSurrogateStringCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("\"\\uDBFB", "\"\uDBFB", 0, 2),
                Arguments.of("\\uDBFB", "x\uDBFBy", 1, 2),
                Arguments.of("\\uDC00[a-z]", "x\uDC00a", 1, 3),
                Arguments.of(".*a\\uDBFB$", "xa\uDBFB", 0, 3),
                Arguments.of("(?:a\\uDBFB|banana)", "xa\uDBFBy", 1, 3)
            )
        }

        fun entryPointCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("\\uDBFB", "a\uDBFB\uDDBDb\uDBFBc"),
                Arguments.of("\\uDDBD", "a\uDBFB\uDDBDb\uDDBDc"),
                Arguments.of("\\x{D800}\\x{DC00}", "a\uD800\uDC00b\uD800c\uDC00d"),
                Arguments.of("a\\uDBFB", "a?a\uDBFBa\uDBFB\uDDBD"),
                Arguments.of("(?i)a\\uDBFB", "A?A\uDBFBa\uDBFB\uDDBD"),
                Arguments.of("(?i)\\uDC00[a-z]", "\uD800\uDC00A\uDC00B")
            )
        }
    }
}
