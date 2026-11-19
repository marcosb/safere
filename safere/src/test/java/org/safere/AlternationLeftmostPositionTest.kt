// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.math.min

private fun assertMatchesJdk(pattern: String, input: String) {
    val saferePattern = Pattern.compile(pattern)
    val jdkPattern = java.util.regex.Pattern.compile(pattern)

    val safereM = saferePattern.matcher(input)
    val jdkM = jdkPattern.matcher(input)

    val jdkFound = jdkM.find()
    val safereFound = safereM.find()

    Assertions.assertThat(safereFound).`as`("find() for /%s/ on \"%s\"", pattern, input).isEqualTo(jdkFound)

    if (jdkFound && safereFound) {
        Assertions.assertThat(safereM.start())
            .`as`(
                "start() for /%s/ on \"%s\": SafeRE matched '%s', JDK matched '%s'",
                pattern,
                input,
                input.substring(safereM.start(), safereM.end()),
                input.substring(jdkM.start(), jdkM.end())
            )
            .isEqualTo(jdkM.start())
        Assertions.assertThat(safereM.end())
            .`as`("end() for /%s/ on \"%s\"", pattern, input)
            .isEqualTo(jdkM.end())
        Assertions.assertThat(safereM.group())
            .`as`("group() for /%s/ on \"%s\"", pattern, input)
            .isEqualTo(jdkM.group())
    }
}

/** Compare all find() results (not just first) against JDK.  */
private fun assertAllMatchesMatchJdk(pattern: String, input: String) {
    val saferePattern = Pattern.compile(pattern)
    val jdkPattern = java.util.regex.Pattern.compile(pattern)

    val safereM = saferePattern.matcher(input)
    val jdkM = jdkPattern.matcher(input)

    val safereResults: MutableList<String> = ArrayList<String>()
    while (safereM.find()) {
        safereResults.add(
            String.format("[%d,%d)='%s'", safereM.start(), safereM.end(), safereM.group())
        )
    }
    val jdkResults: MutableList<String> = ArrayList<String>()
    while (jdkM.find()) {
        jdkResults.add(String.format("[%d,%d)='%s'", jdkM.start(), jdkM.end(), jdkM.group()))
    }

    Assertions.assertThat<String?>(safereResults)
        .`as`("all find() results for /%s/ on \"%s\"", pattern, input)
        .isEqualTo(jdkResults)
}

/** Same as [.assertMatchesJdk] but also compares capture groups.  */
private fun assertMatchesJdkWithGroups(pattern: String, input: String) {
    val saferePattern = Pattern.compile(pattern)
    val jdkPattern = java.util.regex.Pattern.compile(pattern)

    val safereM = saferePattern.matcher(input)
    val jdkM = jdkPattern.matcher(input)

    val jdkFound = jdkM.find()
    val safereFound = safereM.find()

    Assertions.assertThat(safereFound).`as`("find() for /%s/ on \"%s\"", pattern, input).isEqualTo(jdkFound)

    if (jdkFound && safereFound) {
        Assertions.assertThat(safereM.start())
            .`as`("start() for /%s/ on \"%s\"", pattern, input)
            .isEqualTo(jdkM.start())
        Assertions.assertThat(safereM.end())
            .`as`("end() for /%s/ on \"%s\"", pattern, input)
            .isEqualTo(jdkM.end())
        Assertions.assertThat(safereM.group())
            .`as`("group() for /%s/ on \"%s\"", pattern, input)
            .isEqualTo(jdkM.group())

        val groupCount = min(safereM.groupCount(), jdkM.groupCount())
        for (g in 1..groupCount) {
            Assertions.assertThat(safereM.group(g))
                .`as`("group(%d) for /%s/ on \"%s\"", g, pattern, input)
                .isEqualTo(jdkM.group(g))
            Assertions.assertThat(safereM.start(g))
                .`as`("start(%d) for /%s/ on \"%s\"", g, pattern, input)
                .isEqualTo(jdkM.start(g))
            Assertions.assertThat(safereM.end(g))
                .`as`("end(%d) for /%s/ on \"%s\"", g, pattern, input)
                .isEqualTo(jdkM.end(g))
        }
    }
}

/**
 * Tests for leftmost-match semantics with alternation when different alternatives can match at
 * different starting positions in the input.
 * 
 * 
 * The fundamental rule: among all possible matches, return the one starting at the earliest
 * (leftmost) position, regardless of which alternative produces it. Existing tests only covered
 * alternation where alternatives compete at the *same* starting position. This test class
 * covers the missing dimension: alternatives that match at *different* positions.
 * 
 * 
 * See [issue #150](https://github.com/safere/safere/issues/150).
 */
