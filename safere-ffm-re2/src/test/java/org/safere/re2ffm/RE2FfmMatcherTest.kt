// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.re2ffm

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.safere.crosscheck.Pattern.Companion.compile
import org.safere.crosscheck.Pattern.matcher
import java.util.List
import java.util.regex.Pattern

internal class RE2FfmMatcherTest {
    @ParameterizedTest
    @CsvSource("a, ab, true", "b, ab, false", "a+, aaab, true", "a+, b, false", "'', abc, true")
    fun lookingAtUsesNativeStartAnchoring(regex: String, input: String, expected: Boolean) {
        val matcher: RE2FfmMatcher = compile(regex).matcher(input)
        Assertions.assertThat(matcher.lookingAt()).isEqualTo(expected)
        Assertions.assertThat(Pattern.compile(regex).matcher(input).lookingAt())
            .isEqualTo(expected)
        if (!expected) {
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @ParameterizedTest
    @CsvSource("(a|ab), ab", "(a+?), aa", "((a)?b), b", "(a*)(b?), ab")
    fun fullMatchCapturesComeFromTheAnchoredOperation(regex: String, input: String) {
        val expected = Pattern.compile(regex).matcher(input)
        val actual: RE2FfmMatcher = compile(regex).matcher(input)
        Assertions.assertThat(actual.matches()).isEqualTo(expected.matches())
        if (expected.hasMatch()) {
            for (group in 0..expected.groupCount()) {
                Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["(ab)", "^(ab)$", "\\A(ab)\\z", "\\b(ab)\\b"])
    fun regionsUseOpaqueAnchoringBoundsAndAbsoluteOffsets(regex: String) {
        val input = "xabz"
        val expected = Pattern.compile(regex).matcher(input).region(1, 3)
        val actual: RE2FfmMatcher = compile(regex).matcher(input).region(1, 3)
        Assertions.assertThat(actual.regionStart()).isEqualTo(1)
        Assertions.assertThat(actual.regionEnd()).isEqualTo(3)
        Assertions.assertThat(actual.matches()).isEqualTo(expected.matches()).isTrue()
        Assertions.assertThat(actual.group(1)).isEqualTo("ab")
        Assertions.assertThat(actual.start(1)).isEqualTo(1)
        Assertions.assertThat(actual.end(1)).isEqualTo(3)
        actual.region(1, 3)
        Assertions.assertThat(actual.lookingAt()).isTrue()
        actual.region(1, 3)
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.find()).isFalse()
    }

    @Test
    fun findStaysWithinRegionAndRegionDiscardsMatchState() {
        val actual: RE2FfmMatcher = compile("a").matcher("aaxa")
        Assertions.assertThat(actual.find()).isTrue()
        actual.region(1, 3)
        Assertions.assertThatThrownBy(ThrowingCallable { actual.group() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.start()).isEqualTo(1)
        Assertions.assertThat(actual.find()).isFalse()
        Assertions.assertThat(actual.find()).isFalse()
        actual.region(3, 4)
        Assertions.assertThat(actual.lookingAt()).isTrue()
        Assertions.assertThat(actual.start()).isEqualTo(3)
    }

    @Test
    fun resetAndFindStartRestoreWholeInput() {
        val actual: RE2FfmMatcher = compile("a").matcher("aba").region(2, 3)
        Assertions.assertThat(actual.matches()).isTrue()
        actual.reset()
        Assertions.assertThat(actual.regionStart()).isZero()
        Assertions.assertThat(actual.regionEnd()).isEqualTo(3)
        Assertions.assertThatThrownBy(ThrowingCallable { actual.group() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.start()).isZero()
        actual.region(2, 3)
        Assertions.assertThat(actual.find(0)).isTrue()
        Assertions.assertThat(actual.start()).isZero()
        Assertions.assertThat(actual.regionStart()).isZero()
        actual.reset("za")
        Assertions.assertThat(actual.regionEnd()).isEqualTo(2)
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.start()).isEqualTo(1)
    }

    @Test
    fun anchoredSuccessAdvancesFindWithoutRepeatingTheMatch() {
        val actual: RE2FfmMatcher = compile("a").matcher("aa")
        Assertions.assertThat(actual.lookingAt()).isTrue()
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.start()).isEqualTo(1)
        actual.reset("a")
        Assertions.assertThat(actual.matches()).isTrue()
        Assertions.assertThat(actual.find()).isFalse()
    }

    private enum class History {
        NONE,
        LOOKING_AT,
        FIND
    }

    @ParameterizedTest
    @CsvSource(
        "ba, 0, 2, NONE, false",
        "ba, 0, 2, NONE, true",
        "aa, 0, 2, LOOKING_AT, true",
        "baba, 0, 4, FIND, false",
        "baba, 0, 4, FIND, true",
        "xbaaby, 1, 5, FIND, false",
        "xbaaby, 1, 5, FIND, true",
        "é😀a😀a世, 1, 7, FIND, false",
        "é😀a😀a世, 1, 7, FIND, true"
    )
    fun failedAnchoredChecksPreserveFindContinuation(
        input: String, start: Int, end: Int, history: History, fullMatch: Boolean
    ) {
        val actual: RE2FfmMatcher = compile("(a)").matcher(input).region(start, end)
        val expected = Pattern.compile("(a)").matcher(input).region(start, end)
        when (history) {
            History.NONE -> {}
            History.LOOKING_AT -> Assertions.assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt()).isTrue()
            History.FIND -> Assertions.assertThat(actual.find()).isEqualTo(expected.find()).isTrue()
        }
        Assertions.assertThat(if (fullMatch) expected.matches() else expected.lookingAt()).isFalse()
        Assertions.assertThat(if (fullMatch) actual.matches() else actual.lookingAt()).isFalse()
        Assertions.assertThatThrownBy(ThrowingCallable { actual.group() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { actual.start() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { actual.end() }).isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(actual.find()).isEqualTo(expected.find()).isTrue()
        Assertions.assertThat(actual.group(1)).isEqualTo(expected.group(1))
        Assertions.assertThat(actual.start(1)).isEqualTo(expected.start(1))
        Assertions.assertThat(actual.end(1)).isEqualTo(expected.end(1))
    }

    @ParameterizedTest
    @ValueSource(strings = ["b", "é", "😀"])
    fun failedFullMatchPreservesEmptyPrefixCodePointAdvance(prefix: String) {
        val actual: RE2FfmMatcher = compile("(a*)").matcher(prefix + "a")
        Assertions.assertThat(actual.lookingAt()).isTrue()
        Assertions.assertThat(actual.group(1)).isEmpty()
        Assertions.assertThat(actual.matches()).isFalse()
        Assertions.assertThatThrownBy(ThrowingCallable { actual.group() })
            .isInstanceOf(IllegalStateException::class.java)
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.group(1)).isEqualTo("a")
        Assertions.assertThat(actual.start(1)).isEqualTo(prefix.length())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun failedAnchoredChecksDoNotReopenAnExhaustedFind(fullMatch: Boolean) {
        val actual: RE2FfmMatcher = compile("a").matcher("ba")
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.find()).isFalse()
        Assertions.assertThat(if (fullMatch) actual.matches() else actual.lookingAt()).isFalse()
        Assertions.assertThat(actual.find()).isFalse()
        Assertions.assertThat(actual.find()).isFalse()
        actual.reset()
        Assertions.assertThat(actual.find()).isTrue()
    }

    @Test
    fun emptyRegionsAndEmptyCapturesRemainParticipating() {
        for (input in List.of<String?>("", "ab")) {
            val end: Int = input.length()
            val actual: RE2FfmMatcher = compile("()").matcher(input).region(end, end)
            Assertions.assertThat(actual.matches()).isTrue()
            Assertions.assertThat(actual.group(1)).isEmpty()
            Assertions.assertThat(actual.start(1)).isEqualTo(end)
            Assertions.assertThat(actual.find()).isFalse()
            actual.region(end, end)
            Assertions.assertThat(actual.find()).isTrue()
            Assertions.assertThat(actual.find()).isFalse()
        }
    }

    @Test
    fun regionsUseUtf16CoordinatesAtCodePointBoundaries() {
        val input = "é😀世😀z"
        val actual: RE2FfmMatcher = compile("(😀)").matcher(input).region(1, 6)
        val expected = Pattern.compile("(😀)").matcher(input).region(1, 6)
        while (expected.find()) {
            Assertions.assertThat(actual.find()).isTrue()
            Assertions.assertThat(actual.start(1)).isEqualTo(expected.start(1))
            Assertions.assertThat(actual.end(1)).isEqualTo(expected.end(1))
            Assertions.assertThat(actual.group(1)).isEqualTo(expected.group(1))
        }
        Assertions.assertThat(actual.find()).isFalse()
    }

    @ParameterizedTest
    @CsvSource("-1, 2", "0, -1", "3, 2", "0, 4", "4, 4")
    fun invalidRegionsDoNotMutateExistingState(start: Int, end: Int) {
        val actual: RE2FfmMatcher = compile("b").matcher("abc").region(1, 2)
        Assertions.assertThat(actual.matches()).isTrue()
        Assertions.assertThatThrownBy(ThrowingCallable { actual.region(start, end) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThat(actual.regionStart()).isEqualTo(1)
        Assertions.assertThat(actual.group()).isEqualTo("b")
    }

    @Test
    fun surrogateSplittingBoundsAreExplicitlyUnsupported() {
        val actual: RE2FfmMatcher = compile(".").matcher("😀")
        Assertions.assertThatThrownBy(ThrowingCallable { actual.region(1, 2) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("surrogate")
        Assertions.assertThatThrownBy(ThrowingCallable { actual.region(0, 1) })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("surrogate")
        Assertions.assertThatThrownBy(ThrowingCallable { actual.find(1) })
            .isInstanceOf(IllegalArgumentException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { actual.find(-1) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
        Assertions.assertThatThrownBy(ThrowingCallable { actual.find(3) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["(a|ab)", "(a+?)"])
    fun prefixMatchingRetainsNativeAlternationAndLazySelection(regex: String?) {
        val actual: RE2FfmMatcher = compile(regex).matcher("ab")
        Assertions.assertThat(actual.lookingAt()).isTrue()
        Assertions.assertThat(actual.group()).isEqualTo("a")
        Assertions.assertThat(actual.group(1)).isEqualTo("a")
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "(a)()()()()()()()()()"])
    fun replacementConvenienceMethodsRestoreWholeInputRegion(regex: String?) {
        val actual: RE2FfmMatcher = compile(regex).matcher("aba").region(2, 3)
        Assertions.assertThat(actual.replaceAll("x")).isEqualTo("xbx")
        Assertions.assertThat(actual.regionStart()).isZero()
        Assertions.assertThat(actual.regionEnd()).isEqualTo(3)
        actual.region(2, 3)
        Assertions.assertThat(actual.replaceFirst("x")).isEqualTo("xba")
        Assertions.assertThat(actual.regionStart()).isZero()
        Assertions.assertThat(actual.regionEnd()).isEqualTo(3)
    }

    @Test
    fun emptyMatchesAdvanceByCodePoint() {
        val actual: RE2FfmMatcher = compile("").matcher("😀")
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.start()).isZero()
        Assertions.assertThat(actual.find()).isTrue()
        Assertions.assertThat(actual.start()).isEqualTo(2)
        Assertions.assertThat(actual.find()).isFalse()
    }
}
