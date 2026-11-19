// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.safere.Nfa
import java.nio.charset.StandardCharsets
import java.util.stream.Stream
import kotlin.math.max
import kotlin.math.min

/**
 * Tests for [BitState].
 * 
 * 
 * Tests verify that BitState produces the same results as the NFA for both anchored and
 * unanchored searches across a variety of patterns and inputs.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class BitStateTest {
    @Test
    fun greedyRuneLoopsPreserveExitPriorityAndCaptureRollback() {
        for (body in mutableListOf<String>("a", "[a-z]", "[a-z0-9_]", "[é😀]", "(?i:a)")) {
            for (suffix in mutableListOf<String>("(a|ab)", "(ab|a)", "(a?)b", "b")) {
                for (quantifier in mutableListOf<String>("*", "+", "*?")) {
                    val regex = "(" + body + quantifier + ")" + suffix
                    for (input in mutableListOf<String>("", "a", "aab", "aaaab", "aaaac", "é😀b", "AAAAab")) {
                        assertConsistentAnchored(regex, input, false, false)
                        assertConsistentAnchored(regex, input, true, false)
                        assertConsistentAnchored(regex, input, true, true)
                        assertConsistentUnanchored(regex, input)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("greedyRuneLoopWorkCases")
    @Tag("work-counter")
    fun greedyRuneLoopsChargeTheSameWorkAsTheInterpreter(testCase: TestCase) {
        val prog = Pattern.compile(testCase.pattern).prog()
        Assertions.assertThat<Inst?>(prog.greedyRunes()).isNotNull()
        val text = testCase.input
        for (scanner in java.util.List.of<InputScanner?>(
            StringInputScanner(text), Utf8InputScanner(
                text.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        )) {
            for (anchored in mutableListOf<Boolean>(false, true)) {
                val length = scanner.length
                val ncap = 2 * prog.numCaptures()
                val fused = BitState.getOrCreate(null, prog, scanner, 0, length, ncap, false, false)
                val interpreter = BitState(prog, scanner, 0, length, ncap, false, false, null)

                val expected = arrayOfNulls<IntArray>(1)
                val interpreterWork =
                    WorkCounter.countForTesting(
                        Runnable { expected[0] = interpreter.doSearch(0, length, anchored!!) })
                val fusedWork =
                    WorkCounter.countForTesting(
                        Runnable {
                            Assertions.assertThat(fused.doSearch(0, length, anchored!!))
                                .`as`("%s, anchored=%s", scanner.javaClass.getSimpleName(), anchored)
                                .isEqualTo(expected[0])
                        })
                Assertions.assertThat(fusedWork)
                    .`as`("%s, anchored=%s", scanner.javaClass.getSimpleName(), anchored)
                    .isEqualTo(interpreterWork)
                Assertions.assertThat(fused.budgetExceeded()).isEqualTo(interpreter.budgetExceeded())
            }
        }
    }

    @Test
    fun lazyCharacterLoopsPreserveCapturesAcrossFailedExitsAndStarts() {
        for (regex in mutableListOf<String>(
            "(.*?)z",
            "(.*?)(z)",
            "x([ab]*?)z",
            "(a*?)b",
            "((.*?)z)+",
            "(?i:a*?)b",
            "(.*?)\\bz",
            "([a-z]*)(.*?)(z)",
            "([a-z]*?)([a-z]*)(z)",
            "(?:([a-z]*)(.*?)(z)|([a-z]*?)([a-z]*)(x))",
            "(a.*?)z|x(.*?)z"
        )) {
            val prog = Pattern.compile(regex).prog()
            for (input in java.util.List.of<String?>(
                "",
                "z",
                "aaab",
                "xabzqz",
                "aazabz",
                "AAAb",
                "aaaa\nz",
                "é😀az",
                "zz",
                "x" + "a".repeat(100) + "z",
                "a".repeat(100)
            )) {
                for (scanner in java.util.List.of<InputScanner?>(
                    StringInputScanner(input), Utf8InputScanner(
                        input.toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )
                )) {
                    for (anchored in mutableListOf<Boolean>(false, true)) {
                        for (kind in Nfa.MatchKind.entries.toTypedArray()) {
                            val expected =
                                Nfa.search(
                                    prog,
                                    scanner,
                                    0,
                                    scanner.length,
                                    scanner.length,
                                    0,
                                    if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED,
                                    kind,
                                    prog.numCaptures(),
                                    null
                                )
                            val engine =
                                BitState.getOrCreate(
                                    null,
                                    prog,
                                    scanner,
                                    0,
                                    scanner.length,
                                    2 * prog.numCaptures(),
                                    kind != Nfa.MatchKind.FIRST_MATCH,
                                    kind == Nfa.MatchKind.FULL_MATCH
                                )
                            val actual =
                                engine.doSearch(
                                    0, scanner.length, anchored || kind == Nfa.MatchKind.FULL_MATCH
                                )
                            if (engine.budgetExceeded()) {
                                // Overlapping greedy/lazy loops can exhaust this bounded engine in longest mode.
                                // The caller must use NFA; no partial capture vector may escape.
                                Assertions.assertThat(actual).isNull()
                            } else {
                                assertCapturesEqual(regex, input, actual, expected)
                            }
                        }
                    }
                }
            }
        }
        val prog = Pattern.compile("(.*?)z").prog()
        Assertions.assertThat<LazyRuneLoop?>(prog.lazyRuneLoops()).isNotEmpty()
        Assertions.assertThat<LazyRuneLoop?>(prog.lazyRuneLoops()).isSameAs(prog.lazyRuneLoops())
    }

    @Test
    fun fusionMetadataIsSharedWhenInputGrowthRequiresANewWorkspace() {
        val regex = "(?:(a)b(c)|a(b)d|)+"
        val prog = Pattern.compile(regex).prog()
        val small = BitState.getOrCreate(null, prog, "abc", 0, 3, 2, false, false)
        Assertions.assertThat(small.doSearch(0, 3, true)).containsExactly(0, 3)
        val captures = prog.captureRunes()
        Assertions.assertThat<CaptureRune?>(captures).isNotEmpty()

        val input = "abcabd".repeat(32)
        val grown =
            BitState.getOrCreate(
                small, prog, input, 0, input.length, 2 * prog.numCaptures(), false, false
            )
        Assertions.assertThat<BitState?>(grown).isNotSameAs(small)
        Assertions.assertThat<CaptureRune?>(prog.captureRunes()).isSameAs(captures)
        val expected =
            Nfa.search(prog, input, Nfa.Anchor.ANCHORED, Nfa.MatchKind.FULL_MATCH, prog.numCaptures())
        assertCapturesEqual(regex, input, grown.doSearch(0, input.length, true), expected)
    }

    @Test
    fun unreachableCaptureAlternativesDoNotAffectFusion() {
        for (alternatives in mutableListOf<String>("(a)|(b)", "(a)|(b)|(c)|(d)|(e)", "((a)|(b))", "(é)|(😀)")) {
            for (impossible in mutableListOf<String>("[^\\s\\S]", "[a&&b]")) {
                val deadBranch = "(?:" + alternatives + ")" + impossible
                for (regex in java.util.List.of<String?>(deadBranch, deadBranch + "|x|xx", "x|xx|" + deadBranch)) {
                    for (input in mutableListOf<String>("", "ab", "xxy", "é😀")) {
                        assertConsistentAnchored(regex, input, false, false)
                        assertConsistentAnchored(regex, input, true, true)
                        assertConsistentUnanchored(regex, input)
                    }
                }
            }
        }
    }

    @Test
    fun capturesAndLoopRegistersRollBackAcrossNestedRepeatAlternatives() {
        for (atom in mutableListOf<String>("(a)", "((a))", "(a|aa)", "(é|😀)")) {
            for (inner in mutableListOf<String>("{0,2}", "{1,2}", "+?")) {
                for (outer in mutableListOf<String>("*", "+?")) {
                    for (suffix in mutableListOf<String>("b", "ab")) {
                        val regex = "(?:" + atom + inner + ")" + outer + suffix
                        for (input in mutableListOf<String>(
                            "",
                            "a",
                            "ab",
                            "aab",
                            "aaaab",
                            "aaaaab",
                            "aaac",
                            "é😀b",
                            "é😀ab"
                        )) {
                            assertConsistentAnchored(regex, input, false, false)
                            assertConsistentAnchored(regex, input, true, false)
                            assertConsistentAnchored(regex, input, true, true)
                            assertConsistentUnanchored(regex, input)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nullableLoopEntriesPreservePriorityAndRollbackAcrossSearchModes() {
        for (atom in mutableListOf<String>("(a?)", "((a)?)", "(a??)", "(?:()|a)", "(?:(é)|😀)?")) {
            for (inner in mutableListOf<String>("{0,2}", "{1,3}", "{2,3}", "{0,2}?", "+?")) {
                for (outer in mutableListOf<String>("*", "+", "*?", "+?")) {
                    val regex = "(?:(?:" + atom + ")" + inner + ")" + outer + "(?:ab|b)"
                    val prog = Pattern.compile(regex).prog()
                    for (input in mutableListOf<String>("", "b", "ab", "aab", "aaaab", "aaaaac", "é😀ab")) {
                        for (scanner in java.util.List.of<InputScanner?>(
                            StringInputScanner(input), Utf8InputScanner(input!!.toByteArray(StandardCharsets.UTF_8))
                        )) {
                            for (groups in intArrayOf(0, 1, prog.numCaptures())) {
                                for (kind in Nfa.MatchKind.entries.toTypedArray()) {
                                    for (anchor in Nfa.Anchor.entries.toTypedArray()) {
                                        val expected =
                                            Nfa.search(
                                                prog,
                                                scanner,
                                                0,
                                                scanner.length,
                                                scanner.length,
                                                0,
                                                anchor,
                                                kind,
                                                groups,
                                                null
                                            )
                                        val engine =
                                            BitState.getOrCreate(
                                                null,
                                                prog,
                                                scanner,
                                                0,
                                                scanner.length,
                                                2 * max(groups, 1),
                                                kind != Nfa.MatchKind.FIRST_MATCH,
                                                kind == Nfa.MatchKind.FULL_MATCH
                                            )
                                        val actual =
                                            engine.doSearch(
                                                0,
                                                scanner.length,
                                                anchor == Nfa.Anchor.ANCHORED || kind == Nfa.MatchKind.FULL_MATCH
                                            )
                                        if (engine.budgetExceeded()) {
                                            // An exhausted bounded search must delegate to NFA without a partial result.
                                            Assertions.assertThat(actual).isNull()
                                            continue
                                        }
                                        Assertions.assertThat(actual == null)
                                            .`as`(
                                                "/%s/ on %s, groups=%s, %s/%s, %s",
                                                regex, input, groups, anchor, kind, scanner.javaClass.getSimpleName()
                                            )
                                            .isEqualTo(expected == null)
                                        // With no captures requested, NFA returns an empty vector while BitState
                                        // retains its internal group-zero workspace. Only success is promised.
                                        if (groups > 0 && actual != null) {
                                            Assertions.assertThat(actual).isEqualTo(expected)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun captureBoundariesAndPartialCaptureTrackingSurviveFailedAlternatives() {
        for (regex in mutableListOf<String>(
            "(a)b(c)",
            "(a)b(c)|ab(d)",
            "(a)b()c",
            "((a))b(c)",
            "(é).(😀)",
            "(?i:(a)b(c))"
        )) {
            val prog = Compiler.compile(Parser.parse(regex, FLAGS)!!)!!
            for (input in mutableListOf<String>("", "ab", "abc", "abd", "abcc", "ABc", "éa😀", "é😀")) {
                for (submatches in 0..prog.numCaptures()) {
                    for (full in booleanArrayOf(false, true)) {
                        val result = BitState.search(prog, input, true, false, full, submatches)
                        val expected =
                            Nfa.search(
                                prog,
                                input,
                                Nfa.Anchor.ANCHORED,
                                if (full) Nfa.MatchKind.FULL_MATCH else Nfa.MatchKind.FIRST_MATCH,
                                submatches
                            )
                        assertCapturesEqual(regex, input, result, expected)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Basic tests
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Basic matching")
    internal inner class Basic {
        @Test
        fun literalMatch() {
            assertConsistentAnchored("abc", "abc", false, true)
        }

        @Test
        fun literalNoMatch() {
            assertConsistentAnchored("abc", "xyz", false, true)
        }

        @Test
        fun fullMatch() {
            assertConsistentAnchored("abc", "abc", false, true)
            assertConsistentAnchored("abc", "abcd", false, true)
        }

        @Test
        fun prefixMatch() {
            assertConsistentAnchored("abc", "abcdef", false, false)
            assertConsistentAnchored("abc", "xyz", false, false)
        }

        @Test
        fun emptyMatch() {
            assertConsistentAnchored("", "", false, false)
            assertConsistentAnchored("", "abc", false, false)
        }

        @Test
        fun unanchoredSearch() {
            assertConsistentUnanchored("abc", "xyzabcdef")
            assertConsistentUnanchored("abc", "xyz")
        }
    }

    // ---------------------------------------------------------------------------
    // Capture groups
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Capture groups")
    internal inner class CaptureGroups {
        @Test
        fun singleGroup() {
            assertConsistentAnchored("(abc)", "abc", false, true)
        }

        @Test
        fun twoGroups() {
            assertConsistentAnchored("(\\d+)-(\\d+)", "123-456", false, true)
        }

        @Test
        fun nestedGroups() {
            assertConsistentAnchored("(a(b(c)))", "abc", false, true)
        }

        @Test
        fun manyGroups() {
            assertConsistentAnchored("(a)(b)(c)(d)(e)(f)(g)", "abcdefg", false, true)
        }

        @Test
        fun optionalGroup() {
            assertConsistentAnchored("(a)?(b)", "b", false, false)
            assertConsistentAnchored("(a)?(b)", "ab", false, false)
        }

        @Test
        fun unanchoredCaptures() {
            assertConsistentUnanchored("(\\w+)@(\\w+)", "user@host")
            assertConsistentUnanchored("(\\d+)", "abc123def")
        }
    }

    // ---------------------------------------------------------------------------
    // Quantifiers
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Quantifiers")
    internal inner class Quantifiers {
        @Test
        fun star() {
            assertConsistentAnchored("a*", "aaa", false, false)
            assertConsistentAnchored("a*", "", false, false)
            assertConsistentAnchored("a*b", "aaab", false, true)
        }

        @Test
        fun plus() {
            assertConsistentAnchored("a+", "aaa", false, false)
            assertConsistentAnchored("a+", "", false, false)
        }

        @Test
        fun question() {
            assertConsistentAnchored("a?b", "ab", false, true)
            assertConsistentAnchored("a?b", "b", false, true)
        }

        @Test
        fun repetition() {
            assertConsistentAnchored("a{3}", "aaa", false, true)
            assertConsistentAnchored("a{2,4}", "aaaa", false, true)
        }
    }

    // ---------------------------------------------------------------------------
    // Alternation
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Alternation")
    internal inner class Alternation {
        @Test
        fun simpleAlt() {
            assertConsistentAnchored("cat|dog", "cat", false, true)
            assertConsistentAnchored("cat|dog", "dog", false, true)
            assertConsistentAnchored("cat|dog", "cow", false, true)
        }

        @Test
        fun altWithGroups() {
            assertConsistentAnchored("(cat|dog)", "dog", false, true)
            assertConsistentAnchored("(a|b)(c|d)", "bc", false, true)
        }

        @Test
        fun unanchoredAlt() {
            assertConsistentUnanchored("cat|dog", "I have a cat")
            assertConsistentUnanchored("cat|dog", "I have a dog")
        }
    }

    // ---------------------------------------------------------------------------
    // Anchors and assertions
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Anchors and assertions")
    internal inner class Anchors {
        @Test
        fun wordBoundary() {
            assertConsistentUnanchored("\\bfoo\\b", "foo bar")
            assertConsistentUnanchored("\\bfoo\\b", "foobar")
        }

        @Test
        fun beginEnd() {
            assertConsistentAnchored("^abc$", "abc", false, true)
            assertConsistentAnchored("^abc$", "abcd", false, true)
        }
    }

    // ---------------------------------------------------------------------------
    // Budget / text size limit
    // ---------------------------------------------------------------------------
    @Nested
    @DisplayName("Budget")
    internal inner class Budget {
        @Test
        fun cachedWorkspaceGrowsCapturesWithoutDiscardingSearchBuffers() {
            for (regex in arrayOf<String>("(a?){20}a{20}", "((a)(a?)){2}", "((a)|b)+")) {
                val prog = Compiler.compile(Parser.parse(regex, FLAGS)!!)!!
                val text = "a".repeat(1024)
                val fullNcap = 2 * prog.numCaptures()
                val cached = BitState.getOrCreate(null, prog, text, 0, text.length, 2, false, false)
                for (iteration in 0..2) {
                    val bounds =
                        BitState.getOrCreate(cached, prog, text, 0, text.length, 2, false, false)
                    Assertions.assertThat<BitState?>(bounds).isSameAs(cached)
                    val match = bounds.doSearch(0, text.length, false)!!
                    Assertions.assertThat(match).isNotNull()
                    val captures =
                        BitState.getOrCreate(cached, prog, text, match[0], match[1], fullNcap, false, false)
                    Assertions.assertThat<BitState?>(captures).`as`("capture growth for /%s/", regex).isSameAs(cached)
                    Assertions.assertThat(captures.doSearch(match[0], match[1], true))
                        .isEqualTo(
                            Nfa.search(
                                prog,
                                text,
                                Nfa.Anchor.UNANCHORED,
                                Nfa.MatchKind.FIRST_MATCH,
                                prog.numCaptures()
                            )
                        )
                }
            }
        }

        @Test
        fun tooLargeReturnsNull() {
            val re = Parser.parse("abc", FLAGS)!!
            val prog = Compiler.compile(re)!!
            // Create a text larger than the max BitState budget.
            val maxLen = BitState.maxTextSize(prog)
            if (maxLen > 0) {
                val largeText = "a".repeat(maxLen + 1)
                val result = BitState.search(prog, largeText, false, false, false, 1)
                Assertions.assertThat(result).isNull()
            }
        }

        @Test
        fun withinBudgetWorks() {
            val re = Parser.parse("abc", FLAGS)!!
            val prog = Compiler.compile(re)!!
            val maxLen = BitState.maxTextSize(prog)
            if (maxLen > 3) {
                val result = BitState.search(prog, "abc", true, false, true, 1)
                Assertions.assertThat(result).isNotNull()
                Assertions.assertThat(result!![0]).isEqualTo(0)
                Assertions.assertThat(result[1]).isEqualTo(3)
            }
        }

        @Test
        fun workBudgetDependsOnSearchedRangeNotAbsoluteOffset() {
            val re = Parser.parse("a+", FLAGS)!!
            val prog = Compiler.compile(re)!!
            val ncap = 2 * max(prog.numCaptures(), 1)
            val suffix = "aaaa"
            val nearStart =
                BitState.getOrCreate(null, prog, suffix, 0, suffix.length, ncap, false, false)
            nearStart.doSearch(0, suffix.length, true)

            val prefix = "x".repeat(10000)
            val shiftedText = prefix + suffix
            val shiftedStart = prefix.length
            val shiftedEnd = shiftedText.length
            val shifted =
                BitState.getOrCreate(
                    null, prog, shiftedText, shiftedStart, shiftedEnd, ncap, false, false
                )
            shifted.doSearch(shiftedStart, shiftedEnd, true)

            Assertions.assertThat(shifted.stepBudgetForTesting()).isEqualTo(nearStart.stepBudgetForTesting())
        }

        @Test
        fun failedStartsPruneLaterStartsSoGreedyRunsStayLinear() {
            // Without cross-start pruning every start position re-walks the whole run of 'a's, which is
            // quadratic and exhausts the work budget long before the last start is tried.
            val re = Parser.parse("(a*)b", FLAGS)!!
            val prog = Compiler.compile(re)!!
            val ncap = 2 * max(prog.numCaptures(), 1)
            val text = "a".repeat(20000)
            Assertions.assertThat(text.length).isLessThanOrEqualTo(BitState.maxTextSize(prog))

            val bs = BitState.getOrCreate(null, prog, text, 0, text.length, ncap, false, false)

            Assertions.assertThat(bs.doSearch(0, text.length, false)).isNull()
            Assertions.assertThat(bs.budgetExceeded()).isFalse()
        }

        @Test
        fun reusedInstanceStaysCorrectAcrossAnchoredAndUnanchoredSearches() {
            // The dead-pruning bitmap is allocated lazily on the first unanchored search. Exercise a
            // single cached instance through anchored, then unanchored, then anchored again to make sure
            // that lazy allocation and the reset() bookkeeping around it don't corrupt reused state.
            val re = Parser.parse("(a*)b", FLAGS)!!
            val prog = Compiler.compile(re)!!
            val ncap = 2 * max(prog.numCaptures(), 1)
            val text = "xxxaaab"

            var bs = BitState.getOrCreate(null, prog, text, 3, text.length, ncap, false, false)
            Assertions.assertThat(bs.doSearch(3, text.length, true)).containsExactly(3, 7, 3, 6)

            bs = BitState.getOrCreate(bs, prog, text, 0, text.length, ncap, false, false)
            Assertions.assertThat(bs.doSearch(0, text.length, false)).containsExactly(3, 7, 3, 6)

            bs = BitState.getOrCreate(bs, prog, text, 3, text.length, ncap, false, false)
            Assertions.assertThat(bs.doSearch(3, text.length, true)).containsExactly(3, 7, 3, 6)
        }

        @Test
        fun prunedStartsDoNotHideALaterLeftmostMatch() {
            val re = Parser.parse("(a*)c", FLAGS)!!
            val prog = Compiler.compile(re)!!
            // Start 0 walks "aaa", fails at 'b', and marks those positions dead; the match begins at 5.
            val result = BitState.search(prog, "aaabbaac", false, false, false, prog.numCaptures())

            Assertions.assertThat(result).containsExactly(5, 8, 5, 7)
        }

        @Test
        fun pruningStaysExactAcrossProgressCheckLoops() {
            // Nullable loop bodies compile to PROGRESS_CHECK, whose behavior depends on a loop register.
            // Failed starts must still be safe to prune; compare against the NFA on inputs where the
            // leftmost match begins after several pruned starts.
            val patterns = arrayOf<String?>(
                "(a*)+c", "(?:a?)+b(c|d)", "(a*)*b", "(?:(a*)+x)*y", "((a*)+b)+c", "(a|b*)+c", "(x*)+(a*)*b"
            )
            val texts = arrayOf<String?>(
                "aaabbaac", "aabaad", "aaaxaaab", "aaxaayxy", "aabaabc", "abbaaccab", "xxaaxbxb", "aaaa"
            )
            for (pattern in patterns) {
                for (text in texts) {
                    assertConsistentUnanchored(pattern, text)
                    assertConsistentUnanchored(pattern, text!!.repeat(40))
                }
            }
        }

        @Test
        fun pruningActivatesMidStartAndPreservesReuseAcrossShortAndBoundedSearches() {
            val re = Parser.parse("((?:.+?)*)c", FLAGS)!!
            val prog = Compiler.compile(re)!!
            val ncap = 2 * max(prog.numCaptures(), 1)

            // Short unanchored search finishes below PRUNE_ACTIVATION_STEPS.
            val shortText = "abbc"
            var bs =
                BitState.getOrCreate(null, prog, shortText, 0, shortText.length, ncap, false, false)
            Assertions.assertThat(bs.doSearch(0, shortText.length, false))
                .isEqualTo(
                    Nfa.search(
                        prog, shortText, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FIRST_MATCH, ncap / 2
                    )
                )

            // Long unanchored search crosses PRUNE_ACTIVATION_STEPS mid-start and fails.
            val missText = "a".repeat(120)
            bs = BitState.getOrCreate(bs, prog, missText, 0, missText.length, ncap, false, false)
            Assertions.assertThat(bs.doSearch(0, missText.length, false)).isNull()

            // Bounded single-start search (startPos == searchLimit, anchored == false, as used by
            // preselected starts in Matcher) exceeds PRUNE_ACTIVATION_STEPS while revisiting shared ALTs.
            val singleStartText = "xx" + "a".repeat(80) + "c"
            bs =
                BitState.getOrCreate(
                    bs, prog, singleStartText, 2, singleStartText.length, ncap, false, false
                )
            Assertions.assertThat(bs.doSearch(2, 2, false)).containsExactly(2, 83, 2, 82)

            // Reusing again for an unanchored search that matches after crossing the threshold.
            val laterHitText = "b".repeat(80) + "xac"
            val greedyRe = Parser.parse("(a*)c", FLAGS)!!
            val greedyProg = Compiler.compile(greedyRe)!!
            val greedyBs =
                BitState.getOrCreate(
                    null,
                    greedyProg,
                    laterHitText,
                    0,
                    laterHitText.length,
                    2 * greedyProg.numCaptures(),
                    false,
                    false
                )
            Assertions.assertThat(greedyBs.doSearch(0, laterHitText.length, false))
                .containsExactly(81, 83, 81, 82)
        }

        @Test
        fun epsilonCycleAltsRemainExactAcrossPruningThresholdTransitions() {
            val patterns = arrayOf<String?>("(a*)*c", "((?:a?)*)c", "(?:b|(a*)*)+c")
            for (pattern in patterns) {
                assertConsistentUnanchored(pattern, "bbbbaac")
                assertConsistentUnanchored(pattern, "b".repeat(80) + "aac")
                assertConsistentUnanchored(pattern, "a".repeat(80))
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Systematic consistency (parameterized)
    // ---------------------------------------------------------------------------
    @JvmRecord
    internal data class TestCase(val pattern: String, val input: String) {
        override fun toString(): String {
            return "/" + pattern + "/ on \"" + input + "\""
        }
    }

    @JvmRecord
    internal data class ResultBufferCase(
        val pattern: String,
        val input: String?,
        val anchored: Boolean,
        val longest: Boolean,
        val endMatch: Boolean
    ) {
        override fun toString(): String {
            return "/" + pattern + "/ on \"" + input + "\""
        }
    }

    @ParameterizedTest(name = "unanchored: {0}")
    @MethodSource("consistencyCases")
    fun unanchoredConsistency(tc: TestCase) {
        assertConsistentUnanchored(tc.pattern, tc.input)
    }

    @ParameterizedTest(name = "anchored fullMatch: {0}")
    @MethodSource("consistencyCases")
    fun anchoredFullMatchConsistency(tc: TestCase) {
        assertConsistentAnchored(tc.pattern, tc.input, false, true)
    }

    @ParameterizedTest(name = "reusable result: {0}")
    @MethodSource("resultBufferCases")
    fun reusableResultBufferPreservesCaptureSemantics(tc: ResultBufferCase) {
        assertReusableResultConsistent(tc)
    }

    companion object {
        private val FLAGS =
            ParseFlags.PERL_X or ParseFlags.PERL_CLASSES or ParseFlags.PERL_B or ParseFlags.UNICODE_GROUPS

        @JvmStatic
        private fun greedyRuneLoopWorkCases(): Stream<TestCase?> {
            return Stream.of<TestCase?>(
                TestCase("(a*)(a|ab)", "a".repeat(128) + "b"),
                TestCase("([é😀]*)(😀b|b)", "é😀".repeat(64) + "b"),
                TestCase("(a+)(ab|c)", "a".repeat(128) + "xaaaab")
            )
        }

        /** Asserts BitState and NFA agree on an anchored search.  */
        private fun assertConsistentAnchored(
            pattern: String?, text: String?, longest: Boolean, endMatch: Boolean
        ) {
            val re = Parser.parse(pattern, FLAGS)!!
            val prog = Compiler.compile(re)!!
            val nsubmatch = prog.numCaptures()

            val bsResult = BitState.search(prog, text!!, true, longest, endMatch, nsubmatch)
            val kind =
                if (endMatch)
                    Nfa.MatchKind.FULL_MATCH
                else
                    (if (longest) Nfa.MatchKind.LONGEST_MATCH else Nfa.MatchKind.FIRST_MATCH)
            val nfaResult = Nfa.search(prog, text!!, Nfa.Anchor.ANCHORED, kind, nsubmatch)

            assertCapturesEqual(pattern, text, bsResult, nfaResult)
        }

        /** Asserts BitState and NFA agree on an unanchored search.  */
        private fun assertConsistentUnanchored(pattern: String?, text: String?) {
            val re = Parser.parse(pattern, FLAGS)!!
            val prog = Compiler.compile(re)!!
            val nsubmatch = prog.numCaptures()

            val bsResult = BitState.search(prog, text!!, false, false, false, nsubmatch)
            val nfaResult =
                Nfa.search(prog, text!!, Nfa.Anchor.UNANCHORED, Nfa.MatchKind.FIRST_MATCH, nsubmatch)

            assertCapturesEqual(pattern, text, bsResult, nfaResult)
        }

        private fun assertCapturesEqual(
            pattern: String?, text: String?, bsResult: IntArray?, nfaResult: IntArray?
        ) {
            if (nfaResult == null) {
                Assertions.assertThat(bsResult)
                    .`as`("/%s/ on \"%s\": NFA=null, BitState should too", pattern, text)
                    .isNull()
            } else {
                Assertions.assertThat(bsResult)
                    .`as`("/%s/ on \"%s\": NFA matched, BitState should too", pattern, text)
                    .isNotNull()
                val len = min(bsResult!!.size, nfaResult.size)
                for (i in 0..<len) {
                    Assertions.assertThat(bsResult[i])
                        .`as`("/%s/ on \"%s\": cap[%d]", pattern, text, i)
                        .isEqualTo(nfaResult[i])
                }
            }
        }

        private fun assertReusableResultConsistent(tc: ResultBufferCase) {
            val re = Parser.parse(tc.pattern, FLAGS)!!
            val prog = Compiler.compile(re)!!
            val nsubmatch = prog.numCaptures()
            val ncap = 2 * max(nsubmatch, 1)
            val resultBuffer = IntArray(ncap + 2)
            for (i in resultBuffer.indices) {
                resultBuffer[i] = -1000 - i
            }

            val bsResult =
                BitState.search(
                    null,
                    prog,
                    tc.input,
                    0,
                    tc.input!!.length,
                    tc.anchored,
                    tc.longest,
                    tc.endMatch,
                    nsubmatch,
                    resultBuffer
                )
            val anchor = if (tc.anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED
            val kind =
                if (tc.endMatch)
                    Nfa.MatchKind.FULL_MATCH
                else
                    (if (tc.longest) Nfa.MatchKind.LONGEST_MATCH else Nfa.MatchKind.FIRST_MATCH)
            val nfaResult = Nfa.search(prog, tc.input, anchor, kind, nsubmatch)

            Assertions.assertThat(bsResult)
                .`as`("BitState should return the reusable result buffer for %s", tc)
                .isSameAs(resultBuffer)
            assertCapturesEqual(tc.pattern, tc.input, bsResult, nfaResult)
            Assertions.assertThat(resultBuffer[ncap])
                .`as`("BitState should not write past ncap for %s", tc)
                .isEqualTo(-1000 - ncap)
        }

        @JvmStatic
        fun consistencyCases(): Stream<TestCase?> {
            val cases: MutableList<TestCase?> = ArrayList<TestCase?>()
            cases.add(TestCase("abc", "abc"))
            cases.add(TestCase("abc", "xabcy"))
            cases.add(TestCase("abc", "def"))
            cases.add(TestCase("a+", "aaa"))
            cases.add(TestCase("a*", ""))
            cases.add(TestCase("a*b", "aaab"))
            cases.add(TestCase("(a|b)+", "abba"))
            cases.add(TestCase("(\\d+)-(\\w+)", "123-abc"))
            cases.add(TestCase("(\\d+)-(\\w+)", "nope"))
            cases.add(TestCase("[a-z]+@[a-z]+", "user@host"))
            cases.add(TestCase("\\bfoo\\b", "foo bar"))
            cases.add(TestCase("\\bfoo\\b", "foobar"))
            cases.add(TestCase("(\\bfoo\\b)", "foo bar"))
            cases.add(TestCase("(\\Bfoo\\B)", "afoob"))
            cases.add(TestCase("\\b(cat|dog|bird)\\b", "The quick brown cat and the lazy dog"))
            cases.add(TestCase("(a(b)c)", "abc"))
            cases.add(TestCase("x(y|z)w", "xyw"))
            cases.add(TestCase("(.*)x", "abcx"))
            cases.add(TestCase("a{2,4}", "aaaa"))
            cases.add(TestCase("(?i)abc", "ABC"))
            cases.add(TestCase("caf\u00e9", "caf\u00e9"))
            return cases.stream()
        }

        @JvmStatic
        fun resultBufferCases(): Stream<ResultBufferCase?> {
            return Stream.of<ResultBufferCase?>(
                ResultBufferCase("(a(b(c)))", "abc", true, false, true),
                ResultBufferCase("(cat|dog)-(\\d+)?", "dog-", true, false, true),
                ResultBufferCase("(a|ab)+", "ab", true, false, true),
                ResultBufferCase("(a(b)?)+", "aba", true, false, false),
                ResultBufferCase("(a*)", "aaa", true, true, false),
                ResultBufferCase("(\\w+)-(\\d+)", "xx abc-123 yy", false, false, false)
            )
        }
    }
}