@DisplayName("Alternation: leftmost position across alternatives")
internal class AlternationLeftmostPositionTest {
    /** A test case comparing SafeRE find() results against JDK java.util.regex.  */
    @JvmRecord
    internal data class FindCase(val description: String, val pattern: String?, val input: String?) {
        override fun toString(): String {
            return description
        }
    }

    // ==========================================================================
    // 1. Basic: second alternative matches at an earlier position than first
    // ==========================================================================
    @Nested
    @DisplayName("Basic: later alternative matches at earlier position")
    internal inner class BasicDifferentPosition {
        @Test
        @DisplayName("(bcd|abcde) on 'xabcdex' — second alt starts at 1, first at 2")
        fun secondAltMatchesEarlier() {
            assertMatchesJdk("(bcd|abcde)", "xabcdex")
        }

        @Test
        @DisplayName("(cd|abcd) on 'xabcdy' — second alt starts at 1, first at 3")
        fun longerAltMatchesEarlier() {
            assertMatchesJdk("(cd|abcd)", "xabcdy")
        }

        @Test
        @DisplayName("(def|abc) on 'abcdef' — second alt starts at 0, first at 3")
        fun nonOverlappingAltsAtDifferentPositions() {
            assertMatchesJdk("(def|abc)", "abcdef")
        }

        @Test
        @DisplayName("(b|ab) on 'ab' — second alt starts at 0, first at 1")
        fun singleCharVsLonger() {
            assertMatchesJdk("(b|ab)", "ab")
        }

        @Test
        @DisplayName("(bc|abc) on 'abc' — second alt starts at 0, first at 1")
        fun suffixVsFull() {
            assertMatchesJdk("(bc|abc)", "abc")
        }

        @Test
        @DisplayName("(xyz|abcxyz) on '...abcxyz...' — second alt earlier")
        fun longerPrefixedAlt() {
            assertMatchesJdk("(xyz|abcxyz)", "QQabcxyzQQ")
        }
    }

    // ==========================================================================
    // 2. First alternative matches at an earlier position than second
    // ==========================================================================
    @Nested
    @DisplayName("First alternative matches at earlier position")
    internal inner class FirstAltEarlier {
        @Test
        @DisplayName("(abc|def) on 'abcdef' — first alt at 0, second at 3")
        fun firstAltEarlierSimple() {
            assertMatchesJdk("(abc|def)", "abcdef")
        }

        @Test
        @DisplayName("(ab|cd) on 'xabcdy' — first at 1, second at 3")
        fun firstAltEarlierInMiddle() {
            assertMatchesJdk("(ab|cd)", "xabcdy")
        }
    }

    // ==========================================================================
    // 3. Real-world regressions for alternatives that start at different positions
    // ==========================================================================
    @Nested
    @DisplayName("Real-world overlapping alternatives")
    internal inner class RealWorldOverlappingAlternatives {
        @Test
        @DisplayName("Java generics pattern on 'List<List<Integer>> '")
        fun javaGenericsPattern() {
            // Regression for issue #150.
            val pattern =
                ("([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
                        + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)")
            val input = "List<List<Integer>> "
            assertMatchesJdkWithGroups(pattern, input)
        }

        @Test
        @DisplayName("API version pattern on '/literature/{\$api_version}/'")
        fun apiVersionPattern() {
            // Regression for issue #150.
            val pattern = "(?:(/|^)\\{\\\$api_version\\}(/|$)|\\{\\\$api_version\\})"
            val input = "/literature/{\$api_version}/"
            assertMatchesJdkWithGroups(pattern, input)
        }

        @Test
        @DisplayName("Java generics pattern — all matches")
        fun javaGenericsPatternAllMatches() {
            // Regression for issue #150.
            val pattern =
                ("([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
                        + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)")
            val input = "List<List<Integer>> "
            assertAllMatchesMatchJdk(pattern, input)
        }

        @Test
        @DisplayName("API version pattern — all matches")
        fun apiVersionPatternAllMatches() {
            // Regression for issue #150.
            val pattern = "(?:(/|^)\\{\\\$api_version\\}(/|$)|\\{\\\$api_version\\})"
            val input = "/literature/{\$api_version}/"
            assertAllMatchesMatchJdk(pattern, input)
        }
    }

