// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.safere.Matcher.LiteralAlternationPreparedRunner
import org.safere.Matcher.PreparedMatchRunner

@DisabledForCrosscheck("compares the optimized and forced fallback paths with the JDK")
internal class LiteralAlternationTest {
    @Test
    fun onlyCompleteCaptureFreeAsciiAlternationsSelectTheRunner() {
        for (regex in mutableListOf<String>(
            "apple|banana|cherry",
            "foo|barfoo",
            "red|green|blue",
            "ab|cherry|abc",
            "abc|cherry|ab"
        )) {
            Assertions.assertThat(
                Pattern.compile(regex).preparedMatchRunner(false)!!.javaClass.getSimpleName()
            )
                .`as`(regex)
                .isEqualTo("LiteralAlternationPreparedRunner")
        }
        for (regex in mutableListOf<String>(
            "(apple)|banana",
            "(?i)apple|banana",
            "apple|banana$",
            "apple|",
            "éclair|😀",
            "(?:apple|banana)+",
            "gardening|gardener|gardens"
        )) {
            Assertions.assertThat(
                Pattern.compile(regex).preparedMatchRunner(false)!!.javaClass.getSimpleName()
            )
                .`as`(regex)
                .isNotEqualTo("LiteralAlternationPreparedRunner")
        }
    }

