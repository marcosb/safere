// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.List
import java.util.Map
import java.util.function.IntFunction
import java.util.function.IntPredicate
import java.util.stream.Stream

/** Executable boundary rules and bounded composition checks; see docs/GRAPHEME_REGIONS.md.  */
@DisabledForCrosscheck("Includes approved region-local surrogate and grapheme search semantics")
internal class RegionAssertionCompositionTest {
    @JvmRecord
    private data class Rule(
        val name: String,
        val regex: String?,
        val input: String?,
        val start: Int,
        val end: Int,
        val anchoring: Boolean,
        val opaqueEnd: Int,
        val transparentEnd: Int
    )      {
        override fun toString(): String {
            return name
        }
    }

    @ParameterizedTest(name = "{0}, transparent={1}, full={2}")
    @MethodSource("boundaryRules")
    fun consumptionAssertionsAndAnchorsFollowTheDecisionTable(
        rule: Rule, transparent: Boolean, full: Boolean
    ) {
        val matcher = Pattern.compile(rule.regex).matcher(rule.input)
        configure(matcher, rule.start, rule.end, transparent, rule.anchoring)
        val expectedEnd = if (transparent) rule.transparentEnd else rule.opaqueEnd
        val expected = expectedEnd >= 0 && (!full || expectedEnd == rule.end)
        Assertions.assertThat(if (full) matcher.matches() else matcher.lookingAt()).isEqualTo(expected)
        if (expected) {
            Assertions.assertThat(matcher.start()).isEqualTo(rule.start)
            Assertions.assertThat(matcher.end()).isEqualTo(expectedEnd)
        }
    }

    @Test
    fun unreachableGraphemeBranchesPreserveAnchoredResultsAndCaptures() {
        checkGeneratedComposition(INPUTS, true)
    }

    @Test
    fun lineAssertionCompositionIncludesSlicedRegions() {
        Companion.checkGeneratedComposition(mutableListOf<String?>("\n", "a\n", "\u0085a", "a\u0085"), true)
        Companion.checkGeneratedComposition(mutableListOf<String?>("\r\na"), true)
    }

    @Test
    fun findPreservesItsCandidateStartsAndTerminalProgress() {
        val ordinary: Matcher = Pattern.compile("\\p{Cs}").matcher(EMOJI)
        val grapheme: Matcher = Pattern.compile("(?:\\p{Cs})|(?:\\Xz)").matcher(EMOJI)
        Assertions.assertThat(ordinary.find()).isFalse()
        Assertions.assertThat(grapheme.find()).isTrue()
        Assertions.assertThat(grapheme.start()).isEqualTo(1)
        Assertions.assertThat(grapheme.end()).isEqualTo(2)
        Assertions.assertThat(grapheme.find()).isFalse()

        for (transparent in booleanArrayOf(false, true)) {
            for (row in Map.of<String?, Int?>(".", 1, "(?:.|\\X)", 1, "(?:\\X|.)", 1, "\\X", 1).entries) {
                val consuming: Matcher = Pattern.compile(row.key).matcher(EMOJI).region(0, 1)
                consuming.useTransparentBounds(transparent)
                Assertions.assertThat(consuming.find()).`as`(row.key).isTrue()
                Assertions.assertThat(consuming.start()).isZero()
                Assertions.assertThat(consuming.end()).isEqualTo(row.value)
                Assertions.assertThat(consuming.find()).isFalse()
            }
            val boundary: Matcher = Pattern.compile("\\b{g}").matcher(EMOJI).region(0, 1)
            boundary.useTransparentBounds(transparent)
            val expected =
                if (transparent) mutableListOf<String?>("0:0", "false") else mutableListOf<String?>(
                    "0:0",
                    "1:1",
                    "false"
                )
            Assertions.assertThat<String?>(findTrace(boundary, EMOJI.length)).isEqualTo(expected)
            if (!transparent) {
                // A terminal empty match exhausts find(); a failure at an earlier position has a
                // separate continuation contract and is not asserted to be permanently exhausted.
                Assertions.assertThat(boundary.find()).isFalse()
            }
        }
    }