    // ==========================================================================
    // 4. More than two alternatives
    // ==========================================================================
    @Nested
    @DisplayName("Multiple alternatives at different positions")
    internal inner class MultipleAlternatives {
        @Test
        @DisplayName("(c|bc|abc) on 'abc' — third alt starts earliest")
        fun threeAltsLastStartsEarliest() {
            assertMatchesJdk("(c|bc|abc)", "abc")
        }

        @Test
        @DisplayName("(de|cde|bcde|abcde) on 'xabcdey' — last alt earliest")
        fun fourAltsLastStartsEarliest() {
            assertMatchesJdk("(de|cde|bcde|abcde)", "xabcdey")
        }

        @Test
        @DisplayName("(ghi|def|abc) on 'abcdefghi' — third alt at 0")
        fun threeNonOverlapping() {
            assertMatchesJdk("(ghi|def|abc)", "abcdefghi")
        }

        @Test
        @DisplayName("(z|yz|xyz|wxyz) on 'wxyz' — last alt at 0")
        fun chainedSuffixesToFull() {
            assertMatchesJdk("(z|yz|xyz|wxyz)", "wxyz")
        }
    }

    // ==========================================================================
    // 5. Alternation with capturing groups
    // ==========================================================================
    @Nested
    @DisplayName("With capturing groups (affects engine path)")
    internal inner class WithCaptures {
        @Test
        @DisplayName("((b+)|(a+b+)) on 'aabb' — group positions")
        fun captureGroupPositions() {
            assertMatchesJdkWithGroups("((b+)|(a+b+))", "aabb")
        }

        @Test
        @DisplayName("((xy)|(wxy)) on 'zwxyz' — captured group text")
        fun capturedText() {
            assertMatchesJdkWithGroups("((xy)|(wxy))", "zwxyz")
        }

        @Test
        @DisplayName("(([0-9]+)|([a-z]+[0-9]+)) on 'abc123' — charclass alt groups")
        fun charClassCaptureGroups() {
            assertMatchesJdkWithGroups("(([0-9]+)|([a-z]+[0-9]+))", "abc123")
        }

        @Test
        @DisplayName("(?:(b)c|(a)bc) on 'abc' — non-participating groups")
        fun nonParticipatingGroups() {
            assertMatchesJdkWithGroups("(?:(b)c|(a)bc)", "abc")
        }
    }

    // ==========================================================================
    // 6. Alternation with quantifiers
    // ==========================================================================
    @Nested
    @DisplayName("Alternation combined with quantifiers")
    internal inner class WithQuantifiers {
        @Test
        @DisplayName("(b+|a+b+) on 'aabbb' — greedy quantifiers")
        fun greedyQuantifiers() {
            assertMatchesJdk("(b+|a+b+)", "aabbb")
        }

        @Test
        @DisplayName("(b+?|a+?b+?) on 'aabbb' — non-greedy quantifiers")
        fun nonGreedyQuantifiers() {
            assertMatchesJdk("(b+?|a+?b+?)", "aabbb")
        }

        @Test
        @DisplayName("(x{2}|.x{2}) on 'axx' — counted repetition")
        fun countedRepetition() {
            assertMatchesJdk("(x{2}|.x{2})", "axx")
        }

        @Test
        @DisplayName("(a*b|xa*b) on 'xxab' — star quantifier in alt")
        fun starInAlternation() {
            assertMatchesJdk("(a*b|xa*b)", "xxab")
        }

        @Test
        @DisplayName("([0-9]+|[a-z][0-9]+) on 'a123' — charclass+quantifier")
        fun charClassQuantifier() {
            assertMatchesJdk("([0-9]+|[a-z][0-9]+)", "a123")
        }

        @Test
        @DisplayName("(a{2,4}|.a{2,4}) on 'xaaaa' — range repetition")
        fun rangeRepetition() {
            assertMatchesJdk("(a{2,4}|.a{2,4})", "xaaaa")
        }
    }

    // ==========================================================================
    // 7. Alternation with character classes
    // ==========================================================================
    @Nested
    @DisplayName("Alternation with character classes")
    internal inner class WithCharClasses {
        @Test
        @DisplayName("([0-9]+|[a-z]+[0-9]+) on 'abc123' — charclass alts")
        fun charClassAlternation() {
            assertMatchesJdk("([0-9]+|[a-z]+[0-9]+)", "abc123")
        }

        @Test
        @DisplayName("(\\d+|\\w+\\d+) on 'abc123' — shorthand charclass")
        fun shorthandCharClass() {
            assertMatchesJdk("(\\d+|\\w+\\d+)", "abc123")
        }

        @Test
        @DisplayName("([A-Z]+|[a-z]+[A-Z]+) on 'abcXYZ' — case split")
        fun caseSplitCharClass() {
            assertMatchesJdk("([A-Z]+|[a-z]+[A-Z]+)", "abcXYZ")
        }
    }

