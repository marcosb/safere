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
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.stream.Stream

/** Empty iterations publish their completed captures independently of redundant wrappers.  */
@DisabledForCrosscheck("explicit empty-iteration policy and package-private engine controls")
internal class EmptyCaptureSemanticsTest {
    @ParameterizedTest(name = "[{index}] {0}{1}{2}")
    @MethodSource("repetitions")
    fun completedEmptyIterationsPublishCaptures(
        operand: Operand, repeat: Repeat, mode: String, engine: EnginePathOptions?
    ) {
        val matched = repeat.minimum == 0 || operand.succeeds
        val participates =
            operand.succeeds && repeat.maximum != 0 && (repeat.minimum > 0 || mode != "?")
        for (wrappers in 0..2) {
            val wrapped = "(?:".repeat(wrappers) + operand.regex + ")".repeat(wrappers)
            val pattern = Pattern.compile(wrapped + repeat.regex + mode, 0, engine)
            for (region in booleanArrayOf(false, true)) {
                val input = if (region) "😀#" else ""
                val position = input.length
                for (operation in 0..2) {
                    val matcher = pattern.matcher(input)
                    val utf8 = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                    if (region) {
                        matcher.region(position, position)
                        utf8.region(
                            input.toByteArray(StandardCharsets.UTF_8).size,
                            input.toByteArray(StandardCharsets.UTF_8).size
                        )
                    }
                    Assertions.assertThat(matcher.groupCount()).isEqualTo(operand.groups)
                    Assertions.assertThat(match(matcher, operation))
                        .`as`("/%s/ operation %d", pattern, operation)
                        .isEqualTo(matched)
                    Assertions.assertThat(match(utf8, operation)).isEqualTo(matched)
                    if (!matched) {
                        continue
                    }
                    assertEmptyGroups(matcher, position, operand.groups, participates)
                    Assertions.assertThat(utf8.start()).isEqualTo(input.toByteArray(StandardCharsets.UTF_8).size)
                    Assertions.assertThat(utf8.end()).isEqualTo(utf8.start())
                    for (group in 1..operand.groups) {
                        val expected = if (participates) input.toByteArray(StandardCharsets.UTF_8).size else -1
                        Assertions.assertThat(utf8.start(group)).isEqualTo(expected)
                        Assertions.assertThat(utf8.end(group)).isEqualTo(expected)
                    }
                }
            }
        }
    }

