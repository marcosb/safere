// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import java.nio.charset.StandardCharsets
import java.util.List

/** Full-match recognition can defer the ordered captures of progress-bearing programs.  */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("exercises SafeRE engine authority and the approved repetition policy")
internal class NullableFullMatchDfaTest {
    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @Test
    fun successfulFullMatchesUseDfaBeforeAnyCaptureDemand() {
        for (regex in mutableListOf<String?>(
            "(?:a?){0,32}b",
            "(?:a?){0,1000}b",
            "(?:(?<empty>)|a){0,32}b",
            "(a?)*b",
            "(?:(?<empty>)|a){2,3}b"
        )) {
            val input =
                if (regex!!.contains("{2,3}")) "aab" else "a".repeat(if (regex.contains("1000")) 512 else 16) + "b"
            for (utf8 in mutableListOf<Boolean?>(false, true)) {
                val pattern = Pattern.compile(regex)
                Assertions.assertThat(pattern.prog().numLoopRegs()).isPositive()
                Assertions.assertThat<PatternCapability?>(pattern.analysis().capabilities)
                    .contains(
                        PatternCapability.DFA_BOUNDARY_SEARCH, PatternCapability.DFA_REJECT_PREFILTER
                    )
                val events: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
                Pattern.setDiagnostics(
                    object : SafeReMatchDiagnostics() {
                        override fun onOperationCompleted(event: OperationDiagnostics?) {
                            events.add(event)
                        }
                    })
                val matched =
                    if (utf8)
                        pattern.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))).matches()
                    else
                        pattern.matcher(input).matches()
                Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
                Assertions.assertThat(matched).isTrue()
                Assertions.assertThat<OperationDiagnostics?>(events)
                    .singleElement()
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).`as`(regex)
                                .isEqualTo(MatchStrategy.DFA)
                            Assertions.assertThat<PatternCapability?>(event.pattern.analysis.capabilities)
                                .contains(PatternCapability.DFA_BOUNDARY_SEARCH)
                            Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                            Assertions.assertThat(event.forwardDfaSearchCount).isEqualTo(1)
                            Assertions.assertThat<StrategyDecision?>(event.strategyDecisions)
                                .doesNotContain(
                                    StrategyDecision(
                                        MatchStrategy.DFA,
                                        StrategyDisposition.BYPASSED,
                                        StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED
                                    )
                                )
                        })
            }
        }
    }

    @Test
    fun staticDfaCapabilitiesRespectEngineAndGraphemeEligibility() {
        val disabled = Pattern.compile("(?:a?){0,32}b", 0, engineOptions(false))
        val grapheme = Pattern.compile("(?:\\X?){0,2}b")
        for (pattern in List.of<Pattern>(disabled, grapheme)) {
            Assertions.assertThat<PatternCapability?>(pattern.analysis().capabilities)
                .contains(PatternCapability.NFA)
                .doesNotContain(
                    PatternCapability.DFA_BOUNDARY_SEARCH, PatternCapability.DFA_REJECT_PREFILTER
                )
        }
    }

    @Test
    fun fullMatchRecognitionAndDeferredCapturesAgreeWithTheExactEngine() {
        val inputs: MutableList<String> =
            mutableListOf<String?>("", "a", "b", "aa", "ab", "aaa", "aab", "abb", "a\n", "é", "éb", "😀", "😀b")
        for (operand in mutableListOf<String?>(
            "a?",
            "()|a",
            "b?|a",
            "\\A|a",
            "a|\\z",
            "\\b|a",
            "((a)?)",
            "a??",
            "é?",
            "😀|"
        )) {
            for (minimum in mutableListOf<Int?>(0, 1, 2)) {
                for (maximum in List.of<Int?>(minimum, minimum!! + 1, minimum + 3, -1)) {
                    for (mode in mutableListOf<String?>("", "?")) {
                        for (suffix in mutableListOf<String?>("b", "\\z")) {
                            val regex =
                                ("(?:"
                                        + operand
                                        + "){"
                                        + minimum
                                        + (if (maximum == minimum) "" else "," + (if (maximum < 0) "" else maximum))
                                        + "}"
                                        + mode
                                        + suffix)
                            val optimized = Pattern.compile(regex, 0, engineOptions(true))
                            val exact = Pattern.compile(regex, 0, engineOptions(false))
                            for (input in inputs) {
                                val actual = optimized.matcher(input)
                                val expected = exact.matcher(input)
                                val matches = expected.matches()
                                Assertions.assertThat(actual.matches()).`as`("/%s/ on %s", regex, input)
                                    .isEqualTo(matches)
                                val bytes = Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8))
                                val actualBytes = optimized.matcher(bytes)
                                val expectedBytes = exact.matcher(bytes)
                                Assertions.assertThat(expectedBytes.matches()).isEqualTo(matches)
                                Assertions.assertThat(actualBytes.matches())
                                    .`as`("UTF-8 /%s/ on %s", regex, input)
                                    .isEqualTo(matches)
                                if (matches) {
                                    assertGroups(actual, expected)
                                    for (group in 0..actualBytes.groupCount()) {
                                        Assertions.assertThat(actualBytes.start(group))
                                            .isEqualTo(expectedBytes.start(group))
                                        Assertions.assertThat(actualBytes.end(group))
                                            .isEqualTo(expectedBytes.end(group))
                                    }
                                }
                                Assertions.assertThat(actual.find()).isEqualTo(expected.find())
                                if (actual.hasMatch()) {
                                    assertGroups(actual, expected)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun deferredFullMatchCapturesKeepTheirPolicyAcrossRegionsAndSnapshots() {
        for (regex in mutableListOf<String?>("(?:()|a){0,3}b", "(?:()|a){2,3}b", "(a?)*b")) {
            for (region in mutableListOf<Boolean?>(false, true)) {
                val optimized = Pattern.compile(regex, 0, engineOptions(true))
                val exact = Pattern.compile(regex, 0, engineOptions(false))
                val prefix = if (region) "😀#" else ""
                val input = prefix + "aab" + (if (region) "!" else "")
                val actual = optimized.matcher(input)
                val expected = exact.matcher(input)
                val actualBytes = optimized.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                val expectedBytes = exact.matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                if (region) {
                    actual.region(prefix.length, prefix.length + 3)
                    expected.region(prefix.length, prefix.length + 3)
                    actualBytes.region(
                        prefix.toByteArray(StandardCharsets.UTF_8).size, prefix.toByteArray(
                            StandardCharsets.UTF_8
                        ).size + 3
                    )
                    expectedBytes.region(
                        prefix.toByteArray(StandardCharsets.UTF_8).size, prefix.toByteArray(
                            StandardCharsets.UTF_8
                        ).size + 3
                    )
                }
                Assertions.assertThat(actual.matches()).isTrue()
                Assertions.assertThat(expected.matches()).isTrue()
                Assertions.assertThat(actualBytes.matches()).isTrue()
                Assertions.assertThat(expectedBytes.matches()).isTrue()
                val snapshot = actual.toMatchResult()
                assertGroups(actual, expected)
                for (group in 0..actualBytes.groupCount()) {
                    Assertions.assertThat(actualBytes.start(group)).isEqualTo(expectedBytes.start(group))
                    Assertions.assertThat(actualBytes.end(group)).isEqualTo(expectedBytes.end(group))
                }
                actual.reset("reject")
                Assertions.assertThat(actual.matches()).isFalse()
                Assertions.assertThatThrownBy(ThrowingCallable { actual.group(1) })
                    .isInstanceOf(IllegalStateException::class.java)
                Assertions.assertThat(snapshot.group()).isEqualTo("aab")
                Assertions.assertThat(snapshot.start(1)).isEqualTo(expected.start(1))
                Assertions.assertThat(snapshot.end(1)).isEqualTo(expected.end(1))
            }
        }
    }

    @Test
    fun deferredCapturesCannotShortenFullMatchesBeforeFinalTerminators() {
        for (terminator in mutableListOf<String?>("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
            for (flags in List.of<Int?>(0, Pattern.UNIX_LINES)) {
                val regex = "^(a?)*((?:" + Pattern.quote(terminator) + ")??)$"
                for (region in mutableListOf<Boolean?>(false, true)) {
                    val prefix = if (region) "😀#" else ""
                    val input = prefix + "aa" + terminator + (if (region) "!" else "")
                    val pattern = Pattern.compile(regex, flags)
                    val matcher = pattern.matcher(input)
                    val bytes =
                        Pattern.compile(regex, flags)
                            .matcher(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))
                    if (region) {
                        matcher.region(prefix.length, prefix.length + 2 + terminator!!.length)
                        bytes.region(
                            prefix.toByteArray(StandardCharsets.UTF_8).size,
                            prefix.toByteArray(StandardCharsets.UTF_8).size + 2 + terminator.toByteArray(
                                StandardCharsets.UTF_8
                            ).size
                        )
                    }
                    Assertions.assertThat(matcher.matches()).`as`(regex).isTrue()
                    val end = matcher.end()
                    Assertions.assertThat(matcher.group(2)).isEqualTo(terminator)
                    Assertions.assertThat(matcher.end()).isEqualTo(end)
                    Assertions.assertThat(matcher.toMatchResult().group()).isEqualTo("aa" + terminator)
                    Assertions.assertThat(bytes.matches()).isTrue()
                    val byteEnd = bytes.end()
                    Assertions.assertThat(bytes.start(2)).isEqualTo(prefix.toByteArray(StandardCharsets.UTF_8).size + 2)
                    Assertions.assertThat(bytes.end(2)).isEqualTo(byteEnd)
                    Assertions.assertThat(bytes.end()).isEqualTo(byteEnd)
                }
            }
        }
    }

    companion object {
        private fun engineOptions(dfa: Boolean): EnginePathOptions {
            return EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(dfa)
                .reverseDfa(false)
                .bitState(false)
                .lazyCaptureExtraction(false)
                .build()
        }

        private fun assertGroups(actual: Matcher, expected: Matcher) {
            for (group in 0..expected.groupCount()) {
                Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
            }
        }
    }
}
