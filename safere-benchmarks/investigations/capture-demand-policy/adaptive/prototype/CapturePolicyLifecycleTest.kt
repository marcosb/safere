// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions.assertThat

internal class CapturePolicyLifecycleTest {
    @Test
    fun resetInputAndRegionChangesPreservePublicResultsAcrossUsageChanges() {
        val regex = "(?<word>[a-z]+)([0-9]*)"
        val compiled: Pattern = Pattern.compile(regex)
        val jdk: java.util.regex.Pattern = java.util.regex.Pattern.compile(regex)
        val actual: Matcher = compiled.matcher("")
        val expected: java.util.regex.Matcher = jdk.matcher("")
        for (cycle in 0..63) {
            val input = if (cycle % 3 == 0) " ".repeat(96) + "alpha12 beta333" else "7".repeat(128)
            actual.reset(input)
            expected.reset(input)
            actual.useTransparentBounds((cycle and 1) == 0).useAnchoringBounds((cycle and 2) == 0)
            expected.useTransparentBounds((cycle and 1) == 0).useAnchoringBounds((cycle and 2) == 0)
            actual.region(2, input.length - 1)
            expected.region(2, input.length - 1)
            while (expected.find()) {
                assertThat(actual.find()).isTrue()
                assertThat(actual.start()).isEqualTo(expected.start())
                assertThat(actual.end()).isEqualTo(expected.end())
                if (cycle % 4 != 0) {
                    assertThat(actual.group("word")).isEqualTo(expected.group("word"))
                    assertThat(actual.group(2)).isEqualTo(expected.group(2))
                }
            }
            assertThat(actual.find()).isFalse()
        }
    }

    @Test
    fun snapshotsRemainIndependentAndPatternChangesRestartLocalLearning() {
        val matcher: Matcher = Pattern.compile("(a+)(b?)").matcher(" ".repeat(96) + "aaab aa")
        assertThat(matcher.find()).isTrue()
        assertThat(matcher.group()).isEqualTo("aaab")
        assertThatThrownBy({ matcher.group(9) }).isInstanceOf(IndexOutOfBoundsException::class.java)
        assertThat(matcher.capturePolicySnapshot().reads()).isZero()
        val snapshot: MatchResult = matcher.toMatchResult()
        assertThat(matcher.find()).isTrue()
        assertThat(snapshot.group(1)).isEqualTo("aaa")
        assertThat(snapshot.group(2)).isEqualTo("b")
        assertThat(matcher.capturePolicySnapshot().reads()).isEqualTo(1)
        matcher.usePattern(Pattern.compile("(c+)"))
        assertThat(matcher.capturePolicySnapshot().successes()).isZero()
        assertThat(matcher.capturePolicySnapshot().attempts()).isZero()
        matcher.reset(" ".repeat(96) + "ccc")
        assertThat(matcher.find()).isTrue()
        assertThat(matcher.group(1)).isEqualTo("ccc")
        assertThat(snapshot.group()).isEqualTo("aaab")
    }

    @Test
    fun replacementRequirementsDoNotDependOnHistoricalPreference() {
        val regex = "([a-z]+)([0-9]+)"
        val input = " ".repeat(96) + "alpha12 beta333"
        val compiled: Pattern = Pattern.compile(regex)
        val jdk: java.util.regex.Pattern = java.util.regex.Pattern.compile(regex)
        val actual: Matcher = compiled.matcher(input)
        val expected: java.util.regex.Matcher = jdk.matcher(input)
        for (i in 0..63) {
            actual.reset()
            expected.reset()
            assertThat(actual.replaceAll("$2:$1")).isEqualTo(expected.replaceAll("$2:$1"))
            actual.reset()
            expected.reset()
            assertThat(actual.replaceFirst("$1/$2")).isEqualTo(expected.replaceFirst("$1/$2"))
        }
        actual.reset()
        expected.reset()
        val actualText: StringBuilder = StringBuilder()
        val expectedText: StringBuilder = StringBuilder()
        while (expected.find()) {
            assertThat(actual.find()).isTrue()
            actual.appendReplacement(actualText, "$2-$1")
            expected.appendReplacement(expectedText, "$2-$1")
        }
        actual.appendTail(actualText)
        expected.appendTail(expectedText)
        assertThat(actualText.toString()).isEqualTo(expectedText.toString())
    }

    @Test
    fun utf8RetainedInputAndRegionsKeepCodePointAlignedNativeBounds() {
        val regex = "((?:é|😀)+)(x?)"
        val input = " ".repeat(96) + "é😀x | éé"
        val bytes: ByteArray = input.getBytes(StandardCharsets.UTF_8)
        val actual: Utf8Matcher = Pattern.compile(regex).matcher(Utf8Input.trusted(bytes))
        val expected: java.util.regex.Matcher = java.util.regex.Pattern.compile(regex).matcher(input)
        for (cycle in 0..63) {
            val start = if (cycle % 2 == 0) 0 else 96
            actual.reset().region(start, bytes.size)
            expected.reset().region(start, input.length)
            while (expected.find()) {
                assertThat(actual.find()).isTrue()
                for (group in 0..2) {
                    if (cycle % 4 == 0 && group > 0) {
                        continue
                    }
                    val expectedStart: Int =
                        input.substring(0, expected.start(group)).getBytes(StandardCharsets.UTF_8).length
                    val expectedEnd: Int =
                        input.substring(0, expected.end(group)).getBytes(StandardCharsets.UTF_8).length
                    assertThat(actual.start(group)).isEqualTo(expectedStart)
                    assertThat(actual.end(group)).isEqualTo(expectedEnd)
                }
            }
            assertThat(actual.find()).isFalse()
        }
    }
}