    @Test
    fun longAlternativesRetainTheExistingRunner() {
        for (length in intArrayOf(13, 16, 17, 65, 4097)) {
            val literal = "a".repeat(length - 1) + "z"
            for (regex in java.util.List.of<String?>(
                literal + "|banana|cherry|durian|elderberry|fig",
                literal + "|banana|cherry",
                "banana|" + literal + "|cherry"
            )) {
                val pattern = Pattern.compile(regex)
                val fallback =
                    Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build())
                Assertions.assertThat(pattern.preparedMatchRunner(false)!!.javaClass)
                    .`as`(regex)
                    .isEqualTo(fallback.preparedMatchRunner(false)!!.javaClass)
                for (suffix in mutableListOf<String>("", "banana", "z")) {
                    val text = "a".repeat(8192) + suffix
                    compareFinds(pattern.matcher(text), java.util.regex.Pattern.compile(regex).matcher(text))
                }
            }
        }
    }

    @Test
    fun searchSequencesAndReplacementsAgreeWithJdkAndFallback() {
        for (regex in PATTERNS) {
            val optimized = org.safere.Pattern.compile(regex)
            val fallback =
                org.safere.Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build())
            for (offset in intArrayOf(0, 1, 255, 256, 1023, 1024, 4095, 4096, 8192)) {
                val text =
                    ("x".repeat(offset)
                            + "barfoo banana gardens abc red 😀éclair apple"
                            + "x".repeat(5000)
                            + "cherry blue banana")
                for (pattern in java.util.List.of<org.safere.Pattern>(optimized, fallback)) {
                    compareFinds(pattern.matcher(text), java.util.regex.Pattern.compile(regex).matcher(text))
                    Assertions.assertThat(pattern.matcher(text).replaceAll("<$0>"))
                        .`as`("replacement %s offset %s", regex, offset)
                        .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).replaceAll("<$0>"))
                    Assertions.assertThat(pattern.matcher(text).replaceFirst("<$0>"))
                        .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(text).replaceFirst("<$0>"))
                    val actual = pattern.matcher(text)
                    val expected = java.util.regex.Pattern.compile(regex).matcher(text)
                    Assertions.assertThat(actual.matches()).isEqualTo(expected.matches())
                    Assertions.assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt())
                    compareFinds(actual, expected)
                    Assertions.assertThat(actual.find(offset)).isEqualTo(expected.find(offset))
                    compareFinds(actual, expected)
                }
            }
        }
    }

    @Test
    fun regionsAndNonAsciiPaddingPreserveBounds() {
        for (regex in mutableListOf<String>("apple|banana|cherry", "foo|barfoo", "ab|abc", "abc|ab")) {
            for (padding in mutableListOf<String>("x", "é", "😀", "\uD800", "\uDC00")) {
                val text = padding!!.repeat(1024) + "barfoo banana apple abc cherry" + padding
                for (transparent in booleanArrayOf(false, true)) {
                    for (anchoring in booleanArrayOf(false, true)) {
                        for (start in intArrayOf(0, 1, padding.length * 1024 + 3)) {
                            val actual =
                                org.safere.Pattern.compile(regex)
                                    .matcher(text)
                                    .region(start, text.length - 1)
                                    .useTransparentBounds(transparent)
                                    .useAnchoringBounds(anchoring)
                            val expected =
                                java.util.regex.Pattern.compile(regex)
                                    .matcher(text)
                                    .region(start, text.length - 1)
                                    .useTransparentBounds(transparent)
                                    .useAnchoringBounds(anchoring)
                            compareFinds(actual, expected)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun growingWindowBoundariesPreserveLeftmostMatchAndAlternativePriority() {
        for (regex in mutableListOf<String>(
            "ab|cherry|abc",
            "abc|cherry|ab",
            "ab|durian|cherry|elderberry|fig|abc",
            "abc|durian|cherry|elderberry|fig|ab"
        )) {
            val pattern = org.safere.Pattern.compile(regex)
            Assertions.assertThat<PreparedMatchRunner?>(pattern.preparedMatchRunner(false))
                .isInstanceOf(LiteralAlternationPreparedRunner::class.java)
            for (boundary in intArrayOf(256, 768, 1024, 1792, 3072, 3840, 7168, 7936, 15360, 31744)) {
                for (offset in intArrayOf(boundary - 1, boundary, boundary + 1)) {
                    val text = "x".repeat(offset) + "abc" + "x".repeat(65536) + "cherry"
                    compareFinds(pattern.matcher(text), java.util.regex.Pattern.compile(regex).matcher(text))
                }
            }
        }
    }

    @Test
    fun longLiteralsCrossingTheProbeBoundaryStillSelectTheLeftmostMatch() {
        val literal = "a".repeat(2048) + "z"
        for (regex in java.util.List.of<String?>(literal + "|banana|cherry", literal + "|banana|cherry|durian|elderberry|fig")) {
            for (offset in intArrayOf(255, 256, 1023, 1024, 4095, 4096)) {
                for (prefix in java.util.List.of<String?>("x".repeat(offset), "banana" + "x".repeat(offset))) {
                    val text = prefix + literal + "x".repeat(8192) + "cherry"
                    compareFinds(
                        org.safere.Pattern.compile(regex).matcher(text),
                        java.util.regex.Pattern.compile(regex).matcher(text)
                    )
                }
            }
        }
    }

    @Test
    fun literalAlternationsAdvertiseLiteralCapability() {
        val pattern = org.safere.Pattern.compile("apple|banana|cherry")
        Assertions.assertThat<PatternCapability?>(pattern.analysis().capabilities)
            .contains(PatternCapability.LITERAL_MATCH)
    }

    @Test
    fun fullAndPrefixMatchesPreserveTheExistingRunnerSemantics() {
        for (regex in PATTERNS) {
            for (text in mutableListOf<String>("", "apple", "banana", "cherry", "barfoo", "ab", "abc")) {
                val actual = org.safere.Pattern.compile(regex).matcher(text)
                val expected = java.util.regex.Pattern.compile(regex).matcher(text)
                Assertions.assertThat(actual.matches()).isEqualTo(expected.matches())
                Assertions.assertThat(actual.lookingAt()).isEqualTo(expected.lookingAt())
                compareFinds(actual, expected)
            }
        }
    }

    companion object {
        private val PATTERNS: MutableList<String> = mutableListOf<String>(
            "apple|banana|cherry",
            "blossom|sparkling|gardens",
            "foo|barfoo",
            "ab|bcd",
            "red|green|blue|yellow|orange|purple",
            "ab|abc",
            "abc|ab",
            "ab|cherry|abc",
            "abc|cherry|ab",
            "(apple)|banana",
            "(?i)apple|banana",
            "apple|banana$",
            "apple|",
            "éclair|😀"
        )

        private fun compareFinds(actual: org.safere.Matcher, expected: java.util.regex.Matcher) {
            while (true) {
                val found = expected.find()
                Assertions.assertThat(actual.find()).isEqualTo(found)
                if (!found) break
                Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                Assertions.assertThat(actual.toMatchResult().group()).isEqualTo(expected.toMatchResult().group())
                for (group in 0..expected.groupCount()) {
                    Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                }
            }
        }
    }
}