    @Test
    fun countedZeroWidthProgramsStayBoundedWithoutLosingParticipation() {
        for (operand in mutableListOf<String?>("()", "(())", "(()??)", "(?:()())", "(^)", "(\\b)", "(?:()|())")) {
            for (mode in mutableListOf<String?>("", "?", "+")) {
                for (range in mutableListOf<String?>("{0,%d}", "{%d}", "{1,%d}", "{%d,}")) {
                    val small = Pattern.compile(operand + range.formatted(2) + mode)
                    val large = Pattern.compile(operand + range.formatted(1000) + mode)
                    Assertions.assertThat(large.analysis().programSize)
                        .`as`("/%s/ should not expand identical zero-width iterations", large)
                        .isEqualTo(small.analysis().programSize)
                    for (input in mutableListOf<String?>("", "a", "😀a")) {
                        val expected = small.matcher(input)
                        val actual = large.matcher(input)
                        val bytes = large.matcher(Utf8Input.trusted(input!!.toByteArray(StandardCharsets.UTF_8)))
                        while (expected.find()) {
                            Assertions.assertThat(actual.find()).isTrue()
                            for (group in 0..expected.groupCount()) {
                                Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                                Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                            }
                        }
                        Assertions.assertThat(actual.find()).isFalse()
                        // UTF-8 cursor advancement differs on supplementary characters; compare directly on
                        // the empty and ASCII subjects, and leave code-point coordinates to the matrix above.
                        if (!input.contains("😀")) {
                            val chars = large.matcher(input)
                            while (chars.find()) {
                                Assertions.assertThat(bytes.find()).isTrue()
                                for (group in 0..chars.groupCount()) {
                                    Assertions.assertThat(bytes.start(group)).isEqualTo(chars.start(group))
                                    Assertions.assertThat(bytes.end(group)).isEqualTo(chars.end(group))
                                }
                            }
                            Assertions.assertThat(bytes.find()).isFalse()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun followingOperandlessCountsDoNotEraseEarlierCaptures() {
        // Adjacent counted tokens after a quantifier have their own implicit empty operand in the
        // supported syntax. Only an actual enclosing zero-count repeat omits the earlier group.
        for (regex in mutableListOf<String?>("(){0,2}", "(){0,2}{0}", "(){0,2}{0}{1}", "(){0,2}{1}?{0}?")) {
            for (operation in 0..2) {
                val matcher = Pattern.compile(regex).matcher("")
                Assertions.assertThat(match(matcher, operation)).`as`("/%s/", regex).isTrue()
                assertEmptyGroups(matcher, 0, 1, true)
            }
        }
        for (regex in mutableListOf<String?>("(){0}", "(?:(){0,2}){0}")) {
            val matcher = Pattern.compile(regex).matcher("")
            Assertions.assertThat(matcher.matches()).isTrue()
            assertEmptyGroups(matcher, 0, 1, false)
        }
    }

    @Test
    fun emptyCaptureParticipationIsVisibleToReplacementCallbacks() {
        for (quantifier in mutableListOf<String?>("*", "+", "{0,2}", "{1,2}", "{2,}")) {
            for (operand in mutableListOf<String?>("(?<empty>)", "(?:(?<empty>))")) {
                val pattern = Pattern.compile(operand + quantifier)
                Assertions.assertThat(
                    pattern
                        .matcher("")
                        .replaceAll(Function { result: MatchResult? -> if (result!!.group(1) == null) "omitted" else "empty" })
                )
                    .isEqualTo("empty")
                Assertions.assertThat(pattern.matcher("").replaceFirst("[\${empty}]")).isEqualTo("[]")
                val matcher = pattern.matcher("")
                Assertions.assertThat(matcher.find()).isTrue()
                val output = StringBuilder()
                matcher.appendReplacement(output, "[$1]").appendTail(output)
                Assertions.assertThat(output.toString()).isEqualTo("[]")
            }
        }
        Assertions.assertThat(
            Pattern.compile("()*?")
                .matcher("")
                .replaceAll(Function { result: MatchResult? -> if (result!!.group(1) == null) "omitted" else "empty" })
        )
            .isEqualTo("omitted")
    }

    @Test
    fun successiveFindsPublishFreshCapturesAndRepresentationSpecificPositions() {
        for (regex in mutableListOf<String?>("()*", "(?:())*", "(){0,2}", "(?:()){0,2}")) {
            val matcher = Pattern.compile(regex).matcher("😀a")
            val snapshots: MutableList<MatchResult?> = ArrayList<MatchResult?>()
            for (position in 0..3) {
                Assertions.assertThat(matcher.find()).isTrue()
                assertEmptyGroups(matcher, position, 1, true)
                snapshots.add(matcher.toMatchResult())
            }
            Assertions.assertThat(matcher.find()).isFalse()
            matcher.reset()
            for (position in snapshots.indices) {
                Assertions.assertThat(snapshots.get(position)!!.start(1)).isEqualTo(position)
                Assertions.assertThat(snapshots.get(position)!!.end(1)).isEqualTo(position)
            }
            val utf8 = Pattern.compile(regex).matcher(Utf8Input.trusted("😀a".toByteArray(StandardCharsets.UTF_8)))
            for (position in intArrayOf(0, 4, 5)) {
                Assertions.assertThat(utf8.find()).isTrue()
                Assertions.assertThat(utf8.start()).isEqualTo(position)
                Assertions.assertThat(utf8.start(1)).isEqualTo(position)
                Assertions.assertThat(utf8.end(1)).isEqualTo(position)
            }
            Assertions.assertThat(utf8.find()).isFalse()
        }
    }

    @Test
    fun failedBranchesAndOmittedDescendantsKeepTheirOwnParticipation() {
        val failed = Pattern.compile("()*a|b").matcher("b")
        Assertions.assertThat(failed.matches()).isTrue()
        Assertions.assertThat(failed.group(1)).isNull()
        Assertions.assertThat(failed.start(1)).isEqualTo(-1)
        val omitted = Pattern.compile("(()??)*").matcher("")
        Assertions.assertThat(omitted.matches()).isTrue()
        Assertions.assertThat(omitted.group(1)).isEmpty()
        Assertions.assertThat(omitted.group(2)).isNull()
        val retained = Pattern.compile("(?:(a)|())*b").matcher("ab")
        Assertions.assertThat(retained.matches()).isTrue()
        Assertions.assertThat(retained.group(1)).isEqualTo("a")
        Assertions.assertThat(retained.start(2)).isEqualTo(1)
        Assertions.assertThat(retained.end(2)).isEqualTo(1)
    }

    @JvmRecord
    private data class Operand(val regex: String, val groups: Int, val succeeds: Boolean)

    @JvmRecord
    private data class Repeat(val regex: String?, val minimum: Int, val maximum: Int)
    companion object {
        private fun repetitions(): Stream<Arguments?>? {
            val operands =
                List.of<Operand?>(
                    Operand("()", 1, true),
                    Operand("((?:))", 1, true),
                    Operand("(())", 2, true),
                    Operand("(?<empty>)", 1, true),
                    Operand("(^)", 1, true),
                    Operand("(\\A)", 1, true),
                    Operand("(\\B)", 1, true),
                    Operand("(\\b{g})", 1, true),
                    Operand("(\\b)", 1, false)
                )
            val repeats: MutableList<Repeat?> =
                List.of<Repeat?>(
                    Repeat("?", 0, 1),
                    Repeat("*", 0, -1),
                    Repeat("+", 1, -1),
                    Repeat("{0}", 0, 0),
                    Repeat("{1}", 1, 1),
                    Repeat("{2}", 2, 2),
                    Repeat("{0,1}", 0, 1),
                    Repeat("{0,2}", 0, 2),
                    Repeat("{1,2}", 1, 2),
                    Repeat("{2,3}", 2, 3),
                    Repeat("{0,}", 0, -1),
                    Repeat("{1,}", 1, -1),
                    Repeat("{2,}", 2, -1)
                )
            val engines =
                List.of<EnginePathOptions?>(
                    EnginePathOptions.allEnabled(),
                    EnginePathOptions.builder().lazyCaptureExtraction(false).build(),
                    EnginePathOptions.builder()
                        .literalFastPaths(false)
                        .charClassMatchFastPaths(false)
                        .keywordAlternationFastPath(false)
                        .startAcceleration(false)
                        .shiftDfa(false)
                        .onePass(false)
                        .dfa(false)
                        .reverseDfa(false)
                        .bitState(false)
                        .lazyCaptureExtraction(false)
                        .build()
                )
            return operands.stream()
                .flatMap<Arguments?> { operand: Operand? ->
                    repeats.stream()
                        .flatMap<Arguments?> { repeat: Repeat? ->
                            Stream.of<String?>("", "?", "+")
                                .flatMap<Arguments?> { mode: String? ->
                                    engines.stream()
                                        .map<Arguments?> { engine: EnginePathOptions? ->
                                            Arguments.of(
                                                operand,
                                                repeat,
                                                mode,
                                                engine
                                            )
                                        }
                                }
                        }
                }
        }

        private fun assertEmptyGroups(
            matcher: Matcher, position: Int, groups: Int, participates: Boolean
        ) {
            Assertions.assertThat(matcher.start()).isEqualTo(position)
            Assertions.assertThat(matcher.end()).isEqualTo(position)
            val snapshot = matcher.toMatchResult()
            for (group in 1..groups) {
                val expected = if (participates) position else -1
                Assertions.assertThat(matcher.start(group)).`as`("group %d", group).isEqualTo(expected)
                Assertions.assertThat(matcher.end(group)).isEqualTo(expected)
                Assertions.assertThat(matcher.group(group)).isEqualTo(if (participates) "" else null)
                Assertions.assertThat(snapshot.start(group)).isEqualTo(expected)
                Assertions.assertThat(snapshot.end(group)).isEqualTo(expected)
                Assertions.assertThat(snapshot.group(group)).isEqualTo(if (participates) "" else null)
            }
            Assertions.assertThat(matcher.start()).isEqualTo(position)
            Assertions.assertThat(matcher.end()).isEqualTo(position)
        }

        private fun match(matcher: Matcher, operation: Int): Boolean {
            return when (operation) {
                0 -> matcher.find()
                1 -> matcher.matches()
                else -> matcher.lookingAt()
            }
        }

        private fun match(matcher: Utf8Matcher, operation: Int): Boolean {
            return when (operation) {
                0 -> matcher.find()
                1 -> matcher.matches()
                else -> matcher.lookingAt()
            }
        }
    }
}
