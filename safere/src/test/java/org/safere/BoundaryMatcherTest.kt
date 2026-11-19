// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.function.IntFunction
import java.util.regex.PatternSyntaxException

/**
 * Tests for boundary matcher constructs: `\b`, `\B`, `\A`, `\z`, `\Z`, `\G`, and `\b{g}`.
 * 
 * 
 * Covers issue #112 (boundary matchers section).
 */
internal class BoundaryMatcherTest {
    // ---------------------------------------------------------------------------
    // \Z — end of input before final terminator
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("\\Z (end before final terminator)")
    internal inner class BackslashZ {
        @Test
        @DisplayName("\\Z matches at end of string")
        fun matchesAtEnd() {
            val p = Pattern.compile("abc\\Z")
            val m = p.matcher("abc")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
        }

        @Test
        @DisplayName("\\Z matches before trailing \\n")
        fun matchesBeforeTrailingNewline() {
            val p = Pattern.compile("abc\\Z")
            val m = p.matcher("abc\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
        }

        @Test
        @DisplayName("\\Z does not match before two trailing newlines")
        fun noMatchBeforeTwoTrailingNewlines() {
            val p = Pattern.compile("abc\\Z")
            val m = p.matcher("abc\n\n")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\Z does not match in the middle of input")
        fun noMatchInMiddle() {
            val p = Pattern.compile("abc\\Z")
            val m = p.matcher("abcdef")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\Z alone matches at end of string")
        fun aloneMatchesAtEnd() {
            val p = Pattern.compile("\\Z")
            val m = p.matcher("hello")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(5)
        }

        @Test
        @DisplayName("\\Z alone matches before trailing \\n")
        fun aloneMatchesBeforeTrailingNewline() {
            val p = Pattern.compile("\\Z")
            val m = p.matcher("hello\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(5)
        }

        @Test
        @DisplayName("\\Z with MULTILINE still matches only at end, not at line boundaries")
        fun multilineStillOnlyMatchesAtEnd() {
            val p = Pattern.compile("\\w+\\Z", Pattern.MULTILINE)
            val m = p.matcher("abc\ndef\nghi\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("ghi")
            // Should not find another match at abc or def
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\Z behaves like $ in non-MULTILINE mode")
        fun sameAsDollarNonMultiline() {
            val inputs = arrayOf<String?>("foo", "foo\n", "foo\n\n", "foobar")
            for (input in inputs) {
                val zResult = Pattern.compile("foo\\Z").matcher(input).find()
                val dollarResult = Pattern.compile("foo$").matcher(input).find()
                Assertions.assertThat(zResult)
                    .`as`("\\Z vs $ on input '%s'", input!!.replace("\n", "\\n"))
                    .isEqualTo(dollarResult)
            }
        }

        @Test
        @DisplayName("\\Z differs from \\z on trailing newline")
        fun differsFromLowercaseZ() {
            val pZ = Pattern.compile("abc\\Z")
            val pz = Pattern.compile("abc\\z")

            // Both match at absolute end
            Assertions.assertThat(pZ.matcher("abc").find()).isTrue()
            Assertions.assertThat(pz.matcher("abc").find()).isTrue()

            // Only \Z matches before trailing \n
            Assertions.assertThat(pZ.matcher("abc\n").find()).isTrue()
            Assertions.assertThat(pz.matcher("abc\n").find()).isFalse()
        }

        @Test
        @DisplayName("matches() with \\Z — matches bare string")
        fun matchesBareString() {
            Assertions.assertThat(Pattern.compile("abc\\Z").matcher("abc").matches()).isTrue()
        }

        @Test
        @DisplayName("matches() with \\Z — does not match string with trailing newline")
        fun matchesTrailingNewline() {
            // matches() requires the entire string to be consumed; \Z is zero-width
            // so "abc\n" has an unconsumed \n after the \Z assertion.
            Assertions.assertThat(Pattern.compile("abc\\Z").matcher("abc\n").matches()).isFalse()
        }

        @Test
        @DisplayName("\\Z with captures")
        fun withCaptures() {
            val p = Pattern.compile("(\\w+)\\Z")
            val m = p.matcher("hello\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("hello")
        }

        @Test
        @DisplayName("\\Z in alternation")
        fun inAlternation() {
            val p = Pattern.compile("end\\Z|end\\z")
            val m = p.matcher("end\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("end")
        }
    }

    // ---------------------------------------------------------------------------
    // \G — end of previous match (not supported)
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("\\G (end of previous match)")
    internal inner class BackslashG {
        @Test
        @DisplayName("\\G is rejected with a descriptive error")
        fun rejected() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("\\G\\w+") })
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("\\G")
        }

        @Test
        @DisplayName("\\G alone is rejected")
        fun rejectedAlone() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("\\G") })
                .isInstanceOf(PatternSyntaxException::class.java)
                .hasMessageContaining("\\G")
        }

        @Test
        @DisplayName("\\G in character class is rejected (not valid in JDK either)")
        fun inCharClassIsRejected() {
            // Inside [...], \G is not a valid escape in JDK or SafeRE.
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[\\GA]") })
                .isInstanceOf(PatternSyntaxException::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // \b{g} — grapheme cluster boundary (accepted for JDK compatibility)
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("\\b{g} (grapheme cluster boundary)")
    internal class GraphemeClusterBoundary {
        @Test
        @DisplayName("\\b{g} compiles without error")
        fun compiles() {
            Assertions.assertThatNoException().isThrownBy(ThrowingCallable { Pattern.compile("\\b{g}") })
        }

        @Test
        @DisplayName("\\b{g} in a larger pattern compiles without error")
        fun compilesInLargerPattern() {
            Assertions.assertThatNoException().isThrownBy(ThrowingCallable { Pattern.compile("foo\\b{g}bar") })
        }

        @Test
        @DisplayName("\\b{g} does not match inside a base-plus-combining-mark cluster")
        fun doesNotMatchInsideBaseCombiningCluster() {
            assertFindSameAsJdk("a\\b{g}\\u0300", "a\u0300")
        }

        @Test
        @DisplayName("\\b{g} matches at the edges of base-plus-combining-mark clusters")
        fun matchesBaseCombiningClusterEdges() {
            assertFindSameAsJdk("\\b{g}a\\u0300\\b{g}", "a\u0300")
        }

        @Test
        @DisplayName("\\b{g} find() reports cluster boundaries, not every UTF-16 position")
        fun findReportsOnlyClusterBoundaries() {
            assertFindSameAsJdk("\\b{g}", "a\u0300b")
        }

        @Test
        @DisplayName("\\b{g} does not split CRLF")
        fun doesNotSplitCrLf() {
            assertFindSameAsJdk("\\r\\b{g}\\n", "\r\n")
        }

        @Test
        @DisplayName("\\b{g} respects opaque region bounds for base-plus-mark clusters")
        fun respectsOpaqueRegionBoundsForBaseMarkClusters() {
            assertTraceSameAsJdk("\\b{g}", "e\u0301", 1, 2)
            assertTraceSameAsJdk("(\\b{g})", "e\u0301", 1, 2)
            assertTraceSameAsJdk("\\b{g}\\X", "e\u0301", 1, 2)
            assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "e\u0301", 1, 2)
        }

        @Test
        @DisabledForCrosscheck("GB3 and GB9 forbid internal boundaries even after consuming a prefix")
        @DisplayName("opaque regions do not invent boundaries inside base-mark or CRLF clusters")
        fun doesNotInventInternalClusterBoundariesInOpaqueRegions() {
            assertNoMatchInRegion("a\\b{g}\\u0300", "#a\u0300$", 1, 3)
            assertNoMatchInRegion("(?:a)\\b{g}\\u0300", "#a\u0300$", 1, 3)
            assertNoMatchInRegion("(a)\\b{g}\\u0300", "#a\u0300$", 1, 3)
            assertNoMatchInRegion("a(?:\\b{g})\\u0300", "#a\u0300$", 1, 3)
            assertNoMatchInRegion("\\u0061\\b{g}\\u0300", "#a\u0300$", 1, 3)
            assertNoMatchInRegion("\\r\\b{g}\\n", "#\r\n$", 1, 3)
            assertNoMatchInRegion("(?:\\r)\\b{g}\\n", "#\r\n$", 1, 3)
            assertNoMatchInRegion("(\\r)\\b{g}\\n", "#\r\n$", 1, 3)
            assertNoMatchInRegion("\\r(?:\\b{g})\\n", "#\r\n$", 1, 3)
            assertNoMatchInRegion("\\u000D\\b{g}\\u000A", "#\r\n$", 1, 3)
        }

        @Test
        @DisplayName("\\b{g} treats opaque region edges inside non-surrogate clusters as boundaries")
        fun treatsOpaqueRegionEdgesInsideNonSurrogateClustersAsBoundaries() {
            assertTraceSameAsJdk("\\b{g}", "a\u0301b", 1, 2)
            assertTraceSameAsJdk("\\b{g}\\X", "a\u0301b", 1, 2)
            assertTraceSameAsJdk("\\b{g}\\X\\b{g}", "a\u0301b", 1, 2)

            val hangulCluster = "\u1100\u1161"
            assertTraceSameAsJdk("\\b{g}", hangulCluster, 1, 2)
            assertTraceSameAsJdk("\\b{g}\\X", hangulCluster, 1, 2)
            assertTraceSameAsJdk("\\b{g}\\X\\b{g}", hangulCluster, 1, 2)
        }

        @Test
        @DisabledForCrosscheck("SafeRE's region-local grapheme model intentionally composes this JDK edge trace")
        @DisplayName("\\b{g} composes across opaque region edges inside ZWJ sequences")
        fun composesAcrossOpaqueRegionEdgesInsideZwjSequences() {
            val zwjSequence = "\uD83D\uDC69\u200D\uD83D\uDC69"
            assertSafeReFindBounds("\\b{g}\\X", zwjSequence, 2, 5, mutableListOf<String>("2-3", "3-5"))
        }

        @Test
        @DisplayName("bare \\b{g} alternatives preserve priority after empty find() matches")
        fun boundaryOnlyAlternativesPreserveEmptyMatchPriorityOnRepeatedFind() {
            assertTraceSameAsJdk("\\b{g}|a", "aa", 0, 2)
            assertTraceSameAsJdk("(?:\\b{g}|a)", "aa", 0, 2)
            assertTraceSameAsJdk("(?:(?:\\b{g})|a)", "aa", 0, 2)
            assertCapturedFindTraceSameAsJdk("(\\b{g})|(a)", "aa", 0, 2)
            assertTraceSameAsJdk("\\b{g}|\\X", "aa", 0, 2)
            assertTraceSameAsJdk("(?:\\b{g}|\\X)", "aa", 0, 2)
            assertCapturedFindTraceSameAsJdk("(\\b{g})|(\\X)", "aa", 0, 2)
        }

        @Test
        @DisplayName("transparent trailing \\b{g} sees contextual characters beyond region end")
        fun transparentTrailingGraphemeBoundaryUsesFullTextContext() {
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u0301", 0, 1)
            assertTransparentTraceSameAsJdk("^\\X\\b{g}", "a\u0301", 0, 1)
            assertTransparentTraceSameAsJdk("\\b{g}\\X\\b{g}", "a\u0301", 0, 1)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "\r\n", 0, 1)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u200D", 0, 1)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "a\u0903", 0, 1)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83D\uDC4D\uD83C\uDFFD", 0, 2)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uD83C\uDDFA\uD83C\uDDF8", 0, 2)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "\u1100\u1161", 0, 1)
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "\uAC00\u11A8", 0, 1)
        }

        @Test
        @DisplayName("\\b{g} and \\X respect regions split inside surrogate pairs")
        @DisabledForCrosscheck("Grapheme consumption stays inside the region; JDK-8394894")
        fun graphemeBoundariesRespectRegionsSplitInsideSurrogatePairs() {
            val splitBeforeZwj = "\uD83D\uDC69\u200D\uD83D\uDCBB"
            assertTraceSameAsJdk("\\b{g}", splitBeforeZwj, 1, 5)
            assertTransparentTraceSameAsJdk("\\b{g}", "\uD83D\uDE00", 1, 1)
            assertTransparentTraceSameAsJdk("(\\b{g})", "\uD83D\uDE00", 1, 1)
            assertTransparentTraceSameAsJdk("\\b{g}", "\uD83D\uDE00\u0301\u0301", 2, 4)
            assertTransparentTraceSameAsJdk("(\\b{g})", "\uD83D\uDE00\u0301\u0301", 2, 4)
            assertTransparentTraceSameAsJdk("\\b{g}", "\uD83D\uD83C\uDDFA\uD83C\uDDF8\u200D", 1, 5)
            assertTransparentTraceSameAsJdk(
                "\\b{g}", "a\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8\u0300", 1, 7
            )

            val lowSurrogateThenZwj = "\uD83D\uDE00\u200D"
            assertTraceSameAsJdk("\\b{g}", lowSurrogateThenZwj, 1, 3)

            val splitLowSurrogateBeforeModifier = "\uD83D\uDC4D\uD83C\uDFFB"
            // JDK-8394894: a transparent assertion cannot justify consuming outside the region.
            for (regex in mutableListOf<String>("\\X\\b{g}", "\\b{g}\\X\\b{g}")) {
                Assertions.assertThat(
                    Pattern.compile(regex)
                        .matcher(splitLowSurrogateBeforeModifier)
                        .region(1, 3)
                        .useTransparentBounds(true)
                        .find()
                )
                    .`as`(regex)
                    .isFalse()
            }

            val splitBeforeExtender = "\uD83D\uDE00\uD83D\u0301"
            assertTraceSameAsJdk("\\b{g}", splitBeforeExtender, 1, 3)
            assertTraceSameAsJdk("(\\b{g})", splitBeforeExtender, 1, 3)
            assertTraceSameAsJdk("\\X\\b{g}", splitBeforeExtender, 1, 3)
        }

        @Test
        @DisplayName("\\X decodes only the visible text when an opaque region splits a surrogate pair")
        @DisabledForCrosscheck("Region-local grapheme consumption differs from the JDK overrun bug")
        fun graphemeClustersDecodeWithinTheOpaqueRegion() {
            val emoji = "\uD83D\uDE00"
            OpaqueRegionChecks.assertMatchesSubstring("\\X", emoji, 0, 1)
            OpaqueRegionChecks.assertMatchesSubstring("^\\X$", emoji, 0, 1)
            OpaqueRegionChecks.assertMatchesSubstring("\\b{g}\\X", emoji, 0, 1)
            OpaqueRegionChecks.assertMatchesSubstring("\\X\\b{g}", emoji, 0, 1)
        }

        @Test
        @DisabledForCrosscheck("Grapheme expectations use pinned ICU4J, independently of JDK Unicode")
        @DisplayName("chained \\X find() preserves leftmost starts inside Indic conjunct clusters")
        fun chainedGraphemeClustersPreserveLeftmostStartsInsideIndicConjuncts() {
            val oracle = BreakIterator.getCharacterInstance(ULocale.ROOT)
            for (conjunct in mutableListOf<String>("\u0915\u094D\u200D\u0915", "\u0995\u09CD\u200D\u0995")) {
                for (input in java.util.List.of<String>(conjunct, conjunct!!.repeat(2), "a" + conjunct + "b")) {
                    val initial = icuGraphemePair(oracle, input, 0)
                    val expectedFinds: MutableList<GraphemePair> = ArrayList<GraphemePair>()
                    var start = 0
                    while (start < input.length) {
                        val pair = icuGraphemePair(oracle, input, start)
                        if (pair == null) {
                            start = input.offsetByCodePoints(start, 1)
                        } else {
                            expectedFinds.add(pair)
                            start = pair.end
                        }
                    }
                    for (regex in mutableListOf<String>(
                        "\\X\\X",
                        "(?:\\X)(?:\\X)",
                        "(?:\\X){2}",
                        "\\X{1}\\X",
                        "(\\X)(\\X)"
                    )) {
                        val matcher = Pattern.compile(regex).matcher(input)
                        Assertions.assertThat(matcher.matches())
                            .`as`("matches() /%s/ on %s", regex, input)
                            .isEqualTo(initial != null && initial.end == input.length)
                        Assertions.assertThat(matcher.reset().lookingAt())
                            .`as`("lookingAt() /%s/ on %s", regex, input)
                            .isEqualTo(initial != null)
                        matcher.reset()
                        for (pair in expectedFinds) {
                            Assertions.assertThat(matcher.find()).`as`("find() /%s/ on %s", regex, input).isTrue()
                            Assertions.assertThat(intArrayOf(matcher.start(), matcher.end()))
                                .containsExactly(pair.start, pair.end)
                            Assertions.assertThat(matcher.group()).isEqualTo(input.substring(pair.start, pair.end))
                            if (matcher.groupCount() == 2) {
                                Assertions.assertThat(intArrayOf(matcher.start(1), matcher.end(1)))
                                    .containsExactly(pair.start, pair.firstEnd)
                                Assertions.assertThat(intArrayOf(matcher.start(2), matcher.end(2)))
                                    .containsExactly(pair.firstEnd, pair.end)
                                Assertions.assertThat(matcher.group(1))
                                    .isEqualTo(input.substring(pair.start, pair.firstEnd))
                                Assertions.assertThat(matcher.group(2))
                                    .isEqualTo(input.substring(pair.firstEnd, pair.end))
                            }
                        }
                        Assertions.assertThat(matcher.find()).`as`("find() exhausted /%s/ on %s", regex, input)
                            .isFalse()
                    }
                }
            }
        }

        @JvmRecord
        private data class GraphemePair(val start: Int, val firstEnd: Int, val end: Int)

        @Test
        @DisplayName("\\b without {g} still works as word boundary")
        fun plainWordBoundaryStillWorks() {
            val p = Pattern.compile("\\bword\\b")
            Assertions.assertThat(p.matcher("a word here").find()).isTrue()
            Assertions.assertThat(p.matcher("awordhere").find()).isFalse()
        }

        @Test
        @DisplayName("\\B without {g} still works as non-word boundary")
        fun plainNonWordBoundaryStillWorks() {
            val p = Pattern.compile("\\Bor\\B")
            Assertions.assertThat(p.matcher("word").find()).isTrue()
            Assertions.assertThat(p.matcher("or").find()).isFalse()
        }

        @Test
        @DisplayName("\\b followed by literal brace is not rejected")
        fun otherBracedContentNotRejected() {
            // \b{x} should be parsed as \b followed by literal {x}
            // (since {x} is not a valid repetition, it becomes literal)
            val p = Pattern.compile("\\b\\{x}")
            Assertions.assertThat(p.matcher("a{x}b").find()).isTrue()
        }

        companion object {
            private fun assertFindSameAsJdk(regex: String, input: String) {
                val jdkMatcher = java.util.regex.Pattern.compile(regex).matcher(input)
                val safeMatcher = Pattern.compile(regex).matcher(input)

                val jdkMatches: MutableList<IntArray?> = ArrayList<IntArray?>()
                while (jdkMatcher.find()) {
                    jdkMatches.add(intArrayOf(jdkMatcher.start(), jdkMatcher.end()))
                }

                val safeMatches: MutableList<IntArray?> = ArrayList<IntArray?>()
                while (safeMatcher.find()) {
                    safeMatches.add(intArrayOf(safeMatcher.start(), safeMatcher.end()))
                }

                Assertions.assertThat<IntArray?>(safeMatches)
                    .`as`("find() positions for /%s/ on %s", regex, input)
                    .containsExactly(*jdkMatches.toTypedArray())
            }

            private fun assertTraceSameAsJdk(regex: String, input: String, start: Int, end: Int) {
                val jdkMatcher =
                    java.util.regex.Pattern.compile(regex).matcher(input).region(start, end)
                val safeMatcher = Pattern.compile(regex).matcher(input).region(start, end)

                Assertions.assertThat(safeMatcher.matches())
                    .`as`("matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .isEqualTo(jdkMatcher.matches())

                jdkMatcher.reset(input).region(start, end)
                safeMatcher.reset(input).region(start, end)
                val jdkLookingAt = jdkMatcher.lookingAt()
                val safeLookingAt = safeMatcher.lookingAt()
                Assertions.assertThat(safeLookingAt)
                    .`as`("lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .isEqualTo(jdkLookingAt)
                if (jdkLookingAt) {
                    Assertions.assertThat(intArrayOf(safeMatcher.start(), safeMatcher.end()))
                        .`as`("lookingAt() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
                        .containsExactly(jdkMatcher.start(), jdkMatcher.end())
                }

                jdkMatcher.reset(input).region(start, end)
                safeMatcher.reset(input).region(start, end)
                val jdkMatches: MutableList<IntArray?> = ArrayList<IntArray?>()
                while (jdkMatcher.find()) {
                    jdkMatches.add(intArrayOf(jdkMatcher.start(), jdkMatcher.end()))
                }

                val safeMatches: MutableList<IntArray?> = ArrayList<IntArray?>()
                while (safeMatcher.find()) {
                    safeMatches.add(intArrayOf(safeMatcher.start(), safeMatcher.end()))
                }

                Assertions.assertThat<IntArray?>(safeMatches)
                    .`as`("find() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .containsExactly(*jdkMatches.toTypedArray())
            }

            private fun assertNoMatchInRegion(regex: String?, input: String?, start: Int, end: Int) {
                val matcher = Pattern.compile(regex).matcher(input).region(start, end)
                Assertions.assertThat(matcher.matches()).`as`("matches() for /%s/ on %s", regex, input).isFalse()
                Assertions.assertThat(matcher.lookingAt()).`as`("lookingAt() for /%s/ on %s", regex, input).isFalse()
                Assertions.assertThat(matcher.find()).`as`("find() for /%s/ on %s", regex, input).isFalse()
            }

            private fun assertSafeReFindBounds(
                regex: String?, input: String?, start: Int, end: Int, expected: MutableList<String>?
            ) {
                val safeMatcher = Pattern.compile(regex).matcher(input).region(start, end)
                val safeMatches: MutableList<String> = ArrayList<String>()
                while (safeMatcher.find()) {
                    safeMatches.add(safeMatcher.start().toString() + "-" + safeMatcher.end())
                }
                Assertions.assertThat<String?>(safeMatches)
                    .`as`("SafeRE find() positions for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .containsExactlyElementsOf(expected)
            }

            private fun assertTransparentTraceSameAsJdk(
                regex: String, input: String, start: Int, end: Int
            ) {
                val jdkMatcher =
                    java.util.regex.Pattern.compile(regex)
                        .matcher(input)
                        .region(start, end)
                        .useTransparentBounds(true)
                val safeMatcher =
                    Pattern.compile(regex).matcher(input).region(start, end).useTransparentBounds(true)

                Assertions.assertThat(safeMatcher.matches())
                    .`as`("transparent matches() for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .isEqualTo(jdkMatcher.matches())

                jdkMatcher.reset(input).region(start, end).useTransparentBounds(true)
                safeMatcher.reset(input).region(start, end).useTransparentBounds(true)
                val jdkLookingAt = jdkMatcher.lookingAt()
                val safeLookingAt = safeMatcher.lookingAt()
                Assertions.assertThat(safeLookingAt)
                    .`as`("transparent lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .isEqualTo(jdkLookingAt)
                if (jdkLookingAt) {
                    Assertions.assertThat(intArrayOf(safeMatcher.start(), safeMatcher.end()))
                        .`as`(
                            "transparent lookingAt() positions for /%s/ on %s region [%s,%s]",
                            regex, input, start, end
                        )
                        .containsExactly(jdkMatcher.start(), jdkMatcher.end())
                }

                jdkMatcher.reset(input).region(start, end).useTransparentBounds(true)
                safeMatcher.reset(input).region(start, end).useTransparentBounds(true)
                val jdkMatches: MutableList<IntArray?> = ArrayList<IntArray?>()
                while (jdkMatcher.find()) {
                    jdkMatches.add(intArrayOf(jdkMatcher.start(), jdkMatcher.end()))
                }

                val safeMatches: MutableList<IntArray?> = ArrayList<IntArray?>()
                while (safeMatcher.find()) {
                    safeMatches.add(intArrayOf(safeMatcher.start(), safeMatcher.end()))
                }

                Assertions.assertThat<IntArray?>(safeMatches)
                    .`as`(
                        "transparent find() positions for /%s/ on %s region [%s,%s]",
                        regex, input, start, end
                    )
                    .containsExactly(*jdkMatches.toTypedArray())
            }

            private fun assertCapturedFindTraceSameAsJdk(
                regex: String, input: String, start: Int, end: Int
            ) {
                val jdkMatcher =
                    java.util.regex.Pattern.compile(regex).matcher(input).region(start, end)
                val safeMatcher = Pattern.compile(regex).matcher(input).region(start, end)

                val jdkMatches = capturedFindTrace(jdkMatcher)
                val safeMatches = capturedFindTrace(safeMatcher)

                Assertions.assertThat<String?>(safeMatches)
                    .`as`("captured find() trace for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .containsExactlyElementsOf(jdkMatches)
            }

            private fun capturedFindTrace(matcher: java.util.regex.Matcher): MutableList<String> {
                val trace: MutableList<String> = ArrayList<String>()
                while (matcher.find()) {
                    trace.add(capturedMatchTrace(matcher))
                }
                return trace
            }

            private fun capturedFindTrace(matcher: Matcher): MutableList<String> {
                val trace: MutableList<String> = ArrayList<String>()
                while (matcher.find()) {
                    trace.add(capturedMatchTrace(matcher))
                }
                return trace
            }

            private fun capturedMatchTrace(matcher: java.util.regex.Matcher): String {
                val builder = StringBuilder()
                builder.append(matcher.start()).append('-').append(matcher.end()).append(':')
                builder.append(matcher.group())
                for (i in 1..matcher.groupCount()) {
                    builder.append(':').append(matcher.group(i))
                }
                return builder.toString()
            }

            private fun capturedMatchTrace(matcher: Matcher): String {
                val builder = StringBuilder()
                builder.append(matcher.start()).append('-').append(matcher.end()).append(':')
                builder.append(matcher.group())
                for (i in 1..matcher.groupCount()) {
                    builder.append(':').append(matcher.group(i))
                }
                return builder.toString()
            }

            private fun icuGraphemePair(oracle: BreakIterator, input: String, start: Int): GraphemePair? {
                // Each \X segments from its own consuming start. Segment each suffix independently,
                // including find() retries inside the original cluster, rather than reusing whole-input
                // boundaries. These test inputs are small and fixed.
                oracle.setText(input.substring(start))
                oracle.first()
                val firstEnd = oracle.next()
                if (firstEnd == BreakIterator.DONE) {
                    return null
                }
                // The next \X also begins a fresh segmentation context.
                oracle.setText(input.substring(start + firstEnd))
                oracle.first()
                val secondLength = oracle.next()
                return if (secondLength == BreakIterator.DONE)
                    null
                else
                    GraphemePair(start, start + firstEnd, start + firstEnd + secondLength)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // \b in zero-width alternation
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("\\b in zero-width alternation")
    internal inner class WordBoundaryAlternation {
        @Test
        @DisplayName("(?:$|\\b) finds the word boundary before the first word character")
        fun dollarOrWordBoundary() {
            // Regression for issue #42: find() must choose the leftmost boundary, not the first
            // alternative that can match later.
            val m = Pattern.compile("(?:$|\\b)").matcher(" a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("(?:\\b|$) finds the word boundary before the first word character")
        fun wordBoundaryOrDollar() {
            val m = Pattern.compile("(?:\\b|$)").matcher(" a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("$|\\b on longer text finds the boundary at the start of the word")
        fun dollarOrWordBoundaryLongText() {
            val m = Pattern.compile("(?:$|\\b)").matcher("hello world")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
        }

        @Test
        @DisplayName("find() with $|\\b returns each boundary position once")
        fun dollarOrWordBoundaryFindAll() {
            val m = Pattern.compile("(?:$|\\b)").matcher(" a")
            val matches: MutableList<IntArray?> = ArrayList<IntArray?>()
            while (m.find()) {
                matches.add(intArrayOf(m.start(), m.end()))
            }

            Assertions.assertThat<IntArray?>(matches).hasSize(2)
            Assertions.assertThat(matches.get(0)).containsExactly(1, 1)
            Assertions.assertThat(matches.get(1)).containsExactly(2, 2)
        }
    }

    // ---------------------------------------------------------------------------
    // Existing boundary matchers — additional coverage
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("existing boundary matchers")
    internal class ExistingBoundaryMatchers {
        @Test
        @DisplayName("\\A matches only at start of text")
        fun beginText() {
            val p = Pattern.compile("\\Aabc")
            Assertions.assertThat(p.matcher("abc").find()).isTrue()
            Assertions.assertThat(p.matcher("xabc").find()).isFalse()
        }

        @Test
        @DisplayName("\\A with MULTILINE does not match at line start")
        fun beginTextMultiline() {
            val p = Pattern.compile("\\Aabc", org.safere.Pattern.MULTILINE)
            Assertions.assertThat(p.matcher("abc").find()).isTrue()
            Assertions.assertThat(p.matcher("xyz\nabc").find()).isFalse()
        }

        @Test
        @DisplayName("\\z matches only at absolute end of text")
        fun endText() {
            val p = Pattern.compile("abc\\z")
            Assertions.assertThat(p.matcher("abc").find()).isTrue()
            Assertions.assertThat(p.matcher("abc\n").find()).isFalse()
            Assertions.assertThat(p.matcher("abcx").find()).isFalse()
        }

        @Test
        @DisplayName("\\b matches at word/non-word transitions")
        fun wordBoundary() {
            val p = Pattern.compile("\\bcat\\b")
            Assertions.assertThat(p.matcher("the cat sat").find()).isTrue()
            Assertions.assertThat(p.matcher("concatenate").find()).isFalse()
        }

        @Test
        @DisplayName("\\B matches inside a word")
        fun nonWordBoundary() {
            val p = Pattern.compile("\\Bcat\\B")
            Assertions.assertThat(p.matcher("concatenate").find()).isTrue()
            Assertions.assertThat(p.matcher("the cat sat").find()).isFalse()
        }

        @Test
        @DisplayName("\\b at start of string")
        fun wordBoundaryAtStart() {
            val p = Pattern.compile("\\bword")
            Assertions.assertThat(p.matcher("word").find()).isTrue()
            Assertions.assertThat(p.matcher(" word").find()).isTrue()
        }

        @Test
        @DisplayName("\\b at end of string")
        fun wordBoundaryAtEnd() {
            val p = Pattern.compile("word\\b")
            Assertions.assertThat(p.matcher("word").find()).isTrue()
            Assertions.assertThat(p.matcher("word ").find()).isTrue()
        }

        @Test
        @DisabledForCrosscheck("SafeRE follows the documented ASCII word model instead of the JDK combining-mark detail")
        @DisplayName("\\b and \\B treat combining marks as non-word characters by default")
        fun asciiWordBoundariesDoNotAttachCombiningMarksToBaseCharacters() {
            val decomposedEAcute = "e\u0301"

            Assertions.assertThat<Int?>(findStarts("\\b", decomposedEAcute)).containsExactly(0, 1)
            Assertions.assertThat<Int?>(findStarts("\\B", decomposedEAcute)).containsExactly(2)
        }

        companion object {
            private fun findStarts(regex: String?, input: String?): MutableList<Int?> {
                val matcher = Pattern.compile(regex).matcher(input)
                val starts: MutableList<Int?> = ArrayList<Int?>()
                while (matcher.find()) {
                    starts.add(matcher.start())
                }
                return starts
            }
        }
    }
}