    // ==========================================================================
    // 8. Nested alternation
    // ==========================================================================
    @Nested
    @DisplayName("Nested alternation groups")
    internal inner class NestedAlternation {
        @Test
        @DisplayName("((c|bc)|(abc)) on 'abc' — nested groups, outer alt")
        fun nestedGroupsOuterAlt() {
            assertMatchesJdkWithGroups("((c|bc)|(abc))", "abc")
        }

        @Test
        @DisplayName("(a(b|c)|ab(c|d)) on 'abcd' — inner alt different positions")
        fun innerAltDifferentPositions() {
            assertMatchesJdkWithGroups("(a(b|c)|ab(c|d))", "abcd")
        }

        @Test
        @DisplayName("((x|xy)(z|yz)) on 'xyz' — both halves have alternation")
        fun bothHalvesHaveAlternation() {
            assertMatchesJdkWithGroups("((x|xy)(z|yz))", "xyz")
        }
    }

    // ==========================================================================
    // 9. Alternation with anchors and boundaries
    // ==========================================================================
    @Nested
    @DisplayName("Alternation with anchors and boundaries")
    internal inner class WithAnchors {
        @Test
        @DisplayName("(foo$|foobar) on 'foobar' — anchor prevents first alt")
        fun dollarAnchorInFirstAlt() {
            assertMatchesJdk("(foo$|foobar)", "foobar")
        }

        @Test
        @DisplayName("(\\bbar|foobar) on 'foobar' — word boundary affects position")
        fun wordBoundaryInFirstAlt() {
            assertMatchesJdk("(\\bbar|foobar)", "foobar")
        }

        @Test
        @DisplayName("(^abc|xabc) on 'xabc' — caret prevents first alt")
        fun caretPreventsFirstAlt() {
            assertMatchesJdk("(^abc|xabc)", "xabc")
        }
    }

    // ==========================================================================
    // 10. Long text (forces DFA path, >256 chars)
    // ==========================================================================
    @Nested
    @DisplayName("Long text (>256 chars, forces DFA engine path)")
    internal class LongText {
        // --- Capturing groups (DFA sandwich + NFA capture extraction) ---
        @Test
        @DisplayName("(bcd|abcde) in long text — capturing, second alt starts earlier")
        fun capturingBasicLongText() {
            assertMatchesJdk("(bcd|abcde)", PADDING + "abcde" + PADDING)
        }

        @Test
        @DisplayName("(cd|abcd) in long text — capturing")
        fun capturingSuffixVsFullLongText() {
            assertMatchesJdk("(cd|abcd)", PADDING + "abcd" + PADDING)
        }

        @Test
        @DisplayName("(c|bc|abc) in long text — capturing, three alternatives")
        fun capturingThreeAltsLongText() {
            assertMatchesJdk("(c|bc|abc)", PADDING + "abc" + PADDING)
        }

        @Test
        @DisplayName("(b+|a+b+) in long text — capturing, quantifiers")
        fun capturingQuantifiersLongText() {
            assertMatchesJdk("(b+|a+b+)", PADDING + "aabbb" + PADDING)
        }

        @Test
        @DisplayName("([0-9]+|[a-z]+[0-9]+) in long text — capturing, charclass")
        fun capturingCharClassLongText() {
            assertMatchesJdk("([0-9]+|[a-z]+[0-9]+)", PADDING + "abc123" + PADDING)
        }

        // --- Non-capturing groups (DFA path only, no NFA capture extraction) ---
        @Test
        @DisplayName("(?:bcd|abcde) in long text — non-capturing, second alt starts earlier")
        fun nonCapturingBasicLongText() {
            assertMatchesJdk("(?:bcd|abcde)", PADDING + "abcde" + PADDING)
        }

        @Test
        @DisplayName("(?:cd|abcd) in long text — non-capturing")
        fun nonCapturingSuffixVsFullLongText() {
            assertMatchesJdk("(?:cd|abcd)", PADDING + "abcd" + PADDING)
        }

        @Test
        @DisplayName("(?:c|bc|abc) in long text — non-capturing, three alternatives")
        fun nonCapturingThreeAltsLongText() {
            assertMatchesJdk("(?:c|bc|abc)", PADDING + "abc" + PADDING)
        }

