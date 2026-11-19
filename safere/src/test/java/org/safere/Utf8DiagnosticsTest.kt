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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.IntConsumer
import java.util.function.Predicate
import java.util.stream.IntStream
import java.util.stream.Stream

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("UTF-8 match diagnostics are a SafeRE-specific public API")
internal class Utf8DiagnosticsTest {
    private val diagnostics = RecordingDiagnostics()

    @AfterEach
    fun disableDiagnostics() {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a*", "a+", "[é😀]*", "[^a]+", "[aé😀]{0,}"])
    fun greedyRepeatedClassFindUsesUtf8ClassScanner(regex: String?) {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile(regex)
        val matcher: Utf8Matcher = matcher(pattern, "é😀aaaé")

        while (matcher.find()) {
        }

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .filteredOn(Predicate { event: OperationDiagnostics? -> event!!.outcome == MatchOutcome.MATCH })
            .isNotEmpty()
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy)
                        .isEqualTo(MatchStrategy.CHARACTER_CLASS)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                })
    }

    @ParameterizedTest
    @EnumSource(value = MatchOperation::class, names = ["MATCHES", "LOOKING_AT", "FIND"])
    fun operationsReportDiagnosticsWithSlicedByteInputLength(operation: MatchOperation) {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile(".*é.*")
        val logicalInput = "xéy".toByteArray(StandardCharsets.UTF_8)
        val storage = ByteArray(logicalInput.size + 5)
        System.arraycopy(logicalInput, 0, storage, 2, logicalInput.size)
        val matcher = pattern.matcher(Utf8Input.trusted(storage, 2, logicalInput.size))

        val matched =
            when (operation) {
                MatchOperation.MATCHES -> matcher.matches()
                MatchOperation.LOOKING_AT -> matcher.lookingAt()
                MatchOperation.FIND -> matcher.find()
                else -> throw AssertionError("Unexpected operation: " + operation)
            }

        Assertions.assertThat(matched).isTrue()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(operation)
                    Assertions.assertThat<MatchOutcome?>(event.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat(event.inputLength).isEqualTo(logicalInput.size)
                    Assertions.assertThat(event.matchCount).isEqualTo(1)
                })
    }

    @Test
    fun onePassReportsDescriptorAndEagerCaptures() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("^GET ([^ ]+)")

        Assertions.assertThat(matcher(pattern, "GET /café").lookingAt()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<PatternDescriptor?>(event!!.pattern).isSameAs(pattern.descriptor())
                    Assertions.assertThat<MatchOperation?>(event.operation).isEqualTo(MatchOperation.LOOKING_AT)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })
    }

    @Test
    fun literalFindReportsUtf8FastPath() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("é")

        Assertions.assertThat(matcher(pattern, "xéy").find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                })
    }

    @Test
    fun patternBooleanFindReportsUtf8FastPath() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("é")
        val input = Utf8Input.validated("xéy".toByteArray(StandardCharsets.UTF_8))

        Assertions.assertThat(pattern.find(input)).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.FIND)
                    Assertions.assertThat<MatchOutcome?>(event.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                    Assertions.assertThat(event.inputLength).isEqualTo(4)
                    Assertions.assertThat(event.matchCount).isEqualTo(1)
                })
    }

    @ParameterizedTest
    @MethodSource("literalFindOptions")
    fun patternBooleanFindHonorsLiteralFastPathOption(
        literal: String?, literalFastPaths: Boolean, present: Boolean
    ) {
        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        // Disable independent start acceleration so misses also reach the chosen boundary engine.
        val pattern =
            Pattern.compile(
                literal,
                0,
                EnginePathOptions.builder()
                    .literalFastPaths(literalFastPaths)
                    .startAcceleration(false)
                    .build()
            )
        val input: Utf8Input = input(if (present) "prefix " + literal + " suffix" else "unrelated text")

        // With diagnostics disabled, lazy DFA creation proves the literal shortcut was bypassed.
        Assertions.assertThat<Dfa?>(pattern.cachedForwardFirstMatchDfa()).isNull()
        Assertions.assertThat(pattern.find(input)).isEqualTo(present)
        if (literalFastPaths) {
            Assertions.assertThat<Dfa?>(pattern.cachedForwardFirstMatchDfa()).isNull()
        } else {
            Assertions.assertThat<Dfa?>(pattern.cachedForwardFirstMatchDfa()).isNotNull()
        }

        Pattern.setDiagnostics(diagnostics)
        Assertions.assertThat(pattern.find(input)).isEqualTo(present)

        val expected = if (literalFastPaths) MatchStrategy.LITERAL else MatchStrategy.DFA
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(expected)
                    Assertions.assertThat(event.forwardDfaSearchCount).isEqualTo(if (literalFastPaths) 0 else 1)
                    Assertions.assertThat<MatchOutcome?>(event.outcome)
                        .isEqualTo(if (present) MatchOutcome.MATCH else MatchOutcome.NO_MATCH)
                })
    }

    @Test
    fun keywordAlternationFindReportsUtf8FastPath() {
        Pattern.setDiagnostics(diagnostics)
        val matcherPattern = Pattern.compile("(?is).*\\b(you|your)\\b.*")
        val booleanPattern = Pattern.compile("(?is).*\\b(you|your)\\b.*")
        val input: Utf8Input = input("a long prefix ending with YOUR answer")

        Assertions.assertThat(matcherPattern.matcher(input).find()).isTrue()
        Assertions.assertThat(booleanPattern.find(input)).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(matcherPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.KEYWORD)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.KEYWORD)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                })
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(booleanPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.KEYWORD)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                })
    }

    @Test
    fun startAnchoredLiteralFindWithDiagnosticsRejectsLaterOccurrence() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("^target")

        Assertions.assertThat(pattern.find(input("prefix target"))).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(ThrowingConsumer { event: OperationDiagnostics? ->
                Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
            })
    }

    @Test
    fun patternBooleanFindReportsAccelerationAndDfaSearch() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("é[ab]+c")

        Assertions.assertThat(pattern.find(input("xxéabc"))).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.forwardDfaSearchCount).isEqualTo(1)
                    Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION
                            ),
                            StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER)
                        )
                })
    }

    @Test
    fun patternBooleanFindReportsAsciiPrefixClassRejection() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("[xy]q?")

        Assertions.assertThat(pattern.find(input("bbbb"))).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy)
                        .isEqualTo(MatchStrategy.CHARACTER_CLASS)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(
                                MatchStrategy.CHARACTER_CLASS, StrategyRole.START_ACCELERATION
                            )
                        )
                })
    }

    @Test
    fun quantifiedSingleCharClassUtf8MatchesUsesCharacterClassFastPath() {
        val noCharClassFastPath =
            EnginePathOptions.builder().charClassMatchFastPaths(false).build()
        val regexes = mutableListOf<String?>("[a-zA-Z0-9]+", "\\d*", "\\p{L}+", "[^a]+")
        val inputs: MutableList<String> =
            mutableListOf<String?>("", "abcXYZ123", "12345", "12a45", "élanβ", "élan42", "😀", "é😀b", "éab")

        for (regex in regexes) {
            val utf8Fast = Pattern.compile(regex)
            val stringFast = Pattern.compile(regex)
            val fallback = Pattern.compile(regex, 0, noCharClassFastPath)
            for (text in inputs) {
                Pattern.setDiagnostics(diagnostics)
                val expected = stringFast.matcher(text).matches()
                val stringEvent: OperationDiagnostics = operationsFor(stringFast).getLast()
                val utf8Matcher: Utf8Matcher = matcher(utf8Fast, text)
                Assertions.assertThat(utf8Matcher.matches()).`as`("/%s/ on '%s'", regex, text).isEqualTo(expected)
                if (expected) {
                    Assertions.assertThat(utf8Matcher.start()).isZero()
                    Assertions.assertThat(utf8Matcher.end()).isEqualTo(text.toByteArray(StandardCharsets.UTF_8).size)
                }
                Assertions.assertThat<OperationDiagnostics?>(operationsFor(utf8Fast).getLast())
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy)
                                .isEqualTo(stringEvent.boundaryStrategy)
                            if (!text.isEmpty() || expected) {
                                Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                                    .isEqualTo(MatchStrategy.CHARACTER_CLASS)
                            }
                            Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                        })
                Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
                Assertions.assertThat(fallback.matcher(input(text)).matches()).isEqualTo(expected)

                // Region slicing preserves byte coordinates.
                val framed = ("!a!" + text + "!a!").toByteArray(StandardCharsets.UTF_8)
                val start = 3
                val end = framed.size - 3
                val sliced = utf8Fast.matcher(Utf8Input.validated(framed)).region(start, end)
                Assertions.assertThat(sliced.matches()).isEqualTo(expected)
                if (expected) {
                    Assertions.assertThat(sliced.start()).isEqualTo(start)
                    Assertions.assertThat(sliced.end()).isEqualTo(end)
                }
            }
        }

        // Malformed trusted UTF-8 bytes decode as U+FFFD, matching the fallback engine on both
        // ASCII-only classes (rejected) and negated classes (accepted when U+FFFD is in the class).
        val malformed = byteArrayOf(0x80.toByte(), 'b'.code.toByte(), 0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        for (regex in mutableListOf<String?>("[a-z]+", "[^a]+", "[^\\uFFFD]+")) {
            val fast = Pattern.compile(regex)
            val fallback = Pattern.compile(regex, 0, noCharClassFastPath)
            val expected = fallback.matcher(Utf8Input.trusted(malformed)).matches()
            Assertions.assertThat(fast.matcher(Utf8Input.trusted(malformed)).matches())
                .`as`("malformed trusted UTF-8 for /%s/", regex)
                .isEqualTo(expected)
        }
    }

    @Test
    fun patternBooleanFindReportsNfaForGraphemeSemantics() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("\\Xz")

        Assertions.assertThat(pattern.find(input("a\u0301z"))).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.NFA)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })
    }

    @Test
    fun endAnchoredBooleanFindWithCapturesReportsOneCaptureFreeEvent() {
        // The existence-only end-anchored path verifies without computing captures.
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("(foo|fax)$")

        Assertions.assertThat(pattern.find(input("say fax"))).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOperation?>(event!!.operation).isEqualTo(MatchOperation.FIND)
                    Assertions.assertThat<MatchOutcome?>(event.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat(event.matchCount).isEqualTo(1)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })
    }

    @Test
    fun endAnchoredBooleanFindWithoutMatchReportsOneEvent() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("(foo|fax)$")

        Assertions.assertThat(pattern.find(input("say fix"))).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                    Assertions.assertThat(event.matchCount).isZero()
                })
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun fullyAnchoredBooleanFindUsesKnownStart(bitState: Boolean) {
        Pattern.setDiagnostics(diagnostics)
        for (terminal in mutableListOf<String?>("END", "[0-9]")) {
            for (valid in mutableListOf<Boolean?>(true, false)) {
                val pattern =
                    Pattern.compile(
                        "^([a-z]+)" + terminal + "\\z",
                        0,
                        EnginePathOptions.builder().bitState(bitState).build()
                    )
                val text =
                    "a".repeat(61) + (if (valid) "" else "!") + "a" + (if (terminal == "END") "END" else "5")

                Assertions.assertThat(pattern.find(input(text))).isEqualTo(valid)
                Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                    .singleElement()
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchOutcome?>(event!!.outcome)
                                .isEqualTo(if (valid) MatchOutcome.MATCH else MatchOutcome.NO_MATCH)
                            Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                            Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                        })
            }
        }
    }

    @Test
    fun directBooleanFindWithCapturesReportsNoCaptureMode() {
        // Without an end anchor, Pattern.find(Utf8Input) stays on its matcher-free path, which never
        // computes captures.
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("(foo|fax)")

        Assertions.assertThat(pattern.find(input("say fax"))).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })
    }

    @Test
    fun patternBooleanFindReportsAcceleratedRejection() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("é[ab]+c")

        Assertions.assertThat(pattern.find(input("ordinary ASCII text"))).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION
                            )
                        )
                    Assertions.assertThat(event.matchCount).isZero()
                })
    }

    @ParameterizedTest
    @MethodSource("patternBooleanFindScenarios")
    fun patternBooleanFindDiagnosticsPreserveResults(
        regex: String?, text: String, expectedResult: Boolean
    ) {
        val pattern = Pattern.compile(regex)
        val input: Utf8Input = input(text)

        Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE)
        Assertions.assertThat(pattern.find(input)).isEqualTo(expectedResult)

        Pattern.setDiagnostics(diagnostics)
        Assertions.assertThat(pattern.find(input)).isEqualTo(expectedResult)
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern)).hasSize(1)
    }

    @Test
    fun absentRequiredLiteralRejectsBeforeUnboundedReverseWalk() {
        Pattern.setDiagnostics(diagnostics)
        val input: Utf8Input = input("x".repeat(20000))
        for (regex in mutableListOf<String?>("needle.*$", "needle[a-z]*\\z")) {
            for (existenceOnly in mutableListOf<Boolean?>(false, true)) {
                val pattern = Pattern.compile(regex)
                Assertions.assertThat(if (existenceOnly) pattern.find(input) else pattern.matcher(input).find())
                    .isFalse()
                Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
                    .singleElement()
                    .satisfies(
                        ThrowingConsumer { event: OperationDiagnostics? ->
                            Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                            Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy)
                                .isEqualTo(MatchStrategy.LITERAL)
                        })
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun endAnchoredBooleanFindReportsReverseNarrowingAndExactVerification(bitState: Boolean) {
        Pattern.setDiagnostics(diagnostics)
        val pattern =
            Pattern.compile("終点😀$", 0, EnginePathOptions.builder().bitState(bitState).build())
        val verification = if (bitState) MatchStrategy.BIT_STATE else MatchStrategy.NFA

        Assertions.assertThat(pattern.find(input("é😀".repeat(400) + "終点😀\r\n"))).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(verification)
                    Assertions.assertThat(event.reverseDfaSearchCount).isEqualTo(2)
                    Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER),
                            StrategyParticipation(verification, StrategyRole.CANDIDATE_VERIFICATION)
                        )
                })
    }

    @Test
    fun literalPrefixCandidateReportsAccelerationAndVerification() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("é[ab]+c")

        Assertions.assertThat(matcher(pattern, "xxéabc").find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat(event.forwardDfaSearchCount).isEqualTo(2)
                    Assertions.assertThat(event.reverseDfaSearchCount).isZero()
                    Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                        .containsExactly(
                            StrategyParticipation(
                                MatchStrategy.LITERAL, StrategyRole.START_ACCELERATION
                            ),
                            StrategyParticipation(MatchStrategy.DFA, StrategyRole.REJECT_PREFILTER),
                            StrategyParticipation(
                                MatchStrategy.DFA, StrategyRole.CANDIDATE_VERIFICATION
                            )
                        )
                })
    }

    @Test
    fun dfaSandwichReportsForwardAndReverseSearchCounts() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("[ab]+é")

        Assertions.assertThat(matcher(pattern, "xxabé").find()).isTrue()

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
    fun forcedBitStateAndNfaReportTheExactEngine() {
        Pattern.setDiagnostics(diagnostics)
        val bitStateOnly: EnginePathOptions = exactEngineOptions(true)
        val bitState = Pattern.compile("(?:é)+", 0, bitStateOnly)

        Assertions.assertThat(matcher(bitState, "xéé").find()).isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(bitState).getLast().boundaryStrategy)
            .isEqualTo(MatchStrategy.BIT_STATE)

        val nfaOnly: EnginePathOptions = exactEngineOptions(false)
        val nfa = Pattern.compile("(?:é)+", 0, nfaOnly)

        Assertions.assertThat(matcher(nfa, "xéé").find()).isTrue()
        Assertions.assertThat<MatchStrategy?>(operationsFor(nfa).getLast().boundaryStrategy)
            .isEqualTo(MatchStrategy.NFA)
    }

    @Test
    fun dfaBoundsCanLeaveCapturesDeferred() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("(é+)x")
        val matcher: Utf8Matcher = matcher(pattern, "zzééx")

        Assertions.assertThat(matcher.find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
        Assertions.assertThat(matcher.start(1)).isEqualTo(2)
        Assertions.assertThat(matcher.end(1)).isEqualTo(6)
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern)).hasSize(1)
    }

    @Test
    fun appendReplacementWithoutInnerGroupsKeepsSubsequentFindsOnDfa() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("(é+)x")
        val matcher: Utf8Matcher = matcher(pattern, "zzééx yyééx")
        val noopSink = Utf8Sink { bytes: ByteArray?, offset: Int, length: Int -> }
        val replacement: Utf8Input = input("[$0]")

        while (matcher.find()) {
            matcher.appendReplacement(noopSink, replacement)
        }
        matcher.appendTail(noopSink)

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .filteredOn(Predicate { event: OperationDiagnostics? -> event!!.outcome == MatchOutcome.MATCH })
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    // Resolving inner captures records demand for them, which moves later finds off the
                    // DFA.
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.DFA)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.DEFERRED)
                })
    }

    @Test
    fun failedMatchReportsNoMatchWithoutCaptures() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("(é)+")

        Assertions.assertThat(matcher(pattern, "abc").find()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                    Assertions.assertThat(event.matchCount).isZero()
                })
    }

    @Test
    fun largeInputReportsBitStateBypassAndNfaFallback() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("a+b", 0, exactEngineOptions(true))

        Assertions.assertThat(matcher(pattern, "a".repeat(100000) + "b").matches()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(pattern))
            .singleElement()
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
    fun graphemeSemanticsReportNfaFallback() {
        Pattern.setDiagnostics(diagnostics)
        val pattern = Pattern.compile("\\X")

        Assertions.assertThat(matcher(pattern, "a\u0301").matches()).isTrue()

        Assertions.assertThat<MatchStrategy?>(operationsFor(pattern).getFirst().boundaryStrategy)
            .isEqualTo(MatchStrategy.NFA)
    }

    @Test
    fun listenerFailurePropagatesAfterMatcherStateIsFinalized() {
        val pattern = Pattern.compile("é")
        val matcher: Utf8Matcher = matcher(pattern, "é")
        Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    throw IllegalStateException("listener failed")
                }
            })

        Assertions.assertThatThrownBy(ThrowingCallable { matcher.matches() })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("listener failed")
        Assertions.assertThat(matcher.start()).isZero()
        Assertions.assertThat(matcher.end()).isEqualTo(2)
    }

    @Test
    fun listenerCanAggregateOperationsFromMultipleThreads() {
        val pattern = Pattern.compile("é")
        val events = ConcurrentLinkedQueue<OperationDiagnostics?>()
        Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    events.add(event)
                }
            })

        IntStream.range(0, 32)
            .parallel()
            .forEach(IntConsumer { ignored: Int -> Assertions.assertThat(matcher(pattern, "é").matches()).isTrue() })

        Assertions.assertThat<OperationDiagnostics?>(events)
            .filteredOn(Predicate { event: OperationDiagnostics? -> event!!.pattern.patternId == pattern.descriptor().patternId })
            .hasSize(32)
    }

    @Test
    fun utf8PrimaryExactCaptureSearchesReportEagerCaptureAttribution() {
        Pattern.setDiagnostics(diagnostics)

        val nullableLoopBitState =
            Pattern.compile(
                "(?:(é)?)*x", 0, EnginePathOptions.builder().dfa(false).onePass(false).build()
            )
        Assertions.assertThat(matcher(nullableLoopBitState, "ééx").matches()).isTrue()
        Assertions.assertThat(matcher(nullableLoopBitState, "ééxy").lookingAt()).isTrue()

        val regionBitState = Pattern.compile("(é+?)x")
        Assertions.assertThat(matcher(regionBitState, "zééxy").region(1, 6).find()).isTrue()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(nullableLoopBitState))
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(regionBitState))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchStrategy?>(event!!.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.EAGER)
                })

        val nfaPattern = Pattern.compile("(\\X)x")
        Assertions.assertThat(matcher(nfaPattern, "éx").matches()).isTrue()
        Assertions.assertThat(matcher(nfaPattern, "zzéx").find()).isTrue()

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
    fun utf8FailedOnePassAndKeywordSearchesReportNoCaptureStrategy() {
        Pattern.setDiagnostics(diagnostics)

        val onePassPattern = Pattern.compile("^(é+):(\\d+)$")
        // A valid final digit passes terminal rejection so OnePass observes the interior failure.
        Assertions.assertThat(matcher(onePassPattern, "éé:nope1").matches()).isFalse()
        Assertions.assertThat(matcher(onePassPattern, "éé:nope1").find()).isFalse()

        Assertions.assertThat<OperationDiagnostics?>(operationsFor(onePassPattern))
            .hasSize(2)
            .allSatisfy(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.ONE_PASS)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })

        val keywordPattern = Pattern.compile("(?is).*\\b(you|your)\\b.*")
        Assertions.assertThat(matcher(keywordPattern, "nothing matching").find()).isFalse()
        Assertions.assertThat<OperationDiagnostics?>(operationsFor(keywordPattern))
            .singleElement()
            .satisfies(
                ThrowingConsumer { event: OperationDiagnostics? ->
                    Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                    Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.KEYWORD)
                    Assertions.assertThat<MatchStrategy?>(event.captureStrategy).isEqualTo(MatchStrategy.NONE)
                    Assertions.assertThat<CaptureMode?>(event.captureMode).isEqualTo(CaptureMode.NONE)
                })
    }

    @Test
    fun utf8MatchesReportsRejectPrefilterAcrossAllFilterKinds() {
        data class RejectCase(
            val regex: String?,
            val matchingInput: String?,
            val rejectingInput: String?,
            val expectedStrategy: MatchStrategy?
        )

        val cases =
            List.of<RejectCase?>(
                RejectCase(
                    "(?:foo|bar).*needle.*",
                    "foo xx needle yy",
                    "foohaystackonly",
                    MatchStrategy.LITERAL
                ),
                RejectCase(
                    "((?:a|ab)+)#[a-z]*", "aab#def", "aababcdef", MatchStrategy.CHARACTER_CLASS
                ),
                RejectCase(
                    "((?:[a-z]|[a-z]{2})+)[#%][a-z]*",
                    "abc%def", "abcdef", MatchStrategy.CHARACTER_CLASS
                ),
                RejectCase(
                    ".*(?:apple|banana|cherry).*",
                    "fresh banana pie",
                    "fresh grape pie",
                    MatchStrategy.LITERAL
                ),
                RejectCase("((?:a|ab)+)\\.json$", "aab.json", "aab.yaml", MatchStrategy.LITERAL),
                RejectCase("((?:a|ab)+)[0-9]$", "aab7", "aabx", MatchStrategy.CHARACTER_CLASS),
                RejectCase(
                    "(?:foo|bar).*token.*[0-9]$", "foohastoken7", "foonotok7", MatchStrategy.LITERAL
                ),
                RejectCase(
                    "(?:foo|bar).*token.*[0-9]$",
                    "foohastoken7",
                    "foohastokenx",
                    MatchStrategy.CHARACTER_CLASS
                )
            )

        Pattern.setDiagnostics(diagnostics)
        for (tc in cases) {
            val utf8Pattern = Pattern.compile(tc.regex)
            val stringPattern = Pattern.compile(tc.regex)

            Assertions.assertThat(Companion.matcher(utf8Pattern, tc.rejectingInput!!).matches())
                .`as`("UTF-8 reject /%s/ on %s", tc.regex, tc.rejectingInput)
                .isFalse()
            Assertions.assertThat(stringPattern.matcher(tc.rejectingInput).matches())
                .`as`("String reject /%s/ on %s", tc.regex, tc.rejectingInput)
                .isFalse()

            val utf8RejectEvent: OperationDiagnostics = operationsFor(utf8Pattern).getLast()
            val stringRejectEvent: OperationDiagnostics = operationsFor(stringPattern).getLast()
            Assertions.assertThat<MatchOutcome?>(utf8RejectEvent.outcome).isEqualTo(MatchOutcome.NO_MATCH)
            Assertions.assertThat<MatchStrategy?>(utf8RejectEvent.boundaryStrategy).isEqualTo(tc.expectedStrategy)
            Assertions.assertThat<MatchStrategy?>(utf8RejectEvent.boundaryStrategy)
                .isEqualTo(stringRejectEvent.boundaryStrategy)
            Assertions.assertThat<StrategyParticipation?>(utf8RejectEvent.auxiliaryStrategies)
                .contains(
                    StrategyParticipation(tc.expectedStrategy, StrategyRole.REJECT_PREFILTER)
                )
            Assertions.assertThat(utf8RejectEvent.forwardDfaSearchCount).isZero()

            Assertions.assertThat(Companion.matcher(utf8Pattern, tc.matchingInput!!).matches())
                .`as`("UTF-8 match /%s/ on %s", tc.regex, tc.matchingInput)
                .isTrue()
            Assertions.assertThat(stringPattern.matcher(tc.matchingInput).matches()).isTrue()

            // Sliced region() rejects when the mandatory token falls outside the active region.
            val padded = ("needle" + tc.rejectingInput + "token9.jsonbanana#%").toByteArray(StandardCharsets.UTF_8)
            val regionStart = "needle".length
            val regionEnd = regionStart + tc.rejectingInput.toByteArray(StandardCharsets.UTF_8).size
            val regionMatcher =
                utf8Pattern.matcher(Utf8Input.validated(padded)).region(regionStart, regionEnd)
            Assertions.assertThat(regionMatcher.matches()).isFalse()
        }
    }

    @Test
    fun unacceleratedUtf8FindRejectsDisjointLiterals() {
        Pattern.setDiagnostics(diagnostics)
        val matcherPattern = Pattern.compile(".*(?:apple|banana|cherry).*")
        val booleanPattern = Pattern.compile(".*(?:apple|banana|cherry).*")
        val miss: Utf8Input = input("ordinary prose with grape and pear")
        val hit: Utf8Input = input("ordinary prose with cherry pie")

        Assertions.assertThat(matcherPattern.matcher(miss).find()).isFalse()
        Assertions.assertThat(booleanPattern.find(miss)).isFalse()

        for (p in List.of<Pattern>(matcherPattern, booleanPattern)) {
            Assertions.assertThat<OperationDiagnostics?>(operationsFor(p))
                .singleElement()
                .satisfies(
                    ThrowingConsumer { event: OperationDiagnostics? ->
                        Assertions.assertThat<MatchOutcome?>(event!!.outcome).isEqualTo(MatchOutcome.NO_MATCH)
                        Assertions.assertThat<MatchStrategy?>(event.boundaryStrategy).isEqualTo(MatchStrategy.LITERAL)
                        Assertions.assertThat<StrategyParticipation?>(event.auxiliaryStrategies)
                            .contains(
                                StrategyParticipation(
                                    MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER
                                )
                            )
                        Assertions.assertThat(event.forwardDfaSearchCount).isZero()
                    })
        }

        Assertions.assertThat(matcherPattern.matcher(hit).find()).isTrue()
        Assertions.assertThat(booleanPattern.find(hit)).isTrue()
    }

    private fun operationsFor(pattern: Pattern): MutableList<OperationDiagnostics> {
        val patternId = pattern.descriptor().patternId
        return diagnostics.operations.stream()
            .filter { event: OperationDiagnostics? -> event!!.pattern.patternId == patternId }
            .toList()
    }

    private class RecordingDiagnostics : SafeReMatchDiagnostics() {
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()

        override fun onOperationCompleted(event: OperationDiagnostics?) {
            operations.add(event)
        }
    }

    companion object {
        private fun literalFindOptions(): Stream<Arguments?>? {
            return Stream.of<String?>("hello", "é", "😀", "café😀")
                .flatMap<Arguments?> { literal: String? ->
                    Stream.of<Boolean?>(false, true)
                        .flatMap<Arguments?> { literalFastPaths: Boolean? ->
                            Stream.of<Boolean?>(false, true)
                                .map<Arguments?> { present: Boolean? ->
                                    Arguments.of(
                                        literal,
                                        literalFastPaths,
                                        present
                                    )
                                }
                        }
                }
        }

        private fun patternBooleanFindScenarios(): Stream<Arguments?> {
            return Stream.of<Arguments?>(
                Arguments.of("é", "xéy", true),
                Arguments.of("é", "xyz", false),
                Arguments.of("é[ab]+c", "xxéabc", true),
                Arguments.of("é[ab]+c", "xxézz", false),
                Arguments.of("[ab]+é", "xxabé", true),
                Arguments.of("[一-龥]{3,}", "ordinary ASCII text", false),
                Arguments.of("\\Xz", "a\u0301z", true),
                Arguments.of(".*z$", "abc", false),
                Arguments.of("終点😀$", "é😀".repeat(400) + "終点😀", true),
                Arguments.of("終点😀$", "é😀".repeat(400) + "終点😀\r\n", true),
                Arguments.of("終点😀$", "é😀".repeat(400) + "終点😀x", false),
                Arguments.of("(?:a+?|[^x]*)$", "x".repeat(1024) + "a\n", true),
                Arguments.of("(a?)*X", "aaaaX", true)
            )
        }

        private fun exactEngineOptions(bitState: Boolean): EnginePathOptions {
            return EnginePathOptions.builder()
                .literalFastPaths(false)
                .charClassMatchFastPaths(false)
                .keywordAlternationFastPath(false)
                .startAcceleration(false)
                .shiftDfa(false)
                .onePass(false)
                .dfa(false)
                .bitState(bitState)
                .build()
        }

        private fun matcher(pattern: Pattern, input: String): Utf8Matcher {
            return pattern.matcher(input(input))
        }

        private fun input(input: String): Utf8Input {
            return Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
