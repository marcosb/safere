// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import org.safere.exhaustive.SweepWorkers.LineWorker
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.ConcurrentSkipListSet

/** Tests for [SweepWorkers].  */
internal class SweepWorkersTest {
    @Test
    @Throws(Exception::class)
    fun singleThreadRunUsesWorkerIndexZero() {
        val workers: MutableSet<Int?> = ConcurrentSkipListSet<Int?>()

        SweepWorkers.run(1, "test-", SweepWorkers.Worker { e: Int -> workers.add(e) })

        Assertions.assertThat<Int?>(workers).containsExactly(0)
    }

    @Test
    @Throws(Exception::class)
    fun multiThreadRunUsesEveryWorkerIndex() {
        val workers: MutableSet<Int?> = ConcurrentSkipListSet<Int?>()

        SweepWorkers.run(4, "test-", SweepWorkers.Worker { e: Int -> workers.add(e) })

        Assertions.assertThat<Int?>(workers).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun checkedExceptionsPropagateAsIoException() {
        val failure = IOException("boom")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                SweepWorkers.run(
                    1,
                    "test-",
                    SweepWorkers.Worker { workerIndex: Int ->
                        throw failure
                    })
            })
            .isSameAs(failure)
    }

    @Test
    fun runtimeExceptionsPropagateWithoutWrapping() {
        val failure = IllegalStateException("boom")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                SweepWorkers.run(
                    2,
                    "test-",
                    SweepWorkers.Worker { workerIndex: Int ->
                        throw failure
                    })
            })
            .isSameAs(failure)
    }

    @Test
    @Throws(Exception::class)
    fun streamingLinesUsesWorkersAndSkipsBlankAndCommentLines() {
        val lines: MutableSet<String?> = ConcurrentSkipListSet<String?>()

        val produced =
            SweepWorkers.runStreamingLines(
                3,
                "test-",
                BufferedReader(StringReader("a\n\n# comment\nb\nc\n")),
                LineWorker { e: String? -> lines.add(e) })

        Assertions.assertThat(produced).isEqualTo(3)
        Assertions.assertThat<String?>(lines).containsExactly("a", "b", "c")
    }

    @Test
    fun streamingLineWorkerExceptionsPropagateWithoutWrapping() {
        val failure = IllegalStateException("boom")

        Assertions.assertThatThrownBy(
            ThrowingCallable {
                SweepWorkers.runStreamingLines(
                    2,
                    "test-",
                    BufferedReader(StringReader("a\nb\n")),
                    LineWorker { line: String? ->
                        throw failure
                    })
            })
            .isSameAs(failure)
    }

    @Test
    fun firstProgressAtRoundsUpToNextIntervalBoundary() {
        Assertions.assertThat(SweepWorkers.firstProgressAt(0, 10)).isEqualTo(10)
        Assertions.assertThat(SweepWorkers.firstProgressAt(20, 10)).isEqualTo(20)
        Assertions.assertThat(SweepWorkers.firstProgressAt(21, 10)).isEqualTo(30)
    }

    @Test
    fun progressProbeIntervalSplitsProgressIntervalAcrossWorkers() {
        Assertions.assertThat(SweepWorkers.progressProbeInterval(1000, 4)).isEqualTo(250)
        Assertions.assertThat(SweepWorkers.progressProbeInterval(10, 16)).isEqualTo(1)
    }

    @Test
    fun firstOwnedCaseIndexDoesNotOverflowEmptyHighStartRange() {
        Assertions.assertThat(SweepWorkers.firstOwnedCaseIndex(Long.Companion.MAX_VALUE, 10, 2, 0)).isEqualTo(10)
        Assertions.assertThat(SweepWorkers.firstOwnedCaseIndex(Long.Companion.MAX_VALUE, 10, 2, 1)).isEqualTo(10)
    }

    @Test
    fun firstOwnedCaseIndexReturnsEndWhenWorkerHasNoSelectedCase() {
        Assertions.assertThat(SweepWorkers.firstOwnedCaseIndex(0, 1, 2, 0)).isZero()
        Assertions.assertThat(SweepWorkers.firstOwnedCaseIndex(0, 1, 2, 1)).isEqualTo(1)
    }
}
