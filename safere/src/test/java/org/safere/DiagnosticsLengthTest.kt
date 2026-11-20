// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
@Isolated
@DisabledForCrosscheck("SafeRE-specific diagnostics and deterministic work counters")
internal class DiagnosticsLengthTest {
    private val events: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
    private val listener: SafeReMatchDiagnostics = object : SafeReMatchDiagnostics() {
        override fun onOperationCompleted(event: OperationDiagnostics?) {
            events.add(event)
        }
    }

    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["a{1000}", "[ab]{1000}", "[ab]{1000}\\z", "^[ab]{1000}", "([ab]){1000}", "(?i)a{1000}", "\\X[ab]{1000}"
        ]
    )
    fun shortInputsReportLengthRejectionWithoutEngineWork(regex: String?) {
        val pattern = Pattern.compile(regex)
        val text = "a".repeat(200)
        val input = Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8))
        Pattern.setDiagnostics(listener)

        Assertions.assertThat(pattern.find(input)).isFalse()
        Assertions.assertThat(pattern.matcher(input).find()).isFalse()
        Assertions.assertThat(pattern.matcher(input).matches()).isFalse()
        Assertions.assertThat(pattern.matcher(input).lookingAt()).isFalse()
        Assertions.assertThat(pattern.matcher(text).find()).isFalse()
        Assertions.assertThat(pattern.matcher(text).matches()).isFalse()
        Assertions.assertThat(pattern.matcher(text).lookingAt()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(events).hasSize(7)
            .allSatisfy(ThrowingConsumer { event: OperationDiagnostics? -> assertLengthRejection(event!!) })
    }

    @ParameterizedTest
    @ValueSource(strings = ["a{3}", "[ab]{3}", "[ab]{3}\\z", "^[ab]{3}", "([ab]){3}", "é{3}", "😀{3}"])
    fun minimumLengthAndMultibyteMatchesArePreserved(regex: String) {
        val pattern = Pattern.compile(regex)
        val unit = if (regex.startsWith("é")) "é" else if (regex.startsWith("😀")) "😀" else "a"
        for (enabled in mutableListOf<Boolean>(false, true)) {
            Pattern.setDiagnostics(if (enabled) listener else SafeReMatchDiagnostics.NONE)
            for (size in mutableListOf<Int>(2, 3, 4)) {
                val text = unit.repeat(size!!)
                val storage = ("prefix" + text + "suffix").toByteArray(StandardCharsets.UTF_8)
                val input = Utf8Input.trusted(storage, 6, text.toByteArray(StandardCharsets.UTF_8).size)
                val expected = size >= 3
                Assertions.assertThat(pattern.find(input)).`as`(regex).isEqualTo(expected)
                Assertions.assertThat(pattern.matcher(input).find()).`as`(regex).isEqualTo(expected)
                Assertions.assertThat(pattern.matcher(text).find()).`as`(regex).isEqualTo(expected)
            }
        }
    }

    @Test
    fun lengthRejectionUsesRegionAndRemainingSearchLengthAndFinalizesState() {
        Pattern.setDiagnostics(listener)
        val pattern = Pattern.compile("([ab]{3})")
        val matcher = pattern.matcher("aaabbb")
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.find()).isTrue()
        events.clear()

        Assertions.assertThat(matcher.find()).isFalse()
        Assertions.assertThatIllegalStateException().isThrownBy(ThrowingCallable { matcher.start() })
        matcher.region(1, 3)
        Assertions.assertThat(matcher.find()).isFalse()
        Assertions.assertThat(matcher.matches()).isFalse()
        Assertions.assertThat(matcher.lookingAt()).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(events).hasSize(4)
            .allSatisfy(ThrowingConsumer { event: OperationDiagnostics? -> assertLengthRejection(event!!) })

        matcher.reset()
        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.group(1)).isEqualTo("aaa")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "a?", "(?:[ab]{3})?", "\\z"])
    fun nullablePatternsStillMatchEmptyInput(regex: String?) {
        Pattern.setDiagnostics(listener)
        val pattern = Pattern.compile(regex)
        val input = Utf8Input.trusted(ByteArray(0))

        Assertions.assertThat(pattern.find(input)).isTrue()
        Assertions.assertThat(pattern.matcher(input).find()).isTrue()
        Assertions.assertThat(pattern.matcher("").find()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(events)
            .hasSize(3)
            .allSatisfy(ThrowingConsumer { event: OperationDiagnostics? ->
                Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.MATCH)
            })
    }

    @Test
    fun lengthRemainsBoundaryOnlyAlongsideEngineParticipationAndDecisions() {
        Pattern.setDiagnostics(listener)
        val options =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .onePass(false)
                .bitState(false)
                .build()
        val pattern = Pattern.compile("(?:(a)?)*X", 0, options)

        Assertions.assertThat(pattern.find(Utf8Input.trusted(ByteArray(0)))).isFalse()
        Assertions.assertThat(pattern.matcher("").find()).isFalse()
        Assertions.assertThat(pattern.matcher("aaaaX").find()).isTrue()
        // Replacement aggregates an engine match and a terminal minimum-length rejection.
        Assertions.assertThat(pattern.matcher("aaaaX").replaceAll("done")).isEqualTo("done")

        assertLengthRejection(events.get(0)!!)
        assertLengthRejection(events.get(1)!!)
        Assertions.assertThat<OperationDiagnostics?>(events)
            .hasSize(4)
            .anySatisfy(ThrowingConsumer { event: OperationDiagnostics? ->
                Assertions.assertThat<StrategyParticipation?>(
                    event!!.auxiliaryStrategies
                ).isNotEmpty()
            })
            .anySatisfy(ThrowingConsumer { event: OperationDiagnostics? ->
                Assertions.assertThat<StrategyDecision?>(
                    event!!.strategyDecisions
                ).isNotEmpty()
            })
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.captureStrategy).isNotEqualTo(MatchStrategy.LENGTH)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .extracting<MatchStrategy?, RuntimeException?>(StrategyParticipation::strategy)
                        .doesNotContain(MatchStrategy.LENGTH)
                    Assertions.assertThat<StrategyDecision?>(event.strategyDecisions)
                        .extracting<MatchStrategy?, RuntimeException?>(StrategyDecision::strategy)
                        .doesNotContain(MatchStrategy.LENGTH)
                })
    }

    @Tag("work-counter")
    @ParameterizedTest
    @ValueSource(strings = ["a{1000}", "[ab]{1000}", "[ab]{1000}\\z", "^[ab]{1000}"])
    fun diagnosticsPreserveConstantWorkForShortInputs(regex: String?) {
        val pattern = Pattern.compile(regex)
        for (size in mutableListOf<Int>(200, 800)) {
            val text = "a".repeat(size!!)
            val input = Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8))
            for (search in listOf(
                { Assertions.assertThat(pattern.find(input)).isFalse() },
                { Assertions.assertThat(pattern.matcher(input).find()).isFalse() },
                { Assertions.assertThat(pattern.matcher(text).find()).isFalse() })) {
                Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
                search()
                val withoutDiagnostics = WorkCounter.countForTesting(search)
                Pattern.setDiagnostics(listener)
                search()
                val withDiagnostics = WorkCounter.countForTesting(search)
                Assertions.assertThat(withDiagnostics).`as`("%s, length %s", regex, size).isEqualTo(withoutDiagnostics)
                Assertions.assertThat(withDiagnostics).isZero()
                events.clear()
            }
        }
    }

    companion object {
        private fun assertLengthRejection(event: OperationDiagnostics) {
            Assertions.assertThat<MatchOutcome?>(event.outcome).isEqualTo(MatchOutcome.NO_MATCH)
            Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LENGTH)
            Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
            Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
            Assertions.assertThat(event.matchCount).isZero()
            Assertions.assertThat(event.forwardDfaSearchCount).isZero()
            Assertions.assertThat(event.reverseDfaSearchCount).isZero()
            Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies).isEmpty()
            Assertions.assertThat<StrategyDecision?>(event.strategyDecisions).isEmpty()
        }
    }
}
