// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
internal class WorkLimitTest {
    @Test
    fun forRemainingCalculatesDoubleRemainingWithMinimumOne() {
        Assertions.assertThat(WorkLimit.forRemaining(0)).isEqualTo(1L)
        Assertions.assertThat(WorkLimit.forRemaining(-5)).isEqualTo(1L)
        Assertions.assertThat(WorkLimit.forRemaining(100)).isEqualTo(200L)
        Assertions.assertThat(WorkLimit.forRemaining(Int.Companion.MAX_VALUE)).isEqualTo(4294967294L)
    }

    @Test
    fun candidateInBoundsHandlesValidAndOutOfBoundsCandidates() {
        Assertions.assertThat(WorkLimit.candidateInBounds(0, 0, 10, 5)).isTrue()
        Assertions.assertThat(WorkLimit.candidateInBounds(5, 0, 10, 5)).isTrue()
        Assertions.assertThat(WorkLimit.candidateInBounds(6, 0, 10, 5)).isFalse()
        Assertions.assertThat(WorkLimit.candidateInBounds(-1, 0, 10, 5)).isFalse()
        Assertions.assertThat(WorkLimit.candidateInBounds(2, 3, 10, 5)).isFalse()
    }

    @Test
    fun candidateInBoundsDoesNotOverflowNearIntegerMax() {
        Assertions.assertThat(WorkLimit.candidateInBounds(Int.Companion.MAX_VALUE - 4, 0, Int.Companion.MAX_VALUE, 8))
            .isFalse()
        Assertions.assertThat(WorkLimit.candidateInBounds(Int.Companion.MAX_VALUE - 8, 0, Int.Companion.MAX_VALUE, 8))
            .isTrue()
    }

    @Test
    fun addCandidateWorkAccumulatesCandidateCountAndMatchLength() {
        var work: Long = 0
        work = WorkLimit.addCandidateWork(work, 2, 8)
        Assertions.assertThat(work).isEqualTo((2 * 8 + java.lang.Long.BYTES).toLong())

        work = WorkLimit.addCandidateWork(work, 3, 8)
        Assertions.assertThat(work)
            .isEqualTo(((2 * 8 + java.lang.Long.BYTES) + (3 * 8 + java.lang.Long.BYTES)).toLong())
    }

    @Test
    fun isExhaustedThresholdChecks() {
        Assertions.assertThat(WorkLimit.isExhausted(99, 100)).isFalse()
        Assertions.assertThat(WorkLimit.isExhausted(100, 100)).isTrue()
        Assertions.assertThat(WorkLimit.isExhausted(101, 100)).isTrue()
    }
}
