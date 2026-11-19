// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.safere.WorkCounter.StartAccelerationWork
import java.nio.charset.StandardCharsets

/** Deterministic accounting of useful skips and recovery in DFA start acceleration.  */
@DisabledForCrosscheck("WorkCounter and acceleration policies are SafeRE implementation details")
@Tag("work-counter")
internal class DfaStartAccelerationWorkTest {
    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun denseStartsRetainProfitableSkips(utf8: Boolean, nonAscii: Boolean) {
        for (padding in intArrayOf(16, 32, 48, 80)) {
            val unit = "record:" + (if (nonAscii) "é" else "x") + ";" + "x".repeat(padding) + "\n"
            val input = unit.repeat(2048) + MATCH

            val work: StartAccelerationWork = findWork(input, utf8)

            Assertions.assertThat(work.calls).`as`("padding=%s", padding).isPositive()
            Assertions.assertThat(work.skippedUnits)
                .`as`("Profitable calls must keep bypassing DFA work, padding=%s", padding)
                .isGreaterThan(inputLength(input, utf8) * 2L / 5)
            Assertions.assertThat(work.quarantines).`as`("padding=%s", padding).isZero()
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun startsWithNegligibleSkipsBackOff(utf8: Boolean, nonAscii: Boolean) {
        val unit = "record:" + (if (nonAscii) "é" else "x") + ";\n"
        val input = unit.repeat(DENSE_RECORD_COUNT) + MATCH

        val work: StartAccelerationWork = findWork(input, utf8)

        // The general loop can skip the trailing newline; the ASCII loop already consumed it.
        Assertions.assertThat(work.skippedUnits).isLessThanOrEqualTo(work.calls)
        Assertions.assertThat(work.calls).isBetween(1L, DENSE_RECORD_COUNT / 100L)
        Assertions.assertThat(work.largestQuarantine).isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow)
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun scalarProgressCannotRestoreUnprofitableAcceleration(utf8: Boolean, nonAscii: Boolean) {
        // Offsetting the candidate stride from power-of-two quarantine windows must not make the
        // distance covered by the scalar DFA look like work saved by the next accelerator call.
        for (padding in intArrayOf(1, 2, 3)) {
            val unit = "record:" + (if (nonAscii) "é" else "x") + ";" + "x".repeat(padding) + "\n"
            val input = unit.repeat(DENSE_RECORD_COUNT) + MATCH

            val work: StartAccelerationWork = findWork(input, utf8)

            Assertions.assertThat(work.calls).`as`("padding=%s", padding).isBetween(1L, DENSE_RECORD_COUNT / 100L)
            Assertions.assertThat(work.largestQuarantine)
                .`as`("padding=%s", padding)
                .isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow)
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun breakEvenSkipsCannotEraseLossesFromOtherCalls(utf8: Boolean, nonAscii: Boolean) {
        val candidate = "record:" + (if (nonAscii) "é" else "x") + ";"
        val unit =
            (candidate
                    + "x".repeat(AcceleratorPolicy.LITERAL.minProfitableSkip)
                    + "\n"
                    + candidate
                    + "\n")
        val input = unit.repeat(DENSE_RECORD_COUNT / 2) + MATCH

        val work: StartAccelerationWork = findWork(input, utf8)

        Assertions.assertThat(work.calls).isBetween(1L, DENSE_RECORD_COUNT / 100L)
        Assertions.assertThat(work.largestQuarantine).isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow)
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun profitableBatchesCanIncludePartiallyUsefulCalls(utf8: Boolean, nonAscii: Boolean) {
        val candidate = "record:" + (if (nonAscii) "é" else "x") + ";"
        val cost = AcceleratorPolicy.LITERAL.minProfitableSkip
        // Twenty half-cost skips lose less than the sixteen-call allowance. The larger skip pays
        // back that deficit: counting every partially useful call as a full strike defeats too early.
        val batch =
            ((candidate + "x".repeat(cost / 2) + "\n").repeat(20)
                    + candidate
                    + "x".repeat(cost * 12)
                    + "\n")
        val input = batch.repeat(512) + MATCH

        val work: StartAccelerationWork = findWork(input, utf8)

        Assertions.assertThat(work.skippedUnits).isGreaterThan(inputLength(input, utf8) / 2L)
        Assertions.assertThat(work.quarantines).isZero()
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun usefulSkipsRestoreAccelerationAfterDenseStarts(utf8: Boolean, nonAscii: Boolean) {
        val candidate = "record:" + (if (nonAscii) "é" else "x") + ";"
        val dense = (candidate + "\n").repeat(DENSE_RECORD_COUNT)
        val sparse = (candidate + "x".repeat(512) + "\n").repeat(1024)

        val work: StartAccelerationWork = findWork(dense + sparse + MATCH, utf8)

        Assertions.assertThat(work.largestQuarantine).isEqualTo(AcceleratorPolicy.LITERAL.maxQuarantineWindow)
        Assertions.assertThat(work.skippedUnits).isGreaterThan(inputLength(sparse, utf8) * 3L / 4)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun repeatedModerateGapsEscalateBackoff(utf8: Boolean) {
        val dense = "record:x;\n".repeat(200)
        val input = (dense + "z".repeat(200)).repeat(128) + MATCH

        val work: StartAccelerationWork = findWork(input, utf8)

        Assertions.assertThat(work.quarantines).`as`("%s", work).isGreaterThan(16)
        Assertions.assertThat(work.calls).`as`("%s", work).isGreaterThan(512)
        Assertions.assertThat(work.largestQuarantine)
            .`as`("Repeated dense regions should escalate backoff before recovery: %s", work)
            .isGreaterThan(AcceleratorPolicy.LITERAL.initialQuarantineWindow)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun profitableGapsRestoreAccelerationAfterQuarantine(utf8: Boolean) {
        val dense = "record:x;\n".repeat(200)
        val input = (dense + "z".repeat(500)).repeat(128) + MATCH

        val work: StartAccelerationWork = findWork(input, utf8)

        Assertions.assertThat(work.quarantines).isGreaterThan(64)
        Assertions.assertThat(work.largestQuarantine)
            .`as`("Profitable gaps must repay quarantine debt: %s", work)
            .isEqualTo(AcceleratorPolicy.LITERAL.initialQuarantineWindow)
        Assertions.assertThat(work.skippedUnits)
            .isGreaterThan(work.calls * AcceleratorPolicy.LITERAL.minProfitableSkip)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun characterClassStartPolicyAccountsForDenseAndSparseCandidates(utf8: Boolean) {
        val regex = "[ab][^\\r\\n;]*val=200"
        val pattern = Pattern.compile(regex)
        val policy =
            if (utf8) pattern.utf8StartAccelerator()!!.policy() else pattern.stringStartAccelerator()!!.policy()
        Assertions.assertThat<AcceleratorPolicy?>(policy).isEqualTo(AcceleratorPolicy.CHAR_CLASS)

        val dense = "a;\n".repeat(DENSE_RECORD_COUNT) + "atarget_val=200"
        val denseWork: StartAccelerationWork = findWork(regex, "atarget_val=200", dense, utf8)
        Assertions.assertThat(denseWork.largestQuarantine).isEqualTo(policy.maxQuarantineWindow)

        val sparse = ("a;" + "x".repeat(128) + "\n").repeat(1024) + "atarget_val=200"
        val sparseWork: StartAccelerationWork = findWork(regex, "atarget_val=200", sparse, utf8)
        Assertions.assertThat(sparseWork.quarantines).isZero()
        Assertions.assertThat(sparseWork.skippedUnits).isGreaterThan(inputLength(sparse, utf8) / 2L)
    }

    companion object {
        private const val REGEX = "record:[^\\r\\n;]*val=200"
        private const val MATCH = "record:target_val=200"
        private const val DENSE_RECORD_COUNT = 30000

        private fun findWork(input: String, utf8: Boolean): StartAccelerationWork {
            return findWork(REGEX, MATCH, input, utf8)
        }

        private fun findWork(
            regex: String, match: String?, input: String, utf8: Boolean
        ): StartAccelerationWork {
            // Exercise DFA backoff independently of literal-guided narrowing before the engine search.
            val pattern =
                Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build())
            val jdk = java.util.regex.Pattern.compile(regex).matcher(input)
            Assertions.assertThat(jdk.find()).isTrue()
            Assertions.assertThat(jdk.group()).isEqualTo(match)
            if (utf8) {
                val matcher = pattern.matcher(Utf8Input.validated(input.toByteArray(StandardCharsets.UTF_8)))
                Assertions.assertThat(matcher.find()).isTrue()
                matcher.reset()
                val work =
                    WorkCounter.countStartAccelerationForTesting(Runnable {
                        Assertions.assertThat(matcher.find()).isTrue()
                    })
                Assertions.assertThat(matcher.start())
                    .isEqualTo(input.substring(0, jdk.start()).toByteArray(StandardCharsets.UTF_8).size)
                Assertions.assertThat(matcher.end())
                    .isEqualTo(input.substring(0, jdk.end()).toByteArray(StandardCharsets.UTF_8).size)
                return work
            }
            val matcher = pattern.matcher(input)
            Assertions.assertThat(matcher.find()).isTrue()
            matcher.reset()
            val work =
                WorkCounter.countStartAccelerationForTesting(Runnable {
                    Assertions.assertThat(matcher.find()).isTrue()
                })
            Assertions.assertThat(matcher.start()).isEqualTo(jdk.start())
            Assertions.assertThat(matcher.end()).isEqualTo(jdk.end())
            return work
        }

        private fun inputLength(input: String, utf8: Boolean): Int {
            return if (utf8) input.toByteArray(StandardCharsets.UTF_8).size else input.length
        }
    }
}