        @Test
        @DisplayName("(?:b+|a+b+) in long text — non-capturing, quantifiers")
        fun nonCapturingQuantifiersLongText() {
            assertMatchesJdk("(?:b+|a+b+)", PADDING + "aabbb" + PADDING)
        }

        @Test
        @DisplayName("(?:[0-9]+|[a-z]+[0-9]+) in long text — non-capturing, charclass")
        fun nonCapturingCharClassLongText() {
            assertMatchesJdk("(?:[0-9]+|[a-z]+[0-9]+)", PADDING + "abc123" + PADDING)
        }

        // --- Real-world overlapping alternatives on long text ---
        @Test
        @DisplayName("Java generics pattern in long text — capturing")
        fun javaGenericsLongTextCapturing() {
            // Regression for issue #150.
            val pattern =
                ("([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
                        + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)")
            val input = PADDING + "List<List<Integer>> " + PADDING
            assertMatchesJdkWithGroups(pattern, input)
        }

        @Test
        @DisplayName("Java generics pattern in long text — non-capturing")
        fun javaGenericsLongTextNonCapturing() {
            // Regression for issue #150.
            val pattern =
                ("(?:[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
                        + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)")
            val input = PADDING + "List<List<Integer>> " + PADDING
            assertMatchesJdk(pattern, input)
        }

        // --- Bare alternation (no explicit group) on long text ---
        @Test
        @DisplayName("bcd|abcde in long text — bare alternation, no group")
        fun bareAlternationLongText() {
            assertMatchesJdk("bcd|abcde", PADDING + "abcde" + PADDING)
        }

        // --- Tests isolating the "different endpoints" dimension ---
        // The DFA appears to work when alternatives share the same endpoint
        // but fails when they have different endpoints.
        @Test
        @DisplayName("(cd|abcd) long text — same endpoint: both end at same position")
        fun sameEndpointLongText() {
            // cd matches [302,304), abcd matches [300,304) — same endpoint 304
            assertMatchesJdk("(cd|abcd)", PADDING + "abcd" + PADDING)
        }

        @Test
        @DisplayName("(cde|abcde) long text — same endpoint, longer overlap")
        fun sameEndpointLongerOverlapLongText() {
            // cde matches [302,305), abcde matches [300,305) — same endpoint 305
            assertMatchesJdk("(cde|abcde)", PADDING + "abcde" + PADDING)
        }

        @Test
        @DisplayName("(bc|abcde) long text — different endpoints: 3-char vs 5-char")
        fun differentEndpointsLongText() {
            // bc matches [301,303), abcde matches [300,305) — different endpoints!
            assertMatchesJdk("(bc|abcde)", PADDING + "abcde" + PADDING)
        }

        @Test
        @DisplayName("(bcd|abcdef) long text — different endpoints: 3-char vs 6-char")
        fun differentEndpointsSixCharLongText() {
            // bcd matches [301,304), abcdef matches [300,306) — different endpoints!
            assertMatchesJdk("(bcd|abcdef)", PADDING + "abcdef" + PADDING)
        }

        @Test
        @DisplayName("(b|abcd) long text — different endpoints: 1-char vs 4-char")
        fun differentEndpointsOneVsFourLongText() {
            // b matches [301,302), abcd matches [300,304) — different endpoints!
            assertMatchesJdk("(b|abcd)", PADDING + "abcd" + PADDING)
        }

        @Test
        @DisplayName("(d|abcd) long text — same endpoint: both end at same pos")
        fun sameEndpointOneCharLongText() {
            // d matches [303,304), abcd matches [300,304) — same endpoint 304
            assertMatchesJdk("(d|abcd)", PADDING + "abcd" + PADDING)
        }

        companion object {
            private val PADDING = "#".repeat(300)
        }
    }

    // ==========================================================================
    // 11. Non-capturing groups wrapping alternation
    // ==========================================================================
    @Nested
    @DisplayName("Non-capturing groups around alternation")
    internal inner class NonCapturing {
        @Test
        @DisplayName("(?:bcd|abcde) on 'xabcdex' — no captures")
        fun nonCapturingBasic() {
            assertMatchesJdk("(?:bcd|abcde)", "xabcdex")
        }

        @Test
        @DisplayName("(?:cd|abcd) on 'xabcdy'")
        fun nonCapturingSuffixVsFull() {
            assertMatchesJdk("(?:cd|abcd)", "xabcdy")
        }

        @Test
        @DisplayName("(?:c|bc|abc) on 'abc'")
        fun nonCapturingThreeAlts() {
            assertMatchesJdk("(?:c|bc|abc)", "abc")
        }
    }

