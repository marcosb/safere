// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.safere.UnicodeMatchBoundsTest.Companion.trace
import java.util.stream.Stream

@DisplayName("Unicode match bounds")
internal class UnicodeMatchBoundsTest {
    @JvmRecord
    internal data class Case(val label: String, val regex: String?, val flags: Int, val input: String?) {
        override fun toString(): String {
            return label
        }
    }

    @JvmRecord
    internal data class RegionCase(
        val label: String,
        val regex: String?,
        val flags: Int,
        val input: String?,
        val regionStart: Int,
        val regionEnd: Int,
        val transparentBounds: Boolean,
        val anchoringBounds: Boolean
    ) {
        override fun toString(): String {
            return label
        }
    }

    @JvmRecord
    internal data class Outcome(val matched: Boolean, val start: Int, val end: Int)

    @ParameterizedTest
    @MethodSource
    @DisplayName("dot and anchor bounds match java.util.regex")
    fun dotAndAnchorBoundsMatchJdk(c: Case) {
        val safePattern = Pattern.compile(c.regex, c.flags)
        val jdkPattern = java.util.regex.Pattern.compile(c.regex, c.flags)

        Assertions.assertThat<Outcome?>(findOutcome(safePattern.matcher(c.input)))
            .`as`("%s find", c.label)
            .isEqualTo(findOutcome(jdkPattern.matcher(c.input)))
        Assertions.assertThat<Outcome?>(lookingAtOutcome(safePattern.matcher(c.input)))
            .`as`("%s lookingAt", c.label)
            .isEqualTo(lookingAtOutcome(jdkPattern.matcher(c.input)))
        Assertions.assertThat<Outcome?>(matchesOutcome(safePattern.matcher(c.input)))
            .`as`("%s matches", c.label)
            .isEqualTo(matchesOutcome(jdkPattern.matcher(c.input)))
    }

    @JvmRecord
    internal data class DivergentRegionCase(val c: RegionCase?, val expectedSafeTrace: String?) {
        override fun toString(): String {
            return c!!.label
        }
    }

