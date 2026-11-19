// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@DisabledForCrosscheck("Already compares the String and UTF-8 matchers directly with the JDK")
internal class SmallAsciiClassSearchTest {
    @ParameterizedTest
    @ValueSource(strings = ["[YZ]", "[XZ_]", "[\\x00?\\x7F]", "[a-b]", "[iI]"])
    fun searchPreservesRegionsSpansAndMatcherState(regex: String) {
        val noise = "é中😀abc\uD800d\uDC00".repeat(700)
        val text = "Z_YX\u0000?\u007F" + noise + "_YXZbiI?\u007F\u0000"
        for (flags in intArrayOf(0, Pattern.CASE_INSENSITIVE, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)) {
            val pattern = Pattern.compile(regex, flags)
            val expectedPattern = Pattern.compile(regex, flags)
            for (start in intArrayOf(0, 1, 16, 4095, text.length - 12)) {
                for (end in intArrayOf(text.length - 12, text.length)) {
                    for (anchoring in booleanArrayOf(false, true)) {
                        val actual = pattern.matcher(text).region(start, end).useAnchoringBounds(anchoring)
                        val expected =
                            expectedPattern.matcher(text).region(start, end).useAnchoringBounds(anchoring)
                        while (expected.find()) {
                            Assertions.assertThat(actual.find())
                                .`as`("%s, flags %s, region %s:%s", regex, flags, start, end)
                                .isTrue()
                            Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                            Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                            Assertions.assertThat(actual.group()).isEqualTo(expected.group())
                            Assertions.assertThat(actual.toMatchResult().group()).isEqualTo(expected.group())
                        }
                        Assertions.assertThat(actual.find()).isFalse()
                    }
                }
            }
            Assertions.assertThat(pattern.matcher(text).replaceAll("$0!"))
                .isEqualTo(expectedPattern.matcher(text).replaceAll("$0!"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["[YZ]", "[XZ_]"])
    fun denseIterationAndResetPreserveEveryMatch(regex: String?) {
        // Only the later member is present: scans for earlier absent members must stay bounded.
        for (length in intArrayOf(1024, 8192, 65536)) {
            val text = "Z".repeat(length)
            val matcher = org.safere.Pattern.compile(regex).matcher(text)
            var matches = 0
            while (matcher.find()) {
                Assertions.assertThat(matcher.start()).isEqualTo(matches)
                Assertions.assertThat(matcher.end()).isEqualTo(matches + 1)
                matches++
            }
            Assertions.assertThat(matches).isEqualTo(length)
            Assertions.assertThat(matcher.reset().find(length - 1)).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(length - 1)
            Assertions.assertThat(matcher.find()).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["[YZ]", "[XZ_]"])
    fun repeatedSearchReturnsNearestMatchAfterSmallGaps(regex: String?) {
        for (gap in intArrayOf(16, 32, 64, 128, 4096)) {
            val text = ("a".repeat(gap) + "Z").repeat(257)
            val matcher = org.safere.Pattern.compile(regex).matcher(text)
            var matches = 0
            while (matcher.find()) {
                Assertions.assertThat(matcher.start()).isEqualTo(matches * (gap + 1) + gap)
                Assertions.assertThat(matcher.end()).isEqualTo((matches + 1) * (gap + 1))
                matches++
            }
            Assertions.assertThat(matches).isEqualTo(257)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["[YZ]", "[XZ_]"])
    fun utf8SearchHonorsMultibyteInputWindowsAndLimits(regex: String) {
        val text = "é中😀".repeat(1000) + "_YXZ"
        val storage = ("ZZ" + text + "ZZ").toByteArray(StandardCharsets.UTF_8)
        val byteLength = text.toByteArray(StandardCharsets.UTF_8).size
        val input = Utf8Input.validated(storage, 2, byteLength)
        val pattern = org.safere.Pattern.compile(regex)
        val actual = pattern.matcher(input)
        val expected = Pattern.compile(regex).matcher(text)
        Assertions.assertThat(pattern.find(input)).isTrue()
        while (expected.find()) {
            Assertions.assertThat(actual.find()).isTrue()
            Assertions.assertThat(actual.start())
                .isEqualTo(text.substring(0, expected.start()).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(actual.end())
                .isEqualTo(text.substring(0, expected.end()).toByteArray(StandardCharsets.UTF_8).size)
            Assertions.assertThat(
                String(
                    storage,
                    2 + actual.start(),
                    actual.end() - actual.start(),
                    StandardCharsets.UTF_8
                )
            )
                .isEqualTo(expected.group())
        }
        Assertions.assertThat(actual.find()).isFalse()
        Assertions.assertThat(actual.region(0, byteLength - 4).find()).isFalse()
    }
}