    @Test
    fun disablingAccelerationPreservesGraphemeAssertionCandidateStarts() {
        val regex = "\\p{Cs}\\b{g}"
        val accelerated = Pattern.compile(regex)
        val unaccelerated =
            Pattern.compile(regex, 0, EnginePathOptions.builder().startAcceleration(false).build())
        Assertions.assertThat<String?>(findTrace(accelerated.matcher(EMOJI), EMOJI.length))
            .isEqualTo(findTrace(unaccelerated.matcher(EMOJI), EMOJI.length))
    }

    @Test
    fun unreachableGraphemeBranchPreservesOpaqueSplitCrlfAnchorContext() {
        for (regex in mutableListOf<String?>("(.)?$", "(?:(.)?$)|(?:\\Xz)", "(?:\\Xz)|(?:(.)?$)")) {
            val matcher = Pattern.compile(regex).matcher("\r\na").region(1, 2)
            Assertions.assertThat(matcher.lookingAt()).`as`(regex).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(1)
        }
    }

    @Test
    fun unreachableGraphemeBranchPreservesMultilineAnchorAtTheRegionEnd() {
        for (regex in mutableListOf<String?>(
            "[\\s\\S](?m:^)",
            "(?:[\\s\\S](?m:^))|(?:\\Xz)",
            "(?:\\Xz)|(?:[\\s\\S](?m:^))"
        )) {
            val matcher = Pattern.compile(regex).matcher("\na\u0085").region(0, 1)
            Assertions.assertThat(matcher.lookingAt()).`as`(regex).isFalse()
        }
    }

    @Test
    fun unreachableGraphemeBranchPreservesSplitCrlfGraphemeAndLineAssertions() {
        for (regex in mutableListOf<String?>("(.)?\\b{g}$", "(?:(.)?\\b{g}$)|(?:\\Xz)", "(?:\\Xz)|(?:(.)?\\b{g}$)")) {
            val matcher = Pattern.compile(regex).matcher("\r\na").region(1, 2)
            Assertions.assertThat(matcher.lookingAt()).`as`(regex).isTrue()
            Assertions.assertThat(matcher.start()).isEqualTo(1)
            Assertions.assertThat(matcher.end()).isEqualTo(1)
        }
    }