    @ParameterizedTest
    @MethodSource
    @DisabledForCrosscheck("Region-local scalar decoding and boundary positions differ from JDK")
    @DisplayName("scalar region bounds intentionally diverge from java.util.regex")
    fun scalarRegionBoundsIntentionallyDivergeFromJdk(dc: DivergentRegionCase) {
        val safePattern = Pattern.compile(dc.c!!.regex, dc.c.flags)
        Assertions.assertThat(Companion.trace(safePattern.matcher(dc.c.input), dc.c))
            .`as`("%s trace", dc.c.label)
            .isEqualTo(dc.expectedSafeTrace)
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("scalar region bounds match java.util.regex")
    fun scalarRegionBoundsMatchJdk(c: RegionCase) {
        val safePattern = Pattern.compile(c.regex, c.flags)
        val jdkPattern = java.util.regex.Pattern.compile(c.regex, c.flags)

        Assertions.assertThat(trace(safePattern.matcher(c.input), c))
            .`as`("%s trace", c.label)
            .isEqualTo(trace(jdkPattern.matcher(c.input), c))
    }

    companion object {
        @JvmStatic
        fun dotAndAnchorBoundsMatchJdk(): Stream<Case?> {
            return Stream.of<Case?>(
                Case("dot-empty-alternative", ".|", 352, "\ud85c"),
                Case("dollar-anchor", "$", 332, "\udb7f\udb7f\ud85c\r\r"),
                Case("dot-plus-surrogate", ".+", 358, "\ud828"),
                Case(
                    "dot-plus-anchor",
                    ".+^",
                    300,
                    "\u0301\r\u2028\u0301\u0301\u2028\u0301\u0301\ud85c\u2028a"
                ),
                Case("dot-unpaired-high-surrogate", ".", org.safere.Pattern.DOTALL, "\ud83d"),
                Case("dot-unpaired-low-surrogate", ".", org.safere.Pattern.DOTALL, "\ude00"),
                Case("dot-valid-surrogate-pair", ".", org.safere.Pattern.DOTALL, "\ud83d\ude00")
            )
        }

        @JvmStatic
        fun scalarRegionBoundsMatchJdk(): Stream<RegionCase?> {
            return Stream.of<RegionCase?>(
                RegionCase(
                    "surrogate-category-region-starts-at-low-surrogate",
                    "\\p{Cs}",
                    0,
                    "\ud83d\ude00",
                    1,
                    2,
                    false,
                    true
                ),
                RegionCase(
                    "surrogate-category-complement-on-lone-surrogate",
                    "\\P{Cs}",
                    0,
                    "\ud83d",
                    0,
                    1,
                    false,
                    true
                ),
                RegionCase(
                    "end-anchor-finds-region-end-after-split-high-surrogate",
                    "$",
                    0,
                    "\ud83d\ude00",
                    0,
                    1,
                    false,
                    true
                ),
                RegionCase(
                    "end-anchor-finds-transparent-region-end-after-split-high-surrogate",
                    "$",
                    0,
                    "\ud83d\ude00",
                    0,
                    1,
                    true,
                    true
                ),
                RegionCase(
                    "start-anchor-does-not-see-region-start-without-anchoring-bounds",
                    "^a",
                    0,
                    "ba",
                    1,
                    2,
                    false,
                    false
                ),
                RegionCase(
                    "end-anchor-does-not-see-region-end-without-anchoring-bounds",
                    "a$",
                    0,
                    "ab",
                    0,
                    1,
                    false,
                    false
                ),
                RegionCase(
                    "anchored-single-letter-does-not-match-region-without-anchoring-bounds",
                    "^a$",
                    org.safere.Pattern.MULTILINE,
                    "a\u0301",
                    0,
                    1,
                    true,
                    false
                ),
                RegionCase(
                    "multiline-start-anchor-does-not-match-empty-region-inside-surrogate-pair",
                    "^",
                    org.safere.Pattern.MULTILINE,
                    "\ud83d\ude00",
                    1,
                    1,
                    false,
                    true
                ),
                RegionCase(
                    "multiline-anchored-empty-does-not-match-empty-region-inside-surrogate-pair",
                    "^$",
                    org.safere.Pattern.MULTILINE,
                    "x\ud83d\ude00y",
                    2,
                    2,
                    true,
                    true
                )
            )
        }

        @JvmStatic
        fun scalarRegionBoundsIntentionallyDivergeFromJdk(): Stream<DivergentRegionCase?> {
            return Stream.of<DivergentRegionCase?>(
                DivergentRegionCase(
                    RegionCase(
                        "dot-consumes-region-local-high-surrogate",
                        ".",
                        org.safere.Pattern.DOTALL,
                        "\ud83d\ude00",
                        0,
                        1,
                        false,
                        true
                    ),
                    "matches=true@0-1,lookingAt=true@0-1,find0=true@0-1,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "negated-class-consumes-region-local-high-surrogate",
                        "[^a]",
                        0,
                        "\ud83d\ude00",
                        0,
                        1,
                        false,
                        true
                    ),
                    "matches=true@0-1,lookingAt=true@0-1,find0=true@0-1,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "any-class-consumes-region-local-high-surrogate",
                        "[\\s\\S]",
                        0,
                        "\ud83d\ude00",
                        0,
                        1,
                        true,
                        true
                    ),
                    "matches=true@0-1,lookingAt=true@0-1,find0=true@0-1,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-digit-consumes-region-local-high-surrogate",
                        "\\D",
                        0,
                        "\ud83d\ude00",
                        0,
                        1,
                        false,
                        false
                    ),
                    "matches=true@0-1,lookingAt=true@0-1,find0=true@0-1,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "find-continues-at-region-local-high-surrogate-after-low-surrogate",
                        ".",
                        org.safere.Pattern.DOTALL,
                        "\ud83d\udc4d\ud83c\udffd",
                        1,
                        3,
                        false,
                        true
                    ),
                    "matches=false,lookingAt=true@1-2,find0=true@1-2,find1=true@2-3,find2=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-word-boundary-dot-consumes-region-local-high-surrogate",
                        "\\B.",
                        org.safere.Pattern.DOTALL,
                        "\ud83d\ude00",
                        0,
                        1,
                        false,
                        true
                    ),
                    "matches=true@0-1,lookingAt=true@0-1,find0=true@0-1,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-word-boundary-class-consumes-region-local-high-surrogate",
                        "\\B[\\s\\S]",
                        0,
                        "\ud83d\ude00",
                        0,
                        1,
                        true,
                        true
                    ),
                    "matches=true@0-1,lookingAt=true@0-1,find0=true@0-1,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-word-boundary-finds-utf16-offset-inside-supplementary-scalar",
                        "\\B",
                        0,
                        "x\ud83d\ude00y",
                        1,
                        4,
                        true,
                        true
                    ),
                    "matches=false,lookingAt=false,find0=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-word-boundary-alternative-remains-leftmost-inside-supplementary-scalar",
                        "\\B|$",
                        0,
                        "x\ud83d\ude00y",
                        1,
                        4,
                        true,
                        false
                    ),
                    "matches=false,lookingAt=false,find0=true@4-4,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-word-boundary-consuming-alternative-remains-leftmost-inside-supplementary-scalar",
                        "\\B|y",
                        0,
                        "x\ud83d\ude00y",
                        0,
                        4,
                        false,
                        true
                    ),
                    "matches=false,lookingAt=false,find0=true@3-4,find1=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "non-word-boundary-consuming-alternative-finds-utf16-offset-before-later-miss",
                        "\\B|a",
                        0,
                        "x\ud83d\ude00y",
                        0,
                        4,
                        false,
                        true
                    ),
                    "matches=false,lookingAt=false,find0=false"
                ),
                DivergentRegionCase(
                    RegionCase(
                        "consuming-alternative-before-non-word-boundary-still-finds-leftmost-boundary",
                        "y|\\B",
                        0,
                        "x\ud83d\ude00y",
                        0,
                        4,
                        false,
                        true
                    ),
                    "matches=false,lookingAt=false,find0=true@3-4,find1=false"
                )
            )
        }

        private fun trace(matcher: Matcher, c: RegionCase): String {
            matcher
                .region(c.regionStart, c.regionEnd)
                .useTransparentBounds(c.transparentBounds)
                .useAnchoringBounds(c.anchoringBounds)
            val trace = StringBuilder()
            appendOutcome(trace, "matches", matchesOutcome(matcher))
            matcher
                .reset(c.input)
                .region(c.regionStart, c.regionEnd)
                .useTransparentBounds(c.transparentBounds)
                .useAnchoringBounds(c.anchoringBounds)
            appendOutcome(trace, "lookingAt", lookingAtOutcome(matcher))
            matcher
                .reset(c.input)
                .region(c.regionStart, c.regionEnd)
                .useTransparentBounds(c.transparentBounds)
                .useAnchoringBounds(c.anchoringBounds)
            for (i in 0..3) {
                val outcome: Outcome = findOutcome(matcher)
                appendOutcome(trace, "find" + i, outcome)
                if (!outcome.matched) {
                    break
                }
            }
            return trace.toString()
        }

        private fun trace(matcher: java.util.regex.Matcher, c: RegionCase): String {
            matcher
                .region(c.regionStart, c.regionEnd)
                .useTransparentBounds(c.transparentBounds)
                .useAnchoringBounds(c.anchoringBounds)
            val trace = StringBuilder()
            appendOutcome(trace, "matches", matchesOutcome(matcher))
            matcher
                .reset(c.input)
                .region(c.regionStart, c.regionEnd)
                .useTransparentBounds(c.transparentBounds)
                .useAnchoringBounds(c.anchoringBounds)
            appendOutcome(trace, "lookingAt", lookingAtOutcome(matcher))
            matcher
                .reset(c.input)
                .region(c.regionStart, c.regionEnd)
                .useTransparentBounds(c.transparentBounds)
                .useAnchoringBounds(c.anchoringBounds)
            for (i in 0..3) {
                val outcome: Outcome = findOutcome(matcher)
                appendOutcome(trace, "find" + i, outcome)
                if (!outcome.matched) {
                    break
                }
            }
            return trace.toString()
        }

        private fun appendOutcome(trace: StringBuilder, operation: String?, outcome: Outcome) {
            if (!trace.isEmpty()) {
                trace.append(',')
            }
            trace.append(operation).append('=').append(outcome.matched)
            if (outcome.matched) {
                trace.append('@').append(outcome.start).append('-').append(outcome.end)
            }
        }

        private fun findOutcome(matcher: Matcher): Outcome {
            val matched = matcher.find()
            return Outcome(matched, if (matched) matcher.start() else -1, if (matched) matcher.end() else -1)
        }

        private fun findOutcome(matcher: java.util.regex.Matcher): Outcome {
            val matched = matcher.find()
            return Outcome(matched, if (matched) matcher.start() else -1, if (matched) matcher.end() else -1)
        }

        private fun lookingAtOutcome(matcher: Matcher): Outcome {
            val matched = matcher.lookingAt()
            return Outcome(matched, if (matched) matcher.start() else -1, if (matched) matcher.end() else -1)
        }

        private fun lookingAtOutcome(matcher: java.util.regex.Matcher): Outcome {
            val matched = matcher.lookingAt()
            return Outcome(matched, if (matched) matcher.start() else -1, if (matched) matcher.end() else -1)
        }

        private fun matchesOutcome(matcher: Matcher): Outcome {
            val matched = matcher.matches()
            return Outcome(matched, if (matched) matcher.start() else -1, if (matched) matcher.end() else -1)
        }

        private fun matchesOutcome(matcher: java.util.regex.Matcher): Outcome {
            val matched = matcher.matches()
            return Outcome(matched, if (matched) matcher.start() else -1, if (matched) matcher.end() else -1)
        }
    }
}
