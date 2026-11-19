// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.StandardCharsets
import java.util.List

/** Priority, context, and work bounds for speculative BitState searches.  */
@DisabledForCrosscheck("uses package-private engine paths and search windows")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
internal class BitStateWindowTest {
    @Test
    fun higherPriorityPathAtWindowEdgePreventsReturningShorterAlternative() {
        for (regex in mutableListOf<String?>("a+|a", "a+z|a", "(a?)*z|a")) {
            val prog = Pattern.compile(regex).prog()
            val bs: BitState = window(prog, StringInputScanner("a".repeat(1000)), 4)
            Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).`as`(regex).isNull()
            Assertions.assertThat(bs.windowExceeded()).`as`(regex).isTrue()
            Assertions.assertThat(bs.budgetExceeded()).`as`(regex).isFalse()
        }
    }

    @Test
    fun lowerPriorityLongPathDoesNotPreventReturningPreferredMatch() {
        val prog = Pattern.compile("a|a+").prog()
        val bs: BitState = window(prog, StringInputScanner("a".repeat(1000)), 4)
        Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).containsExactly(0, 1)
        Assertions.assertThat(bs.windowExceeded()).isFalse()
    }

    @Test
    fun assertionsAtWindowEdgeStillObserveFullInput() {
        val text = "abcd"
        for (regex in mutableListOf<String?>("ab\\b", "(?:ab$|x)", "(?:ab\\z|x)")) {
            val prog = Pattern.compile(regex).prog()
            val bs: BitState = window(prog, StringInputScanner(text), 2)
            Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).`as`(regex).isNull()
            Assertions.assertThat(bs.windowExceeded()).`as`(regex).isFalse()
        }
        val prog = Pattern.compile("ab\\B").prog()
        val bs: BitState = window(prog, StringInputScanner(text), 2)
        Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).containsExactly(0, 2)
    }

    @Test
    fun codePointStraddlingWindowEdgeRequiresFallback() {
        val prog = Pattern.compile("a😀|a").prog()
        for (input in scanners("a😀x")) {
            val bs: BitState = window(prog, input, 2)
            Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).isNull()
            Assertions.assertThat(bs.windowExceeded()).isTrue()
        }
    }

    @Test
    fun greedyUnicodeLoopsRetainDeferredCapturesAndRespectWindowEdges() {
        val prog = Pattern.compile("([é😀]*)(😀b|b)").prog()
        val text = "é😀😀b"
        for (input in scanners(text)) {
            var bs: BitState = window(prog, input, 2)
            Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).isNull()
            Assertions.assertThat(bs.windowExceeded()).isTrue()
            bs =
                BitState.getOrCreate(
                    bs, prog, input, 0, input.length, 2 * prog.numCaptures(), false, true
                )
            val expected =
                Nfa.search(
                    prog,
                    input,
                    0,
                    input.length,
                    input.length,
                    0,
                    Nfa.Anchor.ANCHORED,
                    Nfa.MatchKind.FULL_MATCH,
                    prog.numCaptures(),
                    null
                )
            Assertions.assertThat(bs.doSearch(0, input.length, true)).isEqualTo(expected)
            Assertions.assertThat(bs.windowExceeded()).isFalse()
        }
    }

    @Test
    fun reusedWindowClearsAbortedSearchState() {
        val prog = Pattern.compile("a+|a").prog()
        var bs: BitState = window(prog, StringInputScanner("a".repeat(1000)), 4)
        Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).isNull()
        Assertions.assertThat(bs.windowExceeded()).isTrue()

        bs = BitState.getOrCreate(bs, prog, StringInputScanner("a"), 0, 1, 2, false, false)
        Assertions.assertThat(bs.doSearch(0, 0, true)).containsExactly(0, 1)
        Assertions.assertThat(bs.windowExceeded()).isFalse()
        Assertions.assertThat(bs.budgetExceeded()).isFalse()
    }

    @Test
    fun longPreferredMatchAndAnchoredOperationsAgreeWithExactEngine() {
        val regex = "(a?)*b|a"
        val fast = Pattern.compile(regex)
        val exact = Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(false).build())
        val text = "a".repeat(10000) + "b"
        val actual = fast.matcher(text)
        val expected = exact.matcher(text)
        Assertions.assertThat(actual.find()).isEqualTo(expected.find())
        Assertions.assertThat(actual.end()).isEqualTo(expected.end())
        Assertions.assertThat(actual.group(1)).isEqualTo(expected.group(1))
        Assertions.assertThat(actual.reset().lookingAt()).isEqualTo(expected.reset().lookingAt())
        Assertions.assertThat(actual.end()).isEqualTo(expected.end())
        Assertions.assertThat(actual.reset().matches()).isEqualTo(expected.reset().matches())
        Assertions.assertThat(actual.end()).isEqualTo(expected.end())
        Assertions.assertThat(fast.matcher(text).replaceAll("X")).isEqualTo(exact.matcher(text).replaceAll("X"))
    }

    @Test
    fun successfulWindowsPreserveExactCapturesAcrossPatternsAndInputRepresentations() {
        val patterns = mutableListOf<String?>(
            "a|a+",
            "a+|a",
            "a+?",
            "a+z|a",
            "(a?)*",
            "(a|)*",
            "(?:\\B|a)*",
            "((a)?b)*",
            "(a*)(b?)",
            "a\\b|a\\B",
            "(.)*?b",
            "([a-z]*)(.*?)(b)",
            "([a-z]*?)([a-z]*)(b)",
            "([é😀]*)(.*?)(b)",
            "(😀|a)*",
            "[^()]*\\([^()]*\\)",
            "((?:<[^>]*>|[^()]*|\\([^()]*\\))*)\\)"
        )
        val texts: MutableList<String> =
            mutableListOf<String?>("", "a", "aaa", "aaabx", "ababx", "a😀b", "(ab)x", "<a>(b))x")
        for (regex in patterns) {
            val prog = Pattern.compile(regex).prog()
            for (text in texts) {
                for (input in scanners(text)) {
                    for (anchored in mutableListOf<Boolean?>(false, true)) {
                        val expected =
                            Nfa.search(
                                prog,
                                input,
                                0,
                                input.length,
                                input.length,
                                0,
                                if (anchored) Nfa.Anchor.ANCHORED else Nfa.Anchor.UNANCHORED,
                                Nfa.MatchKind.FIRST_MATCH,
                                prog.numCaptures(),
                                null
                            )
                        for (end in 0..input.length) {
                            val bs: BitState = window(prog, input, end)
                            val result = bs.doSearchWindow(0, input.length, anchored!!, null)
                            if (result != null) {
                                Assertions.assertThat(bs.windowExceeded()).isFalse()
                                Assertions.assertThat(result)
                                    .`as`("/%s/ on %s, anchored=%s, window=%s", regex, text, anchored, end)
                                    .isEqualTo(expected)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun largeInputFindSequencesAndReplacementsAgreeWithExactEngine() {
        val regex = "\\[(.*?)]\\(customProtocol://((?:<[^>]*>|[^()]*|\\([^()]*\\))*)\\)"
        val fast = Pattern.compile(regex)
        val exact = Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(false).build())
        for (label in List.of<String?>("short", "α😀", "a".repeat(300))) {
            val text = ("[" + label + "](customProtocol://<doc_1>) ").repeat(400)
            for (replacement in mutableListOf<String?>("X", "$1:$2", "$0")) {
                Assertions.assertThat(fast.matcher(text).replaceAll(replacement))
                    .isEqualTo(exact.matcher(text).replaceAll(replacement))
            }
            val actual = fast.matcher(text)
            val expected = exact.matcher(text)
            while (expected.find()) {
                Assertions.assertThat(actual.find()).isTrue()
                for (group in 0..expected.groupCount()) {
                    Assertions.assertThat(actual.start(group)).isEqualTo(expected.start(group))
                    Assertions.assertThat(actual.end(group)).isEqualTo(expected.end(group))
                }
            }
            Assertions.assertThat(actual.find()).isFalse()
            val bytes = Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8))
            val actualUtf8 = fast.matcher(bytes)
            val expectedUtf8 = exact.matcher(bytes)
            while (expectedUtf8.find()) {
                Assertions.assertThat(actualUtf8.find()).isTrue()
                for (group in 0..expectedUtf8.groupCount()) {
                    Assertions.assertThat(actualUtf8.start(group)).isEqualTo(expectedUtf8.start(group))
                    Assertions.assertThat(actualUtf8.end(group)).isEqualTo(expectedUtf8.end(group))
                }
            }
            Assertions.assertThat(actualUtf8.find()).isFalse()
        }
    }

    @Test
    fun shortNullableLoopMatchInLargeInputUsesBitState() {
        val pattern = Pattern.compile("((a)?b?)*c")
        val text = "abbc" + "x".repeat(100000)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics> = ArrayList<OperationDiagnostics>()
        Pattern.setDiagnostics(
            object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    operations.add(event!!)
                }
            })
        try {
            Assertions.assertThat(pattern.matcher(text).find()).isTrue()
            Assertions.assertThat(pattern.matcher(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8))).find())
                .isTrue()
            Assertions.assertThat<OperationDiagnostics?>(operations).hasSize(2)
            for (operation in operations) {
                Assertions.assertThat<MatchStrategy?>(operation.boundaryStrategy).isEqualTo(MatchStrategy.BIT_STATE)
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    @Tag("work-counter")
    fun repeatedShortMatchesUseLinearWorkAcrossInputSizes() {
        for (atom in mutableListOf<String?>("a", "α", "😀")) {
            val pattern = Pattern.compile("S((?:" + atom + "?)*)E")
            for (utf8 in mutableListOf<Boolean?>(false, true)) {
                var previous: Long = -1
                for (size in mutableListOf<Int?>(4000, 8000, 16000)) {
                    val text = ("S" + atom + atom + "E ").repeat(size!!)
                    val stringMatcher = pattern.matcher(text)
                    val utf8Matcher = pattern.matcher(Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)))
                    val work =
                        WorkCounter.countForTesting(
                            Runnable {
                                var matches = 0
                                while (if (utf8) utf8Matcher.find() else stringMatcher.find()) {
                                    matches++
                                }
                                Assertions.assertThat(matches).isEqualTo(size)
                            })
                    if (previous >= 0) {
                        Assertions.assertThat(work).`as`("%s, UTF-8=%s", atom, utf8).isLessThan(previous * 3)
                    }
                    previous = work
                }
            }
        }
    }

    @Test
    @Tag("work-counter")
    fun speculationWorkIsIndependentOfUnvisitedInputLength() {
        val prog = Pattern.compile("(a?)*z|a").prog()
        var previous: Long = -1
        for (size in mutableListOf<Int?>(10000, 100000, 1000000)) {
            val bs: BitState =
                window(prog, StringInputScanner("a".repeat(size!!)), BitState.SPECULATIVE_WINDOW_SIZE)
            val work =
                WorkCounter.countForTesting(
                    Runnable { Assertions.assertThat(bs.doSearchWindow(0, 0, true, null)).isNull() })
            Assertions.assertThat(bs.windowExceeded()).isTrue()
            if (previous >= 0) {
                Assertions.assertThat(work).isEqualTo(previous)
            }
            previous = work
        }
    }

    @Test
    fun longMatchesThenShortMatchesRecoverSpeculationWithExactCaptures() {
        val pattern = Pattern.compile(PROTOCOL_LINK_REGEX)
        val exact =
            Pattern.compile(
                PROTOCOL_LINK_REGEX, 0, EnginePathOptions.builder().bitState(false).build()
            )
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (label in mutableListOf<String?>("Click here", "α😀")) {
                val longMatch = "[" + "a".repeat(400) + "](customProtocol://<doc_1>) "
                val text =
                    longMatch.repeat(2) + ("[" + label + "](customProtocol://<doc_1>) ").repeat(3000)
                val actual: MutableList<Matcher> = matchers(pattern, text)
                val expected: MutableList<Matcher> = matchers(exact, text)
                for (representation in actual.indices) {
                    val matcher = actual.get(representation)
                    val oracle = expected.get(representation)
                    var recovered = false
                    for (i in 0..3001) {
                        operations.clear()
                        Assertions.assertThat(matcher.find()).isTrue()
                        if (i >= 2 && i < 66) {
                            recovered = recovered or (operations.first().boundaryStrategy == MatchStrategy.BIT_STATE)
                        }
                        Assertions.assertThat(oracle.find()).isTrue()
                        for (group in 0..matcher.groupCount()) {
                            Assertions.assertThat(matcher.start(group)).isEqualTo(oracle.start(group))
                            Assertions.assertThat(matcher.end(group)).isEqualTo(oracle.end(group))
                            if (representation == 0) {
                                Assertions.assertThat(matcher.group(group)).isEqualTo(oracle.group(group))
                            }
                        }
                    }
                    Assertions.assertThat(matcher.find()).isFalse()
                    Assertions.assertThat(oracle.find()).isFalse()
                    Assertions.assertThat(recovered)
                        .`as`("short matches should recover while input is still large")
                        .isTrue()
                }
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun failedRetriesBecomeLessFrequentButStillRecoverAfterSustainedFailures() {
        val pattern: Pattern = unhintedPattern(PROTOCOL_LINK_REGEX)
        val longMatch = "[" + "a".repeat(400) + "](customProtocol://<doc_1>) "
        val shortMatch = "[α😀](customProtocol://<doc_1>) "
        val text = longMatch.repeat(2000) + shortMatch.repeat(3000)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (matcher in matchers(pattern, text)) {
                val failures: MutableList<Int> = ArrayList<Int>()
                for (i in 0..1999) {
                    operations.clear()
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat(matcher.end(1) - matcher.start(1)).isEqualTo(400)
                    if (operations.first().strategyDecisions.contains(windowFailure())) {
                        failures.add(i)
                    } else {
                        Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions).contains(
                            windowBackoff()
                        )
                    }
                }
                Assertions.assertThat<Int?>(failures).hasSizeBetween(5, 30).startsWith(0, 1)
                var previousGap = 1
                for (i in 2..<failures.size) {
                    val gap = failures.get(i) - failures.get(i - 1)
                    Assertions.assertThat(gap).isBetween(previousGap, 512)
                    previousGap = gap
                }
                Assertions.assertThat(previousGap)
                    .isEqualTo(failures.get(failures.size - 2) - failures.get(failures.size - 3))
                var recovered = false
                for (i in 0..511) {
                    operations.clear()
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat(matcher.end(1) - matcher.start(1)).isIn(3, 6)
                    if (operations.first().boundaryStrategy == MatchStrategy.BIT_STATE) {
                        recovered = true
                        // A successful retry restores consecutive speculation, without another delay.
                        operations.clear()
                        Assertions.assertThat(matcher.find()).isTrue()
                        Assertions.assertThat<MatchStrategy?>(operations.first().boundaryStrategy)
                            .isEqualTo(MatchStrategy.BIT_STATE)
                        break
                    }
                }
                Assertions.assertThat(recovered).isTrue()
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    @Tag("work-counter")
    fun retryTransitionsPreserveLinearWorkAcrossInputSizes() {
        val pattern = Pattern.compile(PROTOCOL_LINK_REGEX)
        val longMatch = "[" + "a".repeat(400) + "](customProtocol://<doc_1>) "
        val shortMatch = "[α😀](customProtocol://<doc_1>) "
        val block = longMatch.repeat(2) + shortMatch.repeat(32)
        for (utf8 in mutableListOf<Boolean?>(false, true)) {
            var previous: Long = -1
            for (size in mutableListOf<Int?>(64, 128, 256)) {
                val matcher: Matcher = matchers(pattern, block.repeat(size!!)).get(if (utf8) 1 else 0)
                val work =
                    WorkCounter.countForTesting(
                        Runnable {
                            var matches = 0
                            while (matcher.find()) {
                                matches++
                            }
                            Assertions.assertThat(matches).isEqualTo(size * 34)
                        })
                if (previous >= 0) {
                    Assertions.assertThat(work).`as`("UTF-8=%s", utf8).isLessThan(previous * 3)
                }
                previous = work
            }
        }
    }

    @Test
    fun resetRegionAndPatternChangesClearRetryDelayForBothInputRepresentations() {
        val pattern: Pattern = unhintedPattern(PROTOCOL_LINK_REGEX)
        val text = ("[" + "a".repeat(400) + "](customProtocol://<doc_1>) ").repeat(100)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (change in mutableListOf<String?>("reset", "region", "pattern")) {
                for (matcher in matchers(pattern, text)) {
                    for (i in 0..2) {
                        operations.clear()
                        Assertions.assertThat(matcher.find()).isTrue()
                    }
                    Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions).contains(
                        windowBackoff()
                    )
                    when (change) {
                        "reset" -> matcher.reset()
                        "region" -> matcher.region(0, text.length)
                        "pattern" -> matcher.usePattern(unhintedPattern(PROTOCOL_LINK_REGEX + "|Z"))
                        else -> throw AssertionError(change)
                    }
                    operations.clear()
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions).contains(
                        windowFailure()
                    )
                    Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions).doesNotContain(
                        windowBackoff()
                    )
                }
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun repeatedWindowFailuresBackOffUntilResetForBothInputRepresentations() {
        val pattern: Pattern = unhintedPattern(PROTOCOL_LINK_REGEX)
        val longMatch = "[" + "a".repeat(400) + "](customProtocol://<doc_1>) "
        val text = longMatch.repeat(100)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (matcher in matchers(pattern, text)) {
                operations.clear()
                for (i in 0..2) {
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat(matcher.start()).isEqualTo(i * longMatch.length)
                    Assertions.assertThat(matcher.end()).isEqualTo(i * longMatch.length + longMatch.length - 1)
                    Assertions.assertThat(matcher.start(1)).isEqualTo(matcher.start() + 1)
                    Assertions.assertThat(matcher.end(1) - matcher.start(1)).isEqualTo(400)
                }
                Assertions.assertThat<StrategyDecision?>(operations.get(0)!!.strategyDecisions)
                    .contains(windowFailure())
                Assertions.assertThat<StrategyDecision?>(operations.get(1)!!.strategyDecisions)
                    .contains(windowFailure())
                Assertions.assertThat<StrategyDecision?>(operations.get(2)!!.strategyDecisions)
                    .contains(windowBackoff())
                    .doesNotContain(windowFailure())

                operations.clear()
                Assertions.assertThat(matcher.reset().find()).isTrue()
                Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions)
                    .contains(windowFailure())
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun successfulWindowBreaksConsecutiveFailureStreak() {
        val pattern: Pattern = unhintedPattern(PROTOCOL_LINK_REGEX)
        val longMatch = "[" + "a".repeat(400) + "](customProtocol://<doc_1>) "
        val text = longMatch + "[aa](customProtocol://<doc_1>) " + longMatch.repeat(100)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (matcher in matchers(pattern, text)) {
                operations.clear()
                for (i in 0..4) {
                    Assertions.assertThat(matcher.find()).isTrue()
                }
                Assertions.assertThat<StrategyDecision?>(operations.get(0)!!.strategyDecisions)
                    .contains(windowFailure())
                Assertions.assertThat<MatchStrategy?>(operations.get(1)!!.boundaryStrategy)
                    .isEqualTo(MatchStrategy.BIT_STATE)
                Assertions.assertThat<StrategyDecision?>(operations.get(2)!!.strategyDecisions)
                    .contains(windowFailure())
                Assertions.assertThat<StrategyDecision?>(operations.get(3)!!.strategyDecisions)
                    .contains(windowFailure())
                Assertions.assertThat<StrategyDecision?>(operations.get(4)!!.strategyDecisions)
                    .contains(windowBackoff())
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun changingPatternOrInputClearsSpeculativeBackoff() {
        val pattern: Pattern = unhintedPattern(PROTOCOL_LINK_REGEX)
        val longText = ("[" + "a".repeat(400) + "](customProtocol://<doc_1>) ").repeat(100)
        val shortText = "[aa](customProtocol://<doc_1>) " + "x".repeat(40000)
        val matcher = pattern.matcher(longText)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.reset(shortText).find()).isTrue()
            Assertions.assertThat<MatchStrategy?>(operations.last().boundaryStrategy)
                .isEqualTo(MatchStrategy.BIT_STATE)

            matcher.reset(longText)
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat(matcher.find()).isTrue()
            operations.clear()
            matcher.usePattern(unhintedPattern(PROTOCOL_LINK_REGEX + "|Z"))
            Assertions.assertThat(matcher.find()).isTrue()
            Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions).contains(windowFailure())
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun dfaEndHintLetsExactWindowsReachBeyondTheDefaultSize() {
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (atom in mutableListOf<String?>("a", "α", "😀")) {
                val regex = "(" + atom + "?)*b"
                val text = atom!!.repeat(400) + "b" + "q".repeat(40000)
                val pattern = Pattern.compile(regex)
                val exact =
                    Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(false).build())
                val actual: MutableList<Matcher> = matchers(pattern, text)
                val expected: MutableList<Matcher> = matchers(exact, text)
                for (i in actual.indices) {
                    operations.clear()
                    Assertions.assertThat(actual.get(i).find()).isTrue()
                    Assertions.assertThat<MatchStrategy?>(operations.first().boundaryStrategy)
                        .isEqualTo(MatchStrategy.BIT_STATE)
                    Assertions.assertThat(expected.get(i).find()).isTrue()
                    Assertions.assertThat(actual.get(i).start()).isEqualTo(expected.get(i).start())
                    Assertions.assertThat(actual.get(i).end()).isEqualTo(expected.get(i).end())
                }
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    @Tag("work-counter")
    fun lazyRuneLoopsChargeOnlyRequestedCaptureRestoresAndCountDecodes() {
        val prog = Pattern.compile("x(.*?)z").prog()
        for (ncap in intArrayOf(2, 4)) {
            for (utf8 in mutableListOf<Boolean?>(false, true)) {
                val shortWork: Long = lazyRuneLoopWork(prog, 100, ncap, utf8!!)
                val longWork: Long = lazyRuneLoopWork(prog, 300, ncap, utf8)
                // Each extra ASCII rune tries ALT, capture, literal and body. Captures add a restore
                // only when requested. The failed literal and the body each decode one UTF-8 byte.
                val workPerRune = 4 + (if (ncap == 4) 1 else 0) + (if (utf8) 2 else 0)
                Assertions.assertThat(longWork - shortWork)
                    .`as`("ncap=%s, UTF-8=%s", ncap, utf8)
                    .isEqualTo(200L * workPerRune)
            }
        }
    }

    @Test
    fun oversizedDfaHintsLeaveSpeculativeWorkBounded() {
        val pattern = Pattern.compile("(a?)*b")
        val text = "a".repeat(4096) + "b" + "q".repeat(40000)
        Assertions.assertThat(BitState.maxTextSize(pattern.prog())).isGreaterThan(4097)
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (matcher in matchers(pattern, text)) {
                operations.clear()
                Assertions.assertThat(matcher.find()).isTrue()
                Assertions.assertThat(matcher.end()).isEqualTo(4097)
                Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions)
                    .contains(windowFailure())
                Assertions.assertThat<MatchStrategy?>(operations.first().boundaryStrategy)
                    .isEqualTo(MatchStrategy.NFA)
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @ParameterizedTest(name = "prefix repeats {0}, expected speculative window {1}")
    @CsvSource("1023, 2048", "4096, 256")
    fun failedSpeculationChargesOnlyWorkForTheAllowedWindow(repeats: Int, windowSize: Int) {
        val prefix = "ab".repeat(repeats) + "bc"
        val previous = Pattern.diagnostics()
        val operations: MutableList<OperationDiagnostics?> = ArrayList<OperationDiagnostics?>()
        Pattern.setDiagnostics(recordOperations(operations))
        try {
            for (tailLength in mutableListOf<Int?>(20000, 40000)) {
                val text = prefix + "q".repeat(tailLength!!)
                for (scanner in scanners(text)) {
                    val pattern = Pattern.compile("(?:(b?)*?(c|)+)*c")
                    // The hint fits the bitmap, including when it exceeds the separate hint limit.
                    Assertions.assertThat(BitState.maxTextSize(pattern.prog()))
                        .isBetween(prefix.length, scanner.length - 1)
                    val cache = pattern.getOrCreateThreadCache()
                    val matcher =
                        if (scanner is Utf8InputScanner)
                            Matcher(pattern, scanner)
                        else
                            pattern.matcher(text)
                    operations.clear()
                    Assertions.assertThat(matcher.find()).isTrue()
                    Assertions.assertThat<StrategyDecision?>(operations.first().strategyDecisions).contains(
                        windowFailure()
                    )
                    Assertions.assertThat<MatchStrategy?>(operations.first().boundaryStrategy)
                        .isEqualTo(MatchStrategy.NFA)

                    // The NFA restart leaves the failed attempt in the cache with its own step count.
                    val attempt = cache.bitState
                    Assertions.assertThat<BitState?>(attempt).isNotNull()
                    val allowedWork: Long = window(pattern.prog(), scanner, windowSize).stepBudgetForTesting()
                    Assertions.assertThat(attempt!!.stepCountForTesting()).isBetween(1L, allowedWork)
                    Assertions.assertThat(attempt.stepBudgetForTesting()).isEqualTo(allowedWork)
                    Assertions.assertThat(matcher.start()).isEqualTo(prefix.length - 3)
                    Assertions.assertThat(matcher.end()).isEqualTo(prefix.length)
                }
            }
        } finally {
            Pattern.setDiagnostics(previous)
        }
    }

    @Test
    fun hintedWindowsPreservePriorityCapturesAndReplacement() {
        for (regex in mutableListOf<String?>("(a?)*b", "(a?|ab)*b", "([ab]?)*z|b", "((a?)*b)+", "(a?)*b\\B")) {
            val pattern = Pattern.compile(regex)
            val exact =
                Pattern.compile(regex, 0, EnginePathOptions.builder().bitState(false).build())
            for (length in mutableListOf<Int?>(255, 256, 257, 400, 30000)) {
                val text = "a".repeat(length!!) + "bz " + "q".repeat(40000)
                val actual: MutableList<Matcher> = matchers(pattern, text)
                val expected: MutableList<Matcher> = matchers(exact, text)
                for (i in actual.indices) {
                    val matcher = actual.get(i)
                    val oracle = expected.get(i)
                    while (oracle.find()) {
                        Assertions.assertThat(matcher.find()).`as`("%s, length=%s", regex, length).isTrue()
                        for (group in 0..matcher.groupCount()) {
                            Assertions.assertThat(matcher.start(group)).isEqualTo(oracle.start(group))
                            Assertions.assertThat(matcher.end(group)).isEqualTo(oracle.end(group))
                        }
                    }
                    Assertions.assertThat(matcher.find()).isFalse()
                }
                Assertions.assertThat(pattern.matcher(text).replaceAll("<$1>"))
                    .isEqualTo(exact.matcher(text).replaceAll("<$1>"))
                Assertions.assertThat(pattern.matcher(text).replaceAll("X"))
                    .isEqualTo(exact.matcher(text).replaceAll("X"))
            }
        }
    }

    companion object {
        private const val PROTOCOL_LINK_REGEX = "\\[(.*?)]\\(customProtocol://((?:<[^>]*>|[^()]*|\\([^()]*\\))*)\\)"

        private fun lazyRuneLoopWork(prog: Prog?, repeat: Int, ncap: Int, utf8: Boolean): Long {
            val scanner: InputScanner = scanners("x" + "a".repeat(repeat) + "z").get(if (utf8) 1 else 0)
            val engine =
                BitState.getOrCreate(null, prog, scanner, 0, scanner.length, ncap, false, false)
            return WorkCounter.countForTesting(
                Runnable { Assertions.assertThat(engine.doSearch(0, 0, true)).startsWith(0, scanner.length) })
        }

        private fun unhintedPattern(regex: String?): Pattern {
            // Keep retry/backoff coverage for searches that have no usable DFA end hint.
            return Pattern.compile(regex, 0, EnginePathOptions.builder().dfa(false).build())
        }

        private fun recordOperations(operations: MutableList<OperationDiagnostics?>): SafeReMatchDiagnostics {
            return object : SafeReMatchDiagnostics() {
                override fun onOperationCompleted(event: OperationDiagnostics?) {
                    operations.add(event)
                }
            }
        }

        private fun windowFailure(): StrategyDecision {
            return StrategyDecision(
                MatchStrategy.BIT_STATE,
                StrategyDisposition.FALLBACK,
                StrategyReason.SPECULATIVE_WINDOW_EXCEEDED
            )
        }

        private fun windowBackoff(): StrategyDecision {
            return StrategyDecision(
                MatchStrategy.BIT_STATE, StrategyDisposition.BYPASSED, StrategyReason.SPECULATIVE_BACKOFF
            )
        }

        private fun matchers(pattern: Pattern, text: String): MutableList<Matcher> {
            return List.of<Matcher?>(
                pattern.matcher(text),
                Matcher(
                    pattern,
                    (Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)) as ArrayUtf8Input).scanner()
                )
            )
        }

        private fun window(prog: Prog, input: InputScanner?, end: Int): BitState {
            return BitState.getOrCreate(null, prog, input, 0, end, 2 * prog.numCaptures(), false, false)
        }

        private fun scanners(text: String): MutableList<InputScanner> {
            return List.of<InputScanner?>(
                StringInputScanner(text),
                (Utf8Input.trusted(text.toByteArray(StandardCharsets.UTF_8)) as ArrayUtf8Input).scanner()
            )
        }
    }
}