    // ==========================================================================
    // 12. Multiple successive find() calls (all matches)
    // ==========================================================================
    @Nested
    @DisplayName("All find() matches with position-varying alternation")
    internal inner class AllMatches {
        @Test
        @DisplayName("(bcd|abcde) findAll on 'abcde..bcd..'")
        fun findAllMixed() {
            assertAllMatchesMatchJdk("(bcd|abcde)", "abcde..bcd..")
        }

        @Test
        @DisplayName("(xy|wxy) findAll on 'wxy..xy..wxy'")
        fun findAllRepeated() {
            assertAllMatchesMatchJdk("(xy|wxy)", "wxy..xy..wxy")
        }

        @Test
        @DisplayName("(cd|abcd) findAll on 'abcd..cd..abcd'")
        fun findAllOverlap() {
            assertAllMatchesMatchJdk("(cd|abcd)", "abcd..cd..abcd")
        }

        @Test
        @DisplayName("Java generics pattern findAll on multi-generic input")
        fun findAllGenerics() {
            // Regression for issue #150.
            val pattern =
                ("([A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>"
                        + "|[A-Z][a-zA-Z0-9_]*<[A-Z][a-zA-Z0-9_]*<[A-Z][A-Za-z0-9,.& ]+>>)")
            val input = "Map<String, List<Integer>> and List<String> and Set<List<Long>>"
            assertAllMatchesMatchJdk(pattern, input)
        }
    }

    // ==========================================================================
    // 13. Parameterized: systematic position-offset exploration
    // ==========================================================================
    @Nested
    @DisplayName("Systematic: shorter alt at offset N, longer alt at offset 0")
    internal class SystematicOffsets {
        @ParameterizedTest(name = "{0}")
        @MethodSource("offsetCases")
        fun shorterAltAtOffset(desc: String?, pattern: String, input: String) {
            assertMatchesJdk(pattern, input)
        }

        companion object {
            @JvmStatic
            fun offsetCases(): Stream<Arguments?> {
                val cases: MutableList<Arguments?> = ArrayList<Arguments?>()
                // For each offset 1..5, test that the longer alternative matching at position 0
                // beats the shorter alternative matching at a later position.
                for (offset in 1..5) {
                    val prefix = "x".repeat(offset)
                    // Pattern: (short_suffix|full_string) where short_suffix appears later
                    val full = prefix + "ABC"
                    val shortAlt = "ABC"
                    cases.add(
                        Arguments.of(
                            String.format("offset=%d: (%s|%s) on '%s'", offset, shortAlt, full, full),
                            "(" + shortAlt + "|" + full + ")",
                            full
                        )
                    )
                }
                return cases.stream()
            }
        }
    }

