// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.function.Consumer
import java.util.function.Function
import java.util.regex.MatchResult
import java.util.regex.PatternSyntaxException
import java.util.stream.Stream

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("intentional-divergence diagnostics are SafeRE-specific")
internal class IntentionalDivergenceDiagnosticsTest {
    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @Test
    fun exhaustedFindNotificationSeesFinalStateAndPropagatesFailure() {
        val matcher = Pattern.compile("").matcher("")
        Assertions.assertThat(matcher.find()).isTrue()
        val failure: RuntimeException = IllegalStateException("listener failed")
        Pattern.setDiagnostics(
            listener(
                Consumer { divergence: IntentionalDivergence? ->
                    Assertions.assertThat<IntentionalDivergence?>(divergence)
                        .isEqualTo(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH)
                    Assertions.assertThat(matcher.hasMatch()).isFalse()
                    Assertions.assertThatThrownBy(ThrowingCallable { matcher.group() })
                        .isInstanceOf(IllegalStateException::class.java)
                    throw failure
                })
        )

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.find() }).isSameAs(failure)
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        Assertions.assertThat(matcher.find()).isFalse()
    }

    @ParameterizedTest
    @MethodSource("replacementCases")
    fun replacementNotificationSeesFinalStateAcrossEngineAndApiPaths(
        regex: String?, input: String?, options: EnginePathOptions?, functional: Boolean, first: Boolean
    ) {
        val pattern = Pattern.compile(regex, 0, options)
        val expected = pattern.matcher(input)
        replace(expected, functional, first)
        val actual = pattern.matcher(input)
        val failure: RuntimeException = IllegalStateException("listener failed")
        Pattern.setDiagnostics(
            listener(
                Consumer { divergence: IntentionalDivergence? ->
                    Assertions.assertThat<IntentionalDivergence?>(divergence)
                        .isEqualTo(IntentionalDivergence.EXHAUSTED_EMPTY_MATCH)
                    assertSameState(actual, expected)
                    throw failure
                })
        )

        Assertions.assertThatThrownBy(ThrowingCallable { replace(actual, functional, first) }).isSameAs(failure)
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        assertSameState(actual, expected)
        Assertions.assertThat(actual.find()).isEqualTo(expected.find())
    }

    @Test
    fun usePatternNotificationSeesInstalledPatternAndPreservedGroupZero() {
        val matcher = Pattern.compile("a+").matcher("ab")
        Assertions.assertThat(matcher.find()).isTrue()
        val replacement = Pattern.compile("(b)")
        val failure: RuntimeException = IllegalStateException("listener failed")
        Pattern.setDiagnostics(
            listener(
                Consumer { divergence: IntentionalDivergence? ->
                    Assertions.assertThat<IntentionalDivergence?>(divergence)
                        .isEqualTo(IntentionalDivergence.USE_PATTERN_GROUP_ZERO)
                    Assertions.assertThat<Pattern?>(matcher.pattern()).isSameAs(replacement)
                    Assertions.assertThat(matcher.groupCount()).isEqualTo(1)
                    Assertions.assertThat(matcher.group()).isEqualTo("a")
                    Assertions.assertThat(matcher.group(1)).isNull()
                    throw failure
                })
        )

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.usePattern(replacement) }).isSameAs(failure)
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(1)).isEqualTo("b")
    }

    @ParameterizedTest
    @MethodSource("syntaxFailures")
    fun failedParseKeepsSyntaxErrorAndSuppressesNotificationFailure(
        regex: String?, failure: Throwable
    ) {
        val expected =
            Assertions.catchThrowableOfType<PatternSyntaxException?>(
                PatternSyntaxException::class.java,
                ThrowingCallable { Pattern.compile(regex) })
        Assertions.assertThat<PatternSyntaxException?>(expected).isNotNull()
        Pattern.setDiagnostics(
            listener(
                Consumer { divergence: IntentionalDivergence? ->
                    if (failure is RuntimeException) {
                        throw failure
                    }
                    throw failure as Error
                })
        )

        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile(regex) })
            .isInstanceOfSatisfying<PatternSyntaxException?>(
                PatternSyntaxException::class.java,
                Consumer { actual: PatternSyntaxException? ->
                    Assertions.assertThat(actual!!.getPattern()).isEqualTo(expected!!.getPattern())
                    Assertions.assertThat(actual.getIndex()).isEqualTo(expected.getIndex())
                    Assertions.assertThat(actual.getDescription()).isEqualTo(expected.getDescription())
                    Assertions.assertThat<Throwable?>(actual.getSuppressed()).containsExactly(failure)
                })
    }

    @Test
    fun successfulParsePropagatesNotificationFailure() {
        val failure: RuntimeException = IllegalStateException("listener failed")
        Pattern.setDiagnostics(
            listener(
                Consumer { divergence: IntentionalDivergence? ->
                    Assertions.assertThat<IntentionalDivergence?>(divergence)
                        .isEqualTo(IntentionalDivergence.COMMENT_QUOTING)
                    throw failure
                })
        )

        Assertions.assertThatThrownBy(ThrowingCallable { Pattern.compile("(?x)#\\Q\n.") }).isSameAs(failure)
    }

    companion object {
        @JvmStatic
        private fun replacementCases(): Stream<Arguments?> {
            val defaults = EnginePathOptions.builder().build()
            val dfa =
                EnginePathOptions.builder()
                    .literalFastPaths(false)
                    .charClassMatchFastPaths(false)
                    .shiftDfa(false)
                    .onePass(false)
                    .build()
            return Stream.of<Arguments?>(
                Arguments.of("^$", "", defaults, false, false),
                Arguments.of("^$", "", defaults, false, true),
                Arguments.of("[ab]*", "ab", defaults, false, false),
                Arguments.of("[ab]*", "ab", dfa, false, false),
                Arguments.of("[ab]*", "ab", defaults, true, false)
            )
        }

        @JvmStatic
        private fun syntaxFailures(): Stream<Arguments?>? {
            return Stream.of<String?>("[a&&&b]", "(?x)#\\Q\n[", "(?x)#\\Q\n[a&&&b]")
                .flatMap<Arguments?> { regex: String? ->
                    Stream.of<Arguments?>(
                        Arguments.of(regex, IllegalStateException("listener failed")),
                        Arguments.of(regex, AssertionError("listener failed"))
                    )
                }
        }

        private fun listener(callback: Consumer<IntentionalDivergence?>): SafeReMatchDiagnostics {
            return object : SafeReMatchDiagnostics() {
                override fun onIntentionalDivergence(divergence: IntentionalDivergence?) {
                    callback.accept(divergence)
                }
            }
        }

        private fun replace(matcher: Matcher, functional: Boolean, first: Boolean) {
            if (functional) {
                matcher.replaceAll(Function { match: MatchResult? -> "-" })
            } else if (first) {
                matcher.replaceFirst("-")
            } else {
                matcher.replaceAll("-")
            }
        }

        private fun assertSameState(actual: Matcher, expected: Matcher) {
            Assertions.assertThat(actual.hasMatch()).isEqualTo(expected.hasMatch())
            if (expected.hasMatch()) {
                for (group in 0..expected.groupCount()) {
                    Assertions.assertThat(actual.group(group)).isEqualTo(expected.group(group))
                    Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                    Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                }
            } else {
                Assertions.assertThatThrownBy(ThrowingCallable { actual.group() })
                    .isInstanceOf(IllegalStateException::class.java)
            }
            Assertions.assertThat(actual.appendTail(StringBuilder()).toString())
                .isEqualTo(expected.appendTail(StringBuilder()).toString())
        }
    }
}
