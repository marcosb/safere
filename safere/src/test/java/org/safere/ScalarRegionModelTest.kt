// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
/** Checks scalar regions against the corresponding bounded text, including capture traces.  */
@DisabledForCrosscheck("Intentional region-local surrogate decoding, see #867")
internal class ScalarRegionModelTest {
    @Test
    fun consumingAtomsIgnoreHiddenPrefixAndSuffix() {
        checkRegions(
            mutableListOf<String>(
                ".",
                ".*",
                ".+?",
                "(.)*",
                "(.+)",
                "(a|.)*",
                "([^a]+)",
                "[\\s\\S]*",
                "\\p{Cs}+",
                "\\P{Cs}+",
                "[\\p{L}\\p{Cs}]+",
                ".{0,2}",
                "(a+?)?"
            ),
            true
        )
    }

    @Test
    fun opaqueWordAssertionsUseTheBoundedCharacterCategories() {
        checkRegions(mutableListOf<String>("\\b.", "\\B.", "(?U)\\b.", "(?U)\\B.", "(?U).\\b", "(?U).\\B"), false)
    }

    @Test
    fun unreachableGraphemeAlternativeDoesNotChangeOrdinaryWordAssertions() {
        for (prefix in mutableListOf<String>("", "a", " ", "aa")) {
            for (boundary in mutableListOf<String>("\\b", "\\B", "(?U:\\b)", "(?U:\\B)")) {
                val regex = Pattern.quote(prefix) + boundary + "."
                val ordinary = Pattern.compile(regex)
                val mixed = Pattern.compile("(?:" + regex + ")|(?:\\Xz)")
                val input = prefix + "\uD801\uDC00x"
                val end = prefix!!.length + 1
                for (transparent in booleanArrayOf(false, true)) {
                    for (operation in 0..2) {
                        val expected = ordinary.matcher(input).region(0, end)
                        val actual = mixed.matcher(input).region(0, end)
                        expected.useTransparentBounds(transparent)
                        actual.useTransparentBounds(transparent)
                        Assertions.assertThat<String?>(trace(actual, operation, 0))
                            .`as`(
                                "%s prefix=%s transparent=%s operation=%s",
                                regex, prefix, transparent, operation
                            )
                            .isEqualTo(trace(expected, operation, 0))
                    }
                }
            }
        }
    }

