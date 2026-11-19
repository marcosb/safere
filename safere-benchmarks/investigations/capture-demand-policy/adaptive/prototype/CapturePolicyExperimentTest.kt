// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions.assertThat

internal class CapturePolicyExperimentTest {
    @Test
    fun recordsEachRetiredSuccessOnceAfterRepeatedAndDelayedReads() {
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        policy.begin(128)
        policy.finish(true)
        policy.read(true)
        policy.read(false)
        assertThat(policy.snapshot().successes()).isZero()
        policy.retire()
        policy.retire()
        assertThat(policy.snapshot().successes()).isEqualTo(1)
        assertThat(policy.snapshot().reads()).isEqualTo(1)
        assertThat(policy.snapshot().replays()).isEqualTo(1)
    }

    @Test
    fun terminalFailureDoesNotDiluteMeaningfulSearchHistory() {
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        policy.begin(128)
        policy.finish(true)
        policy.begin(0)
        policy.finish(false)
        assertThat(policy.snapshot().successes()).isEqualTo(1)
        assertThat(policy.snapshot().reads()).isZero()
        assertThat(policy.snapshot().attempts()).isEqualTo(1)
        assertThat(policy.snapshot().hits()).isEqualTo(1)
        assertThat(policy.snapshot().credit()).isEqualTo(-1)
        policy.begin(128)
        policy.finish(false)
        assertThat(policy.snapshot().attempts()).isEqualTo(2)
        assertThat(policy.snapshot().hits()).isEqualTo(1)
        assertThat(policy.snapshot().credit()).isEqualTo(-5)
    }

    @Test
    fun anchoredPreferenceAndRepeatedDecisionsDoNotAddExtraAttempts() {
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        policy.begin(128)
        assertThat(policy.prefer(true, true)).isTrue()
        assertThat(policy.snapshot().choices()).isZero()
        val first: Boolean = policy.prefer(false, false)
        assertThat(policy.prefer(true, false)).isEqualTo(first)
        assertThat(policy.snapshot().choices()).isEqualTo(1)
    }

    @Test
    fun historiesMeansAndCreditsRemainBoundedUnderLongRuns() {
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        for (i in 0..100000 - 1) {
            policy.begin(128)
            policy.prefer(false, false)
            policy.work(Long.MAX_VALUE)
            policy.finish(true)
            if ((i and 3) == 0) {
                policy.read(true)
                policy.beginReplay()
                policy.work(Long.MAX_VALUE)
                policy.endReplay()
            }
        }
        policy.retire()
        val state: CapturePolicyExperiment.Snapshot = policy.snapshot()
        assertThat(state.successes()).isEqualTo(32)
        assertThat(state.reads()).isEqualTo(8)
        assertThat(state.attempts()).isEqualTo(32)
        assertThat(state.hits()).isEqualTo(32)
        assertThat(state.credit()).isBetween(-32, 32)
        assertThat(state.dfaMean()).isBetween(0, 1 shl 20)
        assertThat(state.captureMean()).isBetween(0, 1 shl 20)
        assertThat(state.dfaSamples()).isBetween(0, 4096)
        assertThat(state.captureSamples()).isBetween(0, 4096)
    }

    @Test
    fun patternChangeDiscardsLearningAndUnrelatedReads() {
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        policy.begin(128)
        policy.finish(true)
        policy.read(true)
        policy.retire()
        policy.resetPattern()
        policy.read(true)
        policy.retire()
        assertThat(policy.snapshot().successes()).isZero()
        assertThat(policy.snapshot().reads()).isZero()
        assertThat(policy.snapshot().attempts()).isZero()
        assertThat(policy.snapshot().credit()).isZero()
    }

    @Test
    fun localRandomStateMakesIdenticalHistoriesRepeatable() {
        val first: CapturePolicyExperiment = CapturePolicyExperiment()
        val second: CapturePolicyExperiment = CapturePolicyExperiment()
        for (i in 0..1023) {
            first.begin(128)
            second.begin(128)
            assertThat(first.prefer(false, false)).isEqualTo(second.prefer(false, false))
            first.work(100 + i % 127)
            second.work(100 + i % 127)
            first.finish(true)
            second.finish(true)
            first.read(true)
            second.read(true)
        }
        assertThat(first.snapshot()).isEqualTo(second.snapshot())
    }

    @Test
    fun consumptionFractionLearnsFrequentReadsAndForgetsUnusedSuccesses() {
        Assumptions.assumeTrue(
            CapturePolicyExperiment.MODE.equals("fraction")
                    || CapturePolicyExperiment.MODE.equals("joint")
        )
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        for (i in 0..31) {
            policy.begin(128)
            policy.finish(true)
            policy.read(false)
        }
        policy.begin(128)
        assertThat(policy.prefer(false, false)).isTrue()
        policy.finish(true)
        for (i in 0..31) {
            policy.begin(128)
            policy.finish(true)
        }
        policy.begin(128)
        assertThat(policy.prefer(false, false)).isFalse()
        assertThat(policy.snapshot().reads()).isZero()
    }

    @Test
    fun jointPolicyDistinguishesAllReadHitsFromFrequentRejections() {
        Assumptions.assumeTrue(CapturePolicyExperiment.MODE.equals("joint"))
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        for (i in 0..511) {
            policy.begin(128)
            policy.finish((i and 15) == 0)
            if ((i and 15) == 0) {
                policy.read(true)
            }
        }
        policy.begin(128)
        assertThat(policy.snapshot().reads()).isEqualTo(32)
        assertThat(policy.snapshot().hits()).isEqualTo(2)
        assertThat(policy.prefer(false, false)).isFalse()
    }

    @Test
    fun workLearningObservesBothProducersAndGreedyChoiceUsesTheLowerEstimate() {
        Assumptions.assumeTrue(CapturePolicyExperiment.MODE.equals("work"))
        val policy: CapturePolicyExperiment = CapturePolicyExperiment()
        policy.begin(128)
        assertThat(policy.prefer(false, false)).isFalse()
        policy.work(100)
        policy.finish(true)
        policy.read(true)
        policy.begin(128)
        assertThat(policy.prefer(false, false)).isTrue()
        policy.work(10000)
        policy.finish(true)
        policy.read(false)
        policy.begin(128)
        assertThat(policy.prefer(false, false)).isFalse()
        assertThat(policy.snapshot().captureSamples()).isEqualTo(1)
        assertThat(policy.snapshot().dfaSamples()).isEqualTo(1)
    }
}
