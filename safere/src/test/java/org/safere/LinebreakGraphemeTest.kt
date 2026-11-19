// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.IntFunction
import java.util.regex.PatternSyntaxException

/**
 * Tests for `\R` (Unicode linebreak) and `\X` (extended grapheme cluster) support.
 * Validates behavior against JDK 21's [Pattern] specification.
 */
internal class LinebreakGraphemeTest {
    @Nested
    @DisplayName("\\R (Unicode linebreak)")
    internal inner class LinebreakTests {
        @Test
        @DisplayName("\\R matches LF (U+000A)")
        fun matchesLineFeed() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\n").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches CR (U+000D)")
        fun matchesCarriageReturn() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\r").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches CRLF as a single unit")
        fun matchesCrLf() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\r\n").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches vertical tab (U+000B)")
        fun matchesVerticalTab() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\u000B").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches form feed (U+000C)")
        fun matchesFormFeed() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\u000c").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches next line (U+0085)")
        fun matchesNextLine() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\u0085").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches line separator (U+2028)")
        fun matchesLineSeparator() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\u2028").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R matches paragraph separator (U+2029)")
        fun matchesParagraphSeparator() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("\u2029").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R does not match ordinary characters")
        fun doesNotMatchOrdinaryChars() {
            val p = Pattern.compile("\\R")
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
            Assertions.assertThat(p.matcher(" ").matches()).isFalse()
            Assertions.assertThat(p.matcher("\t").matches()).isFalse()
            Assertions.assertThat(p.matcher("1").matches()).isFalse()
        }

        @Test
        @DisplayName("\\R treats CRLF as one match, not two")
        fun crlfIsSingleMatch() {
            val p = Pattern.compile("\\R")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2)
            Assertions.assertThat(m.find()).isFalse() // no second match
        }

        @Test
        @DisplayName("\\R+ matches multiple linebreaks")
        fun plusMatchesMultiple() {
            val p = Pattern.compile("\\R+")
            Assertions.assertThat(p.matcher("\n\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("\r\n\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("\r\n").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R works in context: a\\Rb matches a<linebreak>b")
        fun worksInContext() {
            val p = Pattern.compile("a\\Rb")
            Assertions.assertThat(p.matcher("a\nb").matches()).isTrue()
            Assertions.assertThat(p.matcher("a\rb").matches()).isTrue()
            Assertions.assertThat(p.matcher("a\r\nb").matches()).isTrue()
            Assertions.assertThat(p.matcher("a\u2028b").matches()).isTrue()
            Assertions.assertThat(p.matcher("ab").matches()).isFalse()
        }

        @Test
        @DisplayName("\\R\\n matches CRLF (\\R takes \\r, \\n takes \\n)")
        fun linebreakFollowedByNewline() {
            val p = Pattern.compile("\\R\\n")
            Assertions.assertThat(p.matcher("\r\n").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R{2} matches two linebreak sequences")
        fun repeatedLinebreak() {
            val p = Pattern.compile("\\R{2}")
            Assertions.assertThat(p.matcher("\r\n\n").matches()).isTrue()
            Assertions.assertThat(p.matcher("\n\n").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R? matches optional linebreak")
        fun optionalLinebreak() {
            val p = Pattern.compile("a\\R?b")
            Assertions.assertThat(p.matcher("ab").matches()).isTrue()
            Assertions.assertThat(p.matcher("a\nb").matches()).isTrue()
            Assertions.assertThat(p.matcher("a\r\nb").matches()).isTrue()
        }

        @Test
        @DisplayName("\\R inside [...] is rejected")
        fun rejectedInsideCharClass() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[\\R]") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        @DisplayName("find() locates \\R in mixed text")
        fun findInMixedText() {
            val p = Pattern.compile("\\R")
            val m = p.matcher("hello\nworld\r\n!")
            val matches: MutableList<IntArray?> = ArrayList<IntArray?>()
            while (m.find()) {
                matches.add(intArrayOf(m.start(), m.end()))
            }
            Assertions.assertThat<IntArray?>(matches).hasSize(2)
            Assertions.assertThat(matches.get(0)).containsExactly(5, 6) // \n
            Assertions.assertThat(matches.get(1)).containsExactly(11, 13) // \r\n
        }

        @Test
        @DisplayName("\\R works with capture groups")
        fun captureGroup() {
            val p = Pattern.compile("(\\R)")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("\r\n")
        }

        @Test
        @DisplayName("split on \\R splits lines correctly")
        fun splitOnLinebreak() {
            val p = Pattern.compile("\\R")
            val parts = p.split("line1\nline2\r\nline3\rline4")
            Assertions.assertThat<String?>(parts).containsExactly("line1", "line2", "line3", "line4")
        }
    }

    @Nested
    @DisplayName("\\X (extended grapheme cluster)")
    internal class GraphemeClusterTests {
        @Test
        @DisplayName("\\X matches a single ASCII character")
        fun matchesSingleAscii() {
            val p = Pattern.compile("\\X")
            val m = p.matcher("a")
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        @DisplayName("\\X matches each character in ASCII text")
        fun matchesEachAsciiChar() {
            val p = Pattern.compile("\\X")
            val m = p.matcher("abc")
            var count = 0
            while (m.find()) {
                count++
            }
            Assertions.assertThat(count).isEqualTo(3)
        }

        @Test
        @DisplayName("\\X matches base character + combining marks as one cluster")
        fun matchesBaseWithCombiningMark() {
            // é = e (U+0065) + combining acute accent (U+0301)
            val grapheme = "e\u0301"
            val p = Pattern.compile("\\X")
            val m = p.matcher(grapheme)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(grapheme)
            Assertions.assertThat(m.group()!!.length).isEqualTo(2)
            Assertions.assertThat(m.find()).isFalse() // single grapheme, no more matches
        }

        @Test
        @DisplayName("\\X matches CRLF as a single grapheme cluster")
        fun matchesCrLf() {
            val p = Pattern.compile("\\X")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\r\n")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X matches a single newline")
        fun matchesSingleNewline() {
            val p = Pattern.compile("\\X")
            Assertions.assertThat(p.matcher("\n").matches()).isTrue()
        }

        @Test
        @DisplayName("\\X matches a standalone combining mark as one cluster")
        fun matchesStandaloneCombiningMark() {
            val p = Pattern.compile("\\X")
            val m = p.matcher("\u0301") // combining acute accent alone
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()!!.length).isEqualTo(1)
        }

        @Test
        @DisplayName("\\X groups leading combining marks before the next base character")
        fun groupsLeadingCombiningMarksBeforeNextBaseCharacter() {
            val text = "\u0301\u0301a"
            val m = Pattern.compile("\\X").matcher(text)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isZero()
            Assertions.assertThat(m.end()).isEqualTo(2)
            Assertions.assertThat(m.group()).isEqualTo("\u0301\u0301")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.end()).isEqualTo(3)
            Assertions.assertThat(m.group()).isEqualTo("a")

            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X keeps ZWJ with leading grapheme extenders")
        fun keepsZwjWithLeadingGraphemeExtenders() {
            assertTraceSameAsJdk("\\X", "\u0301\u200D", 0, 2)
            assertTraceSameAsJdk("\\X", "\u0301\u0301\u200D", 0, 3)
            assertTraceSameAsJdk("\\X", "\u0301\u200Da", 0, 3)
            assertTraceSameAsJdk("\\X", "a\u0301\u200D\uD83D\uDE00\u0300", 1, 5)
            assertTraceSameAsJdk("\\X\\X", "\u0301\u200Da", 0, 3)
            assertTraceSameAsJdk("\\X\\b{g}", "\u200D\uD83D\uDC69", 0, 3)
            assertTraceSameAsJdk("\\X\\b{g}", "\uDE00\u200D\uD83D\uDC69", 0, 4)
        }

        @Test
        @DisplayName("\\X repeated find respects region-local transparent bounds")
        fun repeatedFindRespectsRegionLocalTransparentBounds() {
            assertTransparentTraceSameAsJdk("\\X", "aa\u0300", 1, 2)
            assertTransparentTraceSameAsJdk("\\X", "\uD83Da\u0301", 1, 2)
            assertTransparentTraceSameAsJdk("\\X", "\uD83Dab\u0301", 1, 3)
            assertTransparentTraceSameAsJdk("\\X", "\uD83D\uDE00", 1, 1)
            assertTransparentTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5)
            assertTransparentTraceSameAsJdk("\\X", "\uD83D\uDE00\u0301\uD83D\uDC69", 1, 5)
            assertTransparentTraceSameAsJdk("^\\X\\X", "\uD83D\uDE00\u200D\uD83D\uDC69", 1, 5)
            assertTransparentTraceSameAsJdk("^\\X\\X", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 5)
        }

        @Test
        @DisplayName("\\X treats controls as standalone grapheme clusters")
        fun treatsControlsAsStandaloneGraphemeClusters() {
            assertTraceSameAsJdk("\\X", "\r\u0301", 0, 2)
            assertTraceSameAsJdk("\\X", "\n\u0301", 0, 2)
            assertTraceSameAsJdk("\\X", "\u0000\u0301", 0, 2)
            assertTraceSameAsJdk("\\X", "\u0600\r", 0, 2)
            assertTraceSameAsJdk("\\X", "\u0600\n", 0, 2)
            assertTraceSameAsJdk("\\X", "\u0600\uDE00", 0, 2)
            assertTraceSameAsJdk("\\X", "\uD83D\u0301", 0, 2)
            assertTraceSameAsJdk("\\X", "\uD83D\u200D", 0, 2)
        }

        @Test
        @DisplayName("\\X keeps trailing extenders with non-base cluster forms")
        fun keepsTrailingExtendersWithNonBaseClusterForms() {
            assertTraceSameAsJdk("\\X", "\uD83C\uDDFA\uD83C\uDDFA\u0301", 0, 5)
            assertTraceSameAsJdk("\\X", "\uD83C\uDDFA\uD83C\uDDFA\u200D", 0, 5)
            assertTraceSameAsJdk("\\X", "\uD83C\uDDFA\uD83C\uDDFA\uD83C\uDFFD", 0, 6)
            assertTraceSameAsJdk("\\X", "\u1100\u1161\u0301", 0, 3)
            assertTraceSameAsJdk("\\X", "\uAC00\u11A8\u0301", 0, 3)
        }

        @Test
        @DisplayName("\\X keeps prepend characters with following non-base cluster forms")
        fun keepsPrependCharactersWithFollowingNonBaseClusterForms() {
            assertTraceSameAsJdk("\\X", "\u0600\uD83C\uDDFA\uD83C\uDDFA", 0, 5)
            assertTraceSameAsJdk("\\X", "\u0600\u1100\u1161", 0, 3)
            assertTraceSameAsJdk("\\X", "\u0600\uAC00\u11A8", 0, 3)
            assertTraceSameAsJdk("\\X", "\u0600\u0301", 0, 2)
        }

        @Test
        @DisplayName("\\X keeps trailing extenders inside valid pictographic sequences")
        fun keepsTrailingExtendersInsidePictographicSequences() {
            assertTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\uD83D\uDC69\u200D", 0, 6)
            assertTraceSameAsJdk("\\X", "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\u0301", 0, 7)
            assertTraceSameAsJdk(
                "\\X\\X", "#\uDE00\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC69$", 1, 10
            )
        }

        @Test
        @DisplayName("an internal grapheme boundary cannot split CRLF")
        @DisabledForCrosscheck("GB3 forbids an interior CRLF boundary, including after a consumed prefix")
        fun internalGraphemeBoundaryCannotSplitCrLf() {
            val matcher = Pattern.compile("\\r\\b{g}\\n").matcher("#\r\n\r\n$").region(1, 5)
            Assertions.assertThat(matcher.lookingAt()).isFalse()
            Assertions.assertThat(matcher.matches()).isFalse()
            Assertions.assertThat(matcher.find()).isFalse()
        }

        @Test
        @DisplayName("consecutive \\X atoms do not split leading extenders or Hangul clusters")
        fun consecutiveAtomsDoNotSplitLeadingExtendersOrHangulClusters() {
            assertTraceSameAsJdk("\\X\\X", "\u200D\u0301", 0, 2)
            assertTraceSameAsJdk("\\X\\X", "\u200D\u200D", 0, 2)
            assertTraceSameAsJdk("\\X\\X", "\u1100\uAC01", 0, 2)
        }

        @Test
        @DisplayName("\\X treats standalone low surrogates as separate from following extenders")
        @DisabledForCrosscheck("Opaque region behavior uses the independent substring contract")
        fun treatsStandaloneLowSurrogatesAsSeparateFromFollowingExtenders() {
            assertTraceSameAsJdk("\\X", "\uDE00\u0301", 0, 2)
            OpaqueRegionChecks.assertMatchesSubstring("\\b{g}", "\uD83D\u0600\uD83D\uDE00", 1, 3)
            OpaqueRegionChecks.assertMatchesSubstring("\\b{g}", "#\uDE00\u200D\u0301$", 1, 4)
            OpaqueRegionChecks.assertMatchesSubstring("\\b{g}", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 4)
            OpaqueRegionChecks.assertMatchesSubstring("(\\b{g})", "\uD83D\uDE00\u200D\uD83D\uDE00", 1, 4)
            OpaqueRegionChecks.assertMatchesSubstring(
                "\\X\\b{g}", "\uD83D\uDE00\u200D\u0301\uDE00", 1, 4
            )
            OpaqueRegionChecks.assertMatchesSubstring(
                "\\X\\b{g}", "\uD83D\uDE00\u200D\uD83C\uDDFA", 1, 5
            )
            OpaqueRegionChecks.assertMatchesSubstring(
                "\\X\\b{g}", "\uD83D\uDE00\u0301\u200D\uD83C\uDDFA", 1, 6
            )
            OpaqueRegionChecks.assertMatchesSubstring(
                "\\X\\b{g}", "\uD83D\uDE00\u200D\u0301\uD83C\uDDFA", 1, 6
            )
            OpaqueRegionChecks.assertMatchesSubstring(
                "\\X\\b{g}", "\uD83D\uDE00\uD83C\uDFFD\uD83D\uDE00", 1, 6
            )
            assertTransparentTraceSameAsJdk("\\X\\b{g}", "#\u0301$", 1, 2)
            assertTransparentTraceSameAsJdk(
                "\\X\\b{g}", "\uD83D\u0000\u200D\u1100\u0600\uD83D\uDC69\u0903", 1, 7
            )
            assertTraceSameAsJdk("\\X\\X", "\r\uDE00\u0301", 0, 3)
            assertTraceSameAsJdk("\\X\\X", "\uDE00\uDE00\u0301", 0, 3)
            OpaqueRegionChecks.assertMatchesSubstring(
                "\\X\\X", "#\uDE00\uD83D\uDC69\u200D\uD83D\uDC69\u0301$", 1, 8
            )
            OpaqueRegionChecks.assertMatchesSubstring("^\\X\\X$", "\uD83D\uDE00\r\r\uDE00", 1, 4)
            OpaqueRegionChecks.assertMatchesSubstring("^\\X\\X$", "\uD83D\uDE00a\u0903\r\n\uDE00", 1, 6)
        }

        @Test
        @DisplayName("\\X repeated find keeps valid split-surrogate region-local candidates")
        fun repeatedFindKeepsValidSplitSurrogateRegionLocalCandidates() {
            assertTraceSameAsJdk("\\X", "\uD83D\uDE00\u200D\u0301", 1, 3)
            assertTraceSameAsJdk("\\X", "\uD83D\uDE00a\u0301", 1, 3)
        }

        @Test
        @DisplayName("consecutive \\X atoms preserve match bounds after leading combining marks")
        fun consecutiveAtomsPreserveBoundsAfterLeadingCombiningMarks() {
            val text = "\u0301".repeat(44) + "a".repeat(8)
            val m = Pattern.compile("^\\^?\\X\\X").matcher(text)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isZero()
            Assertions.assertThat(m.end()).isEqualTo(45)
            Assertions.assertThat(m.group()).isEqualTo("\u0301".repeat(44) + "a")
        }

        @Test
        @DisplayName("\\X matches multiple combining marks with a base character")
        fun matchesMultipleCombiningMarks() {
            // a + combining tilde (U+0303) + combining acute accent (U+0301)
            val cluster = "a\u0303\u0301"
            val p = Pattern.compile("\\X")
            val m = p.matcher(cluster)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(cluster)
            Assertions.assertThat(m.group()!!.length).isEqualTo(3)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X correctly segments mixed grapheme clusters")
        fun segmentsMixedClusters() {
            // "é" (e + combining accent) followed by "â" (a + combining circumflex)
            val text = "e\u0301a\u0302"
            val p = Pattern.compile("\\X")
            val m = p.matcher(text)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("e\u0301")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("a\u0302")

            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X+ matches entire string of grapheme clusters")
        fun plusMatchesMultipleClusters() {
            val p = Pattern.compile("\\X+")
            Assertions.assertThat(p.matcher("hello").matches()).isTrue()
            Assertions.assertThat(p.matcher("e\u0301").matches()).isTrue()
            Assertions.assertThat(p.matcher("e\u0301a\u0302").matches()).isTrue()
        }

        @Test
        @DisplayName("greedy \\X+ find consumes all adjacent clusters")
        fun greedyPlusFindConsumesAllAdjacentClusters() {
            val ascii = Pattern.compile("\\X+").matcher("ab")
            Assertions.assertThat(ascii.find()).isTrue()
            Assertions.assertThat(ascii.start()).isZero()
            Assertions.assertThat(ascii.end()).isEqualTo(2)
            Assertions.assertThat(ascii.find()).isFalse()

            val combining = Pattern.compile("\\X+").matcher("a\u0301b")
            Assertions.assertThat(combining.find()).isTrue()
            Assertions.assertThat(combining.start()).isZero()
            Assertions.assertThat(combining.end()).isEqualTo(3)
            Assertions.assertThat(combining.group()).isEqualTo("a\u0301b")
            Assertions.assertThat(combining.find()).isFalse()
        }

        @Test
        @DisplayName("ordered alternation preserves earlier branches before \\X")
        fun orderedAlternationPreservesEarlierBranchesBeforeGraphemeCluster() {
            assertTraceSameAsJdk("a|\\X", "a\u0301b", 0, 3)
            assertFindGroupsSameAsJdk("(a)|(\\X)", "a\u0301b", 0, 3)
        }

        @Test
        @DisabledForCrosscheck("SafeRE's region-local grapheme model intentionally diverges from selected JDK traces")
        @DisplayName("repeated find resumes after leading grapheme-boundary clusters")
        fun repeatedFindResumesAfterLeadingGraphemeBoundaryClusters() {
            assertSafeReFindBounds("\\b{g}\\X", "ab", 0, 2, mutableListOf<String>("0-1", "1-2"))
            assertSafeReFindBounds("\\b{g}\\X", "a\u0301b", 0, 3, mutableListOf<String>("0-2", "2-3"))
            assertSafeReFindBounds("(\\b{g})(\\X)", "ab", 0, 2, mutableListOf<String>("0-1", "1-2"))
        }

        @Test
        @DisplayName("\\X inside [...] is rejected")
        fun rejectedInsideCharClass() {
            Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("[\\X]") }).isInstanceOf(
                PatternSyntaxException::class.java
            )
        }

        @Test
        @DisplayName("\\X works with quantifiers")
        fun worksWithQuantifiers() {
            // Two grapheme clusters
            val p = Pattern.compile("\\X{2}")
            Assertions.assertThat(p.matcher("ab").matches()).isTrue()
            Assertions.assertThat(p.matcher("e\u0301a\u0302").matches()).isTrue()
            Assertions.assertThat(p.matcher("e\u0301").matches()).isFalse()
            Assertions.assertThat(p.matcher("a").matches()).isFalse()
        }

        @Test
        @DisplayName("unanchored \\X chains can start at UTF-16 low-surrogate offsets")
        fun unanchoredChainsCanStartAtLowSurrogateOffsets() {
            assertFirstFindBounds("\\X\\X", "\uD83C\uDDFA\uD83C\uDDF8", 1, 4)
            assertFirstFindBounds("\\X{2}", "\uD83C\uDDFA\uD83C\uDDF8", 1, 4)
            assertFirstFindBounds("(?:\\X)(?:\\X)", "\uD83D\uDC4D\uD83C\uDFFD", 1, 4)
            assertFirstFindBounds("(\\X)(\\X)", "\uD83D\uDC69\u200D\uD83D\uDCBB", 1, 3)
            assertTraceSameAsJdk("\\X\\X\\X", "a\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6)
            assertTraceSameAsJdk("\\X{3}", "a\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6)
            assertTraceSameAsJdk("(?:\\X)(?:\\X)(?:\\X)", "a\uD83D\uDC69\u200D\uD83D\uDC69", 0, 6)
        }

        @Test
        @DisplayName("end-anchored \\X searches keep correct long-input bounds")
        fun endAnchoredGraphemeSearchesKeepCorrectLongInputBounds() {
            assertFirstFindBounds("\\Xz$", "a".repeat(2000) + "z", 1999, 2001)
        }

        @Test
        @DisabledForCrosscheck("allocation scaling must measure SafeRE directly, without crosscheck shadow bookkeeping")
        @DisplayName("unanchored multi-boundary \\X search allocation does not scale with input tail")
        fun unanchoredMultiBoundarySearchDoesNotAllocatePerInputPosition() {
            val allocationTracker = allocationTracker()
            val threadId = Thread.currentThread().threadId()

            for (regex in mutableListOf<String>("\\X\\X", "\\X{2}", "(?:\\X)(?:\\X)")) {
                val pattern = Pattern.compile(regex)

                assertImmediateTwoClusterMatch(pattern, "ab" + "c".repeat(1000))
                assertImmediateTwoClusterMatch(pattern, "ab" + "c".repeat(10000))

                val shortAllocated =
                    allocatedForImmediateTwoClusterFind(
                        allocationTracker, threadId, pattern, "ab" + "c".repeat(10000)
                    )
                val longAllocated =
                    allocatedForImmediateTwoClusterFind(
                        allocationTracker, threadId, pattern, "ab" + "c".repeat(100000)
                    )

                Assertions.assertThat(longAllocated - shortAllocated)
                    .`as`("extra allocated bytes when input tail grows for /%s/", regex)
                    .isLessThan(64000L)
            }
        }

        @Test
        @DisplayName("consecutive \\X atoms do not split a single grapheme cluster")
        fun consecutiveAtomsDoNotSplitSingleCluster() {
            val p = Pattern.compile("^\\X\\X$")

            Assertions.assertThat(p.matcher("e\u0301").matches()).isFalse()
            Assertions.assertThat(p.matcher("\uD83D\uDC4D\uD83C\uDFFD").matches()).isFalse()
            Assertions.assertThat(p.matcher("\uD83C\uDDFA\uD83C\uDDF8").matches()).isFalse()
            Assertions.assertThat(p.matcher("\u0600a").matches()).isFalse()
            Assertions.assertThat(p.matcher("\u1100\u1161").matches()).isFalse()
            Assertions.assertThat(p.matcher("e\u0301a").matches()).isTrue()
        }

        @Test
        @DisplayName("\\X matches control characters")
        fun matchesControlChars() {
            val p = Pattern.compile("\\X")
            Assertions.assertThat(p.matcher("\t").matches()).isTrue()
            Assertions.assertThat(p.matcher("\u0000").matches()).isTrue()
        }

        @Test
        @DisplayName("\\X works with capture groups")
        fun captureGroup() {
            val p = Pattern.compile("(\\X)")
            val m = p.matcher("e\u0301")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("e\u0301")
        }

        @Test
        @DisplayName("\\X matches supplementary code points")
        fun matchesSupplementaryCodePoint() {
            // U+1F600 GRINNING FACE (surrogate pair in Java)
            val emoji = "\uD83D\uDE00"
            val p = Pattern.compile("\\X")
            val m = p.matcher(emoji)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(emoji)
        }

        @Test
        @DisplayName("\\X matches a regional indicator flag as one cluster")
        fun matchesRegionalIndicatorFlagAsOneCluster() {
            val flag = "\uD83C\uDDFA\uD83C\uDDF8" // US flag
            val m = Pattern.compile("\\X").matcher(flag)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(flag)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X segments regional indicators in pairs")
        fun segmentsRegionalIndicatorsInPairs() {
            val regionalIndicators = "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDE8" // U, S, C
            val m = Pattern.compile("\\X").matcher(regionalIndicators)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\uD83C\uDDFA\uD83C\uDDF8")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\uD83C\uDDE8")

            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("opaque regions reset regional-indicator grapheme parity")
        fun opaqueRegionsResetRegionalIndicatorGraphemeParity() {
            val regionalIndicators = "\uD83C\uDDE6".repeat(3)

            assertTraceSameAsJdk("\\X", regionalIndicators, 2, 6)
            assertTraceSameAsJdk("\\b{g}", regionalIndicators, 2, 6)
            assertTraceSameAsJdk("\\b{g}\\X", regionalIndicators, 2, 6)
            assertTraceSameAsJdk("\\b{g}\\X\\b{g}", regionalIndicators, 2, 6)
        }

        @Test
        @DisplayName("transparent boundaries keep full-input regional-indicator parity")
        fun transparentBoundariesKeepFullInputRegionalIndicatorParity() {
            val regionalIndicators = "\uD83C\uDDE6".repeat(3)

            assertTransparentTraceSameAsJdk("\\b{g}", regionalIndicators, 2, 6)
            assertTransparentTraceSameAsJdk("\\b{g}\\X", regionalIndicators, 2, 6)
        }

        @Test
        @DisplayName("\\X matches emoji modifier sequences as one cluster")
        fun matchesEmojiModifierSequenceAsOneCluster() {
            val thumbsUpMediumSkinTone = "\uD83D\uDC4D\uD83C\uDFFD"
            val m = Pattern.compile("\\X").matcher(thumbsUpMediumSkinTone)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(thumbsUpMediumSkinTone)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X matches ZWJ emoji sequences as one cluster")
        fun matchesZwjEmojiSequenceAsOneCluster() {
            val technologist = "\uD83D\uDC69\u200D\uD83D\uDCBB"
            val m = Pattern.compile("\\X").matcher(technologist)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(technologist)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X matches ZWJ emoji sequences with modifiers as one cluster")
        fun matchesZwjEmojiSequenceWithModifierAsOneCluster() {
            val technologist = "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB"
            val m = Pattern.compile("\\X").matcher(technologist)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(technologist)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X keeps ZWJ attached to the preceding cluster")
        fun keepsZwjAttachedToPrecedingCluster() {
            val joined = "a\u200D"
            val m = Pattern.compile("\\X").matcher(joined)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(joined)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X matches Hangul jamo sequences as one cluster")
        fun matchesHangulJamoSequenceAsOneCluster() {
            val hangul = "\u1100\u1161"
            val m = Pattern.compile("\\X").matcher(hangul)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(hangul)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X keeps trailing Hangul jongseong with a syllable")
        fun keepsTrailingHangulJongseongWithSyllable() {
            val hangul = "\uAC00\u11A8"
            val m = Pattern.compile("\\X").matcher(hangul)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(hangul)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("\\X keeps prepend characters with the following cluster")
        fun keepsPrependCharactersWithFollowingCluster() {
            val cluster = "\u0600a"
            val m = Pattern.compile("\\X").matcher(cluster)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(cluster)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find() locates grapheme clusters in mixed text")
        fun findInMixedText() {
            // "He\u0301llo" = H + é (2 code points) + l + l + o → 4 grapheme clusters
            val text = "He\u0301llo"
            val p = Pattern.compile("\\X")
            val clusters: MutableList<String> = ArrayList<String>()
            val m = p.matcher(text)
            while (m.find()) {
                clusters.add(m.group()!!)
            }
            Assertions.assertThat<String?>(clusters).containsExactly("H", "e\u0301", "l", "l", "o")
        }

        @JvmRecord
        private data class AllocationTracker(val threadBean: Any?, val allocatedBytesMethod: Method?) {
            fun allocatedBytes(threadId: Long): Long {
                try {
                    return (allocatedBytesMethod!!.invoke(threadBean, threadId) as Long?)!!
                } catch (e: IllegalAccessException) {
                    throw AssertionError(e)
                } catch (e: InvocationTargetException) {
                    throw AssertionError(e.cause)
                }
            }
        }

        companion object {
            private fun assertFirstFindBounds(regex: String?, input: String?, start: Int, end: Int) {
                val matcher = Pattern.compile(regex).matcher(input)
                Assertions.assertThat(matcher.find()).`as`("first find() for /%s/ on %s", regex, input).isTrue()
                Assertions.assertThat(matcher.start())
                    .`as`("first find() start for /%s/ on %s", regex, input)
                    .isEqualTo(start)
                Assertions.assertThat(matcher.end()).`as`("first find() end for /%s/ on %s", regex, input)
                    .isEqualTo(end)
                Assertions.assertThat(matcher.find()).`as`("second find() for /%s/ on %s", regex, input).isFalse()
            }

            private fun assertFindGroupsSameAsJdk(regex: String, input: String, start: Int, end: Int) {
                val jdkMatcher =
                    java.util.regex.Pattern.compile(regex).matcher(input).region(start, end)
                val safeMatcher = Pattern.compile(regex).matcher(input).region(start, end)

                Assertions.assertThat(safeMatcher.find()).`as`("SafeRE find() should match")
                    .isEqualTo(jdkMatcher.find())
                Assertions.assertThat(safeMatcher.groupCount()).isEqualTo(jdkMatcher.groupCount())
                for (group in 0..jdkMatcher.groupCount()) {
                    Assertions.assertThat(safeMatcher.group(group))
                        .`as`("group %s for /%s/ on %s region [%s,%s]", group, regex, input, start, end)
                        .isEqualTo(jdkMatcher.group(group))
                }
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
                Assertions.assertThat(safeMatcher.lookingAt())
                    .`as`("lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .isEqualTo(jdkMatcher.lookingAt())

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
                Assertions.assertThat(safeMatcher.lookingAt())
                    .`as`("transparent lookingAt() for /%s/ on %s region [%s,%s]", regex, input, start, end)
                    .isEqualTo(jdkMatcher.lookingAt())

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

            private fun allocatedForImmediateTwoClusterFind(
                allocationTracker: AllocationTracker, threadId: Long, pattern: Pattern, text: String?
            ): Long {
                val before = allocationTracker.allocatedBytes(threadId)
                assertImmediateTwoClusterMatch(pattern, text)
                return allocationTracker.allocatedBytes(threadId) - before
            }

            private fun assertImmediateTwoClusterMatch(pattern: Pattern, text: String?) {
                val matcher = pattern.matcher(text)
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isZero()
                Assertions.assertThat(matcher.end()).isEqualTo(2)
            }

            private fun allocationTracker(): AllocationTracker {
                try {
                    val managementFactoryClass = Class.forName("java.lang.management.ManagementFactory")
                    val threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null)
                    val allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean")
                    Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean))

                    val supportedMethod = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported")
                    val enabledMethod = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled")
                    val setEnabledMethod =
                        allocationBeanClass.getMethod(
                            "setThreadAllocatedMemoryEnabled",
                            Boolean::class.javaPrimitiveType
                        )
                    val allocatedBytesMethod =
                        allocationBeanClass.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)

                    Assumptions.assumeTrue((supportedMethod.invoke(threadBean) as Boolean?)!!)
                    if ((enabledMethod.invoke(threadBean) as Boolean?) != true) {
                        setEnabledMethod.invoke(threadBean, true)
                    }
                    return AllocationTracker(threadBean, allocatedBytesMethod)
                } catch (e: ReflectiveOperationException) {
                    Assumptions.abort<Any>("thread allocation tracking is unavailable: " + e)
                    throw AssertionError("unreachable")
                }
            }
        }
    }
}