    // ==========================================================================
    // 14. Exhaustive cross-engine: small alphabet with alternation focus
    // ==========================================================================
    @Nested
    @DisplayName("Exhaustive: alternation with multi-char atoms")
    internal inner class ExhaustiveAlternation {
        @Test
        @DisplayName("Exhaustive alternation with overlapping multi-char atoms")
        fun exhaustiveMultiCharAlternation() {
            // Multi-char atoms that can match at different positions in the same string.
            // This is the KEY missing configuration: existing exhaustive tests only use
            // single-char atoms for alternation, which cannot produce different-position matches.
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String>("a", "ab", "ba", "b"),  /* ops= */
                        mutableListOf<String>("%s|%s", "%s%s"),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String>("a", "b"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("exhaustiveMultiCharAlternation: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Exhaustive alternation with three-char atoms")
        fun exhaustiveThreeCharAtoms() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String>("a", "abc", "bc", "c"),  /* ops= */
                        mutableListOf<String>("%s|%s"),  /* maxStrLen= */
                        5,  /* strAlphabet= */
                        mutableListOf<String>("a", "b", "c"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("exhaustiveThreeCharAtoms: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Exhaustive alternation with quantifiers and multi-char atoms")
        fun exhaustiveAlternationWithQuantifiers() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        2,  /* maxOps= */
                        1,  /* atoms= */
                        mutableListOf<String>("a", "ab", "b", "ba"),  /* ops= */
                        mutableListOf<String>("%s|%s", "%s*", "%s+"),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String>("a", "b"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("exhaustiveAlternationWithQuantifiers: %,d tests%n", tests)
        }

        @Test
        @DisplayName("Exhaustive: three-way alternation with different-length atoms")
        fun exhaustiveThreeWayAlternation() {
            val tests =
                ExhaustiveUtils.run(
                    ExhaustiveUtils.Config( /* maxAtoms= */
                        3,  /* maxOps= */
                        2,  /* atoms= */
                        mutableListOf<String>("a", "ab", "b"),  /* ops= */
                        mutableListOf<String>("%s|%s"),  /* maxStrLen= */
                        4,  /* strAlphabet= */
                        mutableListOf<String>("a", "b"),  /* wrapper= */
                        "",  /* flags= */
                        0,  /* testLongText= */
                        true
                    )
                )
            System.err.printf("exhaustiveThreeWayAlternation: %,d tests%n", tests)
        }
    }

    // ==========================================================================
    // 15. Alternation with dot (.) wildcard
    // ==========================================================================
    @Nested
    @DisplayName("Alternation with dot wildcard")
    internal inner class WithDot {
        @Test
        @DisplayName("(.bc|abc) on 'xabc' — dot in first alt")
        fun dotInFirstAlt() {
            assertMatchesJdk("(.bc|abc)", "xabc")
        }

        @Test
        @DisplayName("(..c|abc) on 'xabc' — two dots vs literal")
        fun dotsVsLiteral() {
            assertMatchesJdk("(..c|abc)", "xabc")
        }

        @Test
        @DisplayName("(.+b|a.+b) on 'axxb' — dot-plus in alternation")
        fun dotPlusInAlternation() {
            assertMatchesJdk("(.+b|a.+b)", "axxb")
        }
    }

    // ==========================================================================
    // 16. Alternation where alternatives share a common prefix
    // ==========================================================================
    @Nested
    @DisplayName("Shared prefix between alternatives")
    internal inner class SharedPrefix {
        @Test
        @DisplayName("(ab|abc) on 'xabc' — short prefix vs long prefix")
        fun shortVsLongPrefix() {
            assertMatchesJdk("(ab|abc)", "xabc")
        }

        @Test
        @DisplayName("(abc|abcd) on 'xabcdy' — extending prefix")
        fun extendingPrefix() {
            assertMatchesJdk("(abc|abcd)", "xabcdy")
        }

        @Test
        @DisplayName("(ab.|ab..) on 'xabcdy' — shared prefix with wildcards")
        fun sharedPrefixWildcards() {
            assertMatchesJdk("(ab.|ab..)", "xabcdy")
        }
    }

    // ==========================================================================
    // 17. Real-world patterns that hit this bug class
    // ==========================================================================
    @Nested
    @DisplayName("Real-world patterns")
    internal inner class RealWorld {
        @Test
        @DisplayName("HTTP method pattern: (GET|POST|DELETE) in URL line")
        fun httpMethodInUrl() {
            // Both alternatives can appear, test leftmost
            assertMatchesJdk("(POST|GET|PUT|DELETE)", "GET /api/v1 HTTP/1.1")
        }

        @Test
        @DisplayName("Log level: (WARN|WARNING) in log line")
        fun logLevel() {
            assertMatchesJdk("(WARN|WARNING)", "2026-01-01 WARNING: disk full")
        }

        @Test
        @DisplayName("Path matching: two URL alternatives at different positions")
        fun pathMatching() {
            assertMatchesJdk("(/api/v2/users|/v2/users)", "GET /api/v2/users HTTP/1.1")
        }

        @Test
        @DisplayName("Version string: (\\d+\\.\\d+|\\d+\\.\\d+\\.\\d+) on '1.2.3'")
        fun versionString() {
            assertMatchesJdk("(\\d+\\.\\d+|\\d+\\.\\d+\\.\\d+)", "version 1.2.3")
        }

        @Test
        @DisplayName("HTML tag pattern: simple vs nested")
        fun htmlTags() {
            assertMatchesJdk("(<b>|<b class=\"[^\"]*\">)", "text <b class=\"bold\">hello</b>")
        }
    }

    // ==========================================================================
    // 18. Minimal reproduction: progressively simplify short-text failures
    // ==========================================================================
    @Nested
    @DisplayName("Minimal reproduction of short-text failures")
    internal inner class MinimalRepro {
        // The generics pattern fails on short text. The basic (bcd|abcde) passes on short text.
        // These tests progressively simplify the generics pattern to find the boundary.
        @Test
        @DisplayName("Shared prefix with charclass: ([A-Z]x|[A-Z]xy) on 'Axy'")
        fun sharedCharClassPrefix() {
            assertMatchesJdk("([A-Z]x|[A-Z]xy)", "Axy")
        }

        @Test
        @DisplayName("Shared prefix + quantifier: ([A-Z]+x|[A-Z]+xy) on 'AAAxy'")
        fun sharedCharClassPrefixQuantifier() {
            assertMatchesJdk("([A-Z]+x|[A-Z]+xy)", "AAAxy")
        }

        @Test
        @DisplayName("Alt1 is suffix of alt2's match: (x>|x.x>) on 'axbx>'")
        fun alt1SuffixOfAlt2() {
            assertMatchesJdk("(x>|x.x>)", "axbx>")
        }

        @Test
        @DisplayName("Alt with literal delimiters: (a<b>|a<a<b>>) on 'a<a<b>>'")
        fun literalDelimiters() {
            assertMatchesJdk("(a<b>|a<a<b>>)", "a<a<b>>")
        }

        @Test
        @DisplayName("Charclass + quantifier, different match lengths")
        fun charClassQuantifierDiffLengths() {
            // Alt1 matches shorter thing at later position, alt2 matches longer thing from earlier
            assertMatchesJdk("([a-z]+>|[a-z]+<[a-z]+>)", "ab<cd>")
        }

        @Test
        @DisplayName("Nested angle brackets: (X<Y>|X<X<Y>>)")
        fun nestedAngleBrackets() {
            assertMatchesJdk("(X<Y>|X<X<Y>>)", "X<X<Y>>")
        }

        @Test
        @DisplayName("Simplified generics: (L<I>|L<L<I>>) on 'L<L<I>>'")
        fun simplifiedGenerics() {
            assertMatchesJdk("(L<I>|L<L<I>>)", "L<L<I>>")
        }

        @Test
        @DisplayName("Simplified generics with charclass: ([A-Z]<[A-Z]>|[A-Z]<[A-Z]<[A-Z]>>)")
        fun simplifiedGenericsCharClass() {
            assertMatchesJdk("([A-Z]<[A-Z]>|[A-Z]<[A-Z]<[A-Z]>>)", "A<B<C>>")
        }

        @Test
        @DisplayName("With + quantifier: ([a-z]+<[a-z]+>|[a-z]+<[a-z]+<[a-z]+>>)")
        fun withPlusQuantifier() {
            assertMatchesJdk("([a-z]+<[a-z]+>|[a-z]+<[a-z]+<[a-z]+>>)", "ab<cd<ef>>")
        }

        @Test
        @DisplayName("API version simplified: (/.+/|.+) on '/lit/{v}/'")
        fun apiVersionSimplified() {
            assertMatchesJdk("(/.+/|.+)", "/lit/thing/")
        }

        @Test
        @DisplayName("Alternation where first alt fails, second succeeds, at same start position")
        fun firstAltFailsSameStart() {
            // At position 0: alt1 a<b> needs 'a<b>' but input is 'a<a<b>>',
            // so alt1 sees a<a which fails at second char. Alt2 a<a<b>> matches.
            assertMatchesJdk("(a<b>|a<a<b>>)", " a<a<b>>")
        }

        @Test
        @DisplayName("Both alts share prefix, only second can match at earlier position")
        fun sharedPrefixSecondAltOnly() {
            // In 'xabcdy': at pos 1, alt1 'abc' matches [1,4), at pos 1 alt2 'abcde' matches [1,6)
            // Both start at same position but different end — tests preference for longer match
            assertMatchesJdk("(abc|abcde)", "xabcdy")
        }

        @Test
        @DisplayName("Minimal nested: (y>|xyy>) on 'xyy>'")
        fun minimalNested() {
            // Alt1 y> at pos 1: y✓ y≠> → fail. At pos 2: y✓ >✓ → [2,4).
            // Alt2 xyy> at pos 0: x✓ y✓ y✓ >✓ → [0,4). Leftmost is 0.
            assertMatchesJdk("(y>|xyy>)", "xyy>")
        }

        @Test
        @DisplayName("Three-char minimal: (bc>|abbc>) on 'abbc>'")
        fun threeCharMinimal() {
            assertMatchesJdk("(bc>|abbc>)", "abbc>")
        }

        @Test
        @DisplayName("Same structure, different delimiters: (b]|a[b]) on 'a[b]'")
        fun bracketDelimiters() {
            assertMatchesJdk("(b]|a\\[b])", "a[b]")
        }

        @Test
        @DisplayName("Same structure with parens: (b\\)|a\\(b\\)) on 'a(b)'")
        fun parenDelimiters() {
            assertMatchesJdk("(b\\)|a\\(b\\))", "a(b)")
        }
    }
}
