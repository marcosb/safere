// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.List
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Tests for [Matcher].  */
internal class MatcherTest {
    @Test
    fun capturesInImpossibleAlternativesRemainUnmatched() {
        for (alternatives in mutableListOf<String?>("(a)|(b)", "(a)|(b)|(c)|(d)|(e)", "((a)|(b))", "(é)|(😀)")) {
            for (impossible in mutableListOf<String?>("[^\\s\\S]", "[a&&b]")) {
                val deadBranch = "(?:" + alternatives + ")" + impossible
                for (regex in List.of<String?>(deadBranch + "|x|xx", "x|xx|" + deadBranch)) {
                    val matcher = Pattern.compile(regex).matcher("xxy")
                    for (start in 0..1) {
                        Assertions.assertThat(matcher.find()).`as`(regex).isTrue()
                        Assertions.assertThat(matcher.group()).isEqualTo("x")
                        Assertions.assertThat(matcher.start()).isEqualTo(start)
                        for (group in 1..matcher.groupCount()) {
                            Assertions.assertThat(matcher.group(group)).`as`("/%s/ group %s", regex, group).isNull()
                            Assertions.assertThat(matcher.start(group)).isEqualTo(-1)
                            Assertions.assertThat(matcher.end(group)).isEqualTo(-1)
                        }
                    }
                    Assertions.assertThat(matcher.find()).isFalse()
                }
            }
        }
    }

    @Nested
    @DisplayName("matches() and lookingAt()")
    internal inner class MatchesTests {
        @Test
        @DisplayName("matches() returns true for a full match")
        fun matchesSuccess() {
            val p = Pattern.compile("abc")
            val m = p.matcher("abc")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(3)
        }

        @Test
        @DisplayName("matches() returns false when pattern does not match entire input")
        fun matchesFailure() {
            val p = Pattern.compile("abc")
            val m = p.matcher("xabcx")
            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        @DisplayName("matches() with capturing groups")
        fun matchesWithGroups() {
            val p = Pattern.compile("(a+)(b+)")
            val m = p.matcher("aaabb")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("aaabb")
            Assertions.assertThat(m.group(1)).isEqualTo("aaa")
            Assertions.assertThat(m.group(2)).isEqualTo("bb")
        }

        @Test
        @DisplayName("lookingAt() matches at start of input")
        fun lookingAtSuccess() {
            val p = Pattern.compile("\\d+")
            val m = p.matcher("123abc")
            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(3)
        }

        @Test
        @DisplayName("lookingAt() fails when no match at start")
        fun lookingAtFailure() {
            val p = Pattern.compile("\\d+")
            val m = p.matcher("abc123")
            Assertions.assertThat(m.lookingAt()).isFalse()
        }

        @Test
        @DisplayName("lookingAt() does not require full-string match")
        fun lookingAtPartialMatch() {
            val p = Pattern.compile("abc")
            val m = p.matcher("abcdef")
            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
        }

        @Test
        @DisplayName("lookingAt() preserves alternation and quantifier priority")
        fun lookingAtPreservesAlternationAndQuantifierPriority() {
            val cases = arrayOf<Array<String?>?>(
                arrayOf<String?>("a|aa", "aa", "a"),
                arrayOf<String?>("a|ab", "ab", "a"),
                arrayOf<String?>("a+?", "aaa", "a"),
                arrayOf<String?>("a{1,3}?", "aaa", "a"),
                arrayOf<String?>("a??", "a", ""),
                arrayOf<String?>("a+?b?", "aaab", "a"),
                arrayOf<String?>("(?:a|ab)c?", "abc", "a"),
                arrayOf<String?>("a??b?", "ab", ""),
                arrayOf<String?>("a+", "aaa", "aaa")
            )

            for (testCase in cases) {
                val matcher = Pattern.compile(testCase!![0]).matcher(testCase[1])
                Assertions.assertThat(matcher.lookingAt()).`as`("pattern %s", testCase[0]).isTrue()
                Assertions.assertThat(matcher.group()).`as`("pattern %s", testCase[0]).isEqualTo(testCase[2])
            }
        }

        @Test
        @DisplayName("accelerated ASCII match rejects intervening non-ASCII input")
        fun acceleratedAsciiMatchRejectsInterveningNonAsciiInput() {
            val pattern = Pattern.compile("[\\x00-\\x21\\x23-\\x7F]*\"")

            Assertions.assertThat(pattern.matcher("a".repeat(20) + "é\"").matches()).isFalse()
            Assertions.assertThat(pattern.matcher("😀" + "a".repeat(20) + "\"").matches()).isFalse()
        }

        @Test
        @DisplayName("discrete multi-offset shifted start acceleration matches correctly")
        fun discreteMultiOffsetShiftedStartAcceleration() {
            val p = Pattern.compile("(^|[^#])(#!customTag)")
            val m = p.matcher("###!customTag #!customTag\n#!customTag")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(" #!customTag")
            Assertions.assertThat(m.start()).isEqualTo(13)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\n#!customTag")
            Assertions.assertThat(m.start()).isEqualTo(25)

            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("bounded range shifted start acceleration matches correctly")
        fun boundedRangeShiftedStartAcceleration() {
            val p = Pattern.compile("\\s{0,8}renderElement\\(")
            val m = p.matcher("ignore text    renderElement(arg)")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("    renderElement(")
            Assertions.assertThat(m.start()).isEqualTo(11)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("multi-offset acceleration preserves starts belonging to later literals")
        fun multiOffsetAccelerationPreservesEarlierStartFromLaterLiteral() {
            val matcher = Pattern.compile("(aq|b[a-z]{9})z").matcher("bxxaxzxxxxz")

            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.group()).isEqualTo("bxxaxzxxxxz")
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isEqualTo(11)
        }

        @Test
        @DisplayName("lookingAt() respects lazy quantifier priority")
        fun lookingAtRespectsLazyQuantifierPriority() {
            val m = Pattern.compile("(a+?)").matcher("aaa")

            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("a")
            Assertions.assertThat(m.group(1)).isEqualTo("a")
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("lookingAt() respects empty alternative priority")
        fun lookingAtRespectsEmptyAlternativePriority() {
            val m = Pattern.compile("(|a)").matcher("a")

            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group()).isEmpty()
            Assertions.assertThat(m.group(1)).isEmpty()
            Assertions.assertThat(m.end()).isEqualTo(0)
        }

        @Test
        @DisplayName("matches() fast-rejects when required whitespace is absent")
        fun matchesFastRejectsWhenRequiredWhitespaceIsAbsent() {
            val m = Pattern.compile(".*\\s+.*").matcher("44241504-44f6-4d2a-bdcb-1bf7fd927ba9")

            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        @DisplayName("matches() required-class precheck preserves positive cases")
        fun matchesRequiredClassPrecheckPreservesPositiveCases() {
            val p = Pattern.compile(".*\\s+.*")

            for (input in arrayOf<String>("a b", "a\nb c", "a\n \nc", "a\n\nc")) {
                Assertions.assertThat(p.matcher(input).matches())
                    .`as`("SafeRE and JDK should agree on %s", input.replace("\n", "\\n"))
                    .isEqualTo(Pattern.compile(".*\\s+.*").matcher(input).matches())
            }
        }

        @Test
        @DisplayName("matches() with alternation and non-participating group")
        fun matchesAlternation() {
            val p = org.safere.Pattern.compile("(a)|(b)")
            val m = p.matcher("a")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("a")
            Assertions.assertThat(m.group(2)).isNull()
        }

        @Test
        @DisplayName("alternation with matches() picks the correct branch")
        fun alternationMatches() {
            val p = org.safere.Pattern.compile("(a)|(b)|(c)")
            val m = p.matcher("b")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isNull()
            Assertions.assertThat(m.group(2)).isEqualTo("b")
            Assertions.assertThat(m.group(3)).isNull()
            Assertions.assertThat(m.start(1)).isEqualTo(-1)
            Assertions.assertThat(m.start(2)).isEqualTo(0)
            Assertions.assertThat(m.start(3)).isEqualTo(-1)
        }

        @Test
        @DisplayName("matches() updates match information for group access")
        fun matchesUpdatesMatchInformation() {
            val p = org.safere.Pattern.compile("(\\d+)(\\w+)")
            val m = p.matcher("123abc")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("123abc")
            Assertions.assertThat(m.group(1)).isEqualTo("123")
            Assertions.assertThat(m.group(2)).isEqualTo("abc")
            Assertions.assertThat(m.start(1)).isEqualTo(0)
            Assertions.assertThat(m.end(1)).isEqualTo(3)
            Assertions.assertThat(m.start(2)).isEqualTo(3)
            Assertions.assertThat(m.end(2)).isEqualTo(6)
        }

        @Test
        @DisabledForCrosscheck("java.util.regex backtracking makes this a SafeRE linear-time check")
        @DisplayName("group access stays stack-safe for large repeated captures")
        fun groupAccessWithLargeRepeatedCapturesStaysStackSafe() {
            assertCompletesWithinPerformanceTimeout(
                Runnable {
                    val m = org.safere.Pattern.compile("(a)*").matcher("a".repeat(20000))
                    Assertions.assertThat(m.matches()).isTrue()
                    Assertions.assertThat(m.group(1)).isEqualTo("a")
                })
        }

        @Test
        @DisplayName("find() falls back when OnePass analysis exceeds its memory budget")
        fun findFallsBackWhenOnePassAnalysisExceedsMemoryBudget() {
            val regex =
                ("SELECT"
                        + " REGE|||~\\u06ec?<?|||GEXP_REPLACe(^\"\\uffff\\uffff\\uffff\\uffff"
                        + "\\uffff\\uffff\\uffff\\uffff"
                        + " \\u00bf\\u7000\\u0400\\u5000\\u07b7@\\u02aa \\X@?` \\u0296"
                        + " \\ua03d,\\u0106mv &.\\u017c&.?\t\t?\t\\uffff\\ufff1end1 68  ){680}"
                        + "$ .1")
            val input = ",69\u00c3"

            val expected = Pattern.compile(regex).matcher(input).find()

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).find()).isEqualTo(expected)
        }

        @Test
        @DisplayName("group access after lookingAt() works correctly")
        fun lookingAtUpdatesGroupInfo() {
            val p = org.safere.Pattern.compile("(\\d+)(\\w+)")
            val m = p.matcher("123abc!!!")
            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("123abc")
            Assertions.assertThat(m.group(1)).isEqualTo("123")
            Assertions.assertThat(m.group(2)).isEqualTo("abc")
        }

        @ParameterizedTest
        @ValueSource(ints = [1, 2, 3, 4])
        @DisplayName("captures in repeated dot-star bodies match JDK semantics")
        fun matchesWithRepeatedDotStarCapturesMatchJdkSemantics(captures: Int) {
            val pattern: String = repeatedDotStarCapturePattern(5, captures)
            val input: String = repeatedDotStarCaptureInput(5, captures)
            val m = org.safere.Pattern.compile(pattern).matcher(input)
            val jdk = Pattern.compile(pattern).matcher(input)

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(jdk.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo(jdk.group(1))
            for (i in 2..captures + 1) {
                Assertions.assertThat(m.group(i)).isEqualTo(jdk.group(i))
            }
        }

        @Test
        @DisabledForCrosscheck("java.util.regex backtracks on this SafeRE linear-time stress case")
        @DisplayName("matches() completes for repeated dot-star bodies with multiple captures")
        fun matchesWithRepeatedDotStarBodiesAndMultipleCaptures() {
            val pattern: String = repeatedDotStarCapturePattern(500, 3)
            val input: String = repeatedDotStarCaptureInput(500, 3)

            assertCompletesWithinPerformanceTimeout(
                Runnable {
                    val m = org.safere.Pattern.compile(pattern).matcher(input)
                    Assertions.assertThat(m.matches()).isTrue()
                    Assertions.assertThat(m.group(2)).endsWith("A")
                    Assertions.assertThat(m.group(3)).endsWith("B")
                    Assertions.assertThat(m.group(4)).endsWith("C")
                })
        }
    }

    @Nested
    @DisplayName("find()")
    internal inner class FindTests {
        @Test
        @DisplayName("find() locates a single match in the input")
        fun findSingle() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123def")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.end()).isEqualTo(6)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find() locates multiple successive matches")
        fun findMultiple() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b22c333")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("22")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("333")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find(int) resets region before searching")
        fun findStartResetsRegion() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123def456")
            m.region(3, 6) // "123"

            Assertions.assertThat(m.find(6)).isTrue()
            Assertions.assertThat(m.regionStart()).isEqualTo(0)
            Assertions.assertThat(m.regionEnd()).isEqualTo(12)
            Assertions.assertThat(m.group()).isEqualTo("456")
            Assertions.assertThat(m.start()).isEqualTo(9)
            Assertions.assertThat(m.end()).isEqualTo(12)
        }

        @Test
        @DisplayName("find() handles empty matches by advancing one character")
        fun findEmptyMatches() {
            val p = org.safere.Pattern.compile("a*")
            val m = p.matcher("b")
            // Empty match at position 0
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
            // Empty match at position 1 (past 'b')
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(1)
            // No more matches
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find() returns false when no match exists")
        fun findNoMatch() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find() required-class precheck starts at current search position")
        fun findRequiredClassPrecheckStartsAtCurrentSearchPosition() {
            val m = org.safere.Pattern.compile("\\b{g}z").matcher("z\uD83C\uDDE6\uD83C\uDDE6")

            Assertions.assertThat(m.find(1)).isFalse()
        }

        @Test
        @DisplayName("find() required-literal precheck respects case-insensitive flags")
        fun findRequiredLiteralPrecheckRespectsCaseInsensitiveFlags() {
            val m = org.safere.Pattern.compile("(?i:abc)def").matcher("ABCdef")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("ABCdef")
        }

        @Test
        @DisplayName("find(int) starts search from given position")
        fun findWithStart() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2c3")
            Assertions.assertThat(m.find(3)).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("2")
        }

        @Test
        @DisplayName("find(int) does not move before a start inside a surrogate pair")
        fun findStartInsideSurrogatePairStaysWithinRequestedRange() {
            val input = "😀bX😀b"

            val required = org.safere.Pattern.compile("[\\x{1F600}]+b").matcher(input)
            Assertions.assertThat(required.find(1)).isTrue()
            Assertions.assertThat(required.start()).isEqualTo(4)
            Assertions.assertThat(required.group()).isEqualTo("😀b")

            val optional = org.safere.Pattern.compile("[\\x{1F600}]*b").matcher(input)
            Assertions.assertThat(optional.find(1)).isTrue()
            Assertions.assertThat(optional.start()).isEqualTo(2)
            Assertions.assertThat(optional.group()).isEqualTo("b")
        }

        @Test
        @DisplayName("find(int) keeps bounded supplementary leading expansions within the start")
        fun boundedLeadingExpansionRespectsFindStart() {
            val input = "😀😀bX😀b"
            val matcher = org.safere.Pattern.compile("[\\x{1F600}]{1,2}b").matcher(input)

            Assertions.assertThat(matcher.find(1)).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(2)
            Assertions.assertThat(matcher.group()).isEqualTo("😀b")
        }

        @Test
        @DisplayName("find(int) throws for negative start")
        fun findWithNegativeStart() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc")
            Assertions.assertThatThrownBy(ThrowingCallable { m.find(-1) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("find(int) throws for start past end of input")
        fun findWithStartPastEnd() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc")
            Assertions.assertThatThrownBy(ThrowingCallable { m.find(4) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("find() after failed matches() searches from beginning")
        fun findAfterFailedMatches() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("bab")
            Assertions.assertThat(m.matches()).isFalse()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("a")
            Assertions.assertThat(m.start()).isEqualTo(1)
        }

        @Test
        @DisplayName("failed anchored attempts preserve the end of an earlier successful find")
        fun failedAnchoredAttemptsPreservePreviousFindEnd() {
            // JDK 26 Matcher.find(): an intervening failed attempt does not reset the matcher.
            for (lookingAt in booleanArrayOf(false, true)) {
                val matcher = org.safere.Pattern.compile("a").matcher("ba a")
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isEqualTo(1)
                Assertions.assertThat(if (lookingAt) matcher.lookingAt() else matcher.matches()).isFalse()
                Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isEqualTo(3)
                Assertions.assertThat(matcher.end()).isEqualTo(4)
                Assertions.assertThat(matcher.find()).isFalse()
            }
        }

        @Test
        @DisplayName("failed matches preserves find continuation after lookingAt")
        fun failedMatchesPreservesLookingAtEnd() {
            val matcher = org.safere.Pattern.compile("a*").matcher("a!")
            Assertions.assertThat(matcher.lookingAt()).isTrue()
            Assertions.assertThat(matcher.matches()).isFalse()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("failed find cancels advancement past an empty start-anchored match")
        fun failedFindPreservesEmptyAnchoredMatchEnd() {
            // Compatibility with JDK 26's continuation after failure; regression for issue #817.
            val matcher = org.safere.Pattern.compile("^[ab]*").matcher("!a😀b!")
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.lookingAt()).isTrue()
            for (i in 0..2) {
                Assertions.assertThat(matcher.find()).isFalse()
                Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isZero()
                Assertions.assertThat(matcher.end()).isZero()
                Assertions.assertThat(matcher.group()).isEmpty()
            }
        }

        @Test
        @DisplayName("failed anchored attempts cancel advancement past an earlier empty match")
        fun failedAnchoredAttemptsPreserveEmptyMatchEnd() {
            val matcher = org.safere.Pattern.compile("a*").matcher("!a")
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isZero()
            Assertions.assertThat(matcher.matches()).isFalse()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isZero()
            Assertions.assertThat(matcher.end()).isZero()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(2)
        }

        @Test
        @DisplayName("find() at end of input returns false")
        fun findAtEnd() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        fun findEmptyMatchAtEndOfText() {
            val p = org.safere.Pattern.compile("a*")
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find(int) resets match state to the given position")
        fun findIntResetsState() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2c3")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("1")
            // Jump to position 0 — should find "1" again
            Assertions.assertThat(m.find(0)).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("1")
            // Jump past "1" and "2"
            Assertions.assertThat(m.find(4)).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("3")
        }

        @Test
        @DisplayName("find() exposes group 0 before deferred fallback captures are resolved")
        fun findGroupZeroBeforeDeferredFallbackCaptures() {
            val p = org.safere.Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))")
            val m = p.matcher("id,name\n42,\"Ada Lovelace\"")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("id")
            Assertions.assertThat(m.group(0)).isEqualTo("id")
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2)
        }

