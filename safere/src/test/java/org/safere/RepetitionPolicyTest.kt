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
import java.util.stream.Stream

/** Conformance to the minimum-respecting empty-stop rule in the repetition specification.  */
@DisabledForCrosscheck("SafeRE's explicit repetition policy and package-private engine controls")
internal class RepetitionPolicyTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("policyCases")
    fun emptyIterationStopsOnlyAfterSatisfyingItsOwnMinimum(
        testCase: PolicyCase, engine: EnginePathOptions?
    ) {
        for (wrappers in 0..1) {
            val pattern =
                Pattern.compile(
                    "(?:".repeat(wrappers) + testCase.regex + ")".repeat(wrappers), 0, engine
                )
            for (region in booleanArrayOf(false, true)) {
                val prefix = if (region) "😀#" else ""
                val input = prefix + testCase.input + (if (region) "!" else "")
                val offset = prefix.length
                for (operation in 0..2) {
                    val matcher = pattern.matcher(input)
                    val bytes = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                    if (region) {
                        matcher.region(offset, offset + testCase.input.length)
                        bytes.region(
                            byteOffset(input, offset), byteOffset(input, offset + testCase.input.length)
                        )
                    }
                    Assertions.assertThat(match(matcher, operation))
                        .`as`("/%s/ operation %d", pattern, operation)
                        .isTrue()
                    Assertions.assertThat(match(bytes, operation)).isTrue()
                    val end = if (operation == 2) testCase.input.length else testCase.end
                    Assertions.assertThat(matcher.start()).isEqualTo(offset)
                    Assertions.assertThat(matcher.end()).isEqualTo(offset + end)
                    Assertions.assertThat(bytes.start()).isEqualTo(byteOffset(input, offset))
                    Assertions.assertThat(bytes.end()).isEqualTo(byteOffset(input, offset + end))
                    for (group in 1..matcher.groupCount()) {
                        val start = testCase.groups!!.get(2 * (group - 1))!!
                        val finish = testCase.groups.get(2 * (group - 1) + 1)!!
                        Assertions.assertThat(matcher.start(group))
                            .`as`("/%s/ group %d", pattern, group)
                            .isEqualTo(if (start < 0) -1 else offset + start)
                        Assertions.assertThat(matcher.end(group)).isEqualTo(if (finish < 0) -1 else offset + finish)
                        Assertions.assertThat(bytes.start(group))
                            .isEqualTo(if (start < 0) -1 else byteOffset(input, offset + start))
                        Assertions.assertThat(bytes.end(group))
                            .isEqualTo(if (finish < 0) -1 else byteOffset(input, offset + finish))
                    }
                }
            }
        }
    }

    @Test
    fun nestedNullableCountedSearchPreservesTheUnicodeFindHistory() {
        val regex = "(?:(?:(?:(?:(?:[^a])?)(?:(?:|[ab])))+?){0,2})(?:[^a])"
        val input = "béa\na\n.cabaaa"
        for (engine in java.util.List.of<EnginePathOptions?>(
            EnginePathOptions.allEnabled(),
            EnginePathOptions.builder().dfa(false).onePass(false).bitState(false).build()
        )) {
            val pattern = Pattern.compile(regex, 0, engine)
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.matches()).isFalse()
            matcher.reset()
            Assertions.assertThat(matcher.lookingAt()).isTrue()
            Assertions.assertThat(matcher.end()).isEqualTo(4)
            matcher.reset()
            val bytes = pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
            val expected = arrayOf<IntArray>(intArrayOf(0, 4), intArrayOf(4, 7), intArrayOf(7, 10))
            for (bounds in expected) {
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.start()).isEqualTo(bounds[0])
                Assertions.assertThat(matcher.end()).isEqualTo(bounds[1])
                Assertions.assertThat(bytes.find()).isTrue()
                Assertions.assertThat(bytes.start()).isEqualTo(byteOffset(input, bounds[0]))
                Assertions.assertThat(bytes.end()).isEqualTo(byteOffset(input, bounds[1]))
            }
            Assertions.assertThat(matcher.find()).isFalse()
            Assertions.assertThat(bytes.find()).isFalse()
            Assertions.assertThat(pattern.matcher(input).replaceAll("X")).isEqualTo("XXXaaa")
            Assertions.assertThat<String?>(pattern.split(input, -1)).containsExactly("", "", "", "aaa")
        }
    }

    @Test
    fun operandlessZeroCountsDoNotEraseAnEarlierFiniteEmptyCapture() {
        for (regex in mutableListOf<String>("(){0,2}", "(){0,2}{0}", "(){0,2}{0}{1}", "(){0,2}{1}?{0}?")) {
            val matcher = Pattern.compile(regex).matcher("")
            Assertions.assertThat(matcher.matches()).isTrue()
            Assertions.assertThat(matcher.group(1)).isEmpty()
            Assertions.assertThat(matcher.start(1)).isZero()
            Assertions.assertThat(matcher.end(1)).isZero()
        }
    }

    @Test
    fun finiteEmptyStopControlsFindReplacementAndSplitSequences() {
        for (quantifier in mutableListOf<String>("{0,2}", "{1,2}", "{2}")) {
            val pattern = Pattern.compile("(?:b?|a)" + quantifier + "b")
            val matcher = pattern.matcher("abb")
            val history: MutableList<String> = ArrayList<String>()
            while (matcher.find()) {
                history.add(matcher.group()!!)
                Assertions.assertThat(matcher.toMatchResult().group()).isEqualTo(matcher.group())
            }
            val exact = quantifier == "{2}"
            Assertions.assertThat<String?>(history).containsExactlyElementsOf(
                if (exact) mutableListOf<String>(
                    "ab",
                    "b"
                ) else mutableListOf<String>("abb")
            )
            Assertions.assertThat(pattern.matcher("abb").replaceAll("X")).isEqualTo(if (exact) "XX" else "X")
            Assertions.assertThat<String?>(pattern.split("abb", -1))
                .containsExactlyElementsOf(
                    if (exact) mutableListOf<String>("", "", "") else mutableListOf<String>(
                        "",
                        ""
                    )
                )
        }
    }

    @JvmRecord
    internal data class PolicyCase(val regex: String?, val input: String, val end: Int, val groups: MutableList<Int?>?)

    companion object {
        @JvmStatic
        private fun policyCases(): Stream<Arguments?>? {
            val cases: MutableList<PolicyCase?> =
                java.util.List.of<PolicyCase?>(
                    policy("(?:b?|a){0,2}b", "abb", 3),
                    policy("(?:b?|a){1,2}b", "abb", 3),
                    policy("(?:b?|a){2}b", "abb", 2),
                    policy("(?:()|\\W+){0,2}y", " y", 2, 1, 1),
                    policy("(?:()|\\W+){1,2}y", " y", 2, 1, 1),
                    policy("(?:()|\\W+){2}y", " y", 2, 0, 0),
                    policy("(?:()|a){0,2}\\z", "a", 1, 1, 1),
                    policy("(?:()|a){1,2}b", "ab", 2, 1, 1),
                    policy("(?:()|a){2,3}b", "aab", 3, 0, 0),
                    policy("(?:b?|(a)){2,3}b", "abbb", 3, 0, 1),
                    policy("^(?:(\\b|a){2,3})*?$", "aa", 2, 1, 2),
                    policy("^(?:(\\b|a){2,3})+?$", "aa", 2, 1, 2),
                    policy("^(?:(\\b|a){2,4})*?$", "aa", 2, 2, 2),
                    policy("^(?:(\\b|a){2,3})*$", "aa", 2, 2, 2),
                    policy("(?:\\A|a){2}\\z", "a", 1),
                    policy("(?:^()|a)+b", "ab", 2, -1, -1),
                    policy("(?:^()|a){1,}b", "ab", 2, -1, -1),
                    policy("(?:^()|a)+?b", "ab", 2, -1, -1),
                    policy("(?:^()|a){1,}?b", "ab", 2, -1, -1),
                    policy("(?:^()|a){2,}b", "ab", 2, 0, 0),
                    policy("(?:()|a)+?b", "ab", 2, -1, -1),
                    policy("(?:(?:ab)?|a?b){0,2}a", "baba", 4),
                    policy("(?:a??b?){0,2}b", "abab", 4),
                    policy("(?:\\b|[ab]){0,2}a", "baa", 3),
                    policy("(?:a{0,2}|b?){0,2}a", "baa", 3),
                    policy("(a|aa){0,2}?b", "aab", 3, 1, 2),
                    policy("(a(b)?){0,2}", "aba", 3, 2, 3, 1, 2)
                )
            val engines =
                java.util.List.of<EnginePathOptions?>(
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
            return cases.stream()
                .flatMap<Arguments?> { testCase: PolicyCase? ->
                    engines.stream().map<Arguments?> { engine: EnginePathOptions? -> Arguments.of(testCase, engine) }
                }
        }

        private fun policy(regex: String?, input: String, end: Int, vararg groups: Int?): PolicyCase {
            return PolicyCase(regex, input, end, java.util.List.of<Int?>(*groups))
        }

        private fun byteOffset(input: String, position: Int): Int {
            return input.substring(0, position).toByteArray(StandardCharsets.UTF_8).size
        }

        private fun match(matcher: Matcher, operation: Int): Boolean {
            return when (operation) {
                0 -> matcher.find()
                1 -> matcher.lookingAt()
                else -> matcher.matches()
            }
        }

        private fun match(matcher: Utf8Matcher, operation: Int): Boolean {
            return when (operation) {
                0 -> matcher.find()
                1 -> matcher.lookingAt()
                else -> matcher.matches()
            }
        }
    }
}
