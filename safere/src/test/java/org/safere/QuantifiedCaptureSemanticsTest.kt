// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Stream

/** Differential coverage for JDK-visible captures inside quantified expressions.  */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
internal class QuantifiedCaptureSemanticsTest {
    @ParameterizedTest(name = "[{index}] find captures for /{0}/ on \"{1}\"")
    @MethodSource("quantifiedCaptureCases")
    @DisplayName("find() exposes quantified captures like java.util.regex")
    fun findCapturesMatchJdk(regex: String, input: String) {
        val jdk = Pattern.compile(regex).matcher(input)
        val safere = org.safere.Pattern.compile(regex).matcher(input)

        val jdkMatched = jdk.find()
        Assertions.assertThat(safere.find()).isEqualTo(jdkMatched)
        assertGroupsMatch(regex, input, jdkMatched, jdk, safere)
    }

    @ParameterizedTest(name = "[{index}] matches captures for /{0}/ on \"{1}\"")
    @MethodSource("quantifiedCaptureCases")
    @DisplayName("matches() exposes quantified captures like java.util.regex")
    fun matchesCapturesMatchJdk(regex: String, input: String) {
        val jdk = Pattern.compile(regex).matcher(input)
        val safere = org.safere.Pattern.compile(regex).matcher(input)

        val jdkMatched = jdk.matches()
        Assertions.assertThat(safere.matches()).isEqualTo(jdkMatched)
        assertGroupsMatch(regex, input, jdkMatched, jdk, safere)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("generatedQuantifiedCaptureCases")
    @DisplayName("generated quantified-capture cases match java.util.regex")
    fun generatedQuantifiedCaptureCasesMatchJdk(testCase: GeneratedCase) {
        assertOperationTraceMatchesJdk(testCase)
        assertReplacementTraceMatchesJdk(testCase)
    }

    @ParameterizedTest(name = "[{index}] replacement APIs for /{0}/ on \"{1}\"")
    @MethodSource("quantifiedCaptureCases")
    @DisplayName("replacement APIs consume quantified captures like java.util.regex")
    fun replacementApisMatchJdk(regex: String, input: String) {
        val jdkPattern = Pattern.compile(regex)
        val saferePattern = org.safere.Pattern.compile(regex)

        Assertions.assertThat(saferePattern.matcher(input).replaceAll("[$1]"))
            .`as`("replaceAll(String) for /%s/ on %s", regex, input)
            .isEqualTo(jdkPattern.matcher(input).replaceAll("[$1]"))

        Assertions.assertThat(
            saferePattern.matcher(input).replaceAll(Function { match: MatchResult? -> "[" + match!!.group(1) + "]" })
        )
            .`as`("replaceAll(Function) for /%s/ on %s", regex, input)
            .isEqualTo(
                jdkPattern.matcher(input).replaceAll(Function { match: MatchResult? -> "[" + match!!.group(1) + "]" })
            )

        Assertions.assertThat(appendReplacementResult(saferePattern.matcher(input)))
            .`as`("appendReplacement for /%s/ on %s", regex, input)
            .isEqualTo(appendReplacementResult(jdkPattern.matcher(input)))
    }

    @Test
    @DisplayName("observing retained quantified captures does not enumerate repeat partitions")
    fun observingRetainedQuantifiedCapturesDoesNotEnumerateRepeatPartitions() {
        val prefix = "b".repeat(12000)
        val suffix = "a".repeat(100)
        val input = prefix + "x" + suffix + "y"

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            CAPTURE_OBSERVATION_TIMEOUT,
            Executable {
                val matcher = org.safere.Pattern.compile(".*x(?:(a){1,}){2}y").matcher(input)
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.group(1)).isEqualTo("a")
                Assertions.assertThat(matcher.start(1)).isEqualTo(prefix.length + suffix.length)
                Assertions.assertThat(matcher.end(1)).isEqualTo(prefix.length + suffix.length + 1)
            })
    }

    @ParameterizedTest(name = "[{index}] lazy nullable group 0 for /{0}/ on \"{1}\"")
    @MethodSource("lazyNullableGroupZeroCases")
    @DisplayName("capture observation preserves lazy nullable group zero")
    fun captureObservationPreservesLazyNullableGroupZero(regex: String, input: String) {
        val jdk = Pattern.compile(regex).matcher(input)
        val safere = org.safere.Pattern.compile(regex).matcher(input)

        val jdkMatched = jdk.find()
        Assertions.assertThat(safere.find()).isEqualTo(jdkMatched)
        assertGroupsMatch(regex, input, jdkMatched, jdk, safere)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("zeroWidthPossessiveCaptureCases")
    @DisplayName("possessive quantifiers over zero-width operands preserve captures")
    fun zeroWidthPossessiveQuantifiersPreserveCaptures(testCase: GeneratedCase) {
        assertOperationTraceMatchesJdk(testCase)
        assertReplacementTraceMatchesJdk(testCase)
    }

    @ParameterizedTest(name = "[{index}] #52 divergence for /{0}/ on \"{1}\"")
    @MethodSource("failedStartLeakageCases")
    @DisplayName("failed starting-position capture leakage remains an intentional divergence")
    fun failedStartCaptureLeakageDoesNotMatchJdk(regex: String?, input: String?) {
        val safere = org.safere.Pattern.compile(regex).matcher(input)

        Assertions.assertThat(safere.find()).isTrue()
        Assertions.assertThat(safere.group(1)).isNull()
        Assertions.assertThat(safere.start(1)).isEqualTo(-1)
        Assertions.assertThat(safere.end(1)).isEqualTo(-1)
    }

    @Test
    @DisplayName("failed alternative capture leakage remains an intentional divergence")
    fun failedAlternativeCaptureLeakageDoesNotMatchJdk() {
        val safere = org.safere.Pattern.compile("(?:(?:())*{0}{1}?{1}|a).").matcher("ab")

        Assertions.assertThat(safere.matches()).isTrue()
        Assertions.assertThat(safere.group(1)).isNull()
        Assertions.assertThat(safere.start(1)).isEqualTo(-1)
        Assertions.assertThat(safere.end(1)).isEqualTo(-1)
    }

    @Test
    @DisplayName("failed possessive zero-width alternative capture leakage remains intentional")
    fun failedPossessiveZeroWidthAlternativeCaptureLeakageDoesNotMatchJdk() {
        val safere = org.safere.Pattern.compile("(?:((?:))*+{1}+{2}+{1}|a).").matcher("ab")

        Assertions.assertThat(safere.matches()).isTrue()
        Assertions.assertThat(safere.group(1)).isNull()
        Assertions.assertThat(safere.start(1)).isEqualTo(-1)
        Assertions.assertThat(safere.end(1)).isEqualTo(-1)
    }

    private enum class Operation {
        FIND,
        MATCHES,
        LOOKING_AT
    }

    @JvmRecord
    private data class GeneratedCase(
        val family: String?,
        val regex: String?,
        val input: String?,
        val namedGroup: String
    ) {
        override fun toString(): String {
            return family + " /" + regex + "/ on \"" + input + "\""
        }
    }

    companion object {
        private val CAPTURE_OBSERVATION_TIMEOUT: Duration = Duration.ofSeconds(5)

        private fun quantifiedCaptureCases(): Stream<Arguments?> {
            // Nested-repeat JDK differences use explicit expectations in AcceptedPathCaptureTest.
            return Stream.of<Arguments?>(
                Arguments.of("(?:(a|aa){1,2})*", "aaa"),
                Arguments.of("(?:(a|aa){1,}){2}", "aaaa"),
                Arguments.of("(?:(a|aa)+){2}", "aaaa"),
                Arguments.of("(?:(a|aa)+){2}", "aaa"),
                Arguments.of("((a|aa)+){2}", "aaaa"),
                Arguments.of("(?:(a)|(b))*", "abba"),
                Arguments.of("(?:(a)?b)*", "abab"),
                Arguments.of("(?:(a){1,2}?)*", "aaa"),
                Arguments.of("((ab)?)*", "abab"),
                Arguments.of("((a?))*", "aa"),
                Arguments.of("(.*)+/([a-zA-Z]+)/([^/]+)", "projects/123/locations/test-location/foo/bar"),
                Arguments.of("(.?)+/([a-z]+)/([^/]+)", "abc/foo/bar"),
                Arguments.of("(.?)+([a-z]+)", "abc")
            )
        }

        private fun failedStartLeakageCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(?:(a){1})*$", "ab"),
                Arguments.of("(?:(a){2})*$", "aab"),
                Arguments.of("(?:(a){2})*$", "aaab")
            )
        }

        private fun lazyNullableGroupZeroCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("(?:(?:(a){0,2})*?)", "a"),
                Arguments.of("(?:(?:(a){0,2})*?)", "aa"),
                Arguments.of("(?:(?:(a){0,2})*?)", "ab"),
                Arguments.of("(?:(?:(a){0,2})*?)", "aaa")
            )
        }

        private fun zeroWidthPossessiveCaptureCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of(GeneratedCase("empty group star possessive", "((?:))*+", "", null)),
                Arguments.of(GeneratedCase("nested empty group star possessive", "(())*+", "", null)),
                Arguments.of(GeneratedCase("word boundary star possessive", "(\\b)*+", "a", null)),
                Arguments.of(GeneratedCase("non-word boundary star possessive", "(\\B)*+", "", null)),
                Arguments.of(
                    GeneratedCase(
                        "multiline non-word boundary star possessive", "(?m:^(\\B)*+$)", "\n", null
                    )
                ),
                Arguments.of(
                    GeneratedCase(
                        "multiline non-word boundary star possessive before text",
                        "(?m:^(\\B)*+$)",
                        "\na",
                        null
                    )
                ),
                Arguments.of(
                    GeneratedCase("grapheme boundary star possessive", "(\\b{g})*+", "", null)
                ),
                Arguments.of(GeneratedCase("begin anchor star possessive", "(^)*+", "a", null)),
                Arguments.of(GeneratedCase("end anchor star possessive", "($)*+", "a", null)),
                Arguments.of(
                    GeneratedCase("word boundary counted possessive", "(\\b){0,2}+", "a", null)
                ),
                Arguments.of(
                    GeneratedCase("chained empty group possessive", "(())*+{0}+{1}?{2}?", "", null)
                ),
                Arguments.of(
                    GeneratedCase(
                        "empty group possessive between literals", "a((?:))*+{2}?{0}{2}?b", "ab", null
                    )
                )
            )
        }

        private fun generatedQuantifiedCaptureCases(): Stream<Arguments?>? {
            val cases: MutableList<GeneratedCase?> = ArrayList<GeneratedCase?>()
            addGeneratedCases(cases, "simple repeated capture", "(a)", mutableListOf<String?>("", "a", "aa", "aaa"))
            addGeneratedCases(cases, "nullable repeated capture", "(a?)", mutableListOf<String?>("", "a", "aa"))
            addGeneratedCases(cases, "optional repeated capture", "(a)?", mutableListOf<String?>("", "a", "aa"))
            addGeneratedCases(
                cases, "alternating repeated capture", "(a|aa)", mutableListOf<String?>("", "a", "aa", "aaa")
            )
            addGeneratedCases(cases, "nested repeated capture", "((a))", mutableListOf<String?>("", "a", "aa", "aaa"))
            addGeneratedCases(cases, "suffix repeated capture", "(a)b", mutableListOf<String?>("", "ab", "abab"))
            addGeneratedCases(
                cases, "optional-branch repeated capture", "(a)?b", mutableListOf<String?>("", "b", "ab", "abab")
            )
            addGeneratedCases(
                cases, "empty-alternative repeated capture", "(?:|(a))", mutableListOf<String?>("", "a", "aa")
            )

            cases.add(GeneratedCase("named repeated capture", "(?:(?<word>a)){1,2}", "aa", "word"))
            cases.add(
                GeneratedCase("named nullable repeated capture", "(?:(?<word>a)?){1,2}", "a", "word")
            )
            cases.add(
                GeneratedCase(
                    "named alternation repeated capture", "(?:(?<word>a|aa)){1,2}", "aaa", "word"
                )
            )

            return cases.stream().map<Arguments?> { arguments: GeneratedCase? -> Arguments.of(arguments) }
        }

        private fun addGeneratedCases(
            cases: MutableList<GeneratedCase?>, family: String?, atom: String, inputs: MutableList<String?>
        ) {
            val quantifiers =
                mutableListOf<String?>("*", "+", "?", "{0,2}", "{1,2}", "*?", "+?", "??", "{0,2}?", "{1,2}?")
            for (quantifier in quantifiers) {
                val regex = "(?:" + atom + ")" + quantifier
                for (input in inputs) {
                    cases.add(GeneratedCase(family + " " + quantifier, regex, input, null))
                    cases.add(
                        GeneratedCase(
                            family + " " + quantifier + " with suffix", regex + "c", input + "c", null
                        )
                    )
                    cases.add(
                        GeneratedCase(
                            family + " " + quantifier + " in context",
                            "x" + regex + "y",
                            "x" + input + "y",
                            null
                        )
                    )
                }
            }
        }

        private fun assertGroupsMatch(
            regex: String?, input: String?, matched: Boolean, jdk: Matcher, safere: org.safere.Matcher
        ) {
            if (!matched) {
                return
            }
            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            for (group in 0..jdk.groupCount()) {
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

        private fun assertOperationTraceMatchesJdk(testCase: GeneratedCase) {
            val jdkPattern = Pattern.compile(testCase.regex)
            val saferePattern = org.safere.Pattern.compile(testCase.regex)

            assertSingleOperationMatchesJdk(testCase, jdkPattern, saferePattern, Operation.FIND)
            assertSingleOperationMatchesJdk(testCase, jdkPattern, saferePattern, Operation.MATCHES)
            assertSingleOperationMatchesJdk(testCase, jdkPattern, saferePattern, Operation.LOOKING_AT)
            assertFindSequenceMatchesJdk(testCase, jdkPattern, saferePattern)
        }

        private fun assertSingleOperationMatchesJdk(
            testCase: GeneratedCase,
            jdkPattern: Pattern,
            saferePattern: org.safere.Pattern,
            operation: Operation
        ) {
            val jdk = jdkPattern.matcher(testCase.input)
            val safere = saferePattern.matcher(testCase.input)

            val jdkMatched =
                when (operation) {
                    Operation.FIND -> jdk.find()
                    Operation.MATCHES -> jdk.matches()
                    Operation.LOOKING_AT -> jdk.lookingAt()
                }
            val safereMatched =
                when (operation) {
                    Operation.FIND -> safere.find()
                    Operation.MATCHES -> safere.matches()
                    Operation.LOOKING_AT -> safere.lookingAt()
                }

            Assertions.assertThat(safereMatched).`as`("%s result for %s", operation, testCase).isEqualTo(jdkMatched)
            assertGroupsMatch(testCase.regex, testCase.input, jdkMatched, jdk, safere)
            if (jdkMatched) {
                assertMatchResultMatchesJdk(testCase, jdk.toMatchResult(), safere.toMatchResult())
            }
        }

        private fun assertFindSequenceMatchesJdk(
            testCase: GeneratedCase, jdkPattern: Pattern, saferePattern: org.safere.Pattern
        ) {
            val jdk = jdkPattern.matcher(testCase.input)
            val safere = saferePattern.matcher(testCase.input)

            var matchIndex = 0
            while (true) {
                val jdkMatched = jdk.find()
                val safereMatched = safere.find()
                Assertions.assertThat(safereMatched)
                    .`as`("find sequence result %d for %s", matchIndex, testCase)
                    .isEqualTo(jdkMatched)
                assertGroupsMatch(testCase.regex, testCase.input, jdkMatched, jdk, safere)
                if (!jdkMatched) {
                    return
                }
                matchIndex++
            }
        }

        private fun assertMatchResultMatchesJdk(
            testCase: GeneratedCase?, jdk: MatchResult, safere: MatchResult
        ) {
            Assertions.assertThat(safere.groupCount()).isEqualTo(jdk.groupCount())
            for (group in 0..jdk.groupCount()) {
                Assertions.assertThat(safere.group(group))
                    .`as`("toMatchResult group(%d) for %s", group, testCase)
                    .isEqualTo(jdk.group(group))
                Assertions.assertThat(safere.start(group))
                    .`as`("toMatchResult start(%d) for %s", group, testCase)
                    .isEqualTo(jdk.start(group))
                Assertions.assertThat(safere.end(group))
                    .`as`("toMatchResult end(%d) for %s", group, testCase)
                    .isEqualTo(jdk.end(group))
            }
        }

        private fun assertReplacementTraceMatchesJdk(testCase: GeneratedCase) {
            val jdkPattern = Pattern.compile(testCase.regex)
            val saferePattern = org.safere.Pattern.compile(testCase.regex)
            val numberedReplacement = "[$1]"

            Assertions.assertThat(saferePattern.matcher(testCase.input).replaceAll(numberedReplacement))
                .`as`("generated replaceAll(String) for %s", testCase)
                .isEqualTo(jdkPattern.matcher(testCase.input).replaceAll(numberedReplacement))
            Assertions.assertThat(saferePattern.matcher(testCase.input).replaceFirst(numberedReplacement))
                .`as`("generated replaceFirst(String) for %s", testCase)
                .isEqualTo(jdkPattern.matcher(testCase.input).replaceFirst(numberedReplacement))
            Assertions.assertThat(appendReplacementResult(saferePattern.matcher(testCase.input)))
                .`as`("generated appendReplacement for %s", testCase)
                .isEqualTo(appendReplacementResult(jdkPattern.matcher(testCase.input)))
            Assertions.assertThat(
                saferePattern.matcher(testCase.input)
                    .replaceAll(Function { match: MatchResult? -> "[" + match!!.group(1) + "]" })
            )
                .`as`("generated replaceAll(Function) for %s", testCase)
                .isEqualTo(
                    jdkPattern.matcher(testCase.input)
                        .replaceAll(Function { match: MatchResult? -> "[" + match!!.group(1) + "]" })
                )

            if (testCase.namedGroup != null) {
                val namedReplacement = "[\${" + testCase.namedGroup + "}]"
                Assertions.assertThat(saferePattern.matcher(testCase.input).replaceAll(namedReplacement))
                    .`as`("generated named replaceAll(String) for %s", testCase)
                    .isEqualTo(jdkPattern.matcher(testCase.input).replaceAll(namedReplacement))
            }
        }

        private fun appendReplacementResult(matcher: org.safere.Matcher): String {
            val result = StringBuffer()
            while (matcher.find()) {
                matcher.appendReplacement(result, "[$1]")
            }
            matcher.appendTail(result)
            return result.toString()
        }

        private fun appendReplacementResult(matcher: Matcher): String {
            val result = StringBuffer()
            while (matcher.find()) {
                matcher.appendReplacement(result, "[$1]")
            }
            matcher.appendTail(result)
            return result.toString()
        }
    }
}