    @Test
    fun unreachableGraphemeAlternativeDoesNotChangeScalarGraphemeAssertions() {
        for (prefix in mutableListOf<String>("", "a", "a\u0301", "\uD83C\uDDE6\uD83C\uDDE7")) {
            for (scalar in mutableListOf<String>("(.)\\b{g}", "(.)\\b{g}$", "\\b{g}(.)\\b{g}")) {
                val regex = Pattern.quote(prefix) + scalar
                val ordinary = Pattern.compile(regex)
                for (pair in mutableListOf<String>("\uD83D\uDC4D", "\uD801\uDC00", "\uD83C\uDDE6")) {
                    val input = "q" + prefix + pair + "x"
                    val end = 1 + prefix!!.length + 1
                    for (mixedRegex in java.util.List.of<String?>("(?:" + regex + ")|(?:\\Xz)", "(?:\\Xz)|(?:" + regex + ")")) {
                        val mixed = Pattern.compile(mixedRegex)
                        for (transparent in booleanArrayOf(false, true)) {
                            for (anchoring in booleanArrayOf(false, true)) {
                                for (operation in 0..2) {
                                    val expected = ordinary.matcher(input).region(1, end)
                                    val actual = mixed.matcher(input).region(1, end)
                                    expected.useTransparentBounds(transparent).useAnchoringBounds(anchoring)
                                    actual.useTransparentBounds(transparent).useAnchoringBounds(anchoring)
                                    Assertions.assertThat<String?>(trace(actual, operation, 0))
                                        .`as`(
                                            "%s input=%s transparent=%s anchoring=%s operation=%s",
                                            mixedRegex, input, transparent, anchoring, operation
                                        )
                                        .isEqualTo(trace(expected, operation, 0))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun unreachableGraphemeAlternativeDoesNotChangeInteriorAssertions() {
        for (pair in mutableListOf<String>("\uD83C\uDDE6", "\uD83D\uDC4D", "\uD801\uDC00")) {
            val input = pair!!.repeat(3)
            for (regex in mutableListOf<String>(".\\b{g}", ".\\b{g}.", "(.)\\b{g}(.)", "[^a]\\b{g}[\\s\\S]")) {
                val ordinary = Pattern.compile(regex)
                for (mixedRegex in java.util.List.of<String?>("(?:" + regex + ")|(?:\\Xz)", "(?:\\Xz)|(?:" + regex + ")")) {
                    val mixed = Pattern.compile(mixedRegex)
                    for (transparent in booleanArrayOf(false, true)) {
                        for (anchoring in booleanArrayOf(false, true)) {
                            for (operation in 0..2) {
                                val expected = ordinary.matcher(input).region(1, 3)
                                val actual = mixed.matcher(input).region(1, 3)
                                expected.useTransparentBounds(transparent).useAnchoringBounds(anchoring)
                                actual.useTransparentBounds(transparent).useAnchoringBounds(anchoring)
                                Assertions.assertThat<String?>(trace(actual, operation, 0))
                                    .`as`(
                                        "%s input=%s transparent=%s anchoring=%s operation=%s",
                                        mixedRegex, input, transparent, anchoring, operation
                                    )
                                    .isEqualTo(trace(expected, operation, 0))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun interiorRegionalIndicatorAssertionUsesVisibleCharacterContext() {
        val input = "\uD83C\uDDE6".repeat(2)
        for (regex in mutableListOf<String>(".\\b{g}", ".\\b{g}.", "\\X\\b{g}")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat(pattern.matcher(input).region(1, 3).lookingAt()).isTrue()
            Assertions.assertThat(pattern.matcher(input.substring(1, 3)).lookingAt()).isTrue()
            Assertions.assertThat(pattern.matcher(input).region(1, 3).useTransparentBounds(true).lookingAt())
                .isFalse()
        }
    }

    @Test
    fun terminalClosureFailureStillAllowsAnEmptyMatchAtTheRegionEnd() {
        val matcher = Pattern.compile("a\\Xb|$").matcher("a\uD83D\uDC4D").region(0, 2)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.start()).isEqualTo(2)
        Assertions.assertThat(matcher.end()).isEqualTo(2)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    companion object {
        private fun checkRegions(patterns: MutableList<String>, includeTransparent: Boolean) {
            for (regex in patterns) {
                val pattern = Pattern.compile(regex)
                for (input in mutableListOf<String>(
                    "a\uD83D\uDC4Db",
                    "x\uD801\uDC00a",
                    "\uD83Dx\uDC4D",
                    "a\u0301",
                    ""
                )) {
                    val region = pattern.matcher(input)
                    for (start in 0..input!!.length) {
                        for (end in start..input.length) {
                            for (transparent in booleanArrayOf(false, true)) {
                                if (transparent && !includeTransparent) {
                                    continue
                                }
                                for (anchoring in booleanArrayOf(false, true)) {
                                    for (operation in 0..2) {
                                        region
                                            .region(start, end)
                                            .useTransparentBounds(transparent)
                                            .useAnchoringBounds(anchoring)
                                        val bounded = pattern.matcher(input.substring(start, end))
                                        Assertions.assertThat<String?>(trace(region, operation, start))
                                            .`as`(
                                                "%s input=%s region=%s:%s transparent=%s anchoring=%s operation=%s",
                                                regex, input, start, end, transparent, anchoring, operation
                                            )
                                            .isEqualTo(trace(bounded, operation, 0))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun trace(matcher: Matcher, operation: Int, offset: Int): MutableList<String?> {
            val result: MutableList<String?> = ArrayList<String?>()
            while (true) {
                val matched =
                    when (operation) {
                        0 -> matcher.matches()
                        1 -> matcher.lookingAt()
                        else -> matcher.find()
                    }
                result.add(matched.toString())
                if (matched) {
                    for (group in 0..matcher.groupCount()) {
                        val start = matcher.start(group)
                        val end = matcher.end(group)
                        result.add((if (start < 0) start else start - offset).toString() + ":" + (if (end < 0) end else end - offset))
                        // Unmatched optional groups are null, matching java.util.regex.
                        result.add(matcher.group(group))
                    }
                }
                if (operation != 2 || !matched) {
                    return result
                }
            }
        }
    }
}