        @Test
        @DisplayName("group access resolves deferred fallback captures")
        fun groupAccessResolvesDeferredFallbackCaptures() {
            val p = org.safere.Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))")
            val m = p.matcher("id,name\n42,\"Ada Lovelace\"")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isNull()
            Assertions.assertThat(m.group(2)).isEqualTo("id")
            Assertions.assertThat(m.start(2)).isEqualTo(0)
            Assertions.assertThat(m.end(2)).isEqualTo(2)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(",name")
            Assertions.assertThat(m.group(1)).isNull()
            Assertions.assertThat(m.group(2)).isEqualTo("name")
        }

        @Test
        @DisplayName("toMatchResult() resolves deferred fallback captures into snapshot")
        fun toMatchResultResolvesDeferredFallbackCaptures() {
            val p = org.safere.Pattern.compile("(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))")
            val m = p.matcher("id,name\n42,\"Ada Lovelace\"")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.find()).isTrue()
            val snapshot = m.toMatchResult()

            Assertions.assertThat(snapshot.group()).isEqualTo(",\"Ada Lovelace\"")
            Assertions.assertThat(snapshot.group(1)).isEqualTo("Ada Lovelace")
            Assertions.assertThat(snapshot.group(2)).isNull()
            Assertions.assertThat(snapshot.start(1)).isEqualTo(12)
            Assertions.assertThat(snapshot.end(1)).isEqualTo(24)
        }

        @Test
        @DisplayName("successive find() calls do not require resolving previous fallback captures")
        fun successiveFindCallsWithDeferredFallbackCaptures() {
            val p = org.safere.Pattern.compile("(?i)\\b(error|warning|timeout|failed)\\b")
            val m = p.matcher("Warning cache miss\nrequest failed after TIMEOUT\nERROR")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("Warning")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("failed")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("TIMEOUT")
            Assertions.assertThat(m.group(1)).isEqualTo("TIMEOUT")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("ERROR")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find() keyword alternation fast path matches JDK across case and punctuation")
        fun findKeywordAlternationFastPathMatchesJdk() {
            assertAllFindsMatchJdk(
                "(?i)\\b(error|warning|timeout|failed)\\b",
                "Info: Warning, request failed after TIMEOUT; ERROR-rate ignored"
            )
        }

        @Test
        @DisplayName("find() keyword alternation rejects adjacent ASCII word characters")
        fun findKeywordAlternationRejectsAdjacentAsciiWordCharacters() {
            assertAllFindsMatchJdk(
                "(?i)\\b(error|warning|timeout|failed)\\b",
                "preerror error2 _warning warning-post failed"
            )
        }

        @Test
        @DisplayName("find() keyword alternation uses Unicode boundaries when requested")
        fun findKeywordAlternationUsesUnicodeBoundaries() {
            assertAllFindsMatchJdk(
                "(?i)\\b(error|warning)\\b",
                org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CHARACTER_CLASS,
                "éerroré error βwarning warning!"
            )
        }

        @Test
        @DisplayName("keyword alternation preserves independently scoped boundary modes")
        fun keywordAlternationPreservesIndependentlyScopedBoundaryModes() {
            assertAllFindsMatchJdk("(?-U)\\b(?i)(foo|bar)(?U)\\b", "éfoo! fooé bar!")
            assertAllFindsMatchJdk("(?U)\\b(?i)(foo|bar)(?-U)\\b", "éfoo! fooé bar!")
        }

        @Test
        @DisplayName("find() keyword alternation rejects partly case-sensitive scoped flags")
        fun findKeywordAlternationRejectsPartlyCaseSensitiveScopedFlags() {
            assertAllFindsMatchJdk("\\b((?i:a)B|(?i:x)Y)\\b", "ab aB AB xy xY XY")
        }

        @Test
        @DisplayName("keyword alternation keeps default case folding ASCII-only")
        fun keywordAlternationKeepsDefaultCaseFoldingAsciiOnly() {
            assertAllFindsMatchJdk("(?i)\\b(ak|as)\\b", "a\u212Ax ask")
            assertAllFindsMatchJdk("(?is).*\\b(ak|as)\\b.*", "a\u212Ax ask")
        }

        @Test
        @DisplayName("greedy whole-input keyword alternation preserves rightmost capture")
        fun greedyWholeInputKeywordAlternationPreservesRightmostCapture() {
            assertAllFindsMatchJdk(
                "(?is).*\\b(you|your)\\b.*",
                "You spoke first.\nThen your example referred to YOU at the end."
            )
        }

        @Test
        @DisplayName("greedy whole-input keyword alternation honors explicit find start")
        fun greedyWholeInputKeywordAlternationHonorsExplicitFindStart() {
            val regex = "(?is).*\\b(you|your)\\b.*"
            val input = "ignore YOU, then keep your answer"
            val safere = org.safere.Pattern.compile(regex).matcher(input)
            val jdk = Pattern.compile(regex).matcher(input)

            Assertions.assertThat(safere.find(12)).isEqualTo(jdk.find(12))
            Assertions.assertThat(safere.start()).isEqualTo(jdk.start())
            Assertions.assertThat(safere.end()).isEqualTo(jdk.end())
            Assertions.assertThat(safere.group(1)).isEqualTo(jdk.group(1))
            Assertions.assertThat(safere.start(1)).isEqualTo(jdk.start(1))
            Assertions.assertThat(safere.end(1)).isEqualTo(jdk.end(1))
        }

        @Test
        @DisplayName("find() start acceleration matches JDK for comma-or-line-start CSV fields")
        fun findStartAccelerationForCsvFields() {
            assertAllFindsMatchJdk(
                "(?m)(?:^|,)(?:\"([^\"]*)\"|([^,\r\n]+))",
                "id,name,email\r\n42,\"Ada Lovelace\",ada@example.com\n"
                        + "43,\"Grace Hopper\",grace@example.com"
            )
        }

        @Test
        @DisplayName("find() start acceleration matches JDK for multiline line-start whitespace")
        fun findStartAccelerationForLineStartWhitespace() {
            assertAllFindsMatchJdk(
                "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$",
                """
          java.lang.IllegalStateException: failed
          ${'\t'}at org.example.api.Handler.handle(Handler.java:87)
          Caused by: java.io.IOException: timeout${'\r'}
          ${'\t'}at org.example.net.Client.read(Client.java:203)
          """.trimIndent()
            )
        }

        @Test
        @DisplayName("find() start acceleration handles Unicode line terminators")
        fun findStartAccelerationWithUnicodeLineTerminators() {
            assertAllFindsMatchJdk("(?m)^\\s+at\\s+(\\w+)$", "header\u2028\tat alpha\u2029\tat beta")
        }

        @Test
        @DisplayName("find() skips fixed-offset literals that cannot start a match")
        fun findSkipsInvalidFixedOffsetLiteralCandidates() {
            assertAllFindsMatchJdk(
                "\\d{3}/\\d{3}/\\d{4}",
                "path /api/42 then 12/345/6789 and finally 123/456/7890 plus 987/654/3210"
            )
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}", "[^']*(?:'[^']*'[^']*)*END", "[^<]*(?:<[^>]*>[^<]*)*TARGET"
            ]
        )
        @DisplayName("find() preserves leftmost start before a later required literal")
        fun findPreservesLeftmostStartBeforeLaterRequiredLiteral(regex: String) {
            val input =
                when (regex) {
                    "[^{']*(?:'[^']*'[^{']*)*\\{([^}]*)\\}" -> "Foo '{0}' Bar: {0}"
                    "[^']*(?:'[^']*'[^']*)*END" -> "prefix 'not END' suffix END"
                    "[^<]*(?:<[^>]*>[^<]*)*TARGET" -> "prefix <not TARGET> suffix TARGET"
                    else -> throw AssertionError("unexpected regex: " + regex)
                }

            assertFirstFindMatchesJdk(regex, input)
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["(bcd|abcde)", "(.+?X|bc)", "(?:a{1,3})?a{3}", "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$", "(?i)\\b(error|warning|timeout|failed)\\b"
            ]
        )
        @DisplayName("find() fallback paths match JDK for unreliable DFA-start patterns")
        fun findFallbackMatchesJdkForUnreliableDfaStartPatterns(regex: String) {
            val text =
                """
          java.lang.IllegalStateException: failed
          ${'\t'}at org.example.Main.main(Main.java:25)
          xxabcde yy aaa failed bcX
          """.trimIndent()
            val m = org.safere.Pattern.compile(regex).matcher(text)
            val jdk = Pattern.compile(regex).matcher(text)

            val safereFound = m.find()
            val jdkFound = jdk.find()
            Assertions.assertThat(safereFound).isEqualTo(jdkFound)
            if (!jdkFound) {
                return
            }
            Assertions.assertThat(m.group()).isEqualTo(jdk.group())
            Assertions.assertThat(m.start()).isEqualTo(jdk.start())
            Assertions.assertThat(m.end()).isEqualTo(jdk.end())
            for (group in 1..jdk.groupCount()) {
                Assertions.assertThat(m.group(group)).isEqualTo(jdk.group(group))
                Assertions.assertThat(m.start(group)).isEqualTo(jdk.start(group))
                Assertions.assertThat(m.end(group)).isEqualTo(jdk.end(group))
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["\n", "\r\n", "\u2028"])
        @DisplayName("find() after $ before a trailing terminator advances to text end")
        fun findAfterDollarBeforeTrailingTerminatorAdvancesToTextEnd(terminator: String?) {
            assertAllFindsMatchJdk("$", "x".repeat(2000) + "a" + terminator)
        }

        @Test
        @DisplayName("find() falls back when reverse DFA start is ambiguous")
        fun findFallsBackWhenReverseDfaStartIsAmbiguous() {
            assertAllFindsMatchJdk("(?:\\B{1}|a).a?", "ab".repeat(600) + "c")
        }

        @Test
        @DisplayName("find() does not overconsume before trailing terminator for end anchor")
        fun findDoesNotOverconsumeBeforeTrailingTerminatorForEndAnchor() {
            assertAllFindsMatchJdk("(?:a+?|(?:[^x])*)$", "x".repeat(1100) + "a\n")
        }

        @Test
        @DisplayName("find() preserves boundary candidate priority in reverse DFA fallback")
        fun findPreservesBoundaryCandidatePriorityInReverseDfaFallback() {
            assertAllFindsMatchJdk("(?:a{2,}|(?:.|\\B){1,2}){1,2}", "baax")
        }

        @ParameterizedTest
        @ValueSource(
            strings = ["(bcd|abcde)", "(.+?X|bc)", "(?:a{1,3})?a{3}", "(?m)^\\s+at\\s+([A-Za-z0-9_.$]+)\\.([A-Za-z0-9_$<>]+)\\(([^:()]+):(\\d+)\\)$", "(?i)\\b(error|warning|timeout|failed)\\b"
            ]
        )
        @DisplayName("find() returns false for no-match unreliable DFA-start patterns")
        fun findFallbackNoMatchForUnreliableDfaStartPatterns(regex: String) {
            val m = org.safere.Pattern.compile(regex).matcher("plain text without the target shape")
            val jdk =
                Pattern.compile(regex).matcher("plain text without the target shape")

            Assertions.assertThat(m.find()).isEqualTo(jdk.find())
        }

        @Test
        @DisplayName("find() preserves leftmost start for zero-width alternation branches")
        fun findPreservesLeftmostStartForZeroWidthAlternationBranches() {
            assertFirstFindMatchesJdk("(?:\\B{1}|a).", "ab")
            assertFirstFindMatchesJdk("(?:a|\\B?).", "ab")
            assertFirstFindMatchesJdk("(?:a|(\\B)?).", "ab")
            assertFirstFindMatchesJdk("(?:(?:)\\B|a).", "ab")
        }

        @Test
        @DisplayName("find() preserves leftmost start before repeated consuming tails")
        fun findPreservesLeftmostStartBeforeRepeatedConsumingTails() {
            assertFirstFindMatchesJdk("\\B([^a])*[^a][^a]", "bbbb")
            assertFirstFindMatchesJdk("\\B(?:[^a])*[^a][^a]", "bbbb")
            assertFirstFindMatchesJdk("\\B[^a]*[^a][^a]", "bbbb")
            assertFirstFindMatchesJdk("\\B(?:b|bb)*bb", "bbbb")
        }

        @Test
        @DisplayName("find() keeps searching after a failed boundary-prefixed literal candidate")
        fun findKeepsSearchingAfterFailedBoundaryPrefixedLiteralCandidate() {
            assertFirstFindMatchesJdk("\\bfoo", "_foo foo")
            assertFirstFindMatchesJdk("\\Bfoo", " foo afoo")
        }

        @Test
        @DisplayName("find() respects non-word boundaries inside repetitions")
        fun findNonWordBoundaryInsideRepetitions() {
            val p = org.safere.Pattern.compile("(?:(?:(?:\\B)\\w)*)")
            val m = p.matcher("aba")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(3)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.end()).isEqualTo(3)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("find() preserves leftmost start for overlapping alternation branches (b/528687345)")
        fun testAlternationPriorityInversion() {
            val regex = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')|(\\btype\\b)"
            val input = "x = \"type\""
            val m = org.safere.Pattern.compile(regex).matcher(input)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("\"type\"")
            Assertions.assertThat(m.group(1)).isNull()
        }

        @Test
        @DisplayName("find() preserves leftmost start for overlapping lazy quantifiers (b/528722782)")
        fun testLazyQuantifierPriorityInversion() {
            val regex = "\\[.*?\\]\\((.*?)\\)|(\\b\\w+\\.md\\b)"
            val input = "abc [def](xyz.md) ghi"
            val m = org.safere.Pattern.compile(regex).matcher(input)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("[def](xyz.md)")
            Assertions.assertThat(m.group(1)).isEqualTo("xyz.md")
        }

        companion object {
            private fun assertAllFindsMatchJdk(regex: String, input: String) {
                assertAllFindsMatchJdk(regex, 0, input)
            }

            private fun assertAllFindsMatchJdk(regex: String, flags: Int, input: String) {
                val m = org.safere.Pattern.compile(regex, flags).matcher(input)
                val jdk = Pattern.compile(regex, flags).matcher(input)

                while (true) {
                    val safereFound = m.find()
                    val jdkFound = jdk.find()
                    Assertions.assertThat(safereFound).isEqualTo(jdkFound)
                    if (!jdkFound) {
                        return
                    }
                    Assertions.assertThat(m.group()).isEqualTo(jdk.group())
                    Assertions.assertThat(m.start()).isEqualTo(jdk.start())
                    Assertions.assertThat(m.end()).isEqualTo(jdk.end())
                    for (group in 1..jdk.groupCount()) {
                        Assertions.assertThat(m.group(group)).isEqualTo(jdk.group(group))
                        Assertions.assertThat(m.start(group)).isEqualTo(jdk.start(group))
                        Assertions.assertThat(m.end(group)).isEqualTo(jdk.end(group))
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Group extraction")
    internal inner class GroupTests {
        @Test
        @DisplayName("group() returns the full match, group(int) returns subgroups")
        fun groupAccess() {
            val p = org.safere.Pattern.compile("(a)(b)")
            val m = p.matcher("ab")
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("ab")
            Assertions.assertThat(m.group(0)).isEqualTo("ab")
            Assertions.assertThat(m.group(1)).isEqualTo("a")
            Assertions.assertThat(m.group(2)).isEqualTo("b")
        }

        @Test
        @DisplayName("group(String) returns named group text")
        fun namedGroup() {
            val p = org.safere.Pattern.compile("(?<first>\\w+) (?<last>\\w+)")
            val m = p.matcher("John Smith")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group("first")).isEqualTo("John")
            Assertions.assertThat(m.group("last")).isEqualTo("Smith")
        }

        @Test
        @DisplayName("group(String) throws for unknown name")
        fun namedGroupUnknown() {
            val p = org.safere.Pattern.compile("(?<first>\\w+)")
            val m = p.matcher("hello")
            m.find()
            Assertions.assertThatThrownBy(ThrowingCallable { m.group("unknown") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        @DisplayName("toMatchResult() snapshot supports named-group lookup")
        fun toMatchResultNamedGroups() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello")
            Assertions.assertThat(m.find()).isTrue()

            val mr = m.toMatchResult()
            Assertions.assertThat<String?, Int?>(mr.namedGroups()).containsEntry("word", 1)
            Assertions.assertThat(mr.group("word")).isEqualTo("hello")
            Assertions.assertThat(mr.start("word")).isEqualTo(0)
            Assertions.assertThat(mr.end("word")).isEqualTo(5)
        }

        @Test
        @DisplayName("start(String) and end(String) return named group positions")
        fun namedGroupStartEnd() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)@(?<host>\\w+)")
            val m = p.matcher("user@host")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start("word")).isEqualTo(0)
            Assertions.assertThat(m.end("word")).isEqualTo(4)
            Assertions.assertThat(m.start("host")).isEqualTo(5)
            Assertions.assertThat(m.end("host")).isEqualTo(9)
        }

        @Test
        @DisplayName("start(String) throws for unknown name")
        fun namedGroupStartUnknownThrows() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello")
            m.find()
            Assertions.assertThatThrownBy(ThrowingCallable { m.start("missing") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        @DisplayName("end(String) throws for unknown name")
        fun namedGroupEndUnknownThrows() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello")
            m.find()
            Assertions.assertThatThrownBy(ThrowingCallable { m.end("missing") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        @DisplayName("named group that did not participate returns null and -1 positions")
        fun nonParticipatingNamedGroup() {
            val p = org.safere.Pattern.compile("(?<a>a)|(?<b>b)")
            val m = p.matcher("b")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group("a")).isNull()
            Assertions.assertThat(m.start("a")).isEqualTo(-1)
            Assertions.assertThat(m.end("a")).isEqualTo(-1)
            Assertions.assertThat(m.group("b")).isEqualTo("b")
            Assertions.assertThat(m.start("b")).isEqualTo(0)
            Assertions.assertThat(m.end("b")).isEqualTo(1)
        }

        @Test
        @DisplayName("namedGroups() returns named groups from pattern")
        fun namedGroupsReturnsMap() {
            val p = org.safere.Pattern.compile("(?<user>\\w+)@(?<host>\\w+)")
            val m = p.matcher("user@host")
            Assertions.assertThat<String?, Int?>(m.namedGroups()).containsEntry("user", 1)
            Assertions.assertThat<String?, Int?>(m.namedGroups()).containsEntry("host", 2)
        }

        @Test
        @DisplayName("namedGroups() returns empty map for no named groups")
        fun namedGroupsEmpty() {
            val p = org.safere.Pattern.compile("(\\w+)@(\\w+)")
            val m = p.matcher("user@host")
            Assertions.assertThat<String?, Int?>(m.namedGroups()).isEmpty()
        }

        @Test
        @DisplayName("namedGroups() is unmodifiable")
        fun namedGroupsUnmodifiable() {
            val p = org.safere.Pattern.compile("(?<name>\\w+)")
            val m = p.matcher("hello")
            Assertions.assertThatThrownBy(ThrowingCallable { m.namedGroups().put("foo", 99) })
                .isInstanceOf(UnsupportedOperationException::class.java)
        }

        @Test
        @DisplayName("namedGroups() returns from MatchResult interface")
        fun namedGroupsFromMatchResult() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello")
            Assertions.assertThat(m.find()).isTrue()
            val result = m.toMatchResult()
            Assertions.assertThat<String?, Int?>(result.namedGroups()).containsEntry("word", 1)
        }

        @Test
        @DisplayName("named group methods reject null names")
        fun namedGroupMethodsRejectNullNames() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello")
            Assertions.assertThat(m.find()).isTrue()

            Assertions.assertThatThrownBy(ThrowingCallable { m.group(null as String?) })
                .isInstanceOf(NullPointerException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { m.start(null as String?) })
                .isInstanceOf(NullPointerException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { m.end(null as String?) })
                .isInstanceOf(NullPointerException::class.java)
        }

        @Test
        @DisplayName("groupCount() returns number of capturing groups (excluding group 0)")
        fun groupCount() {
            val p = org.safere.Pattern.compile("(a)(b)(c)")
            val m = p.matcher("abc")
            Assertions.assertThat(m.groupCount()).isEqualTo(3)
        }

        @Test
        @DisplayName("zero-count repetitions preserve capturing group count")
        fun zeroCountRepetitionsPreserveCapturingGroupCount() {
            assertZeroCountGroupBehavior("(a){0}", "")
            assertZeroCountGroupBehavior("(?:(a){0})", "")
            assertZeroCountGroupBehavior("(?:(?:(a){0}))", "")
            assertZeroCountGroupBehavior("(a){0}(b)", "b")
            assertZeroCountGroupBehavior("((a){0})", "")
        }

        @Test
        @DisabledForCrosscheck("JDK suppresses completed empty captures for direct repeat operands")
        @DisplayName("find() publishes completed captures in empty iterations")
        fun findPublishesCompletedCapturesInEmptyIterations() {
            for (regex in mutableListOf<String?>("()*", "(())*", "(?:())*", "(a?)*", "(?:((^){2}))*")) {
                val matcher = org.safere.Pattern.compile(regex).matcher("")
                Assertions.assertThat(matcher.find()).`as`("/%s/", regex).isTrue()
                for (group in 0..matcher.groupCount()) {
                    Assertions.assertThat(matcher.start(group)).isZero()
                    Assertions.assertThat(matcher.end(group)).isZero()
                    Assertions.assertThat(matcher.group(group)).isEmpty()
                }
            }
            val alternatives = org.safere.Pattern.compile("$|^Fe|()ü|()*|||||^D*4").matcher("\u007f*\u007f")
            Assertions.assertThat(alternatives.find()).isTrue()
            Assertions.assertThat(alternatives.start()).isZero()
            Assertions.assertThat(alternatives.end()).isZero()
            Assertions.assertThat(alternatives.group(1)).isNull()
            Assertions.assertThat(alternatives.start(2)).isZero()
            Assertions.assertThat(alternatives.end(2)).isZero()
        }

        @Test
        @DisabledForCrosscheck("JDK nested repeats can retain an earlier accepted capture")
        @DisplayName("counted repetitions expose their latest accepted capture")
        fun countedRepetitionsExposeTheirLatestAcceptedCapture() {
            assertFirstFindMatchesJdk("(?:(a){1}){0}$", "ab")
            assertFirstFindMatchesJdk("(?:(a){1}){0,1}$", "ab")
            assertLatestRepeatedCapture("(?:(a){1,}){2}", "aaa")
            assertLatestRepeatedCapture("(?:(a){1,}){2}", "aa")
            assertLatestRepeatedCapture("(?:(a){1,}){2,}", "aaa")
            assertLatestRepeatedCapture("(?:(a){1,}){2,3}", "aaaaa")
            assertLatestRepeatedCapture("(?:(a){0,2})*", "aaa")
            assertLatestRepeatedCapture("(?:(a){1,2})*", "aaa")
            assertLatestRepeatedCapture("(?:(a){1,2}){2}", "aa")
            assertLatestRepeatedCapture("(?:(a){1,2}){2}", "aaa")
            assertLatestRepeatedCapture("(?:(a){3,4})*", "aaaaaa")
            assertLatestRepeatedCapture("(?:(a){0,1})*", "aaa")
        }

        @Test
        fun nestedCapturesPreserveWholeMatchPriority() {
            val pattern = org.safere.Pattern.compile("(?:(ab?)+(a){0,2})+")
            val input = "aaab"
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.group()).isEqualTo(input)
            Assertions.assertThat(matcher.start(1)).isEqualTo(2)
            Assertions.assertThat(matcher.group(1)).isEqualTo("ab")
            Assertions.assertThat(matcher.group(2)).isNull()
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo("X")
            Assertions.assertThat(pattern.matcher(input).replaceAll(Function { result: MatchResult? -> "X" }))
                .isEqualTo("X")
            Assertions.assertThat<String?>(pattern.split(input, -1)).containsExactly("", "")
        }

        @Test
        @DisplayName("non-participating group returns null")
        fun nonParticipatingGroup() {
            val p = org.safere.Pattern.compile("(a)|(b)")
            val m = p.matcher("b")
            m.find()
            Assertions.assertThat(m.group(1)).isNull()
            Assertions.assertThat(m.group(2)).isEqualTo("b")
            Assertions.assertThat(m.start(1)).isEqualTo(-1)
            Assertions.assertThat(m.end(1)).isEqualTo(-1)
        }

        @Test
        @DisplayName("start() and end() return full match positions")
        fun startEnd() {
            val p = org.safere.Pattern.compile("b+")
            val m = p.matcher("aabba")
            m.find()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.end()).isEqualTo(4)
        }

        @Test
        @DisplayName("start(int) and end(int) return group positions")
        fun startEndGroup() {
            val p = org.safere.Pattern.compile("(a+)(b+)")
            val m = p.matcher("aaabb")
            m.find()
            Assertions.assertThat(m.start(1)).isEqualTo(0)
            Assertions.assertThat(m.end(1)).isEqualTo(3)
            Assertions.assertThat(m.start(2)).isEqualTo(3)
            Assertions.assertThat(m.end(2)).isEqualTo(5)
        }

        @Test
        @DisplayName("start() throws IllegalStateException before any match")
        fun startNoMatch() {
            val p = org.safere.Pattern.compile("x")
            val m = p.matcher("abc")
            Assertions.assertThatThrownBy(ThrowingCallable { m.start() })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("group() throws IllegalStateException before any match")
        fun groupNoMatch() {
            val p = org.safere.Pattern.compile("x")
            val m = p.matcher("abc")
            Assertions.assertThatThrownBy(ThrowingCallable { m.group() })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("end() throws IllegalStateException before any match")
        fun endNoMatch() {
            val p = org.safere.Pattern.compile("x")
            val m = p.matcher("abc")
            Assertions.assertThatThrownBy(ThrowingCallable { m.end() }).isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("start(int) throws IndexOutOfBoundsException for invalid group")
        fun invalidGroupIndexPositive() {
            val p = org.safere.Pattern.compile("(a)")
            val m = p.matcher("a")
            m.find()
            Assertions.assertThatThrownBy(ThrowingCallable { m.start(2) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("start(int) throws IndexOutOfBoundsException for negative group")
        fun invalidGroupIndexNegative() {
            val p = org.safere.Pattern.compile("(a)")
            val m = p.matcher("a")
            m.find()
            Assertions.assertThatThrownBy(ThrowingCallable { m.start(-1) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("end(int) throws IndexOutOfBoundsException for invalid group")
        fun invalidGroupIndexEnd() {
            val p = org.safere.Pattern.compile("(a)")
            val m = p.matcher("a")
            m.find()
            Assertions.assertThatThrownBy(ThrowingCallable { m.end(2) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("find() with zero-width assertions and groups")
        fun groupZeroWidthAssertions() {
            val p = org.safere.Pattern.compile("(^|[^a-zA-Z])(\\w+)")
            val m = p.matcher("hello, world")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(2)).isEqualTo("hello")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(2)).isEqualTo("world")
        }
    }

    @Nested
    @DisplayName("Replace operations")
    internal inner class ReplaceTests {
        @Test
        @DisplayName("quoteReplacement() escapes dollar signs and backslashes")
        fun quoteReplacement() {
            Assertions.assertThat(org.safere.Matcher.quoteReplacement("hello")).isEqualTo("hello")
            Assertions.assertThat(org.safere.Matcher.quoteReplacement("$1")).isEqualTo("\\$1")
            Assertions.assertThat(org.safere.Matcher.quoteReplacement("a\\b")).isEqualTo("a\\\\b")
            Assertions.assertThat(org.safere.Matcher.quoteReplacement("\$foo\\bar$")).isEqualTo("\\\$foo\\\\bar\\$")
            Assertions.assertThat(org.safere.Matcher.quoteReplacement("")).isEqualTo("")
        }

        @Test
        @DisplayName("quoteReplacement() result used in replaceAll() is literal")
        fun quoteReplacementInReplace() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2c3")
            Assertions.assertThat(m.replaceAll(org.safere.Matcher.quoteReplacement("$0"))).isEqualTo("a$0b$0c$0")
        }

        @Test
        @DisplayName("replaceFirst() replaces only the first match")
        fun replaceFirst() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2c3")
            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("aXb2c3")
        }

        @Test
        @DisplayName("replaceFirst() retains capture group state of the matched occurrence")
        fun replaceFirstRetainsCaptureGroups() {
            val p = org.safere.Pattern.compile("testingRecord\\[(.+?)\\]")
            val m = p.matcher("object.testingDetails.testingRecord[0].result")
            if (m.find()) {
                m.replaceFirst("testingRecord")
                Assertions.assertThat(m.group(1)).isEqualTo("0")
            }
        }

        @Test
        @DisplayName("replaceAll() replaces all matches")
        fun replaceAll() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2c3")
            Assertions.assertThat(m.replaceAll("X")).isEqualTo("aXbXcX")
        }

        @Test
        @DisplayName("replaceFirst() leaves find() positioned after the replaced match")
        fun replaceFirstLeavesFindPositionAfterReplacedMatch() {
            val p = org.safere.Pattern.compile("[a-z]+")
            val m = p.matcher("123abc456def")

            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("123X456def")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("def")
        }

        @Test
        @DisplayName("replaceAll() leaves find() exhausted after replacing matches")
        fun replaceAllLeavesFindExhaustedAfterReplacingMatches() {
            val p = org.safere.Pattern.compile("[a-z]+")
            val m = p.matcher("123abc456def")

            Assertions.assertThat(m.replaceAll("X")).isEqualTo("123X456X")

            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("replaceFirst() leaves appendTail positioned after the replaced match")
        fun replaceFirstLeavesAppendTailPositionAfterReplacedMatch() {
            val p = org.safere.Pattern.compile("[a-z]+")
            val m = p.matcher("123abc456def")
            val sb = StringBuilder()

            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("123X456def")
            m.appendTail(sb)

            Assertions.assertThat(sb).hasToString("456def")
        }

        @Test
        @DisplayName("replaceAll() leaves appendTail positioned after the last replaced match")
        fun replaceAllLeavesAppendTailPositionAfterLastReplacedMatch() {
            val p = org.safere.Pattern.compile("[a-z]+")
            val m = p.matcher("123abc456def789")
            val sb = StringBuilder()

            Assertions.assertThat(m.replaceAll("X")).isEqualTo("123X456X789")
            m.appendTail(sb)

            Assertions.assertThat(sb).hasToString("789")
        }

        @Test
        @DisplayName("replaceAll() preserves lazy character-class match boundaries")
        fun replaceAllPreservesLazyCharacterClassMatchBoundaries() {
            val p = org.safere.Pattern.compile("[a-z]+?")
            val m = p.matcher("abc")

            Assertions.assertThat(m.replaceAll("X")).isEqualTo("XXX")
        }

        @Test
        @DisplayName("replaceFirst() preserves lazy character-class match boundaries")
        fun replaceFirstPreservesLazyCharacterClassMatchBoundaries() {
            val p = org.safere.Pattern.compile("[a-z]+?")
            val m = p.matcher("abc")

            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("Xbc")
        }

        @ParameterizedTest
        @ValueSource(strings = ["a?", "a*", ".*", ".?"])
        @DisplayName("replaceAll() with nullable pattern matches JDK replacement sequence")
        fun replaceAllNullablePatternMatchesJdkReplacementSequence(regex: String) {
            val input = "a"

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceAll("X"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceAll("X"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["a?", "a*", ".*", ".?"])
        @DisplayName("replaceFirst() with nullable pattern matches JDK replacement sequence")
        fun replaceFirstNullablePatternMatchesJdkReplacementSequence(regex: String) {
            val input = "a"

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceFirst("X"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceFirst("X"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "abc", "aa", "baab"])
        @DisplayName("nullable repeated character-class replacement matches JDK across input shapes")
        fun nullableRepeatedCharacterClassReplacementMatchesJdk(input: String) {
            val regex = "a*"

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceAll("xyz"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceAll("xyz"))
            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceFirst("xyz"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceFirst("xyz"))
        }

        @Test
        @DisabledForCrosscheck(
            ("String empty-match progression may split surrogate pairs; the UTF-8 API advances by code"
                    + " point")
        )
        @DisplayName("nullable character-class replacement matches JDK UTF-16 progression")
        fun nullableCharacterClassReplacementMatchesJdkUtf16Progression() {
            val regex = "a*"
            val input = "💰a💰"

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceAll("xyz"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceAll("xyz"))
            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceFirst("xyz"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceFirst("xyz"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["[a-z]+", "[a-z]*"])
        @DisplayName("character-class replacement fallback retains the first match")
        fun characterClassReplacementFallbackRetainsFirstMatch(regex: String) {
            val input = "abc def"
            val replacement = "[$0]"

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceAll(replacement))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceAll(replacement))
        }

        @Test
        @DisplayName("replaceAll() with nullable alternation matches JDK replacement sequence")
        fun replaceAllNullableAlternationMatchesJdkReplacementSequence() {
            val regex = "(?:\\B|a).a?"
            val input = "bbaaa"

            Assertions.assertThat(org.safere.Pattern.compile(regex).matcher(input).replaceAll("X"))
                .isEqualTo(Pattern.compile(regex).matcher(input).replaceAll("X"))
        }

        @Test
        @DisplayName("replaceFirst() with no match returns original text")
        fun replaceFirstNoMatch() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc")
            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("abc")
        }

        @Test
        @DisplayName("replaceAll() with no match returns original text")
        fun replaceAllNoMatch() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc")
            Assertions.assertThat(m.replaceAll("X")).isEqualTo("abc")
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisplayName("replaceAll() with no match does not validate malformed replacement")
        fun replaceAllNoMatchDoesNotValidateMalformedReplacement(replacement: String?) {
            for (regex in arrayOf<String>("\\d+", "literal")) {
                val matcher = org.safere.Pattern.compile(regex).matcher("abc")

                Assertions.assertThat(matcher.replaceAll(replacement)).`as`("pattern %s", regex).isEqualTo("abc")
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisplayName("replaceFirst() with no match does not validate malformed replacement")
        fun replaceFirstNoMatchDoesNotValidateMalformedReplacement(replacement: String?) {
            for (regex in arrayOf<String>("\\d+", "literal")) {
                val matcher = org.safere.Pattern.compile(regex).matcher("abc")

                Assertions.assertThat(matcher.replaceFirst(replacement)).`as`("pattern %s", regex).isEqualTo("abc")
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisabledForCrosscheck("generated wrapper cannot advance both delegates after the first delegate throws")
        @DisplayName("replaceAll() with malformed replacement records first match before throwing")
        fun replaceAllMalformedReplacementRecordsFirstMatch(replacement: String?) {
            val p = org.safere.Pattern.compile("a.")
            val m = p.matcher("abc")

            Assertions.assertThatThrownBy(ThrowingCallable { m.replaceAll(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2)
            Assertions.assertThat(m.group()).isEqualTo("ab")
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisabledForCrosscheck("generated wrapper cannot advance both delegates after the first delegate throws")
        @DisplayName("replaceFirst() with malformed replacement records first match before throwing")
        fun replaceFirstMalformedReplacementRecordsFirstMatch(replacement: String?) {
            val p = org.safere.Pattern.compile("a.")
            val m = p.matcher("abc")

            Assertions.assertThatThrownBy(ThrowingCallable { m.replaceFirst(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2)
            Assertions.assertThat(m.group()).isEqualTo("ab")
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisabledForCrosscheck("generated wrapper cannot advance both delegates after the first delegate throws")
        @DisplayName("replaceAll() char-class fast path records first match before throwing")
        fun replaceAllCharClassMalformedReplacementRecordsFirstMatch(replacement: String?) {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("123 abc")

            Assertions.assertThatThrownBy(ThrowingCallable { m.replaceAll(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(3)
            Assertions.assertThat(m.group()).isEqualTo("123")
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisabledForCrosscheck("generated wrapper cannot advance both delegates after the first delegate throws")
        @DisplayName("replaceFirst() char-class fast path records first match before throwing")
        fun replaceFirstCharClassMalformedReplacementRecordsFirstMatch(replacement: String?) {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("123 abc")

            Assertions.assertThatThrownBy(ThrowingCallable { m.replaceFirst(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(3)
            Assertions.assertThat(m.group()).isEqualTo("123")
        }

        @ParameterizedTest
        @ValueSource(strings = ["$", "\\"])
        @DisabledForCrosscheck("generated wrapper cannot advance both delegates after the first delegate throws")
        @DisplayName("nullable char-class fast path records first match before throwing")
        fun nullableCharClassMalformedReplacementRecordsFirstMatch(replacement: String?) {
            val matcher = org.safere.Pattern.compile("a*").matcher("abc")

            Assertions.assertThatThrownBy(ThrowingCallable { matcher.replaceAll(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThat(matcher.start()).isEqualTo(0)
            Assertions.assertThat(matcher.end()).isEqualTo(1)
            Assertions.assertThat(matcher.group()).isEqualTo("a")
        }

        @Test
        @DisplayName("replaceFirst() with no match rejects null replacement")
        fun replaceFirstNoMatchRejectsNullReplacement() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc")

            Assertions.assertThatThrownBy(ThrowingCallable { m.replaceFirst(null as String?) })
                .isInstanceOf(NullPointerException::class.java)
        }

        @Test
        @DisplayName("replaceFirst() with start-anchored pattern replaces correct match")
        fun replaceFirstStartAnchored() {
            val p = org.safere.Pattern.compile("^\\d+")
            val m = p.matcher("123abc456")
            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("Xabc456")
        }

        @Test
        @DisplayName("replaceFirst() leaves matcher state at the replaced occurrence")
        fun replaceFirstLeavesMatcherAtReplacedOccurrence() {
            val m = org.safere.Pattern.compile("a").matcher("aba")

            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("Xba")

            Assertions.assertThat(m.toMatchResult().start()).isEqualTo(0)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
        }

        @Test
        @DisplayName("replaceFirst() leaves append position after the replaced occurrence")
        fun replaceFirstLeavesAppendPositionAfterReplacedOccurrence() {
            val m = org.safere.Pattern.compile("a").matcher("aba")

            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("Xba")
            val sb = StringBuilder()
            m.appendTail(sb)

            Assertions.assertThat(sb).hasToString("ba")
        }

        @Test
        @DisplayName("anchored OnePass replaceFirst preserves the matched occurrence")
        fun anchoredOnePassReplaceFirstPreservesMatchedOccurrence() {
            val m = org.safere.Pattern.compile("^([a-z]+)$").matcher("abc")

            Assertions.assertThat(m.replaceFirst("[$1]")).isEqualTo("[abc]")

            Assertions.assertThat(m.start()).isZero()
            Assertions.assertThat(m.end()).isEqualTo(3)
            Assertions.assertThat(m.group(1)).isEqualTo("abc")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("literal replaceFirst() retains numbered and named captures")
        fun literalReplaceFirstRetainsCaptures() {
            val matcher = org.safere.Pattern.compile("(?<word>(foo))").matcher("foo bar")

            Assertions.assertThat(matcher.replaceFirst("X")).isEqualTo("X bar")

            Assertions.assertThat(matcher.group()).isEqualTo("foo")
            Assertions.assertThat(matcher.group(1)).isEqualTo("foo")
            Assertions.assertThat(matcher.group(2)).isEqualTo("foo")
            Assertions.assertThat(matcher.group("word")).isEqualTo("foo")
            Assertions.assertThat(matcher.start(1)).isZero()
            Assertions.assertThat(matcher.end(2)).isEqualTo(3)
        }

        @Test
        @DisplayName("anchored OnePass replaceFirst preserves the append position")
        fun anchoredOnePassReplaceFirstPreservesAppendPosition() {
            val m = org.safere.Pattern.compile("^([a-z]+)$").matcher("abc\n")

            Assertions.assertThat(m.replaceFirst("[$1]")).isEqualTo("[abc]\n")
            val sb = StringBuilder()
            m.appendTail(sb)

            Assertions.assertThat(sb).hasToString("\n")
        }

        @Test
        fun repeatedFindAfterReplaceAllRemainsExhausted() {
            for (regex in arrayOf<String>("a", "[ab]+", "^([a-z]+)$")) {
                val matcher = org.safere.Pattern.compile(regex).matcher("aba\n")
                matcher.replaceAll("X")
                for (i in 0..2) {
                    Assertions.assertThat(matcher.find()).`as`("find after replaceAll for %s", regex).isFalse()
                }
            }
        }

        @Test
        @DisplayName("anchored OnePass replaceAll leaves the matcher exhausted")
        fun anchoredOnePassReplaceAllLeavesMatcherExhausted() {
            val m = org.safere.Pattern.compile("^([a-z]+)$").matcher("abc\n")

            Assertions.assertThat(m.replaceAll("[$1]")).isEqualTo("[abc]\n")
            val sb = StringBuilder()
            m.appendTail(sb)

            Assertions.assertThat(sb).hasToString("\n")
            Assertions.assertThat(m.find()).isFalse()
            Assertions.assertThatThrownBy(ThrowingCallable { m.start() })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("replaceFirst() preserves dollar-anchor match before trailing line terminator")
        fun replaceFirstPreservesDollarAnchorBeforeTrailingLineTerminator() {
            val m = org.safere.Pattern.compile("([^a](\\s\\s)+)*$").matcher("a\n")

            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("aX\n")
        }

        @Test
        @DisplayName("replaceAll() with start-anchored pattern replaces correct match")
        fun replaceAllStartAnchored() {
            val p = org.safere.Pattern.compile("^\\d+")
            val m = p.matcher("123abc456")
            Assertions.assertThat(m.replaceAll("X")).isEqualTo("Xabc456")
        }

        @Test
        @DisplayName("replaceAll() leaves matcher exhausted after the final replacement")
        fun replaceAllLeavesMatcherExhaustedAfterFinalReplacement() {
            val m = org.safere.Pattern.compile("a").matcher("aba")

            Assertions.assertThat(m.replaceAll("X")).isEqualTo("XbX")

            val sb = StringBuilder()
            m.appendTail(sb)
            Assertions.assertThat(sb).hasToString("")
            Assertions.assertThat(m.find()).isFalse()
            Assertions.assertThatThrownBy(ThrowingCallable { m.toMatchResult().start() }).isInstanceOf(
                IllegalStateException::class.java
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["x*", "y*", "a*", "x?", "(x)*", "(x*)", "", "(?<part>x*)", "$"])
        @DisabledForCrosscheck("JDK retains inconsistent match state after terminal empty replacement; see #931")
        fun replaceAllTerminalEmptyMatchInvalidatesState(regex: String) {
            for (input in arrayOf<String>("", "x", "yxxy", "yyyy")) {
                val matcher = org.safere.Pattern.compile(regex).matcher(input)
                val jdk = Pattern.compile(regex).matcher(input)
                Assertions.assertThat(matcher.replaceAll("-")).isEqualTo(jdk.replaceAll("-"))
                Assertions.assertThat(matcher.groupCount()).isEqualTo(jdk.groupCount())
                assertExhaustedMatchState(matcher)
                if (matcher.namedGroups().containsKey("part")) {
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.group("part") }).isInstanceOf(
                        IllegalStateException::class.java
                    )
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.start("part") }).isInstanceOf(
                        IllegalStateException::class.java
                    )
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.end("part") }).isInstanceOf(
                        IllegalStateException::class.java
                    )
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["x+", "(x+)", "x", "[xy]"])
        fun replaceAllNonNullableMatchInvalidatesStateLikeJdk(regex: String) {
            for (input in arrayOf<String>("", "x", "yxxy", "yyyy")) {
                val matcher = org.safere.Pattern.compile(regex).matcher(input)
                val jdk = Pattern.compile(regex).matcher(input)
                Assertions.assertThat(matcher.replaceAll("-")).isEqualTo(jdk.replaceAll("-"))
                assertExhaustedMatchState(matcher)
                assertExhaustedMatchState(jdk)
                Assertions.assertThat(matcher.groupCount()).isEqualTo(jdk.groupCount())
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["x*", "y*", "a*", "x?", "(x)*", "(x*)", "", "x+", "$"])
        fun replaceFirstPreservesMatchStateLikeJdk(regex: String) {
            for (input in arrayOf<String>("", "x", "yxxy", "yyyy")) {
                val matcher = org.safere.Pattern.compile(regex).matcher(input)
                val jdk = Pattern.compile(regex).matcher(input)
                Assertions.assertThat(matcher.replaceFirst("-")).isEqualTo(jdk.replaceFirst("-"))
                Assertions.assertThat(matcher.hasMatch()).isEqualTo(jdk.hasMatch())
                Assertions.assertThat(matcher.groupCount()).isEqualTo(jdk.groupCount())
                if (jdk.hasMatch()) {
                    Assertions.assertThat(matcher.group()).isEqualTo(jdk.group())
                    Assertions.assertThat(matcher.start()).isEqualTo(jdk.start())
                    Assertions.assertThat(matcher.end()).isEqualTo(jdk.end())
                    for (group in 0..jdk.groupCount()) {
                        Assertions.assertThat(matcher.group(group)).isEqualTo(jdk.group(group))
                        Assertions.assertThat(matcher.start(group)).isEqualTo(jdk.start(group))
                        Assertions.assertThat(matcher.end(group)).isEqualTo(jdk.end(group))
                    }
                } else {
                    assertExhaustedMatchState(matcher)
                    assertExhaustedMatchState(jdk)
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["x*", "(x*)", "", "$", "\\b"])
        @DisabledForCrosscheck("JDK retains inconsistent state after a terminal empty find; see #931")
        fun terminalEmptyFindInvalidatesSnapshotsAndReplacement(regex: String?) {
            for (input in arrayOf<String>("ab", "yxxy")) {
                for (replaceAll in booleanArrayOf(false, true)) {
                    val matcher = org.safere.Pattern.compile(regex).matcher(input)
                    if (replaceAll) {
                        matcher.replaceAll("-")
                    } else {
                        while (matcher.find()) {
                            // Exhaust the matcher through the same terminal find as replaceAll().
                        }
                    }
                    assertExhaustedMatchState(matcher)
                    val snapshot = matcher.toMatchResult()
                    Assertions.assertThat(snapshot.groupCount()).isEqualTo(matcher.groupCount())
                    assertExhaustedMatchState(snapshot)

                    val builder = StringBuilder("A")
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendReplacement(builder, "[$0]") })
                        .isInstanceOf(IllegalStateException::class.java)
                    Assertions.assertThat(builder).hasToString("A")
                    val buffer = StringBuffer("A")
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.appendReplacement(buffer, "[$0]") })
                        .isInstanceOf(IllegalStateException::class.java)
                    Assertions.assertThat(buffer).hasToString("A")
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "b", "bbb"])
        @DisplayName("nullable DFA replaceAll() consumes its terminal empty match")
        fun nullableDfaReplaceAllConsumesTerminalEmptyMatch(input: String) {
            val matcher = org.safere.Pattern.compile("a?").matcher(input)
            val jdkMatcher = Pattern.compile("a?").matcher(input)

            Assertions.assertThat(matcher.replaceAll("X")).isEqualTo(jdkMatcher.replaceAll("X"))

            Assertions.assertThat(matcher.find()).isEqualTo(jdkMatcher.find()).isFalse()
            Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("replaceAll() with numeric backreference")
        fun replaceAllWithBackref() {
            val p = org.safere.Pattern.compile("(\\w+)")
            val m = p.matcher("hello world")
            Assertions.assertThat(m.replaceAll("[$1]")).isEqualTo("[hello] [world]")
        }

        @Test
        @DisplayName("replaceAll() with ambiguous reverse DFA match start handles correctness")
        fun replaceAllWithAmbiguousReverseDfaMatchStart() {
            val p = org.safere.Pattern.compile("(b|ab)c")
            val m = p.matcher("abc")
            Assertions.assertThat(m.replaceAll("X")).isEqualTo("X")
        }

        @Test
        @DisplayName("find() with alternation prefers leftmost match over earliest DFA end")
        fun testAlternationDfaSandwichCorrectness() {
            val p = org.safere.Pattern.compile("b|a.c")
            val m = p.matcher("abc")
            val found = m.find()
            Assertions.assertThat(found).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
        }

        @Test
        @DisplayName("replaceAll with group references preserves start-anchor find semantics")
        fun replaceAllWithGroupReferencesPreservesStartAnchorFindSemantics() {
            val cases = arrayOf<Array<String?>?>(
                arrayOf<String?>("^(.*)", "ExistingValue", "An$1"),
                arrayOf<String?>("\\A(.*)", "ExistingValue", "An$1"),
                arrayOf<String?>("^(a*)", "aaa", "[$1]"),
                arrayOf<String?>("\\A(a*)", "aaa", "[$1]"),
                arrayOf<String?>("^(a*)", "bbb", "[$1]"),
                arrayOf<String?>("\\A(a*)", "bbb", "[$1]"),
                arrayOf<String?>("^([0-9].*)", "abc123", "<$1>"),
                arrayOf<String?>("\\A([0-9].*)", "abc123", "<$1>"),
            )

            for (tc in cases) {
                val pattern: String = tc!![0]!!
                val input: String = tc[1]!!
                val replacement = tc[2]
                Assertions.assertThat(org.safere.Pattern.compile(pattern).matcher(input).replaceAll(replacement))
                    .`as`("replaceAll(%s) for /%s/ on %s", replacement, pattern, input)
                    .isEqualTo(
                        Pattern.compile(pattern).matcher(input).replaceAll(replacement)
                    )
            }
        }

        @Test
        @DisplayName("numeric replacement references keep trailing digits literal when needed")
        fun numericReplacementReferencesUseLongestLegalGroup() {
            val p = org.safere.Pattern.compile("(\\w+)")

            Assertions.assertThat(p.matcher("abc").replaceFirst("$11")).isEqualTo("abc1")
            Assertions.assertThat(p.matcher("abc").replaceFirst("$19")).isEqualTo("abc9")
            Assertions.assertThat(p.matcher("abc").replaceFirst("$10")).isEqualTo("abc0")
            Assertions.assertThat(p.matcher("ab cd").replaceAll("$11")).isEqualTo("ab1 cd1")
        }

        @Test
        @DisplayName("numeric replacement references use multiple digits for existing groups")
        fun numericReplacementReferencesUseExistingMultiDigitGroup() {
            val p = org.safere.Pattern.compile("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)")

            Assertions.assertThat(p.matcher("abcdefghijkl").replaceFirst("$11")).isEqualTo("kl")
            Assertions.assertThat(p.matcher("abcdefghijkl").replaceFirst("$111")).isEqualTo("k1l")
        }

        @Test
        @DisplayName("appendReplacement parses numeric references using longest legal group")
        fun appendReplacementNumericReferencesUseLongestLegalGroup() {
            val p = org.safere.Pattern.compile("(\\w+)")
            val m = p.matcher("ab cd")
            val sb = StringBuilder()

            while (m.find()) {
                m.appendReplacement(sb, "$11")
            }
            m.appendTail(sb)

            Assertions.assertThat(sb.toString()).isEqualTo("ab1 cd1")
        }

        @Test
        @DisplayName("numeric replacement reference with invalid first digit still throws")
        fun numericReplacementReferenceInvalidFirstDigitThrows() {
            val p = org.safere.Pattern.compile("(\\w+)")

            Assertions.assertThatThrownBy(ThrowingCallable { p.matcher("abc").replaceFirst("$99") })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { p.matcher("abc").replaceAll("$99") })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("numeric replacement reference with invalid digit does not throw if no match")
        fun numericReplacementReferenceInvalidFirstDigitDoesNotThrowIfNoMatch() {
            val p = org.safere.Pattern.compile("\\d+")

            Assertions.assertThat(p.matcher("foobar").replaceAll("$99")).isEqualTo("foobar")
        }

        @Test
        @DisplayName("replaceAll() with named backreference")
        fun replaceAllWithNamedBackref() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello world")
            Assertions.assertThat(m.replaceAll("\${word}!")).isEqualTo("hello! world!")
        }

        @Test
        @DisplayName("replaceFirst() with named backreference")
        fun replaceFirstWithNamedBackref() {
            val p = org.safere.Pattern.compile("(?<word>\\w+)")
            val m = p.matcher("hello world")
            Assertions.assertThat(m.replaceFirst("\${word}!")).isEqualTo("hello! world")
        }

        @Test
        @DisplayName("replacement string with escaped dollar and backslash")
        fun replacementEscapes() {
            val p = org.safere.Pattern.compile("x")
            val m = p.matcher("x")
            Assertions.assertThat(m.replaceFirst("\\$1")).isEqualTo("$1")
        }

        @Test
        @DisplayName("replacement string with escaped backslash")
        fun replacementEscapedBackslash() {
            val p = org.safere.Pattern.compile("x")
            val m = p.matcher("x")
            Assertions.assertThat(m.replaceFirst("\\\\")).isEqualTo("\\")
        }

        @Test
        @DisplayName("appendReplacement() and appendTail() manual iteration")
        fun appendReplacementAndTail() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2c3")
            val sb = StringBuilder()
            while (m.find()) {
                m.appendReplacement(sb, "X")
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("aXbXcX")
        }

        @Test
        @DisplayName("appendReplacement() with backreference in replacement")
        fun appendReplacementWithBackref() {
            val p = org.safere.Pattern.compile("(\\d+)")
            val m = p.matcher("a1b22")
            val sb = StringBuilder()
            while (m.find()) {
                m.appendReplacement(sb, "[$1]")
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("a[1]b[22]")
        }

        @Test
        fun appendReplacementTrailingBackslash() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            m.find()
            val sb = StringBuilder()
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(sb, "\\") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun appendReplacementTrailingDollar() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            m.find()
            val sb = StringBuilder()
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(sb, "$") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun appendReplacementInvalidGroupRef() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            m.find()
            val sb = StringBuilder()
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(sb, "$9") })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        fun appendReplacementUnclosedNameRef() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            m.find()
            val sb = StringBuilder()
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(sb, "\${name") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        // Only the ASCII digits '0'-'9' can start or continue a numeric group reference, as in
        // java.util.regex. Other Unicode decimal digits (Arabic-Indic, fullwidth, ...) after '$' are
        // illegal group references, not group numbers derived from the code point's distance from '0'.
        @ParameterizedTest
        @ValueSource(strings = ["$\u0661", "$\u06F1", "$\u0966", "$\uFF11", "$\uFF19", "x$\uFF19y"])
        fun nonAsciiDigitAfterDollarIsIllegalGroupReference(replacement: String?) {
            val p = org.safere.Pattern.compile("(a)")
            Assertions.assertThatThrownBy(ThrowingCallable { p.matcher("a").replaceAll(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { p.matcher("a").replaceFirst(replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(StringBuilder(), replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(StringBuffer(), replacement) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun nonAsciiDigitAfterDollarDoesNotSelectGroupByCodePointOffset() {
            // U+0661 - '0' == 1585, which is a valid group number in this pattern. It must not be
            // treated as a reference to group 1585.
            val p = org.safere.Pattern.compile("(a)" + "()".repeat(1599))
            Assertions.assertThatThrownBy(ThrowingCallable { p.matcher("a").replaceAll("[$\u0661]") })
                .isInstanceOf(IllegalArgumentException::class.java)
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThatThrownBy(ThrowingCallable { m.appendReplacement(StringBuilder(), "[$\u0661]") })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun nonAsciiDigitAfterGroupReferenceIsLiteral() {
            // The first digit after '$' is a group reference; a following non-ASCII digit is not part of
            // the reference and is copied literally.
            val p = org.safere.Pattern.compile("(a)")
            Assertions.assertThat(p.matcher("a").replaceAll("$1\u0662")).isEqualTo("a\u0662")
            Assertions.assertThat(p.matcher("a").replaceAll("$1\uFF10")).isEqualTo("a\uFF10")
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            val sb = StringBuilder()
            m.appendReplacement(sb, "$1\u0662")
            Assertions.assertThat(sb.toString()).isEqualTo("a\u0662")
        }

        @Test
        fun nonAsciiDigitAfterGroupReferenceDoesNotAccumulateIntoLargeGroup() {
            // With enough groups, "$1" + U+0662 must not accumulate to group 1596; it is group 1
            // followed by a literal U+0662. Only group 1 captures text, so selecting another group
            // would change the output.
            val p = org.safere.Pattern.compile("(a)" + "()".repeat(1699))
            Assertions.assertThat(p.matcher("a").replaceAll("[$1\u0662]")).isEqualTo("[a\u0662]")
            Assertions.assertThat(p.matcher("a").replaceFirst("[$1\u0661]")).isEqualTo("[a\u0661]")
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            val sb = StringBuilder()
            m.appendReplacement(sb, "[$1\u0662]")
            Assertions.assertThat(sb.toString()).isEqualTo("[a\u0662]")
        }

        @Test
        fun escapedNonAsciiDigitIsLiteral() {
            val p = org.safere.Pattern.compile("(a)")
            Assertions.assertThat(p.matcher("a").replaceAll("\\\u0661")).isEqualTo("\u0661")
        }

        @Test
        fun replaceAllEmptyMatches() {
            val p = org.safere.Pattern.compile("a*")
            val m = p.matcher("b")
            val result = m.replaceAll("x")
            Assertions.assertThat(result).isEqualTo("xbx")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("replaceAll with backreference wrapping")
        fun replaceAllBackrefWrapping() {
            val p = org.safere.Pattern.compile("(\\w+):(\\d+)")
            val m = p.matcher("a:1 b:2")
            Assertions.assertThat(m.replaceAll("$2=$1")).isEqualTo("1=a 2=b")
        }

        @Test
        @DisplayName("appendReplacement with empty capturing group in replacement")
        fun appendReplacementEmptyGroup() {
            val p = org.safere.Pattern.compile("(a*)b")
            val m = p.matcher("b")
            val sb = StringBuilder()
            Assertions.assertThat(m.find()).isTrue()
            m.appendReplacement(sb, "[$1]")
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("[]")
        }

        @Test
        @DisplayName("appendReplacement handles multiple matches with groups")
        fun appendReplacementMultipleMatches() {
            val p = org.safere.Pattern.compile("(\\d)(\\d)")
            val m = p.matcher("a12b34c")
            val sb = StringBuilder()
            while (m.find()) {
                m.appendReplacement(sb, "$2$1")
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("a21b43c")
        }

        @Test
        @DisplayName("replaceFirst with group that did not participate")
        fun replaceFirstNonParticipatingGroup() {
            val p = org.safere.Pattern.compile("(a)|(b)")
            val m = p.matcher("b")
            // $1 didn't participate (null), should be replaced with empty string
            Assertions.assertThat(m.replaceFirst("[$1][$2]")).isEqualTo("[][b]")
        }

        @Test
        @DisplayName("replaceAll() with dollar anchor and alternation matches JDK")
        fun testReplaceAllDollarAnchorAlternation() {
            val regex = "(?:a+?|(?:[^x])*)$"
            val input = "x".repeat(1100) + "a\n"
            val p = org.safere.Pattern.compile(regex)
            val m = p.matcher(input)
            val result = m.replaceAll("X")

            val jdkP = Pattern.compile(regex)
            val jdkM = jdkP.matcher(input)
            val jdkResult = jdkM.replaceAll("X")

            Assertions.assertThat(result).isEqualTo(jdkResult)
        }

        @Test
        @DisplayName("find() with ambiguous reverse DFA start matches JDK")
        fun testFindAmbiguousReverseDfa() {
            val regex = "(?:\\B|a).a?"
            val input = "ab".repeat(600) + "c"
            val p = org.safere.Pattern.compile(regex)
            val m = p.matcher(input)
            val jdkP = Pattern.compile(regex)
            val jdkM = jdkP.matcher(input)

            while (jdkM.find()) {
                Assertions.assertThat(m.find()).isTrue()
                Assertions.assertThat(m.start()).isEqualTo(jdkM.start())
                Assertions.assertThat(m.end()).isEqualTo(jdkM.end())
            }
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("case-insensitive single-character replaceAll() and replaceFirst()")
        fun caseInsensitiveSingleCharacterReplace() {
            val p = org.safere.Pattern.compile("(?i)a")
            val m = p.matcher("aAbBaA")
            Assertions.assertThat(m.replaceAll("X")).isEqualTo("XXbBXX")
            m.reset()
            Assertions.assertThat(m.replaceFirst("X")).isEqualTo("XAbBaA")
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("case-insensitive multi-character literal replaceAll() and replaceFirst()")
        fun caseInsensitiveLiteralReplace() {
            val p = org.safere.Pattern.compile("(?i)keyword_to_find")
            val input = "KEYWORD_TO_FIND and keyword_to_find and KeyWord_To_Find"
            val m = p.matcher(input)
            Assertions.assertThat(m.replaceAll("FOUND")).isEqualTo("FOUND and FOUND and FOUND")
            m.reset()
            Assertions.assertThat(m.replaceFirst("FOUND"))
                .isEqualTo("FOUND and keyword_to_find and KeyWord_To_Find")
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(15)
            Assertions.assertThat(m.group()).isEqualTo("KEYWORD_TO_FIND")
        }

        @Test
        @DisplayName("case-insensitive literal replaceAll() with $0 backreference")
        fun caseInsensitiveLiteralReplaceWithGroupZero() {
            val p = org.safere.Pattern.compile("(?i)abc")
            val m = p.matcher("ABC abc AbC")
            Assertions.assertThat(m.replaceAll("[$0]")).isEqualTo("[ABC] [abc] [AbC]")
            m.reset()
            Assertions.assertThat(m.replaceFirst("[$0]")).isEqualTo("[ABC] abc AbC")
            Assertions.assertThat(m.group()).isEqualTo("ABC")
        }

        @Test
        @DisplayName("case-insensitive literal replaceAll() on large repeated input matches JDK")
        fun caseInsensitiveLiteralReplaceAllLargeInputMatchesJdk() {
            val regex = "(?i)keyword_to_find"
            val input = "KEYWORD_TO_FIND ".repeat(200)
            val p = org.safere.Pattern.compile(regex)
            val jdkP = Pattern.compile(regex)

            Assertions.assertThat(p.matcher(input).replaceAll("")).isEqualTo(jdkP.matcher(input).replaceAll(""))
            Assertions.assertThat(p.matcher(input).replaceFirst("X"))
                .isEqualTo(jdkP.matcher(input).replaceFirst("X"))
        }

        companion object {
            private fun assertExhaustedMatchState(matcher: MatchResult) {
                Assertions.assertThat(matcher.hasMatch()).isFalse()
                Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowingCallable { matcher.start() })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThatThrownBy(ThrowingCallable { matcher.end() })
                    .isInstanceOf(IllegalStateException::class.java)
                for (group in 0..matcher.groupCount()) {
                    val index = group
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.group(index) }).isInstanceOf(
                        IllegalStateException::class.java
                    )
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.start(index) }).isInstanceOf(
                        IllegalStateException::class.java
                    )
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.end(index) }).isInstanceOf(
                        IllegalStateException::class.java
                    )
                }
            }
        }
    }

    @Nested
    @DisplayName("State management")
    internal inner class StateTests {
        @Test
        @DisplayName("reset() clears match state and restarts from beginning")
        fun reset() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2")
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("1")
            m.reset()
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("1") // restarts from the beginning
        }

        @Test
        @DisplayName("reset(CharSequence) changes input and clears state")
        fun resetWithNewInput() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("a1b2")
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("1")
            m.reset("x9y8")
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("9")
        }

        @Test
        @DisplayName("reset() re-reads mutable CharSequence")
        fun resetRereadsMutableCharSequence() {
            val sb = StringBuilder("a1b2")
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher(sb)
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("1")
            sb.setLength(0)
            sb.append("x9y8")
            m.reset()
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("9")
        }

        @Test
        @DisabledForCrosscheck("SafeRE supports CharSequence group access that JDK cannot")
        @DisplayName("matcher works with CharSequence that does not override toString()")
        fun customCharSequenceWithoutToString() {
            // A CharSequence backed by a byte array that does NOT override toString().
            // This mimics Ghidra's ByteCharSequence pattern.
            val data = "hello world".toByteArray(StandardCharsets.US_ASCII)
            val byteSeq: CharSequence =
                object : CharSequence {
                    override fun length(): Int {
                        return data.size
                    }

                    override fun charAt(index: Int): Char {
                        return (data[index].toInt() and 0xff).toChar()
                    }

                    override fun subSequence(start: Int, end: Int): CharSequence {
                        throw UnsupportedOperationException()
                    } // Deliberately no toString() override — falls back to Object.toString()
                }

            val p = org.safere.Pattern.compile("world")
            val m = p.matcher(byteSeq)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("world")
            Assertions.assertThat(m.start()).isEqualTo(6)

            // Also test find() returning false when pattern doesn't match
            val p2 = org.safere.Pattern.compile("xyz")
            val m2 = p2.matcher(byteSeq)
            Assertions.assertThat(m2.find()).isFalse()
        }

        @Test
        @DisplayName("pattern() returns the Pattern that created this Matcher")
        fun patternAccess() {
            val p = org.safere.Pattern.compile("abc")
            val m = p.matcher("abc")
            Assertions.assertThat<org.safere.Pattern?>(m.pattern()).isSameAs(p)
        }

        @Test
        @DisabledForCrosscheck("Matcher.toString() format is implementation-specific")
        @DisplayName("toString() reports pattern, region, and last match")
        fun toStringReportsMatcherState() {
            val m = org.safere.Pattern.compile("a").matcher("ba")

            Assertions.assertThat(m.toString()).contains("pattern=a").contains("region=0,2").contains("lastmatch=")

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.toString()).contains("pattern=a").contains("region=0,2").contains("lastmatch=a")
        }

        @Test
        @DisplayName("toMatchResult() returns an independent snapshot")
        fun toMatchResult() {
            val p = org.safere.Pattern.compile("(\\d+)")
            val m = p.matcher("a1b2")
            m.find()
            Assertions.assertThat(m.hasMatch()).isTrue()
            val r = m.toMatchResult()
            Assertions.assertThat(r.hasMatch()).isTrue()
            Assertions.assertThat(r.group()).isEqualTo("1")
            Assertions.assertThat(r.group(1)).isEqualTo("1")
            Assertions.assertThat(r.start()).isEqualTo(1)
            Assertions.assertThat(r.end()).isEqualTo(2)
            Assertions.assertThat(r.groupCount()).isEqualTo(1)
            // Advance matcher; snapshot should be unaffected.
            m.find()
            Assertions.assertThat(m.group()).isEqualTo("2")
            Assertions.assertThat(r.group()).isEqualTo("1")
            Assertions.assertThat(r.hasMatch()).isTrue()
            // Reset matcher; snapshot remains unaffected.
            m.reset()
            Assertions.assertThat(m.hasMatch()).isFalse()
            Assertions.assertThat(r.hasMatch()).isTrue()
        }

        @Test
        @DisplayName("toMatchResult() before a match defers failures to result access")
        fun toMatchResultBeforeMatchDefersFailuresToResultAccess() {
            val p = org.safere.Pattern.compile("(?<letter>x)(y)?")
            val m = p.matcher("abc")

            Assertions.assertThat(m.hasMatch()).isFalse()
            val result = m.toMatchResult()
            Assertions.assertThat(result.hasMatch()).isFalse()

            Assertions.assertThat(result.groupCount()).isEqualTo(2)
            Assertions.assertThat<String?, Int?>(result.namedGroups()).containsEntry("letter", 1)
            Assertions.assertThatThrownBy(ThrowingCallable { result.start() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { result.end() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { result.group() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { result.start(1) })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { result.start(99) })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { result.group("letter") })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("toMatchResult() after a failed match defers failures to result access")
        fun toMatchResultAfterFailedMatchDefersFailuresToResultAccess() {
            val p = org.safere.Pattern.compile("x")
            val m = p.matcher("abc")
            Assertions.assertThat(m.find()).isFalse()
            Assertions.assertThat(m.hasMatch()).isFalse()

            val result = m.toMatchResult()
            Assertions.assertThat(result.hasMatch()).isFalse()

            Assertions.assertThat(result.groupCount()).isZero()
            Assertions.assertThatThrownBy(ThrowingCallable { result.start() })
                .isInstanceOf(IllegalStateException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { result.group() })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun toMatchResultNonParticipatingGroup() {
            val p = org.safere.Pattern.compile("(a)|(b)")
            val m = p.matcher("a")
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.hasMatch()).isTrue()
            val mr = m.toMatchResult()
            Assertions.assertThat(mr.hasMatch()).isTrue()
            Assertions.assertThat(mr.group(1)).isEqualTo("a")
            Assertions.assertThat(mr.group(2)).isNull()
            Assertions.assertThat(mr.start(2)).isEqualTo(-1)
            Assertions.assertThat(mr.end(2)).isEqualTo(-1)
        }

        @Nested
        @DisplayName("hasMatch() lifecycle & contract tests")
        internal inner class HasMatchTests {
            @Test
            @DisplayName("hasMatch() on fresh matcher returns false")
            fun freshMatcherHasMatchIsFalse() {
                val p = org.safere.Pattern.compile("abc")
                val m = p.matcher("abcdef")
                Assertions.assertThat(m.hasMatch()).isFalse()
            }

            @Test
            @DisplayName("hasMatch() reflects find() success and failure")
            fun findHasMatch() {
                val p = org.safere.Pattern.compile("\\d+")
                val m = p.matcher("abc123def")
                Assertions.assertThat(m.hasMatch()).isFalse()

                Assertions.assertThat(m.find()).isTrue()
                Assertions.assertThat(m.hasMatch()).isTrue()

                Assertions.assertThat(m.find()).isFalse()
                Assertions.assertThat(m.hasMatch()).isFalse()
            }

            @Test
            @DisplayName("hasMatch() reflects matches() and lookingAt() success and failure")
            fun matchesAndLookingAtHasMatch() {
                val p = org.safere.Pattern.compile("abc")
                val m = p.matcher("abcdef")

                Assertions.assertThat(m.matches()).isFalse()
                Assertions.assertThat(m.hasMatch()).isFalse()

                Assertions.assertThat(m.lookingAt()).isTrue()
                Assertions.assertThat(m.hasMatch()).isTrue()
            }

            @Test
            @DisplayName("hasMatch() survives usePattern() and resets to false on reset()")
            fun resetAndUsePatternHasMatch() {
                val p1 = org.safere.Pattern.compile("abc")
                val p2 = org.safere.Pattern.compile("xyz")
                val m = p1.matcher("abc")

                Assertions.assertThat(m.find()).isTrue()
                Assertions.assertThat(m.hasMatch()).isTrue()

                m.usePattern(p2)
                Assertions.assertThat(m.hasMatch()).isTrue()

                m.reset("xyz")
                Assertions.assertThat(m.hasMatch()).isFalse()
                Assertions.assertThat(m.find()).isTrue()
                Assertions.assertThat(m.hasMatch()).isTrue()

                m.reset()
                Assertions.assertThat(m.hasMatch()).isFalse()
            }
        }

        @Test
        @DisplayName("reset(CharSequence) allows reuse with different input")
        fun resetCharSequenceReuse() {
            val p = org.safere.Pattern.compile("(\\d+)")
            val m = p.matcher("abc123")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("123")

            m.reset("xyz789def")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("789")
        }
    }

    @Nested
    @DisplayName("Unicode support")
    internal inner class UnicodeTests {
        @Test
        @DisplayName("Unicode supplementary code point matching")
        fun unicodeSupplementaryCodePoints() {
            // U+1F600 = 😀, a supplementary character (surrogate pair in Java)
            val smiley = "\uD83D\uDE00"
            val p = org.safere.Pattern.compile(".")
            val m = p.matcher(smiley)
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(smiley)
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2) // 2 Java chars (surrogate pair)
        }

        @Test
        @DisplayName("find() with Unicode surrogate pairs in text")
        fun findWithSurrogatePairs() {
            // "a😀b" = 'a' + surrogate pair + 'b'
            val text = "a\uD83D\uDE00b"
            val p = org.safere.Pattern.compile("b")
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(3) // 'a'=0, smiley=1,2, 'b'=3
            Assertions.assertThat(m.end()).isEqualTo(4)
        }

        @Test
        @DisplayName("find() with negated classes can start at lone surrogates like JDK")
        fun findWithNegatedClassesCanStartAtLoneSurrogatesLikeJdk() {
            assertFirstFindMatchesJdk("[^a-z]..", "\uD801\uD800\uDC00xyz")
        }
    }

    @Nested
    @DisplayName("Integration")
    internal inner class IntegrationTests {
        @Test
        @DisplayName("complex find/group iteration (documented example)")
        fun documentedExample() {
            val p = org.safere.Pattern.compile("(\\d+)([a-z]+)")
            val m = p.matcher("12abc 34def")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("12abc")
            Assertions.assertThat(m.group(1)).isEqualTo("12")
            Assertions.assertThat(m.group(2)).isEqualTo("abc")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("34def")
            Assertions.assertThat(m.group(1)).isEqualTo("34")
            Assertions.assertThat(m.group(2)).isEqualTo("def")
            Assertions.assertThat(m.find()).isFalse()
        }
    }

    @Nested
    @DisplayName("StringBuffer append methods")
    internal inner class StringBufferAppendTests {
        @Test
        @DisplayName("appendReplacement and appendTail with StringBuffer")
        fun stringBufferAppendReplacementAndTail() {
            val p = org.safere.Pattern.compile("(\\d+)")
            val m = p.matcher("abc 123 def 456")
            val sb = StringBuffer()
            while (m.find()) {
                m.appendReplacement(sb, "NUM")
            }
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("abc NUM def NUM")
        }

        @Test
        @DisplayName("appendReplacement with StringBuffer supports group references")
        fun stringBufferGroupRef() {
            val p = org.safere.Pattern.compile("(\\w+)@(\\w+)")
            val m = p.matcher("user@host")
            val sb = StringBuffer()
            m.find()
            m.appendReplacement(sb, "$2/$1")
            m.appendTail(sb)
            Assertions.assertThat(sb.toString()).isEqualTo("host/user")
        }

        @Test
        @DisplayName("appendReplacement with region preserves text outside the region")
        fun stringBufferAppendReplacementPreservesTextOutsideRegion() {
            val p = org.safere.Pattern.compile("\\d")
            val m = p.matcher("ab1cd2ef")
            m.region(2, 6)
            val sb = StringBuffer()

            while (m.find()) {
                m.appendReplacement(sb, "X")
            }
            m.appendTail(sb)

            Assertions.assertThat(sb.toString()).isEqualTo("abXcdXef")
        }
    }

    @Nested
    @DisplayName("Transparent and anchoring bounds")
    internal inner class BoundsTests {
        @Test
        @DisplayName("default anchoring bounds is true")
        fun defaultAnchoringBounds() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            Assertions.assertThat(m.hasAnchoringBounds()).isTrue()
        }

        @Test
        @DisplayName("useAnchoringBounds stores flag and returns this")
        fun useAnchoringBounds() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            Assertions.assertThat<org.safere.Matcher?>(m.useAnchoringBounds(false)).isSameAs(m)
            Assertions.assertThat(m.hasAnchoringBounds()).isFalse()
            m.useAnchoringBounds(true)
            Assertions.assertThat(m.hasAnchoringBounds()).isTrue()
        }

        @Test
        @DisplayName("default transparent bounds is false")
        fun defaultTransparentBounds() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            Assertions.assertThat(m.hasTransparentBounds()).isFalse()
        }

        @Test
        @DisplayName("useTransparentBounds stores flag and returns this")
        fun useTransparentBounds() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("a")
            Assertions.assertThat<org.safere.Matcher?>(m.useTransparentBounds(true)).isSameAs(m)
            Assertions.assertThat(m.hasTransparentBounds()).isTrue()
            m.useTransparentBounds(false)
            Assertions.assertThat(m.hasTransparentBounds()).isFalse()
        }
    }

    @Nested
    @DisplayName("Case-insensitive DFA correctness")
    internal inner class CaseInsensitiveDfaTests {
        @Test
        @DisplayName("matches() works after failed case-insensitive match on reused matcher")
        fun matchesAfterMismatch() {
            val p = org.safere.Pattern.compile(
                "birds",
                org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
            )
            val m = p.matcher("Cats")
            Assertions.assertThat(m.matches()).isFalse()
            m.reset("Birds")
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        @DisplayName("sequential case-insensitive matches on reused matcher")
        fun sequentialCaseInsensitiveMatches() {
            val p = org.safere.Pattern.compile(
                "birds",
                org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
            )
            val m = p.matcher("")
            val tokens = arrayOf<String?>("dogs", "cats", "Cats", "Birds", "birds")
            val expected = booleanArrayOf(false, false, false, true, true)
            for (i in tokens.indices) {
                m.reset(tokens[i])
                Assertions.assertThat(m.matches()).`as`("token '%s'", tokens[i]).isEqualTo(expected[i])
            }
        }

        @Test
        @DisplayName("fresh matcher works after DFA cached non-matching transitions")
        fun freshMatcherAfterDfaCache() {
            val p = org.safere.Pattern.compile(
                "birds",
                org.safere.Pattern.CASE_INSENSITIVE or org.safere.Pattern.UNICODE_CASE
            )
            // Warm up DFA with non-matching inputs
            val m1 = p.matcher("Cats")
            m1.matches()
            // Fresh matcher on same pattern should still work
            Assertions.assertThat(p.matcher("Birds").matches()).isTrue()
        }
    }

    @Nested
    @DisplayName("region()")
    internal inner class RegionTests {
        @Test
        @DisplayName("find() respects region boundaries")
        fun findRespectsRegion() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc 123 def 456 ghi")
            m.region(4, 7) // "123"
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
            Assertions.assertThat(m.start()).isEqualTo(4)
            Assertions.assertThat(m.end()).isEqualTo(7)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("quantified capture extraction evaluates anchors against opaque region bounds")
        fun quantifiedCaptureExtractionRespectsOpaqueRegionAnchors() {
            val m = org.safere.Pattern.compile("^((a|aa))*$").matcher("xaa")
            m.region(1, 3)

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("a")
            Assertions.assertThat(m.start(1)).isEqualTo(2)
            Assertions.assertThat(m.end(1)).isEqualTo(3)
            Assertions.assertThat(m.group(2)).isEqualTo("a")
            Assertions.assertThat(m.start(2)).isEqualTo(2)
            Assertions.assertThat(m.end(2)).isEqualTo(3)
        }

        @Test
        @DisplayName("find() does not match outside region")
        fun findDoesNotMatchOutsideRegion() {
            val p = org.safere.Pattern.compile("ghi")
            val m = p.matcher("abc ghi xyz")
            m.region(0, 5) // "abc g"
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("matches() matches entire region")
        fun matchesEntireRegion() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123def")
            m.region(3, 6) // "123"
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.end()).isEqualTo(6)
        }

        @Test
        @DisplayName("grapheme matches() stays bounded to region without anchoring bounds")
        fun graphemeMatchesStaysRegionBoundedWithoutAnchoringBounds() {
            val jdk =
                Pattern.compile("\\X").matcher("#a$").region(1, 2)
            jdk.useAnchoringBounds(false)
            Assertions.assertThat(jdk.matches()).isTrue()

            val m = org.safere.Pattern.compile("\\X").matcher("#a$").region(1, 2)
            m.useAnchoringBounds(false)
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(2)
        }

        @Test
        @DisplayName("matches() fails if region doesn't fully match")
        fun matchesFailsPartialRegion() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123def")
            m.region(2, 7) // "c123d"
            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        @DisplayName("lookingAt() matches at start of region")
        fun lookingAtRegion() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123def456")
            m.region(3, 9) // "123def"
            Assertions.assertThat(m.lookingAt()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.end()).isEqualTo(6)
        }

        @Test
        @DisplayName("lookingAt() fails if region doesn't start with match")
        fun lookingAtRegionFails() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("abc123def")
            m.region(0, 6) // "abc123" — starts with 'a' not digit
            Assertions.assertThat(m.lookingAt()).isFalse()
        }

        @Test
        @DisplayName("regionStart() and regionEnd() return bounds")
        fun regionAccessors() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("abcdef")
            m.region(2, 5)
            Assertions.assertThat(m.regionStart()).isEqualTo(2)
            Assertions.assertThat(m.regionEnd()).isEqualTo(5)
        }

        @Test
        @DisplayName("reset() restores region to full text")
        fun resetRestoresRegion() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("abcdef")
            m.region(2, 5)
            m.reset()
            Assertions.assertThat(m.regionStart()).isEqualTo(0)
            Assertions.assertThat(m.regionEnd()).isEqualTo(6)
        }

        @Test
        @DisplayName("region returns this matcher")
        fun regionReturnsSelf() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("abc")
            Assertions.assertThat<org.safere.Matcher?>(m.region(0, 2)).isSameAs(m)
        }

        @Test
        @DisplayName("region with invalid bounds throws")
        fun regionInvalidBoundsThrows() {
            val p = org.safere.Pattern.compile("a")
            val m = p.matcher("abc")
            Assertions.assertThatThrownBy(ThrowingCallable { m.region(-1, 3) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { m.region(0, 4) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
            Assertions.assertThatThrownBy(ThrowingCallable { m.region(3, 1) })
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }

        @Test
        @DisplayName("^ matches at region start with anchoring bounds")
        fun caretMatchesAtRegionStart() {
            val p = org.safere.Pattern.compile("^\\d+")
            val m = p.matcher("abc123def")
            m.region(3, 6) // "123"
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
        }

        @Test
        @DisplayName("$ matches at region end with anchoring bounds")
        fun dollarMatchesAtRegionEnd() {
            val p = org.safere.Pattern.compile("\\d+$")
            val m = p.matcher("abc123def")
            m.region(3, 6) // "123"
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("123")
        }

        @Test
        @DisplayName("^ does not match region start when anchoring bounds are disabled")
        fun caretDoesNotMatchRegionStartWithoutAnchoringBounds() {
            val p = org.safere.Pattern.compile("^\\d+")
            val m = p.matcher("abc123def")
            m.region(3, 6) // "123"
            m.useAnchoringBounds(false)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("$ does not match region end when anchoring bounds are disabled")
        fun dollarDoesNotMatchRegionEndWithoutAnchoringBounds() {
            val p = org.safere.Pattern.compile("\\d+$")
            val m = p.matcher("abc123def")
            m.region(3, 6) // "123"
            m.useAnchoringBounds(false)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("$ uses full-text trailing terminator context without anchoring bounds")
        fun dollarUsesFullTextTrailingTerminatorContextWithoutAnchoringBounds() {
            val anchored = org.safere.Pattern.compile("^[\\s\\S][\\s\\S]$")
            val anchoredMatcher = anchored.matcher("a\u0000\n\n")
            anchoredMatcher.region(0, 3)
            anchoredMatcher.useAnchoringBounds(false)
            Assertions.assertThat(anchoredMatcher.find()).isFalse()

            val unanchored = org.safere.Pattern.compile("[\\s\\S][\\s\\S]$")
            val unanchoredMatcher = unanchored.matcher("a\u0000\n\n")
            unanchoredMatcher.region(0, 3)
            unanchoredMatcher.useAnchoringBounds(false)
            Assertions.assertThat(unanchoredMatcher.find()).isTrue()
            Assertions.assertThat(unanchoredMatcher.start()).isEqualTo(1)
            Assertions.assertThat(unanchoredMatcher.end()).isEqualTo(3)
        }

        @Test
        @DisplayName("successive find() calls within region")
        fun successiveFindInRegion() {
            val p = org.safere.Pattern.compile("[a-z]+")
            val m = p.matcher("111aaa222bbb333ccc444")
            m.region(3, 15) // "aaa222bbb333"
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("aaa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("bbb")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("capture groups work with region")
        fun captureGroupsWithRegion() {
            val p = org.safere.Pattern.compile("(\\w+)=(\\w+)")
            val m = p.matcher("xxx a=1 yyy b=2 zzz")
            m.region(4, 15) // "a=1 yyy b=2"
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("a")
            Assertions.assertThat(m.group(2)).isEqualTo("1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("b")
            Assertions.assertThat(m.group(2)).isEqualTo("2")
        }

        @Test
        @DisplayName("empty region matches empty pattern")
        fun emptyRegion() {
            val p = org.safere.Pattern.compile("")
            val m = p.matcher("abc")
            m.region(1, 1) // empty region
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("region at end of text")
        fun regionAtEnd() {
            val p = org.safere.Pattern.compile("xyz")
            val m = p.matcher("abcxyz")
            m.region(3, 6) // "xyz"
            Assertions.assertThat(m.matches()).isTrue()
        }

        @Test
        @DisplayName("replaceAll within region (after find loop)")
        fun replaceAllWithRegion() {
            val p = org.safere.Pattern.compile("\\d+")
            val m = p.matcher("aa11bb22cc")
            m.region(2, 8) // "11bb22"
            // replaceAll resets, which resets region to full text
            // So this tests that reset() works correctly
            val result = m.replaceAll("N")
            Assertions.assertThat(result).isEqualTo("aaNbbNcc")
        }

        @Test
        @DisplayName("transparent bounds let word boundary see before region start")
        fun transparentBoundsWordBoundarySeesBeforeRegionStart() {
            val p = org.safere.Pattern.compile("\\bfoo")
            val m = p.matcher("afoo")
            m.region(1, 4) // "foo", preceded by word char outside the region
            m.useTransparentBounds(true)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("transparent bounds let word boundary see after region end")
        fun transparentBoundsWordBoundarySeesAfterRegionEnd() {
            val p = org.safere.Pattern.compile("foo\\b")
            val m = p.matcher("fooa")
            m.region(0, 3) // "foo", followed by word char outside the region
            m.useTransparentBounds(true)
            Assertions.assertThat(m.find()).isFalse()
        }
    }

    @Nested
    @DisplayName("Reverse-first DFA optimization for end-anchored patterns")
    internal inner class ReverseFirstDfaTests {
        @Test
        @DisplayName("end-anchored pattern, no match on large text — fast fail")
        fun endAnchoredNoMatch() {
            val p = org.safere.Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$")
            // Random lowercase text — pattern can't match because no uppercase letters at end.
            val text = repeat('a', 2000)
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("end-anchored pattern, match at end of large text")
        fun endAnchoredMatchAtEnd() {
            val p = org.safere.Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$")
            val text = repeat('x', 2000) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo(text)
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(text.length)
        }

        @Test
        @DisplayName("dollar anchor with trailing newline — match before \\n")
        fun dollarAnchorTrailingNewline() {
            val p = org.safere.Pattern.compile("abc$")
            val text = repeat('x', 2000) + "abc\n"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
            Assertions.assertThat(m.start()).isEqualTo(2000)
            Assertions.assertThat(m.end()).isEqualTo(2003)
        }

        @Test
        @DisplayName("dollar anchor with trailing \\r\\n — match before \\r\\n")
        fun dollarAnchorTrailingCrLf() {
            val p = org.safere.Pattern.compile("abc$")
            val text = repeat('x', 2000) + "abc\r\n"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
            Assertions.assertThat(m.start()).isEqualTo(2000)
            Assertions.assertThat(m.end()).isEqualTo(2003)
        }

        @Test
        @DisplayName("dollar anchor, no trailing newline — match at absolute end")
        fun dollarAnchorNoNewline() {
            val p = org.safere.Pattern.compile("abc$")
            val text = repeat('x', 2000) + "abc"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
            Assertions.assertThat(m.start()).isEqualTo(2000)
            Assertions.assertThat(m.end()).isEqualTo(2003)
        }

        @Test
        @DisplayName("\\\\z anchor — match only at absolute end, not before \\n")
        fun absoluteEndAnchor() {
            val p = org.safere.Pattern.compile("abc\\z")
            val text = repeat('x', 2000) + "abc"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("abc")
        }

        @Test
        @DisplayName("\\\\z anchor with trailing newline — no match")
        fun absoluteEndAnchorNoMatchBeforeNewline() {
            val p = org.safere.Pattern.compile("abc\\z")
            val text = repeat('x', 2000) + "abc\n"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("end-anchored with capture groups")
        fun endAnchoredWithCaptures() {
            val p = org.safere.Pattern.compile("(\\w+)@(\\w+)$")
            // Use non-word chars as padding so \w+ doesn't match them.
            val text = repeat('-', 2000) + "user@host"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("user@host")
            Assertions.assertThat(m.group(1)).isEqualTo("user")
            Assertions.assertThat(m.group(2)).isEqualTo("host")
        }

        @Test
        @DisplayName("end-anchored, second find() returns false")
        fun endAnchoredSecondFindFails() {
            val p = org.safere.Pattern.compile("abc$")
            val text = repeat('x', 2000) + "abc"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("char-class prefix + end anchor, no match")
        fun charClassPrefixEndAnchorNoMatch() {
            val p = org.safere.Pattern.compile("[XYZ]ABCDEFGHIJKLMNOPQRSTUVWXYZ$")
            val text = repeat('a', 2000)
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("char-class prefix + end anchor, match at end")
        fun charClassPrefixEndAnchorMatch() {
            val p = org.safere.Pattern.compile("[XYZ]ABC$")
            val text = repeat('a', 2000) + "XABC"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("XABC")
        }

        @Test
        @DisplayName("end-anchored pattern on text just above threshold")
        fun textJustAboveThreshold() {
            val p = org.safere.Pattern.compile("xyz$")
            val text = repeat('a', 1024) + "xyz"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("xyz")
            Assertions.assertThat(m.start()).isEqualTo(1024)
        }

        @Test
        @DisplayName("end-anchored pattern on text below threshold — uses normal path")
        fun textBelowThreshold() {
            val p = org.safere.Pattern.compile("xyz$")
            val text = repeat('a', 500) + "xyz"
            val m = p.matcher(text)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group()).isEqualTo("xyz")
        }

        @Test
        @DisplayName("end-anchored pattern with a leading expansion matches like the JDK")
        fun endAnchoredLeadingExpansionAgreesWithJdk() {
            val prose = "fine food offer ".repeat(200)
            for (regex in mutableListOf<String?>(
                "(?i)\\s*(foo|fax)$",
                "\\s*(foo|fax)\\z",
                "(?i)_?(foo|fax)$",
                "\\s+foo$"
            )) {
                for (text in List.of<String>(
                    prose,
                    prose + "foo",
                    prose + "  FOO",
                    prose + "fax\n",
                    prose + "_fax",
                    prose + "foo bar",
                    "foo" + prose
                )) {
                    val expected = Pattern.compile(regex).matcher(text)
                    val actual = org.safere.Pattern.compile(regex).matcher(text)
                    val found = expected.find()
                    Assertions.assertThat(actual.find())
                        .`as`("%s on ...%s", regex, text.substring(text.length - 8))
                        .isEqualTo(found)
                    if (found) {
                        Assertions.assertThat(actual.start()).isEqualTo(expected.start())
                        Assertions.assertThat(actual.end()).isEqualTo(expected.end())
                    }
                }
            }
        }

        companion object {
            /** Helper to create a string of repeated characters.  */
            private fun repeat(ch: Char, count: Int): String {
                return ch.toString().repeat(count)
            }
        }
    }

    /**
     * Tests for the OnePass fast path with alternation patterns. The anchored OnePass fast path now
     * handles non-nullable alternation (e.g., GET|POST) directly, skipping the DFA sandwich. Nullable
     * alternation (zero-width vs consuming branches) still falls through to DFA+BitState.
     */
    @Nested
    @DisplayName("OnePass alternation fast path")
    internal inner class OnePassAlternationFastPath {
        @Test
        @DisplayName("HTTP pattern — anchored with non-nullable alternation uses OnePass")
        fun httpPatternFullRequest() {
            val p = org.safere.Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
            val m =
                p.matcher(
                    "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka"
                            + " HTTP/1.1"
                )
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0))
                .isEqualTo(
                    "GET /asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka"
                            + " HTTP"
                )
            Assertions.assertThat(m.group(1))
                .isEqualTo(
                    "/asdfhjasdhfasdlfhasdflkjasdfkljasdhflaskdjhflkajsdhflkajshfklasjdhfklasjdhfklashdflka"
                )
        }

        @Test
        @DisplayName("HTTP pattern — POST variant")
        fun httpPatternPost() {
            val p = org.safere.Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
            val m = p.matcher("POST /submit HTTP/1.1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("POST /submit HTTP")
            Assertions.assertThat(m.group(1)).isEqualTo("/submit")
        }

        @Test
        @DisplayName("HTTP pattern — small request")
        fun httpPatternSmallRequest() {
            val p = org.safere.Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
            val m = p.matcher("GET /abc HTTP/1.1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("GET /abc HTTP")
            Assertions.assertThat(m.group(1)).isEqualTo("/abc")
        }

        @Test
        @DisplayName("HTTP pattern — no match")
        fun httpPatternNoMatch() {
            val p = org.safere.Pattern.compile("^(?:GET|POST) +([^ ]+) HTTP")
            val m = p.matcher("PUT /resource HTTP/1.1")
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("non-nullable alternation with captures — both branches")
        fun nonNullableAlternationCaptures() {
            val p = org.safere.Pattern.compile("^(cat|dog) (\\w+)")
            val m1 = p.matcher("cat fluffy")
            Assertions.assertThat(m1.find()).isTrue()
            Assertions.assertThat(m1.group(1)).isEqualTo("cat")
            Assertions.assertThat(m1.group(2)).isEqualTo("fluffy")

            val m2 = p.matcher("dog rex")
            Assertions.assertThat(m2.find()).isTrue()
            Assertions.assertThat(m2.group(1)).isEqualTo("dog")
            Assertions.assertThat(m2.group(2)).isEqualTo("rex")
        }

        @Test
        @DisplayName("nullable alternation — zero-width branch wins via first-match priority")
        fun nullableAlternationZeroWidth() {
            // \\b is zero-width, \\d is consuming — first-match should pick \\b
            val p = org.safere.Pattern.compile("(?:\\b|\\d)")
            val m = p.matcher("1")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("") // \\b matches empty at word boundary
        }

        @Test
        @DisplayName("nullable alternation — non-word-boundary vs consuming")
        fun nullableAlternationNonWordBoundary() {
            val p = org.safere.Pattern.compile("(?:\\B|a)")
            val m = p.matcher("ba _")
            Assertions.assertThat(m.find()).isTrue()
            // First match: \\B at position 0 is not a word boundary (start of string before 'b'),
            // but JDK considers position 0 a word boundary, so \\B fails and 'a' is not at pos 0.
            // Actual behavior depends on JDK semantics — just verify we match JDK.
            val jdkM = Pattern.compile("(?:\\B|a)").matcher("ba _")
            Assertions.assertThat(jdkM.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo(jdkM.group(0))
        }

        @Test
        @DisplayName("unanchored non-nullable alternation on small text — uses OnePass")
        fun unanchoredNonNullableSmallText() {
            val p = org.safere.Pattern.compile("(GET|POST) (\\w+)")
            val m = p.matcher("method: GET data")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(1)).isEqualTo("GET")
            Assertions.assertThat(m.group(2)).isEqualTo("data")
        }
    }

    @Nested
    @DisplayName("Atomic \\r\\n line terminator (#77, #78)")
    internal inner class AtomicCrLfTests {
        @Test
        @DisplayName("find() with $ on \\r\\n does not infinite-loop (#77)")
        fun dollarFindOnCrLfTerminates() {
            // Before the fix, this never terminated because $ matched between \r and \n,
            // causing find() to loop without advancing.
            val p = org.safere.Pattern.compile("$")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("(?m)$ on \\r\\n matches at 0 and 2 only (#78)")
        fun multilineDollarOnCrLf() {
            val p = org.safere.Pattern.compile("(?m)$")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("(?m)$ on a\\r\\nb matches at 1 and 4 only (#78)")
        fun multilineDollarOnTextWithCrLf() {
            val p = org.safere.Pattern.compile("(?m)$")
            val m = p.matcher("a\r\nb")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(4)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("$ (non-multiline) on \\r\\n matches at end")
        fun dollarNonMultilineOnCrLf() {
            val p = org.safere.Pattern.compile("$")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("(?m)$ on standalone \\r still matches")
        fun multilineDollarOnStandaloneCr() {
            val p = org.safere.Pattern.compile("(?m)$")
            val m = p.matcher("a\rb")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("(?m)$ on standalone \\n still matches")
        fun multilineDollarOnStandaloneLf() {
            val p = org.safere.Pattern.compile("(?m)$")
            val m = p.matcher("a\nb")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("UNIX_LINES: (?m)$ on \\r\\n treats \\n only")
        fun unixLinesMultilineDollarOnCrLf() {
            val p = org.safere.Pattern.compile("(?m)$", org.safere.Pattern.UNIX_LINES)
            val m = p.matcher("a\r\nb")
            Assertions.assertThat(m.find()).isTrue()
            // In UNIX_LINES, only \n is a line terminator; \r is ordinary.
            // $ matches before \n (pos 2) and at end (pos 4).
            Assertions.assertThat(m.start()).isEqualTo(2)
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(4)
            Assertions.assertThat(m.find()).isFalse()
        }

        @Test
        @DisplayName("(?:$|\\n)+ on \\r\\n matches correctly (#78)")
        fun dollarOrNewlineOnCrLf() {
            val p = org.safere.Pattern.compile("(?m)(?:$|\\n)+")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
        }

        @Test
        @DisplayName("(?:$|\\r)+ on \\r\\n matches correctly (#78)")
        fun dollarOrCrOnCrLf() {
            val p = org.safere.Pattern.compile("(?m)(?:$|\\r)+")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
        }

        @Test
        @DisplayName("(?:$|[\\r\\n])+ on \\r\\n matches correctly (#78)")
        fun dollarOrCrLfClassOnCrLf() {
            val p = org.safere.Pattern.compile("(?m)(?:$|[\\r\\n])+")
            val m = p.matcher("\r\n")
            Assertions.assertThat(m.find()).isTrue()
        }
    }

    @Nested
    @DisplayName("Repetition with nullable bodies")
    internal inner class RepetitionWithNullableBodies {
        @Test
        @DisplayName("(?:\\B|a)* stops after the first consuming iteration")
        fun nonWordBoundaryOrCharStar() {
            // Regression for issue #55: JDK breaks a repetition loop after a zero-width body match.
            val p = org.safere.Pattern.compile("(?:\\B|a)*")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("(?:\\B|a)+ stops after the first consuming iteration")
        fun nonWordBoundaryOrCharPlus() {
            val p = org.safere.Pattern.compile("(?:\\B|a)+")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("(?:\\b|a)* terminates on the zero-width word-boundary match")
        fun wordBoundaryOrCharStar() {
            val p = org.safere.Pattern.compile("(?:\\b|a)*")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
        }

        @Test
        @DisplayName("(a|)* can consume before the empty exit match")
        fun consumingOrEmptyStar() {
            val p = org.safere.Pattern.compile("(a|)*")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2)
            Assertions.assertThat(m.start(1)).isEqualTo(2)
            Assertions.assertThat(m.end(1)).isEqualTo(2)
        }

        @Test
        @DisplayName("(a|)+ can consume before the empty exit match")
        fun consumingOrEmptyPlus() {
            val p = org.safere.Pattern.compile("(a|)+")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(2)
        }

        @Test
        @DisplayName("([a]*)* captures the zero-width exit match")
        fun starOfStar() {
            val p = org.safere.Pattern.compile("([a]*)*")
            val m = p.matcher("aaaaaa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(6)
            Assertions.assertThat(m.start(1)).isEqualTo(6)
            Assertions.assertThat(m.end(1)).isEqualTo(6)
        }

        @Test
        @DisplayName("X(.?){2,}Y captures the zero-width exit match")
        fun boundedRepetitionCapture() {
            val p = org.safere.Pattern.compile("X(.?){2,}Y")
            val m = p.matcher("XABCDEFY")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(8)
            Assertions.assertThat(m.start(1)).isEqualTo(7)
            Assertions.assertThat(m.end(1)).isEqualTo(7)
        }

        @Test
        @DisplayName("(?:$)+ finds the zero-width end-of-input match")
        fun dollarPlus() {
            val p = org.safere.Pattern.compile("(?:$)+")
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("(?:^)+ matches zero-width at the start of input")
        fun caretPlus() {
            val p = org.safere.Pattern.compile("(?:^)+")
            val m = p.matcher("a")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
        }

        @Test
        @DisplayName("(?:(?:^)|\\w)* full-matches a word character")
        fun caretOrWordStar() {
            Assertions.assertThat(org.safere.Pattern.matches("(?:(?:^)|\\w)*", "a")).isTrue()
        }

        @Test
        @DisplayName("(a|)*? prefers the empty match")
        fun nonGreedyStarNullable() {
            val p = org.safere.Pattern.compile("(a|)*?")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(0)
        }

        @Test
        @DisplayName("(a|)+? consumes the minimum non-empty match")
        fun nonGreedyPlusNullable() {
            val p = org.safere.Pattern.compile("(a|)+?")
            val m = p.matcher("aa")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(0)
            Assertions.assertThat(m.end()).isEqualTo(1)
        }

        @Test
        @DisplayName("(?:$|\\n)+ matches JDK dollar-newline repetition behavior")
        fun dollarOrNewlinePlus() {
            val p = org.safere.Pattern.compile("(?:$|\\n)+")
            val m = p.matcher("a\n\n")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(1)
            Assertions.assertThat(m.end()).isEqualTo(2)
        }

        @Test
        @DisplayName("$ before trailing newline stops first-match DFA search")
        fun dollarBeforeTrailingNewlineStopsFirstMatchDfaSearch() {
            assertFirstFindMatchesJdk("^(?:\\n|\\n*)$", "\n\n")
            assertFirstFindMatchesJdk("^(?:\\R|\\R*)$", "\r\n\r\n")
            assertFirstFindMatchesJdk("^(?:\\u2028|\\u2028*)$", "\u2028\u2028")
        }
    }

    @Test
    @DisplayName("find() with empty branch before complex char class")
    fun findEmptyBranchBeforeComplexCharClass() {
        val m = org.safere.Pattern.compile("|[\u0166&\u00bfA&&?;]+&?\udb7d\udda6+];]+&?&\u00e9v").matcher("")

        Assertions.assertThat(m.find()).isTrue()
        Assertions.assertThat(m.group()).isEmpty()
        Assertions.assertThat(m.find()).isFalse()
    }

    @Nested
    @DisplayName("Tier 1 Trivial match specializations")
    internal inner class TrivialMatchSpecializationTests {
        @Test
        @DisplayName("exact literal matches and lookingAt on String")
        fun literalStringMatches() {
            val p = org.safere.Pattern.compile("hello")
            val m = p.matcher("hello")

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("hello")
            Assertions.assertThat(m.start(0)).isEqualTo(0)
            Assertions.assertThat(m.end(0)).isEqualTo(5)

            Assertions.assertThat(m.reset("hell").matches()).isFalse()
            Assertions.assertThat(m.reset("hello world").matches()).isFalse()
            Assertions.assertThat(m.reset("hello world").lookingAt()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("hello")
            Assertions.assertThat(m.start(0)).isEqualTo(0)
            Assertions.assertThat(m.end(0)).isEqualTo(5)

            Assertions.assertThat(m.reset("").matches()).isFalse()
            Assertions.assertThat(m.reset("").lookingAt()).isFalse()
        }

        @Test
        @DisplayName("exact literal matches and lookingAt on Utf8Input")
        fun literalUtf8Matches() {
            val p = org.safere.Pattern.compile("hello")
            val exact = Utf8Input.validated("hello".toByteArray(StandardCharsets.UTF_8))
            val prefix = Utf8Input.validated("hello world".toByteArray(StandardCharsets.UTF_8))
            val mismatch = Utf8Input.validated("world".toByteArray(StandardCharsets.UTF_8))

            val mExact = p.matcher(exact)
            Assertions.assertThat(mExact.matches()).isTrue()
            Assertions.assertThat(mExact.start()).isEqualTo(0)
            Assertions.assertThat(mExact.end()).isEqualTo(5)

            val mPrefix = p.matcher(prefix)
            Assertions.assertThat(mPrefix.matches()).isFalse()
            Assertions.assertThat(mPrefix.lookingAt()).isTrue()
            Assertions.assertThat(mPrefix.start()).isEqualTo(0)
            Assertions.assertThat(mPrefix.end()).isEqualTo(5)

            val mMismatch = p.matcher(mismatch)
            Assertions.assertThat(mMismatch.matches()).isFalse()
            Assertions.assertThat(mMismatch.lookingAt()).isFalse()
        }

        @Test
        @DisplayName("empty literal matches empty input")
        fun emptyLiteralMatches() {
            val p = org.safere.Pattern.compile("")
            val m = p.matcher("")

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEmpty()
            Assertions.assertThat(m.start(0)).isEqualTo(0)
            Assertions.assertThat(m.end(0)).isEqualTo(0)

            Assertions.assertThat(m.reset("a").matches()).isFalse()
            Assertions.assertThat(m.reset("a").lookingAt()).isTrue()
        }

        @Test
        @DisplayName("single character class matches BMP characters")
        fun singleCharClassBmp() {
            val p = org.safere.Pattern.compile("[a-z]")
            val m = p.matcher("a")

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("a")
            Assertions.assertThat(m.start(0)).isEqualTo(0)
            Assertions.assertThat(m.end(0)).isEqualTo(1)

            Assertions.assertThat(m.reset("z").matches()).isTrue()
            Assertions.assertThat(m.reset("A").matches()).isFalse()
            Assertions.assertThat(m.reset("").matches()).isFalse()
            Assertions.assertThat(m.reset("ab").matches()).isFalse()
            Assertions.assertThat(m.reset("ab").lookingAt()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("a")
        }

        @Test
        @DisplayName("single character class matches supplementary surrogate pairs")
        fun singleCharClassSupplementary() {
            val p = org.safere.Pattern.compile("[\\uD83D\\uDE00-\\uD83D\\uDE4F]")
            val emoji = "\uD83D\uDE00"
            val m = p.matcher(emoji)

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo(emoji)
            Assertions.assertThat(m.start(0)).isEqualTo(0)
            Assertions.assertThat(m.end(0)).isEqualTo(2)

            Assertions.assertThat(m.reset(emoji + " abc").matches()).isFalse()
            Assertions.assertThat(m.reset(emoji + " abc").lookingAt()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo(emoji)

            Assertions.assertThat(m.reset("a").matches()).isFalse()
            Assertions.assertThat(m.reset("").matches()).isFalse()
        }

        @Test
        @DisplayName("single character class on Utf8Input")
        fun singleCharClassUtf8() {
            val p = org.safere.Pattern.compile("\\d")
            val digit = Utf8Input.validated("7".toByteArray(StandardCharsets.UTF_8))
            val letter = Utf8Input.validated("x".toByteArray(StandardCharsets.UTF_8))
            val multi = Utf8Input.validated("78".toByteArray(StandardCharsets.UTF_8))

            Assertions.assertThat(p.matcher(digit).matches()).isTrue()
            Assertions.assertThat(p.matcher(letter).matches()).isFalse()
            Assertions.assertThat(p.matcher(multi).matches()).isFalse()
            Assertions.assertThat(p.matcher(multi).lookingAt()).isTrue()
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "a", "12", "abc"])
        @DisplayName("rejects strings strictly shorter than minimum match length")
        fun rejectsShortStrings(input: String?) {
            val p = org.safere.Pattern.compile("[0-9]{4,8}")
            val m = p.matcher(input)

            Assertions.assertThat(m.matches()).isFalse()
            Assertions.assertThat(m.lookingAt()).isFalse()
            Assertions.assertThat(m.find()).isFalse()
            Assertions.assertThatThrownBy(ThrowingCallable { m.group(0) })
                .isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        @DisplayName("accepts valid-length strings and matches correctly")
        fun acceptsValidLength() {
            val p = org.safere.Pattern.compile("[0-9]{4,8}")
            val m = p.matcher("1234")

            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("1234")
            Assertions.assertThat(m.start(0)).isEqualTo(0)
            Assertions.assertThat(m.end(0)).isEqualTo(4)

            Assertions.assertThat(m.reset("12345678").matches()).isTrue()
            Assertions.assertThat(m.reset("123456789").matches()).isFalse()
            Assertions.assertThat(m.reset("123456789").lookingAt()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("12345678")
        }

        @Test
        @DisplayName("alternation minimum length lower bound is accurate")
        fun alternationMinLength() {
            val p = org.safere.Pattern.compile("a{5}|b{10}")
            val m = p.matcher("aaaa")

            Assertions.assertThat(m.matches()).isFalse()
            Assertions.assertThat(m.lookingAt()).isFalse()
            Assertions.assertThat(m.find()).isFalse()

            Assertions.assertThat(m.reset("aaaaa").matches()).isTrue()
            Assertions.assertThat(m.reset("bbbbbbbbbb").matches()).isTrue()
        }

        @Test
        @DisplayName("custom region boundaries work with fast paths")
        fun customRegions() {
            val p = org.safere.Pattern.compile("hello")
            val m = p.matcher("---hello---")

            m.region(3, 8)
            Assertions.assertThat(m.matches()).isTrue()
            Assertions.assertThat(m.start(0)).isEqualTo(3)
            Assertions.assertThat(m.end(0)).isEqualTo(8)
            Assertions.assertThat(m.group(0)).isEqualTo("hello")

            m.region(0, 5)
            Assertions.assertThat(m.matches()).isFalse()
        }

        @Test
        @DisplayName("chained matcher operations preserve state correctly")
        fun chainedOperations() {
            val p = org.safere.Pattern.compile("\\d")
            val m = p.matcher("a1b2c")

            Assertions.assertThat(m.matches()).isFalse()
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("1")
            Assertions.assertThat(m.start(0)).isEqualTo(1)

            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.group(0)).isEqualTo("2")
            Assertions.assertThat(m.start(0)).isEqualTo(3)

            Assertions.assertThat(m.find()).isFalse()

            Assertions.assertThat(m.replaceAll("X")).isEqualTo("aXbXc")
        }

        @Test
        @DisplayName("replaceAll and replaceFirst with unused captures produce identical results")
        fun replaceWithUnusedCaptures() {
            val p = org.safere.Pattern.compile("x([a-z]+?)y([0-9]+?)z")
            val m = p.matcher("xabcdey123z and xghy456z")

            Assertions.assertThat(m.replaceAll("REPLACED")).isEqualTo("REPLACED and REPLACED")
            Assertions.assertThat(m.replaceFirst("FIRST")).isEqualTo("FIRST and xghy456z")
            Assertions.assertThat(p.matcher("xabcdey123z and xghy456z").replaceAll("[$0]"))
                .isEqualTo("[xabcdey123z] and [xghy456z]")
            Assertions.assertThat(p.matcher("xabcdey123z and xghy456z").replaceAll("[$1-$2]"))
                .isEqualTo("[abcde-123] and [gh-456]")
        }

        @Test
        @DisplayName("replaceFirst preserves deferred capture resolution for subsequent group queries")
        fun replaceFirstPreservesDeferredCapturesForSubsequentGroupInspection() {
            val p = org.safere.Pattern.compile("x([a-z]+)y([0-9]+)z")
            val m = p.matcher("xabcdey123z and other")

            val result = m.replaceFirst("FIRST")
            Assertions.assertThat(result).isEqualTo("FIRST and other")

            // Invariant: group(1) and group(2) must still resolve accurately on the same Matcher instance
            Assertions.assertThat(m.group(0)).isEqualTo("xabcdey123z")
            Assertions.assertThat(m.group(1)).isEqualTo("abcde")
            Assertions.assertThat(m.group(2)).isEqualTo("123")
            Assertions.assertThat(m.start(1)).isEqualTo(1)
            Assertions.assertThat(m.end(1)).isEqualTo(6)
        }

        @Test
        @DisplayName("split with parenthesized literal delimiters matches correctly")
        fun splitWithParenthesizedLiteralDelimiters() {
            val p = org.safere.Pattern.compile("(delim)")
            val parts = p.split("one delim two delim three")
            Assertions.assertThat<String?>(parts).containsExactly("one ", " two ", " three")
        }

        @Test
        @DisplayName(
            ("case-insensitive multi-character prefix matches across String and handles dense false"
                    + " candidates")
        )
        fun caseInsensitiveMultiCharPrefixStringMatchesAndDenseCandidates() {
            val pattern = "(?i)http_query"
            val input =
                ("http_first http_second http_third "
                        + "HTTP_other HTTP_next "
                        + "hTtP_almost "
                        + "HTTP_QUERY_final")
            val p = org.safere.Pattern.compile(pattern)
            val m = p.matcher(input)

            Assertions.assertThat(m.find()).isTrue()
            val expectedStart = input.indexOf("HTTP_QUERY")
            Assertions.assertThat(m.start()).isEqualTo(expectedStart)
            Assertions.assertThat(m.end()).isEqualTo(expectedStart + "http_query".length)
            Assertions.assertThat(m.find()).isFalse()

            // Dense repetitive candidate noise falling back to KMP
            val noisePattern = "(?i)the_zone"
            val noise = "the_alpha the_beta the_gamma the_delta "
            val denseInput = noise.repeat(5) + "THE_ZONE"
            val p2 = org.safere.Pattern.compile(noisePattern)
            val m2 = p2.matcher(denseInput)

            Assertions.assertThat(m2.find()).isTrue()
            val expectedDenseStart = denseInput.indexOf("THE_ZONE")
            Assertions.assertThat(m2.start()).isEqualTo(expectedDenseStart)
            Assertions.assertThat(m2.end()).isEqualTo(expectedDenseStart + "the_zone".length)
        }

        @Test
        @DisplayName("case-insensitive single char prefix matches correctly")
        fun caseInsensitiveSingleCharPrefix() {
            val p = org.safere.Pattern.compile("(?i)a")
            val m = p.matcher("---A---")
            Assertions.assertThat(m.find()).isTrue()
            Assertions.assertThat(m.start()).isEqualTo(3)
            Assertions.assertThat(m.end()).isEqualTo(4)

            val p2 = org.safere.Pattern.compile("(?i)z")
            val m2 = p2.matcher("xxxZxxx")
            Assertions.assertThat(m2.find()).isTrue()
            Assertions.assertThat(m2.start()).isEqualTo(3)
        }

        @Test
        @DisplayName("split with case-insensitive literals reusing precomputed search state")
        fun splitWithCaseInsensitiveLiteralsReusingPrecomputedSearchState() {
            // Length >= 4 without groups -> LiteralPreparedRunner path
            val p1 = org.safere.Pattern.compile("(?i)DELIM")
            val parts1 = p1.split("one delim two DELIM three DeLiM four")
            Assertions.assertThat<String?>(parts1).containsExactly("one ", " two ", " three ", " four")

            // Length >= 4 with groups -> MatchDescriptor.classHashChain() path
            val p2 = org.safere.Pattern.compile("(?i)(DELIM)")
            val parts2 = p2.split("one delim two DELIM three DeLiM four")
            Assertions.assertThat<String?>(parts2).containsExactly("one ", " two ", " three ", " four")

            // splitAsStream length >= 4 with and without groups
            Assertions.assertThat<String?>(p1.splitAsStream("alphaDELIMbeta").toList()).containsExactly("alpha", "beta")
            Assertions.assertThat<String?>(p2.splitAsStream("alphaDELIMbeta").toList()).containsExactly("alpha", "beta")

            // Short literal (< 4 chars) case-insensitive
            val p3 = org.safere.Pattern.compile("(?i)XYZ")
            val parts3 = p3.split("1xyz2XYZ3xYz4")
            Assertions.assertThat<String?>(parts3).containsExactly("1", "2", "3", "4")

            // Single char case-insensitive
            val p4 = org.safere.Pattern.compile("(?i)X")
            val parts4 = p4.split("1x2X3")
            Assertions.assertThat<String?>(parts4).containsExactly("1", "2", "3")
        }

        @Test
        @DisplayName("citation scrubber pattern matches and replaces correctly across positions")
        fun citationScrubberPatternMatchesAndReplaces() {
            val regex = " ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]"
            val pattern = org.safere.Pattern.compile(regex)
            val jdkPattern = Pattern.compile(regex)

            val inputs = arrayOf<String?>( // Match at index 0 (no leading space)
                "[1.2.3.4] suffix text",
                "［1.2.3.4］ suffix text",  // Match at index 0 (with leading space)
                " [1.2.3.4] suffix text",
                " ［1.2.3.4］ suffix text",  // Match mid-string
                "The cite [1.2.3.4] is here",
                "The cite ［1.2.3.4］ is here",
                "The cite [1.2.3.4, 5.6.7.8] is multiple",
                "The cite ［1.2.3.4, 5.6.7.8］ is multiple",  // Match at end
                "end citation [1.2.3.4]",
                "end citation ［1.2.3.4］",  // Non-matching: brackets without digits or with wrong format
                "[brackets without digits]",
                "［fullwidth without digits］",
                "[1.2] only one dot",
                "［1.2］ only one dot",  // Non-matching: digits and dots without brackets
                "1.2.3.4 version string without brackets",
                "Key facts: launched on December 25, 2021, 1.5 million km",  // Non-matching: plain text
                "plain text with no brackets or digits",  // Mixed matching and non-matching
                "[1.2] but also [1.2.3.4] and ［5.6.7.8］ end"
            )

            for (input in inputs) {
                val matcher = pattern.matcher(input)
                val jdkMatcher = jdkPattern.matcher(input)

                // Differential results against java.util.regex for replaceAll
                Assertions.assertThat(matcher.replaceAll(""))
                    .`as`("replaceAll for '%s'", input)
                    .isEqualTo(jdkMatcher.replaceAll(""))

                // Differential find results
                matcher.reset()
                jdkMatcher.reset()
                while (jdkMatcher.find()) {
                    Assertions.assertThat(matcher.find()).`as`("find() for '%s'", input).isTrue()
                    Assertions.assertThat(matcher.start()).`as`("start() for '%s'", input).isEqualTo(jdkMatcher.start())
                    Assertions.assertThat(matcher.end()).`as`("end() for '%s'", input).isEqualTo(jdkMatcher.end())
                    Assertions.assertThat(matcher.group()).`as`("group() for '%s'", input).isEqualTo(jdkMatcher.group())
                }
                Assertions.assertThat(matcher.find()).`as`("no more matches for '%s'", input).isFalse()
            }
        }

        @Test
        @DisplayName("two-character leading class searches stay correct when a matcher is reused")
        fun smallSetSearchesStayCorrectAcrossMatcherReuse() {
            val regex = "[a\uFF3B][0-9]"
            val pattern = org.safere.Pattern.compile(regex)
            val jdkPattern = Pattern.compile(regex)
            val first = "x1 \uFF3B2 y3 a4 \uFF3B5 z6"
            val second = "a7 b8 a9"

            val matcher = pattern.matcher(first)
            assertSameMatches(matcher, jdkPattern.matcher(first))
            // After exhausting the input, the memo holds "no more occurrences"; a reset must search
            // again.
            matcher.reset()
            assertSameMatches(matcher, jdkPattern.matcher(first))
            matcher.reset(second)
            assertSameMatches(matcher, jdkPattern.matcher(second))

            // Searching backwards from where earlier searches left off.
            matcher.reset(first)
            Assertions.assertThat(matcher.find(12)).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(12)
            Assertions.assertThat(matcher.find(0)).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(3)

            matcher.reset(first).region(4, first.length)
            val jdkRegion = jdkPattern.matcher(first).region(4, first.length)
            assertSameMatches(matcher, jdkRegion)

            // A different pattern queries different chars over the same text.
            matcher.reset(first).usePattern(org.safere.Pattern.compile("[y\uFF3B][0-9]"))
            assertSameMatches(matcher, Pattern.compile("[y\uFF3B][0-9]").matcher(first))
        }

        companion object {
            private fun assertSameMatches(matcher: org.safere.Matcher, jdkMatcher: Matcher) {
                while (jdkMatcher.find()) {
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat(matcher.start()).isEqualTo(jdkMatcher.start())
                    Assertions.assertThat(matcher.end()).isEqualTo(jdkMatcher.end())
                }
                Assertions.assertThat(matcher.find()).isFalse()
            }
        }
    }

    companion object {
        private val PERFORMANCE_SCENARIO_TIMEOUT: Duration = Duration.ofSeconds(30)

        private fun assertZeroCountGroupBehavior(regex: String, input: String) {
            val jdk = Pattern.compile(regex).matcher(input)
            val safere = org.safere.Pattern.compile(regex).matcher(input)

            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            Assertions.assertThat(safere.matches()).isEqualTo(jdk.matches())
            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            for (group in 1..jdk.groupCount()) {
                Assertions.assertThat(safere.group(group))
                    .`as`("group(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.group(group))
                Assertions.assertThat(safere.start(group))
                    .`as`("start(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.start(group))
                Assertions.assertThat(safere.end(group))
                    .`as`("end(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.end(group))
            }
        }

        private fun assertFirstFindMatchesJdk(regex: String, input: String) {
            val jdk = Pattern.compile(regex).matcher(input)
            val safere = org.safere.Pattern.compile(regex).matcher(input)

            Assertions.assertThat(safere.find()).isEqualTo(jdk.find())
            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            Assertions.assertThat(safere.group()).isEqualTo(jdk.group())
            Assertions.assertThat(safere.start()).isEqualTo(jdk.start())
            Assertions.assertThat(safere.end()).isEqualTo(jdk.end())
            for (group in 1..jdk.groupCount()) {
                Assertions.assertThat(safere.group(group))
                    .`as`("group(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.group(group))
                Assertions.assertThat(safere.start(group))
                    .`as`("start(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.start(group))
                Assertions.assertThat(safere.end(group))
                    .`as`("end(%d) for /%s/ on %s", group, regex, input)
                    .isEqualTo(jdk.end(group))
            }
        }

        private fun assertLatestRepeatedCapture(regex: String?, input: String?) {
            for (fullMatch in booleanArrayOf(false, true)) {
                val matcher = org.safere.Pattern.compile(regex).matcher(input)
                Assertions.assertThat(if (fullMatch) matcher.matches() else matcher.find()).`as`("/%s/", regex).isTrue()
                Assertions.assertThat(matcher.start(1)).isEqualTo(matcher.end() - 1)
                Assertions.assertThat(matcher.end(1)).isEqualTo(matcher.end())
                Assertions.assertThat(matcher.group(1)).isEqualTo("a")
            }
        }

        private fun repeatedDotStarCapturePattern(repetitions: Int, captures: Int): String {
            val pattern = StringBuilder("(")
            for (i in 0..<captures) {
                pattern.append("(.*").append(('A'.code + i).toChar()).append(")")
            }
            pattern.append("){").append(repetitions).append(",}")
            return pattern.toString()
        }

        private fun repeatedDotStarCaptureInput(repetitions: Int, captures: Int): String {
            val input = StringBuilder()
            for (i in 0..<repetitions) {
                for (j in 0..<captures) {
                    input.append(('x'.code + j).toChar()).append(('A'.code + j).toChar())
                }
            }
            return input.toString()
        }

        private fun assertCompletesWithinPerformanceTimeout(task: Runnable) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                PERFORMANCE_SCENARIO_TIMEOUT,
                Executable { task.run() })
        }
    }
}
