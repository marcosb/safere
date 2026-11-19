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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.RecordComponent
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.Map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.function.Function
import java.util.function.IntConsumer
import java.util.function.Predicate
import java.util.regex.MatchResult
import java.util.regex.Pattern
import java.util.stream.IntStream
import java.util.stream.Stream

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("match diagnostics are a SafeRE-specific public API")
internal class DiagnosticsTest {
    private val diagnostics = RecordingDiagnostics()

    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @Test
    fun anchoredOnePassUsesTerminalRejectionBeforeScanning() {
        Pattern.setDiagnostics(diagnostics)
        val input = "a".repeat(4096) + "c"
        for (regex in mutableListOf<String?>("^(a)*b$", "^([a-z]+)[0-9]$", "^([a-z]+)END\\z")) {
            val pattern = Pattern.compile(regex)
            Assertions.assertThat(pattern.canOnePassPrimary()).`as`("/%s/", regex).isTrue()
            Assertions.assertThat(pattern.matcher(input).matches()).isFalse()
            Assertions.assertThat(pattern.matcher(input).find()).isFalse()
            Assertions.assertThat(pattern.matcher(input).replaceAll("$1")).isEqualTo(input)
            val utf8 = Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
            Assertions.assertThat(pattern.matcher(utf8).matches()).isFalse()
            Assertions.assertThat(pattern.matcher(utf8).find()).isFalse()
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .hasSize(5)
                .allSatisfy(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy)
                            .isIn(MatchStrategy.LITERAL, MatchStrategy.CHARACTER_CLASS)
                        Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                        Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                            .anyMatch(Predicate { strategy: StrategyParticipation? -> strategy!!.role == StrategyRole.REJECT_PREFILTER })
                    })
        }
    }

    @Test
    fun patternIdentityIsStableAndDistinctWithoutDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        val first = Pattern.compile("abc")
        val second = Pattern.compile("abc")

        val firstId = first.patternId()
        Assertions.assertThat(firstId).isPositive()
        Assertions.assertThat(second.patternId()).isPositive().isNotEqualTo(firstId)
        Assertions.assertThat(first.matcher("abc").matches()).isTrue()
        Assertions.assertThat(first.patternId()).isEqualTo(firstId)
    }

    @Test
    @Throws(Exception::class)
    fun deserializedPatternHasANewDiagnosticsIdentity() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        val original = Pattern.compile("(a+)b", Pattern.CASE_INSENSITIVE)
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { output ->
            output.writeObject(original)
        }
        val restored: Pattern
        ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            restored = input.readObject() as Pattern
        }
        Assertions.assertThat(restored.patternId()).isPositive().isNotEqualTo(original.patternId())
        Assertions.assertThat(restored.pattern()).isEqualTo(original.pattern())
        Assertions.assertThat(restored.flags()).isEqualTo(original.flags())
        Pattern.setDiagnostics(diagnostics)
        Assertions.assertThat(restored.matcher("AAB").matches()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(diagnostics.operations)
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat(event!!.pattern.patternId).isEqualTo(restored.patternId())
                })
    }

    @Test
    fun staticAnalysisReportsFeaturesCapabilitiesAndLimitations() {
        val pattern = Pattern.compile("^(?:(a)?)*$")

        val analysis = pattern.analysis()

        Assertions.assertThat<PatternFeature?>(analysis.features)
            .contains(
                PatternFeature.CAPTURES,
                PatternFeature.ANCHOR,
                PatternFeature.START_ANCHOR,
                PatternFeature.END_ANCHOR,
                PatternFeature.NULLABLE,
                PatternFeature.NESTED_NULLABLE_QUANTIFIER,
                PatternFeature.PROGRESS_CHECK,
                PatternFeature.CAPTURES_IN_QUANTIFIER
            )
        Assertions.assertThat<PatternCapability?>(analysis.capabilities)
            .contains(PatternCapability.DFA_REJECT_PREFILTER, PatternCapability.NFA)
        Assertions.assertThat<PatternLimitation?>(analysis.limitations)
            .contains(
                PatternLimitation.NULLABLE_LOOP_REQUIRES_EXACT_ENGINE,
                PatternLimitation.CAPTURE_PRIORITY_REQUIRES_EXACT_ENGINE
            )
        Assertions.assertThat(analysis.captureCount).isEqualTo(1)
        Assertions.assertThatThrownBy(ThrowingCallable { analysis.features.clear() })
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun listenerInstalledAfterCompilationStillReceivesPatternAnalysis() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        val pattern = Pattern.compile("abc")
        val patternId = pattern.patternId()
        Pattern.setDiagnostics(diagnostics)

        Assertions.assertThat(pattern.matcher("abc").matches()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<PatternAnalysis?>(event!!.pattern.analysis).isSameAs(pattern.analysis())
                    Assertions.assertThat(event.pattern.patternId).isEqualTo(patternId)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                })
        Assertions.assertThat<PatternCompiledEvent?>(diagnostics.compilations).isEmpty()
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        Assertions.assertThat(pattern.patternId()).isEqualTo(patternId)
    }

    @Test
    fun asPredicateReportsOneFindOperation() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("[0-9]+x")

        Assertions.assertThat(pattern.asPredicate().test("ab 12x cd")).isTrue()
        Assertions.assertThat(pattern.asPredicate().test("ab 12 cd")).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .extracting(OperationDiagnostics::operation, OperationDiagnostics::outcome)
            .containsExactly(
                Assertions.tuple(MatchOperation.FIND, MatchOutcome.MATCH),
                Assertions.tuple(MatchOperation.FIND, MatchOutcome.NO_MATCH)
            )
    }

    @Test
    fun literalAlternationSearchesReportADistinctStrategy() {
        Pattern.setDiagnostics(diagnostics)
        for (regex in mutableListOf<String?>("apple|banana|cherry", "foo|barfoo")) {
            val pattern = Pattern.compile(regex)
            for (input in mutableListOf<String?>("barfoo apple", "xxxxx")) {
                Assertions.assertThat(pattern.matcher(input).find()).isEqualTo(input == "barfoo apple")
                val event: OperationDiagnostics = operationsFor(pattern).last()
                Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).`as`(regex)
                    .isEqualTo(MatchStrategy.LITERAL_ALTERNATION)
                Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                Assertions.assertThat(event.reverseDfaSearchCount).isZero()
            }
        }
    }

    @Test
    fun compilationAndOperationsShareDescriptor() {
        Pattern.setDiagnostics(diagnostics)

        val pattern = Pattern.compile("^GET ([^ ]+)")
        Assertions.assertThat(pattern.matcher("GET /index").lookingAt()).isTrue()

        val compilation =
            diagnostics.compilations.stream()
                .filter { event: PatternCompiledEvent? -> event!!.pattern.patternId == pattern.patternId() }
                .findFirst()
                .orElseThrow()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<PatternDescriptor?>(event!!.pattern).isSameAs(compilation.pattern)
                    Assertions.assertThat<MatchOperation?>(event.operation).isEqualTo(MatchOperation.LOOKING_AT)
                    Assertions.assertThat<MatchOutcome?>(event.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })
    }

    @Test
    fun capturedLiteralRetainsEnginesNeededWhenLiteralRunnerIsIneligible() {
        Pattern.setDiagnostics(diagnostics)

        val lookingAtPattern = Pattern.compile("(foo)")
        Assertions.assertThat(lookingAtPattern.matcher("foobar").lookingAt()).isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(lookingAtPattern).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.ONE_PASS)

        val foldedUtf8LookingAt = Pattern.compile("(?i)foo")
        Assertions.assertThat(
            foldedUtf8LookingAt.matcher(Utf8Input.validated("FOObar".toByteArray(StandardCharsets.UTF_8))).lookingAt()
        )
            .isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(foldedUtf8LookingAt).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.ONE_PASS)

        val foldedUtf8Matches = Pattern.compile("(?i)foo")
        Assertions.assertThat(
            foldedUtf8Matches.matcher(Utf8Input.validated("FOO".toByteArray(StandardCharsets.UTF_8))).matches()
        )
            .isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(foldedUtf8Matches).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.SHIFT_DFA)

        val foldedUtf8Find = Pattern.compile("(?i)foo")
        Assertions.assertThat(
            foldedUtf8Find.matcher(Utf8Input.validated("xxFOO".toByteArray(StandardCharsets.UTF_8))).find()
        )
            .isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(foldedUtf8Find).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.DFA)
    }

    @ParameterizedTest
    @MethodSource("lazyShiftDfaSearchCases")
    fun lazyShiftDfaSearchesRetainOnePassFallback(
        regex: String?, input: String, operation: MatchOperation?, utf8: Boolean
    ) {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile(regex)
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()

        val matched: Boolean
        if (utf8) {
            val matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            matched = if (operation == MatchOperation.FIND) matcher.find() else matcher.lookingAt()
        } else {
            val matcher = pattern.matcher(input)
            matched = if (operation == MatchOperation.FIND) matcher.find() else matcher.lookingAt()
        }

        Assertions.assertThat(matched).isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(pattern).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.ONE_PASS)
        Assertions.assertThat(pattern.shiftDfaAnalyzed()).isFalse()
    }

    @Test
    fun forcedBitStateAndNfaReportTheExactEngine() {
        Pattern.setDiagnostics(diagnostics)
        val bitStateOnly =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .build()
        val bitState = Pattern.compile("a+b", 0, bitStateOnly)

        Assertions.assertThat(bitState.matcher("aaab").matches()).isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(bitState).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.BIT_STATE)

        val nfaOnly =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .bitState(false)
                .build()
        val nfa = Pattern.compile("a+b", 0, nfaOnly)

        Assertions.assertThat(nfa.matcher("aaab").matches()).isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(nfa).last().boundaryStrategy)
            .isEqualTo(MatchStrategy.NFA)
    }

    @ParameterizedTest
    @MethodSource("reverseCandidateVerificationCases")
    fun utf8ReverseCandidateReportsItsExactEngine(bitState: Boolean, regex: String, input: String) {
        Pattern.setDiagnostics(diagnostics)
        val pattern =
            Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(bitState).build())

        Assertions.assertThat(pattern.find(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat(event!!.reverseDfaSearchCount).isPositive()
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                        .isEqualTo(if (bitState) MatchStrategy.BIT_STATE else MatchStrategy.NFA)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .contains(
                            StrategyParticipation(
                                if (bitState) MatchStrategy.BIT_STATE else MatchStrategy.NFA,
                                StrategyRole.CANDIDATE_VERIFICATION
                            )
                        )
                    if (regex == "foo[ab]*$") {
                        Assertions.assertThat(event.reverseDfaSearchCount).isEqualTo(2)
                    }
                })
    }

    @Test
    fun replacementFastPathEmitsOneOperationWithTotalMatchCount() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("[a-z]+")

        Assertions.assertThat(pattern.matcher("a 22 bb").replaceAll("x")).isEqualTo("x 22 x")

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.REPLACE_ALL)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                        .isEqualTo(MatchStrategy.CHARACTER_CLASS)
                    Assertions.assertThat(event.matchCount).isEqualTo(2)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(
                                MatchStrategy.CHARACTER_CLASS, StrategyRole.CANDIDATE_VERIFICATION
                            )
                        )
                })
    }

    @Test
    fun compositeRejectPrefilterReportsTheChildThatRejected() {
        Pattern.setDiagnostics(diagnostics)

        for (operation in List.of<MatchOperation?>(MatchOperation.FIND, MatchOperation.MATCHES)) {
            // Reach the composite prefilter rather than the earlier minimum-length rejection.
            assertCompositeRejection(operation, "needle ", MatchStrategy.CHARACTER_CLASS)
            assertCompositeRejection(operation, "xxxxxxx", MatchStrategy.LITERAL)
        }
    }

    @Test
    fun compositeRejectPrefilterReplacementReportsTheChildThatRejected() {
        Pattern.setDiagnostics(diagnostics)

        for (operation in List.of<MatchOperation?>(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
            val pattern = Pattern.compile("foo.*bar.*[0-9]$")
            val matcher = pattern.matcher("foo---7")

            val result =
                if (operation == MatchOperation.REPLACE_FIRST)
                    matcher.replaceFirst("replacement")
                else
                    matcher.replaceAll("replacement")

            Assertions.assertThat(result).isEqualTo("foo---7")
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(operation)
                        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                            .containsExactly(
                                StrategyParticipation(
                                    MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                                )
                            )
                    })
        }
    }

    @Test
    fun citationScrubberReplacementRejectsOnCharacterClassPrefilter() {
        Pattern.setDiagnostics(diagnostics)

        val regex = " ?[\\[\uFF3B](?:(?:\\d+\\.){2,}\\d+(?:, )?)+[\\]\uFF3D]"
        val pattern = Pattern.compile(regex)

        for (input in mutableListOf<String?>("digits 1.2.3.4 and dots but no brackets", "letters and spaces only")) {
            for (operation in List.of<MatchOperation?>(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
                diagnostics.operations.clear()
                val matcher = pattern.matcher(input)
                val result =
                    if (operation == MatchOperation.REPLACE_FIRST)
                        matcher.replaceFirst("replacement")
                    else
                        matcher.replaceAll("replacement")

                Assertions.assertThat(result).isEqualTo(input)
                Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                    .singleElement()
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(operation)
                            Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                                .isEqualTo(MatchStrategy.CHARACTER_CLASS)
                            Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                                .contains(
                                    StrategyParticipation(
                                        MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER
                                    )
                                )
                        })
            }
        }

        // Brackets present with no matching digits return the right result without false-positive
        // match.
        for (input in mutableListOf<String?>(
            "[brackets without digits]",
            "prefix [1.2 incomplete] suffix",
            "［full width brackets without digits］"
        )) {
            for (operation in List.of<MatchOperation?>(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
                diagnostics.operations.clear()
                val matcher = pattern.matcher(input)
                val result =
                    if (operation == MatchOperation.REPLACE_FIRST)
                        matcher.replaceFirst("replacement")
                    else
                        matcher.replaceAll("replacement")

                Assertions.assertThat(result).isEqualTo(input)
                Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                    .singleElement()
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(operation)
                            Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                        })
            }
        }
    }

    @Test
    fun startAnchoredReplacementRejectsOnTheAnchoredPrefix() {
        Pattern.setDiagnostics(diagnostics)

        // Start-anchored scrubbers with a literal anchored prefix of "(" and "[" respectively.
        for (regex in mutableListOf<String?>(
            "^\\([Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\)\\s*",
            "^\\[[Ss]ource:\\s*\\d+(?:\\s*,\\s*(?:[Ss]ource:\\s*)?\\d+)*\\s*\\]\\s*"
        )) {
            for (operation in List.of<MatchOperation?>(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
                val pattern = Pattern.compile(regex)
                val input = "No citation marker begins this paragraph."
                val matcher = pattern.matcher(input)

                val result =
                    if (operation == MatchOperation.REPLACE_FIRST)
                        matcher.replaceFirst("replacement")
                    else
                        matcher.replaceAll("replacement")

                Assertions.assertThat(result).isEqualTo(input)
                Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                    .singleElement()
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(operation)
                            Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                                .isEqualTo(MatchStrategy.LITERAL)
                            Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                                .containsExactly(
                                    StrategyParticipation(
                                        MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                                    )
                                )
                        })
            }
        }
    }

    @Test
    fun startAnchoredReplacementRejectsOnTheAnchoredCharacterClass() {
        Pattern.setDiagnostics(diagnostics)

        for (operation in List.of<MatchOperation?>(MatchOperation.REPLACE_FIRST, MatchOperation.REPLACE_ALL)) {
            val pattern = Pattern.compile("^[0-9]+ items")
            val input = "no leading digit here"
            val matcher = pattern.matcher(input)

            val result =
                if (operation == MatchOperation.REPLACE_FIRST)
                    matcher.replaceFirst("replacement")
                else
                    matcher.replaceAll("replacement")

            Assertions.assertThat(result).isEqualTo(input)
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(operation)
                        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                            .isEqualTo(MatchStrategy.CHARACTER_CLASS)
                        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                            .containsExactly(
                                StrategyParticipation(
                                    MatchStrategy.CHARACTER_CLASS, StrategyRole.REJECT_PREFILTER
                                )
                            )
                    })
        }
    }

    @Test
    fun multilineStartAnchorDoesNotTakeTheStartAnchoredRejectPath() {
        Pattern.setDiagnostics(diagnostics)

        // (?m)^ lowers to a begin-line assertion, not prog().anchorStart(), so the new reject arm must
        // not fire: a match can legitimately begin at a later line.
        val pattern = Pattern.compile("(?m)^\\(Source: \\d+\\)\\s*")
        val matcher = pattern.matcher("first line\n(Source: 7) second line")

        Assertions.assertThat(matcher.replaceAll("")).isEqualTo("first line\nsecond line")
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<StrategyParticipation?>(event!!.auxiliaryStrategies)
                        .doesNotContain(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                            )
                        )
                })
    }

    @Test
    fun ordinaryReplacementLoopSuppressesNestedFindEvents() {
        Pattern.setDiagnostics(diagnostics)
        val exactOnly =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .charClassReplacementFastPath(false)
                .keywordAlternationFastPath(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .build()
        val pattern = Pattern.compile("(a)", 0, exactOnly)

        Assertions.assertThat(pattern.matcher("aba").replaceAll("$1x")).isEqualTo("axbax")

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.REPLACE_ALL)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat(event.matchCount).isEqualTo(2)
                })
    }

    @Test
    fun dfaReplacementReportsOneEventAndAllMatches() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("a+b")

        Assertions.assertThat(pattern.matcher("aaab xx ab").replaceAll("z")).isEqualTo("z xx z")

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.REPLACE_ALL)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.matchCount).isEqualTo(2)
                    // The terminal start-accelerator scan rejects without another DFA search.
                    Assertions.assertThat(event.forwardDfaSearchCount).isEqualTo(2)
                    Assertions.assertThat(event.reverseDfaSearchCount).isEqualTo(2)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .contains(
                            StrategyParticipation(
                                MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION
                            )
                        )
                })
    }

    @Test
    fun captureHistoryPreservesDfaReplacementBoundaryAcrossTerminalRejection() {
        val pattern = Pattern.compile("(error:\\[)[A-Z](\\] code:500)")
        val priming = pattern.matcher("error:[C] code:500")
        Assertions.assertThat(priming.find()).isTrue()
        Assertions.assertThat(priming.group(1)).isEqualTo("error:[")

        Pattern.setDiagnostics(diagnostics)
        Assertions.assertThat(
            pattern.matcher("error:[C] code:500 xxx error:[x] code:500")
                .replaceAll(Function { result: MatchResult? -> "ok" })
        )
            .isEqualTo("ok xxx error:[x] code:500")

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.REPLACE_ALL)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.matchCount).isEqualTo(1)
                })
    }

    @Test
    fun nullableDfaReplacementCountsTerminalEmptyMatch() {
        Pattern.setDiagnostics(diagnostics)

        for (input in mutableListOf<String?>("", "b", "bbb")) {
            val pattern = Pattern.compile("a?")
            val expectedMatches = input!!.length + 1L

            Assertions.assertThat(pattern.matcher(input).replaceAll("x"))
                .isEqualTo(Pattern.compile("a?").matcher(input).replaceAll("x"))

            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.REPLACE_ALL)
                        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                        Assertions.assertThat(event.matchCount).isEqualTo(expectedMatches)
                    })
        }
    }

    @Test
    fun dfaSandwichReportsForwardAndReverseSearchCounts() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("[ab]+c")

        Assertions.assertThat(pattern.matcher("xxabc").find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.forwardDfaSearchCount).isEqualTo(1)
                    Assertions.assertThat(event.reverseDfaSearchCount).isEqualTo(1)
                })
    }

    @Test
    fun captureHistoryFromEagerResultsPreservesAcceleratedDfaRejection() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        for (utf8 in booleanArrayOf(false, true)) {
            val pattern = org.safere.Pattern.compile("(error:\\[)[A-Z](\\] code:500)")
            val successful = "error:[C] code:500"
            val rejected = "error:[x] code:500 ".repeat(20)
            if (utf8) {
                val primer = pattern.matcher(Utf8Input.trusted(successful.toByteArray(StandardCharsets.UTF_8)))
                Assertions.assertThat(primer.matches()).isTrue()
                Assertions.assertThat(primer.start(1)).isZero()
            } else {
                val primer = pattern.matcher(successful)
                Assertions.assertThat(primer.matches()).isTrue()
                Assertions.assertThat(primer.start(1)).isZero()
            }
            Assertions.assertThat(pattern.innerCapturesObserved()).isTrue()
            if (utf8) {
                Assertions.assertThat(
                    pattern.matcher(Utf8Input.trusted(rejected.toByteArray(StandardCharsets.UTF_8))).find()
                ).isFalse()
            } else {
                Assertions.assertThat(pattern.matcher(rejected).find()).isFalse()
            }
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .last()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                        Assertions.assertThat(event.forwardDfaSearchCount).isPositive()
                    })
        }
    }

    @ParameterizedTest
    @MethodSource("captureHistoryFindCases")
    fun captureHistoryKeepsUnanchoredFindDfaFirst(utf8: Boolean, accelerate: Boolean) {
        val pattern: org.safere.Pattern = captureHistoryPattern(accelerate, false)
        org.safere.Pattern.setDiagnostics(diagnostics)
        val input = "prefix error:[C] code:500 suffix"
        if (utf8) {
            val matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(matcher.find()).isTrue()
            assertDfaFindBoundary(pattern)
            Assertions.assertThat(input.substring(matcher.start(1), matcher.end(1))).isEqualTo("error:[")
            Assertions.assertThat(input.substring(matcher.start(2), matcher.end(2))).isEqualTo("] code:500")
        } else {
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.find()).isTrue()
            assertDfaFindBoundary(pattern)
            Assertions.assertThat(matcher.group(1)).isEqualTo("error:[")
            Assertions.assertThat(matcher.group(2)).isEqualTo("] code:500")
        }
    }

    @ParameterizedTest
    @MethodSource("captureHistoryFindCases")
    fun captureHistoryKeepsUnanchoredRejectionOnDfa(utf8: Boolean, accelerate: Boolean) {
        val pattern: org.safere.Pattern = captureHistoryPattern(accelerate, false)
        org.safere.Pattern.setDiagnostics(diagnostics)
        // All required literals and an uppercase character occur, but not in a matching arrangement.
        val input = "prefix error:[x] code:500 suffix C"
        if (utf8) {
            Assertions.assertThat(
                pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))).find()
            ).isFalse()
        } else {
            Assertions.assertThat(pattern.matcher(input).find()).isFalse()
        }
        assertDfaFindBoundary(pattern)
    }

    @ParameterizedTest
    @MethodSource("captureHistoryFindCases")
    fun captureHistoryPreservesAnchoredFindPreference(utf8: Boolean, accelerate: Boolean) {
        val pattern: org.safere.Pattern = captureHistoryPattern(accelerate, true)
        org.safere.Pattern.setDiagnostics(diagnostics)
        val input = "error:[D] code:500"
        if (utf8) {
            val matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(input.substring(matcher.start(1), matcher.end(1))).isEqualTo("error:[")
        } else {
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.group(1)).isEqualTo("error:[")
        }
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .last()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                })
    }

    private fun assertDfaFindBoundary(pattern: org.safere.Pattern) {
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .last()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.forwardDfaSearchCount).isPositive()
                })
    }

    @Test
    fun replacementCaptureModeAgreesWithCaptureStrategyAcrossTerminalFindFailure() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val deferredPattern = org.safere.Pattern.compile("(a+)b")

        Assertions.assertThat(deferredPattern.matcher("aaab xx ab").replaceAll("x")).isEqualTo("x xx x")

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(deferredPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })

        val eagerPattern = org.safere.Pattern.compile("(a+)b")
        Assertions.assertThat(eagerPattern.matcher("aaab xx ab").replaceAll("$1")).isEqualTo("aaa xx a")
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(eagerPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.captureStrategy).isNotEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })
    }

    @Test
    fun functionalReplacementAlsoSuppressesNestedFindEvents() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("a")

        Assertions.assertThat(
            pattern.matcher("aba").replaceAll(Function { result: MatchResult? -> result!!.group() + "x" })
        )
            .isEqualTo("axbax")

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.REPLACE_ALL)
                    Assertions.assertThat(event.matchCount).isEqualTo(2)
                })
    }

    @Test
    fun nullableLoopDfaCanAuthoritativelyReject() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val dfaOnly =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .onePass(false)
                .bitState(false)
                .build()
        val pattern = org.safere.Pattern.compile("(?:(a)?)*X", 0, dfaOnly)

        Assertions.assertThat(pattern.matcher("aaaa").find()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .contains(
                            StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
                        )
                })

        Assertions.assertThat(pattern.matcher("aaaaX").find()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern).last())
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<StrategyDecision?>(event.strategyDecisions)
                        .contains(
                            StrategyDecision(
                                MatchStrategy.DFA,
                                StrategyDisposition.BYPASSED,
                                StrategyReason.EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED
                            )
                        )
                })
    }

    @Test
    fun usePatternRunsDisjointLiteralPrefilterForReplacementPattern() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val first = org.safere.Pattern.compile("(?:banana\\d|apple\\d)")
        val replacement = org.safere.Pattern.compile("(?:cherry\\d|pear\\d)")
        val matcher = first.matcher("apple0 remainder")

        Assertions.assertThat(matcher.find()).isTrue()
        matcher.usePattern(replacement)
        matcher.reset("remainder")
        Assertions.assertThat(matcher.find()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(replacement))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<StrategyParticipation?>(event!!.auxiliaryStrategies)
                        .contains(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                            )
                        )
                })
    }

    @Test
    fun prefixCandidateFailureContinuesThroughDfa() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern =
            org.safere.Pattern.compile("(?s)<block>\\n(\\s)*# (category|group)_defined:.*?\\n</block>")
        val input =
            "<block>\n  # comment\n</block> " + "<block>\n  # category_defined: alpha\n</block>"

        Assertions.assertThat(pattern.matcher(input).find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .startsWith(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION
                            )
                        )
                        .contains(
                            StrategyParticipation(
                                MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION
                            )
                        )
                })
    }

    @Test
    fun dfaBoundsCanLeaveCapturesDeferred() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("(a+)b")
        val matcher = pattern.matcher("xxaaab")

        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
        Assertions.assertThat(matcher.group(1)).isEqualTo("aaa")
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern)).hasSize(1)
    }

    @Test
    fun failedRegexOperationDoesNotEmitAnEvent() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val matcher = org.safere.Pattern.compile("a").matcher("a")

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.find(2) })
            .isInstanceOf(IndexOutOfBoundsException::class.java)

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(matcher.pattern())).isEmpty()
    }

    @Test
    fun listenerCanAggregateOperationsFromMultipleThreads() {
        val pattern = org.safere.Pattern.compile("abc")
        val events = ConcurrentLinkedQueue<OperationDiagnostics?>()
        org.safere.Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    events.add(event)
                }
            })

        IntStream.range(0, 32)
            .parallel()
            .forEach(IntConsumer { ignored: Int -> Assertions.assertThat(pattern.matcher("abc").matches()).isTrue() })

        Assertions.assertThat<OperationDiagnostics?>(events)
            .filteredOn(Predicate { event: OperationDiagnostics? -> event!!.pattern.patternId == pattern.descriptor().patternId })
            .hasSize(32)
    }

    @Test
    fun listenerFailurePropagatesAfterMatcherStateIsFinalized() {
        val pattern = org.safere.Pattern.compile("abc")
        val matcher = pattern.matcher("abc")
        org.safere.Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    throw IllegalStateException("listener failed")
                }
            })

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.matches() })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("listener failed")
        Assertions.assertThat(matcher.group()).isEqualTo("abc")
    }

    @Test
    fun listenerReplacementResetAndOperationSnapshotAreStable() {
        val pattern = org.safere.Pattern.compile("abc")
        val replacement = RecordingDiagnostics()
        val replacingListener: SafeReMatchDiagnostics =
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    diagnostics.operations.add(event!!)
                    org.safere.Pattern.setDiagnostics(replacement)
                }
            }
        org.safere.Pattern.setDiagnostics(replacingListener)

        Assertions.assertThat(pattern.matcher("abc").matches()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(diagnostics.operations).hasSize(1)
        Assertions.assertThat<OperationDiagnostics?>(replacement.operations).isEmpty()

        Assertions.assertThat(pattern.matcher("abc").matches()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(replacement.operations).hasSize(1)

        org.safere.Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        Assertions.assertThat(pattern.matcher("abc").matches()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(replacement.operations).hasSize(1)
    }

    @Test
    fun eventsContainOnlyOpaqueIdentityAnalysisAndAggregateInputFacts() {
        val regexSecret = "diagnosticPatternSecret"
        val inputSecret = "prefix-diagnosticInputSecret-suffix"
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile(regexSecret)

        Assertions.assertThat(pattern.matcher(inputSecret).find()).isFalse()

        val event: OperationDiagnostics = operationsFor(pattern).first()
        Assertions.assertThat(event.toString()).doesNotContain(regexSecret, inputSecret)
        Assertions.assertThat<RecordComponent>(OperationDiagnostics::class.java.getRecordComponents())
            .noneMatch(Predicate { component: RecordComponent? -> component!!.getType() == String::class.java })
        Assertions.assertThat<RecordComponent>(PatternDescriptor::class.java.getRecordComponents())
            .noneMatch(Predicate { component: RecordComponent? -> component!!.getType() == String::class.java })
        Assertions.assertThat(event.inputLength).isEqualTo(inputSecret.length)
    }

    @Test
    fun keywordAlternationReportsKeywordStrategy() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("(?i)\\b(error|warning|timeout|failed)\\b")

        Assertions.assertThat(pattern.matcher("Info: Warning").find()).isTrue()

        Assertions.assertThat<MatchStrategy?>(operationsFor(pattern).first().boundaryStrategy)
            .isEqualTo(MatchStrategy.KEYWORD)
    }

    @Test
    fun largeInputBypassesBitStateAndReportsNfaFallback() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val exactOnly =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .build()
        val pattern = org.safere.Pattern.compile("a+b", 0, exactOnly)

        Assertions.assertThat(pattern.matcher("a".repeat(100000) + "b").matches()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern).first())
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<StrategyDecision?>(event.strategyDecisions)
                        .contains(
                            StrategyDecision(
                                MatchStrategy.BIT_STATE,
                                StrategyDisposition.BYPASSED,
                                StrategyReason.INPUT_TOO_LARGE
                            )
                        )
                })
    }

    @Test
    fun graphemeSemanticsReportUnsupportedOptimizedPathsAndExactFallback() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("\\X")

        Assertions.assertThat(pattern.matcher("a\u0301").matches()).isTrue()

        Assertions.assertThat<PatternFeature?>(pattern.analysis().features).contains(PatternFeature.GRAPHEME)
        Assertions.assertThat<PatternLimitation?>(pattern.analysis().limitations)
            .contains(PatternLimitation.GRAPHEME_REQUIRES_EXACT_ENGINE)
        Assertions.assertThat<MatchStrategy?>(operationsFor(pattern).first().boundaryStrategy)
            .isEqualTo(MatchStrategy.NFA)
    }

    @Test
    fun dfaBudgetExhaustionReportsFallbackToAnExactEngine() {
        val regex = "[ab]*a" + "[ab]".repeat(14) + "X"
        val pattern = org.safere.Pattern.compile(regex)
        val oracle = Pattern.compile(regex)
        val observedBudgetFallback = AtomicBoolean()
        val observedForwardSearchCount = AtomicInteger()
        org.safere.Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics) {
                    if (event
                            .strategyDecisions
                            .contains(
                                StrategyDecision(
                                    MatchStrategy.DFA,
                                    StrategyDisposition.FALLBACK,
                                    StrategyReason.DFA_BUDGET_EXCEEDED
                                )
                            )
                    ) {
                        observedBudgetFallback.set(true)
                        observedForwardSearchCount.set(event.forwardDfaSearchCount)
                    }
                }
            })

        var bits = 0
        while (bits < 1 shl 15 && !observedBudgetFallback.get()) {
            val input = StringBuilder(16)
            for (index in 0..14) {
                input.append(if ((bits and (1 shl index)) == 0) 'a' else 'b')
            }
            input.append('X')
            val text = input.toString()
            Assertions.assertThat(pattern.matcher(text).matches()).isEqualTo(oracle.matcher(text).matches())
            bits++
        }

        Assertions.assertThat(observedBudgetFallback).isTrue()
        Assertions.assertThat(observedForwardSearchCount).hasPositiveValue()
    }

    @Test
    fun exactEngineRespectsRegionAndAnchoringBoundsAndReportsItsStrategy() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val exactOnly =
            EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .onePass(false)
                .dfa(false)
                .build()
        val pattern = org.safere.Pattern.compile("^a", 0, exactOnly)
        val matcher = pattern.matcher("za").region(1, 2).useAnchoringBounds(true)

        Assertions.assertThat(matcher.matches()).isTrue()

        Assertions.assertThat<MatchStrategy?>(operationsFor(pattern).first().boundaryStrategy)
            .isEqualTo(MatchStrategy.BIT_STATE)
    }

    @Test
    fun exhaustedStartAnchorDoesNotReportPrefilterParticipation() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("^a")
        val matcher = pattern.matcher("za").region(1, 2).useAnchoringBounds(true)

        Assertions.assertThat(matcher.find()).isTrue()
        Assertions.assertThat(matcher.find()).isFalse()

        Assertions.assertThat<StrategyParticipation?>(operationsFor(pattern).get(1).auxiliaryStrategies).isEmpty()
    }

    @Test
    fun startAnchoredRejectionReportsPrefilterStrategy() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val cases =
            Map.of<String?, MatchStrategy?>("^target", MatchStrategy.LITERAL, "^[a-z]", MatchStrategy.CHARACTER_CLASS)

        cases.forEach { (regex: String?, expectedStrategy: MatchStrategy?) ->
            val pattern = org.safere.Pattern.compile(regex)
            Assertions.assertThat(pattern.matcher("9target").find()).isFalse()
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(expectedStrategy)
                        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                            .containsExactly(
                                StrategyParticipation(
                                    expectedStrategy, StrategyRole.REJECT_PREFILTER
                                )
                            )
                    })
        }
    }

    @Test
    fun listenerCanAggregateStrategiesWithLongAdders() {
        val pattern = org.safere.Pattern.compile("abc")
        val counts: MutableMap<MatchStrategy?, LongAdder?> = ConcurrentHashMap<MatchStrategy?, LongAdder?>()
        org.safere.Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics) {
                    counts
                        .computeIfAbsent(event.boundaryStrategy) { ignored: MatchStrategy? -> LongAdder() }!!
                        .increment()
                }
            })

        IntStream.range(0, 64)
            .parallel()
            .forEach(IntConsumer { ignored: Int -> Assertions.assertThat(pattern.matcher("abc").matches()).isTrue() })

        Assertions.assertThat(counts.get(MatchStrategy.LITERAL)!!.sum()).isEqualTo(64)
    }

    @Test
    fun variableGapChainsFallBackToGeneralEngine() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile(".*error:\\[[A-Z]+\\]\\s+code:500\\s+msg:crash")
        val input = "2026-08-27 12:00:00 [worker-1] error:[CRITICAL] code:500 msg:crash\n"
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.forwardDfaSearchCount).isPositive()
                })
    }

    @Test
    fun multiAnchorNegativeShortCircuitBypassesDfa() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile(".*error:\\[[A-Z]+\\]\\s+code:500\\s+msg:crash")
        val input = "2026-08-27 12:00:00 [worker-1] error:[NORMAL] code:200 msg:ok\n"
        val matcher = pattern.matcher(input)
        Assertions.assertThat(matcher.find()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat(event!!.forwardDfaSearchCount).isZero()
                })
    }

    @Test
    fun primaryBitStateAndNfaCaptureSearchesReportEagerCaptureAttribution() {
        org.safere.Pattern.setDiagnostics(diagnostics)

        val nullableLoopBitState =
            org.safere.Pattern.compile(
                "(?:(a)?)*b", 0, EnginePathOptions.builder().dfa(false).onePass(false).build()
            )
        val matchesMatcher = nullableLoopBitState.matcher("aab")
        Assertions.assertThat(matchesMatcher.matches()).isTrue()
        Assertions.assertThat(matchesMatcher.capturesResolved()).isTrue()

        val lookingAtMatcher = nullableLoopBitState.matcher("aabx")
        Assertions.assertThat(lookingAtMatcher.lookingAt()).isTrue()
        Assertions.assertThat(lookingAtMatcher.capturesResolved()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(nullableLoopBitState))
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })
        val nfaPattern = org.safere.Pattern.compile("(\\X)b")
        val nfaMatches = nfaPattern.matcher("ab")
        Assertions.assertThat(nfaMatches.matches()).isTrue()
        Assertions.assertThat(nfaMatches.capturesResolved()).isTrue()

        val nfaFind = nfaPattern.matcher("xxab")
        Assertions.assertThat(nfaFind.find()).isTrue()
        Assertions.assertThat(nfaFind.capturesResolved()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(nfaPattern))
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })
    }

    @Test
    fun regionFindReportsIndependentTextBoundsAndDeferredCaptureAttribution() {
        org.safere.Pattern.setDiagnostics(diagnostics)

        val independentPattern = org.safere.Pattern.compile("(a+?)b")
        val independentMatcher = independentPattern.matcher("aab")
        Assertions.assertThat(independentMatcher.find()).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(independentPattern)).hasSize(1)
        val independentEvent: OperationDiagnostics = operationsFor(independentPattern).first()

        val regionPattern = org.safere.Pattern.compile("(a+?)b")
        val regionMatcher = regionPattern.matcher("xaabx").region(1, 4)
        Assertions.assertThat(regionMatcher.find()).isTrue()
        Assertions.assertThat(regionMatcher.capturesResolved()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(regionPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy)
                        .isEqualTo(independentEvent.boundaryStrategy)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy)
                        .isEqualTo(independentEvent.captureStrategy)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(independentEvent.captureMode)
                })
    }

    @Test
    fun deferredExactBoundarySearchesKeepDeferredCaptureAttribution() {
        org.safere.Pattern.setDiagnostics(diagnostics)

        val noDfa = EnginePathOptions.builder().dfa(false).build()
        val lazyFallbackPattern = org.safere.Pattern.compile("(a+?)b", 0, noDfa)
        val lazyFallbackMatcher = lazyFallbackPattern.matcher("xxaab")
        Assertions.assertThat(lazyFallbackMatcher.find()).isTrue()
        Assertions.assertThat(lazyFallbackMatcher.capturesResolved()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(lazyFallbackPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
        Assertions.assertThat(lazyFallbackMatcher.group(1)).isEqualTo("aa")

        val endAnchoredPattern = org.safere.Pattern.compile("foo(a+?)b$")
        val endAnchoredMatcher = endAnchoredPattern.matcher("zzfooaab")
        Assertions.assertThat(endAnchoredMatcher.find()).isTrue()
        Assertions.assertThat(endAnchoredMatcher.capturesResolved()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(endAnchoredPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
        Assertions.assertThat(endAnchoredMatcher.group(1)).isEqualTo("aa")
    }

    @Test
    fun captureHistoryKeepsAcceleratedCandidateHitsAndMissesOnDfa() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("foo(a+)b")

        val warmup = pattern.matcher("fooaab")
        Assertions.assertThat(warmup.find()).isTrue()
        Assertions.assertThat(warmup.group(1)).isEqualTo("aa")
        diagnostics.operations.clear()

        val acceleratedHit = pattern.matcher("xxfooaab")
        Assertions.assertThat(acceleratedHit.find()).isTrue()
        Assertions.assertThat(acceleratedHit.capturesResolved()).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
        Assertions.assertThat(acceleratedHit.group(1)).isEqualTo("aa")
        diagnostics.operations.clear()

        val missedCandidate = pattern.matcher("fooxxx xxfooaab")
        Assertions.assertThat(missedCandidate.find()).isTrue()
        Assertions.assertThat(missedCandidate.capturesResolved()).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
        Assertions.assertThat(missedCandidate.group(1)).isEqualTo("aa")
    }

    @Test
    fun matchesTrailingNewlineNfaFallbackOverridesCaptureAttribution() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val noDfa =
            EnginePathOptions.builder().dfa(false).onePass(false).shiftDfa(false).build()

        val fullMatchViaNfa = org.safere.Pattern.compile("^(a+)(\\n??)$", 0, noDfa)
        val matched = fullMatchViaNfa.matcher("aa\n")
        Assertions.assertThat(matched.matches()).isTrue()
        Assertions.assertThat(matched.group(1)).isEqualTo("aa")
        Assertions.assertThat(matched.group(2)).isEqualTo("\n")
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(fullMatchViaNfa))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })

        val rejectedViaNfa = org.safere.Pattern.compile("^(a+?)$", 0, noDfa)
        val rejected = rejectedViaNfa.matcher("aa\n")
        Assertions.assertThat(rejected.matches()).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(rejectedViaNfa))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })
    }

    @Test
    fun failedOnePassAndKeywordOperationsReportNoCaptureStrategy() {
        org.safere.Pattern.setDiagnostics(diagnostics)

        val onePassPattern = org.safere.Pattern.compile("^([a-z]+):(\\d+)$")
        // A valid final digit passes terminal rejection so OnePass observes the interior failure.
        val rejectedInput = "abc:nope1"
        Assertions.assertThat(onePassPattern.matcher(rejectedInput).matches()).isFalse()
        Assertions.assertThat(onePassPattern.matcher(rejectedInput).find()).isFalse()
        Assertions.assertThat(onePassPattern.matcher(rejectedInput).replaceFirst("$1")).isEqualTo(rejectedInput)
        Assertions.assertThat(onePassPattern.matcher(rejectedInput).replaceAll("$1")).isEqualTo(rejectedInput)

        val onePassPrefixPattern = org.safere.Pattern.compile("^([a-z]+):(\\d+)")
        Assertions.assertThat(onePassPrefixPattern.matcher("abc:nope").lookingAt()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(onePassPattern))
            .hasSize(4)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(onePassPrefixPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })

        val anchoredKeywordPattern = org.safere.Pattern.compile("(?is).*\\b(you|your)\\b.*")
        Assertions.assertThat(anchoredKeywordPattern.matcher("nothing matching here").find()).isFalse()
        val unanchoredKeywordPattern = org.safere.Pattern.compile("(?i)\\b(you|your)\\b")
        Assertions.assertThat(unanchoredKeywordPattern.matcher("nothing matching here").find()).isFalse()

        for (pattern in List.of<org.safere.Pattern>(anchoredKeywordPattern, unanchoredKeywordPattern)) {
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.KEYWORD)
                        Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                        Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                    })
        }
    }

    @Test
    fun literalStartAcceleratorShortCircuitsBeforeInfixRejectPrefilter() {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile("status=3\\d\\d took=(\\d+)ms")
        val input = "id=42 status=200 took=12ms\nid=43 status=201 took=8ms\n"

        Assertions.assertThat(pattern.matcher(input).find()).isFalse()
        Assertions.assertThat(pattern.find(Utf8Input.trusted(input.toByteArray(StandardCharsets.UTF_8)))).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION
                            )
                        )
                })

        // With the start literal present but the infix literal absent, the reject prefilter still runs
        // from the first start candidate when enough input remains after it, so the forward DFA never
        // scans the rest of the input.
        diagnostics.operations.clear()
        val startOnly = "id=42 status=300 elapsed=12ms\nid=43 status=301 elapsed=8ms\n".repeat(3)
        Assertions.assertThat(pattern.matcher(startOnly).find()).isFalse()
        Assertions.assertThat(pattern.find(Utf8Input.trusted(startOnly.toByteArray(StandardCharsets.UTF_8)))).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactlyInAnyOrder(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION
                            ),
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                            )
                        )
                })

        for (regex in arrayOf<String>(
            "(?i:status=3\\d\\d) took=(\\d+)ms", "\u03b1\u03b2\\d+<<!nav>>\\d+", "er\\d+<<!nav>>\\d+",
        )) {
            diagnostics.operations.clear()
            val rejectFirst = org.safere.Pattern.compile(regex)
            val miss = "id=42 status=200 elapsed=12ms\nid=43 status=201 elapsed=8ms\n"
            Assertions.assertThat(rejectFirst.matcher(miss).find()).isFalse()
            Assertions.assertThat(rejectFirst.find(Utf8Input.trusted(miss.toByteArray(StandardCharsets.UTF_8))))
                .isFalse()
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(rejectFirst))
                .`as`("%s should run exact rare-anchor REJECT_PREFILTER first", regex)
                .hasSize(2)
                .allSatisfy(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                        Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                            .containsExactly(
                                StrategyParticipation(
                                    MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                                )
                            )
                    })
        }
    }

    @ParameterizedTest
    @MethodSource("captureAttributionAlignmentCases")
    fun captureModeAndCaptureStrategyAgreeWithMatcherStateAcrossOperations(
        regex: String?, matchingInput: String, nonMatchingInput: String
    ) {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile(regex)
        val hasCaptures = pattern.analysis().captureCount > 0

        for (input in List.of<String?>(matchingInput, nonMatchingInput)) {
            diagnostics.operations.clear()
            val mMatches = pattern.matcher(input)
            val matched = mMatches.matches()
            assertSingleOperationCaptureAgreement(
                operationsFor(pattern), matched, hasCaptures, mMatches.capturesResolved()
            )

            diagnostics.operations.clear()
            val mLookingAt = pattern.matcher(input)
            val lookedAt = mLookingAt.lookingAt()
            assertSingleOperationCaptureAgreement(
                operationsFor(pattern), lookedAt, hasCaptures, mLookingAt.capturesResolved()
            )

            diagnostics.operations.clear()
            val mFind = pattern.matcher(input)
            val found = mFind.find()
            assertSingleOperationCaptureAgreement(
                operationsFor(pattern), found, hasCaptures, mFind.capturesResolved()
            )
            if (found && hasCaptures) {
                mFind.group(1)
                diagnostics.operations.clear()
                val mPostDemandFind = pattern.matcher(input)
                val foundAgain = mPostDemandFind.find()
                assertSingleOperationCaptureAgreement(
                    operationsFor(pattern), foundAgain, hasCaptures, mPostDemandFind.capturesResolved()
                )
            }

            val captureReplacement = if (hasCaptures) "$1" else "x"
            for (replacement in List.of<String?>("x", captureReplacement)) {
                diagnostics.operations.clear()
                Assertions.assertThat(pattern.matcher(input).replaceFirst(replacement)).isNotNull()
                assertCaptureStrategyMatchesMode(operationsFor(pattern))

                diagnostics.operations.clear()
                Assertions.assertThat(pattern.matcher(input).replaceAll(replacement)).isNotNull()
                assertCaptureStrategyMatchesMode(operationsFor(pattern))
            }
        }
    }

    @ParameterizedTest
    @MethodSource("onePassCapabilityAlignmentCases")
    fun onePassCapabilitiesAgreeWithRuntimeExecution(
        regex: String?,
        options: EnginePathOptions,
        matchingInput: String?,
        expectPrimary: Boolean,
        expectCaptureExtraction: Boolean
    ) {
        org.safere.Pattern.setDiagnostics(diagnostics)
        val pattern = org.safere.Pattern.compile(regex, 0, options)
        val analysis = pattern.analysis()

        if (analysis.limitations.contains(PatternLimitation.LAZY_SEMANTICS_LIMIT_ONE_PASS)
            || analysis.limitations.contains(PatternLimitation.NULLABLE_ALTERNATION_LIMITS_ONE_PASS)
            || analysis.limitations.contains(PatternLimitation.GRAPHEME_REQUIRES_EXACT_ENGINE)
            || analysis.captureCount > OnePass.MAX_CAPTURE_GROUPS - 1 || !options.onePass
        ) {
            Assertions.assertThat<PatternCapability?>(analysis.capabilities)
                .doesNotContain(
                    PatternCapability.ONE_PASS_PRIMARY, PatternCapability.ONE_PASS_CAPTURE_EXTRACTION
                )
        }
        if (analysis.captureCount == 0) {
            Assertions.assertThat<PatternCapability?>(analysis.capabilities)
                .doesNotContain(PatternCapability.ONE_PASS_CAPTURE_EXTRACTION)
        }
        Assertions.assertThat(analysis.capabilities.contains(PatternCapability.ONE_PASS_PRIMARY))
            .isEqualTo(expectPrimary)
        Assertions.assertThat(analysis.capabilities.contains(PatternCapability.ONE_PASS_CAPTURE_EXTRACTION))
            .isEqualTo(expectCaptureExtraction)

        Assertions.assertThat(pattern.matcher(matchingInput).matches()).isTrue()
        val lookingAtInput =
            if (pattern.prog().anchorEnd() || pattern.prog().dollarAnchorEnd())
                matchingInput
            else
                matchingInput + " suffix"
        Assertions.assertThat(pattern.matcher(lookingAtInput).lookingAt()).isTrue()
        val findMatcher = pattern.matcher("prefix " + matchingInput + " suffix")
        if (!pattern.prog().anchorStart() && !pattern.prog().anchorEnd()) {
            Assertions.assertThat(findMatcher.find()).isTrue()
            if (analysis.captureCount > 0) {
                findMatcher.group(1)
            }
        } else {
            val anchoredFind = pattern.matcher(matchingInput)
            Assertions.assertThat(anchoredFind.find()).isTrue()
            if (analysis.captureCount > 0) {
                anchoredFind.group(1)
            }
        }
        val replacement = if (analysis.captureCount > 0) "$1" else "x"
        Assertions.assertThat(pattern.matcher(matchingInput).replaceFirst(replacement)).isNotNull()
        Assertions.assertThat(pattern.matcher(matchingInput).replaceAll(replacement)).isNotNull()

        val events = operationsFor(pattern)
        val ranOnePassPrimary =
            events.stream()
                .anyMatch { event: OperationDiagnostics? -> event!!.boundaryStrategy == MatchStrategy.ONE_PASS }
        val ranOnePassCapture =
            events.stream()
                .anyMatch { event: OperationDiagnostics? -> event!!.captureStrategy == MatchStrategy.ONE_PASS }
        val ranOnePassAuxiliary =
            events.stream()
                .flatMap<StrategyParticipation?> { event: OperationDiagnostics? -> event!!.auxiliaryStrategies.stream() }
                .anyMatch { participation: StrategyParticipation? -> participation!!.strategy == MatchStrategy.ONE_PASS }

        Assertions.assertThat(ranOnePassPrimary).isEqualTo(expectPrimary)
        Assertions.assertThat(ranOnePassCapture).isEqualTo(expectCaptureExtraction)
        if (!expectPrimary && !expectCaptureExtraction) {
            Assertions.assertThat(ranOnePassAuxiliary).isFalse()
        }
    }

    private fun operationsFor(pattern: org.safere.Pattern): MutableList<OperationDiagnostics> {
        val patternId = pattern.patternId()
        return diagnostics.operations.stream()
            .filter { event: OperationDiagnostics? -> event!!.pattern.patternId == patternId }
            .toList()
    }

    private fun assertCompositeRejection(
        operation: MatchOperation, input: String?, expectedStrategy: MatchStrategy?
    ) {
        val pattern = org.safere.Pattern.compile(".*x.*needle.*")
        val matcher = pattern.matcher(input)

        val matched =
            when (operation) {
                MatchOperation.FIND -> matcher.find()
                MatchOperation.MATCHES -> matcher.matches()
                else -> throw AssertionError("unsupported operation: " + operation)
            }

        Assertions.assertThat(matched).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(expectedStrategy)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(expectedStrategy, StrategyRole.REJECT_PREFILTER)
                        )
                })
    }

    private class RecordingDiagnostics : SafeReMatchDiagnostics() {
        val compilations: MutableList<PatternCompiledEvent> = ArrayList<PatternCompiledEvent>()
        val operations: MutableList<OperationDiagnostics> = ArrayList<OperationDiagnostics>()

        override fun onPatternCompiled(event: PatternCompiledEvent?) {
            compilations.add(event!!)
        }

        override fun onOperationCompleted(event: OperationDiagnostics?) {
            operations.add(event!!)
        }
    }

    companion object {
        private fun lazyShiftDfaSearchCases(): Stream<Arguments?> {
            val cases = Stream.builder<Arguments?>()
            for (utf8 in mutableListOf<Boolean?>(false, true)) {
                for (operation in List.of<MatchOperation?>(MatchOperation.FIND, MatchOperation.LOOKING_AT)) {
                    cases.add(Arguments.of("^a[é]*", "aéé", operation, utf8))
                    cases.add(Arguments.of("^a{9}b?c?", "aaaaaaaaabc", operation, utf8))
                    cases.add(Arguments.of("^a[bc]*", "abcbc", operation, utf8))
                }
            }
            return cases.build()
        }

        private fun reverseCandidateVerificationCases(): Stream<Arguments?>? {
            return Stream.of<Boolean?>(false, true)
                .flatMap<Arguments?> { bitState: Boolean? ->
                    Stream.of<Arguments?>(
                        Arguments.of(bitState, "(?:foo|fax)$", "x".repeat(1400) + "foo"),
                        Arguments.of(bitState, "(?:foo|fax)$", "x".repeat(1400) + "foo\r\n"),
                        Arguments.of(bitState, "(?:foo|fax)\\z", "x".repeat(1400) + "fax"),
                        Arguments.of(bitState, "foo[ab]*$", "zzfoo" + "a".repeat(2048))
                    )
                }
        }

        private fun captureHistoryFindCases(): Stream<Arguments?>? {
            return Stream.of<Boolean?>(false, true)
                .flatMap<Arguments?> { utf8: Boolean? ->
                    Stream.of<Boolean?>(false, true)
                        .map<Arguments?> { accelerate: Boolean? -> Arguments.of(utf8, accelerate) }
                }
        }

        private fun captureHistoryPattern(accelerate: Boolean, anchored: Boolean): org.safere.Pattern {
            val options =
                EnginePathOptions.builder()
                    .startAcceleration(accelerate)
                    .onePass(false)
                    .shiftDfa(false)
                    .build()
            val pattern =
                org.safere.Pattern.compile((if (anchored) "^" else "") + "(error:\\[)[A-Z](\\] code:500)", 0, options)
            val primer = pattern.matcher("error:[C] code:500")
            Assertions.assertThat(primer.find()).isTrue()
            Assertions.assertThat(primer.group(1)).isEqualTo("error:[")
            return pattern
        }

        private fun captureAttributionAlignmentCases(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("^([a-z]+):(\\d+)$", "abc:123", "abc:nope"),
                Arguments.of("([a-z]+):(\\d+)", "abc:123", "nope"),
                Arguments.of("(a+?)b", "aab", "zzz"),
                Arguments.of("(a+|ab)c", "abc", "zzz"),
                Arguments.of("foo(a+)b", "xxfooaab", "fooxxx"),
                Arguments.of("(?is).*\\b(you|your)\\b.*", "here is your token", "nothing"),
                Arguments.of("(\\X)b", "ab", "zz"),
                Arguments.of("\\d{4}-\\d{2}-\\d{2}", "2026-10-30", "nope")
            )
        }

        private fun assertSingleOperationCaptureAgreement(
            events: MutableList<OperationDiagnostics>?,
            matched: Boolean,
            hasCaptures: Boolean,
            matcherCapturesResolved: Boolean
        ) {
            assertCaptureStrategyMatchesMode(events)
            Assertions.assertThat<OperationDiagnostics?>(events)
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        if (!matched || !hasCaptures) {
                            Assertions.assertThat<CaptureMode?>(event!!.captureMode).isEqualTo(CaptureMode.NONE)
                            Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                        } else {
                            Assertions.assertThat(event!!.captureMode == CaptureMode.EAGER)
                                .isEqualTo(matcherCapturesResolved)
                        }
                    })
        }

        private fun assertCaptureStrategyMatchesMode(events: MutableList<OperationDiagnostics>?) {
            Assertions.assertThat<OperationDiagnostics?>(events)
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat(event!!.captureStrategy != MatchStrategy.NONE)
                            .isEqualTo(event.captureMode == CaptureMode.EAGER)
                        if (event.outcome == MatchOutcome.NO_MATCH
                            || event.pattern.analysis.captureCount == 0
                        ) {
                            Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                            Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                        }
                    })
        }

        private fun onePassCapabilityAlignmentCases(): Stream<Arguments?> {
            val defaults = EnginePathOptions.allEnabled()
            val onePassDisabled = EnginePathOptions.builder().onePass(false).build()
            return Stream.of<Arguments?>(
                Arguments.of("^([a-z]+):(\\d+)$", defaults, "abc:123", true, true),
                Arguments.of("([a-z]+):(\\d+)", defaults, "abc:123", true, true),
                Arguments.of("^[a-z]+:\\d+$", defaults, "abc:123", true, false),
                Arguments.of("\\d{4}-\\d{2}-\\d{2}", defaults, "2026-10-30", true, false),
                Arguments.of("(a)*", defaults, "aaa", false, true),
                Arguments.of("^(a|)b$", defaults, "ab", false, false),
                Arguments.of("(a|)b", defaults, "ab", false, false),
                Arguments.of("(a*|b)c", defaults, "ac", false, false),
                Arguments.of("^([a-z]+?)b$", defaults, "aab", false, false),
                Arguments.of("([a-z]+?)b", defaults, "aab", false, false),
                Arguments.of("\\b{g}(a+)", defaults, "aaa", false, false),
                Arguments.of("(\\X)", defaults, "a", false, false),
                Arguments.of(
                    "(a)".repeat(OnePass.MAX_CAPTURE_GROUPS),
                    defaults,
                    "a".repeat(OnePass.MAX_CAPTURE_GROUPS),
                    false,
                    false
                ),
                Arguments.of("(a|ab)c", defaults, "abc", false, false),
                Arguments.of("^([a-z]+):(\\d+)$", onePassDisabled, "abc:123", false, false),
                Arguments.of("([a-z]+):(\\d+)", onePassDisabled, "abc:123", false, false)
            )
        }
    }
}