    companion object {
        private const val EMOJI = "\uD83D\uDC4D"
        private const val LETTER = "\uD801\uDC00"
        private const val RI = "\uD83C\uDDE6"

        // The fuzzer uses the same atom/assertion/atom grammar, with generated inputs and regions.
        private val LEFT = mutableListOf<String?>(".", "(.)?", "(.)*", "(.)+?", "[\\s\\S]", "\\p{Cs}", "\\P{Cs}")
        private val ASSERTIONS = mutableListOf<String?>(
            "\\b",
            "\\B",
            "(?U:\\b)",
            "(?U:\\B)",
            "\\b{g}",
            "^",
            "$",
            "\\A",
            "\\z",
            "\\Z",
            "(?m:^)",
            "(?m:$)",
            "(?dm:$)",
            "\\b{g}$",
            "(?U:\\b)\\b{g}",
            "\\b{g}\\b{g}"
        )
        private val RIGHT = mutableListOf<String?>("", ".", "(.)?")
        private val INPUTS: MutableList<String> = List.of<String?>(
            "",
            "a",
            EMOJI,
            "a" + LETTER,
            RI.repeat(3),
            "a\u0301",
            "\uD83D\uDC69\u200D\uD83D\uDC69",
            "\uD83Dx\uDC4D",
            "\u1100\u1161",
            "\u0915\u094D\u0937",
            " a "
        )

        fun boundaryRules(): Stream<Arguments?>? {
            // These rows have a single possible prefix endpoint, so full matching additionally requires
            // that endpoint to equal the logical region end. -1 means no prefix match.
            val rules =
                List.of<Rule?>(
                    Rule("ordinary split high", ".", EMOJI, 0, 1, true, 1, 1),
                    Rule("ordinary complete pair", ".", EMOJI, 0, 2, true, 2, 2),
                    Rule("ordinary exposed low", ".", EMOJI, 1, 2, true, 2, 2),
                    Rule("surrogate category", "\\p{Cs}", EMOJI, 0, 1, true, 1, 1),
                    Rule("non-surrogate category", "\\P{Cs}", EMOJI, 0, 1, true, -1, -1),
                    Rule("bounded grapheme", "\\X", EMOJI, 0, 1, true, 1, 1),
                    Rule("assertion at region end", "\\X\\b{g}", EMOJI, 0, 1, true, 1, -1),
                    Rule("scalar grapheme endpoint", ".\\b{g}", EMOJI, 0, 1, true, 1, -1),
                    Rule("opaque start edge", "\\b{g}.", EMOJI, 1, 2, true, 2, -1),
                    Rule("empty region inside pair", "\\b{g}", EMOJI, 1, 1, true, 1, -1),
                    Rule("Unicode word category", "(?U:\\b).", LETTER, 0, 1, true, -1, 1),
                    Rule("Unicode non-word category", "(?U:\\B).", LETTER, 0, 1, true, 1, -1),
                    Rule("word context at region end", "\\X(?U:\\b)", LETTER + "a", 0, 1, true, -1, -1),
                    Rule("bounded RI context", "\\X\\b{g}", RI.repeat(2), 0, 1, true, 1, -1),
                    Rule("interior RI context", ".\\b{g}.", RI.repeat(2), 1, 3, true, 3, -1),
                    Rule("region end anchor", ".$", EMOJI, 0, 1, true, 1, 1),
                    Rule("input end anchor", ".$", EMOJI, 0, 1, false, -1, -1),
                    Rule("grapheme region end anchor", "\\X$", EMOJI, 0, 1, true, 1, 1),
                    Rule("grapheme input end anchor", "\\X$", EMOJI, 0, 1, false, -1, -1),
                    Rule("strict region end", ".\\z", EMOJI, 0, 1, true, 1, 1),
                    Rule("strict input end", ".\\z", EMOJI, 0, 1, false, -1, -1),
                    Rule("region start anchor", "^.", "a" + EMOJI, 1, 2, true, 2, 2),
                    Rule("input start anchor", "^.", "a" + EMOJI, 1, 2, false, -1, -1),
                    Rule("multiline input start", "(?m:^.)", "\n" + EMOJI, 1, 2, false, 2, 2)
                )
            return rules.stream()
                .flatMap<Arguments?> { rule: Rule? ->
                    Stream.of<Boolean?>(false, true)
                        .flatMap<Arguments?> { transparent: Boolean? ->
                            Stream.of<Boolean?>(false, true)
                                .map<Arguments?> { full: Boolean? -> Arguments.of(rule, transparent, full) }
                        }
                }
        }

        private fun checkGeneratedComposition(inputs: MutableList<String>, everyRegion: Boolean) {
            for (left in LEFT) {
                for (assertion in ASSERTIONS) {
                    for (right in RIGHT) {
                        val regex = left + assertion + right
                        val ordinary = Pattern.compile(regex)
                        val appended = Pattern.compile("(?:" + regex + ")|(?:\\Xz)")
                        val prepended = Pattern.compile("(?:\\Xz)|(?:" + regex + ")")
                        for (input in inputs) {
                            Assertions.assertThat(input).doesNotContain("z")
                            val comparableFind = input.codePoints()
                                .allMatch(IntPredicate { codePoint: Int -> Character.isBmpCodePoint(codePoint) })
                            val expected = ordinary.matcher(input)
                            val after = appended.matcher(input)
                            val before = prepended.matcher(input)
                            for (start in 0..(if (everyRegion) input.length else 0)) {
                                for (end in if (everyRegion) start else input.length..input.length) {
                                    for (transparent in booleanArrayOf(false, true)) {
                                        for (anchoring in booleanArrayOf(false, true)) {
                                            for (full in booleanArrayOf(false, true)) {
                                                configure(expected, start, end, transparent, anchoring)
                                                configure(after, start, end, transparent, anchoring)
                                                configure(before, start, end, transparent, anchoring)
                                                val matched = if (full) expected.matches() else expected.lookingAt()
                                                compareResult(
                                                    expected,
                                                    matched,
                                                    after,
                                                    if (full) after.matches() else after.lookingAt(),
                                                    input,
                                                    full
                                                )
                                                compareResult(
                                                    expected,
                                                    matched,
                                                    before,
                                                    if (full) before.matches() else before.lookingAt(),
                                                    input,
                                                    full
                                                )
                                            }
                                            // Supplementary find() candidate policies are tested separately. An
                                            // existing grapheme assertion alone does not establish equal candidate
                                            // sets: ordinary prefix acceleration may still skip complete pairs.
                                            if (comparableFind) {
                                                configure(expected, start, end, transparent, anchoring)
                                                configure(after, start, end, transparent, anchoring)
                                                configure(before, start, end, transparent, anchoring)
                                                val trace: MutableList<String?> = findTrace(expected, input.length)
                                                Assertions.assertThat<String?>(findTrace(after, input.length))
                                                    .`as`(label(after))
                                                    .isEqualTo(trace)
                                                Assertions.assertThat<String?>(findTrace(before, input.length))
                                                    .`as`(label(before))
                                                    .isEqualTo(trace)
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

        private fun configure(
            matcher: Matcher, start: Int, end: Int, transparent: Boolean, anchoring: Boolean
        ) {
            matcher.region(start, end).useTransparentBounds(transparent).useAnchoringBounds(anchoring)
        }

        private fun compareResult(
            expected: Matcher,
            expectedMatch: Boolean,
            actual: Matcher,
            actualMatch: Boolean,
            input: String,
            full: Boolean
        ) {
            var same = actualMatch == expectedMatch
            if (same && actualMatch) {
                for (group in 0..expected.groupCount()) {
                    if (actual.start(group) != expected.start(group) || actual.end(group) != expected.end(group) || (actual.group(
                            group
                        ) != expected.group(group))
                    ) {
                        same = false
                        break
                    }
                }
            }
            if (!same) {
                Assertions.assertThat<String?>(resultTrace(actual, actualMatch))
                    .`as`(
                        "%s input=%s full=%s",
                        label(actual),
                        input.codePoints().mapToObj<String?>(IntFunction { i: Int -> Integer.toHexString(i) }).toList(),
                        full
                    )
                    .isEqualTo(resultTrace(expected, expectedMatch))
            }
        }

        private fun resultTrace(matcher: Matcher, matched: Boolean): MutableList<String?> {
            val result: MutableList<String?> = ArrayList<String?>()
            result.add(matched.toString())
            if (matched) {
                for (group in 0..matcher.groupCount()) {
                    result.add(matcher.start(group).toString() + ":" + matcher.end(group))
                    result.add(matcher.group(group))
                }
            }
            return result
        }

        private fun findTrace(matcher: Matcher, inputLength: Int): MutableList<String?> {
            val trace: MutableList<String?> = ArrayList<String?>()
            for (step in 0..inputLength + 1) {
                if (!matcher.find()) {
                    trace.add("false")
                    return trace
                }
                for (group in 0..matcher.groupCount()) {
                    trace.add(matcher.start(group).toString() + ":" + matcher.end(group))
                }
            }
            throw AssertionError("find() did not exhaust: " + label(matcher))
        }

        private fun label(matcher: Matcher): String {
            return (matcher.pattern()
                .toString() + " region="
                    + matcher.regionStart()
                    + ":"
                    + matcher.regionEnd()
                    + " transparent="
                    + matcher.hasTransparentBounds()
                    + " anchoring="
                    + matcher.hasAnchoringBounds())
        }
    }
}
