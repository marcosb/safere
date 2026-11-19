// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.exhaustive

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.safere.exhaustive.SweepWorkers.ProgressReporter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.List
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier

/** Tests for [SweepRunState].  */
internal class SweepRunStateTest {
    @TempDir
    var tempDir: Path? = null

    @Test
    @Throws(Exception::class)
    fun recordDivergenceCountsEveryDivergence() {
        val options = options()

        SweepRunState(options, 10).use { state ->
            state.recordDivergence()
            state.recordDivergence()
            Assertions.assertThat(state.divergences.sum()).isEqualTo(2)
        }
    }

    @Test
    @Throws(Exception::class)
    fun appendJsonlWritesOneLinePerCall() {
        val options = options()

        SweepRunState(options, 10).use { state ->
            state.appendJsonl("{\"a\":1}")
            state.appendJsonl("{\"b\":2}")
        }
        Assertions.assertThat<String>(Files.readAllLines(options.jsonlPath())).containsExactly("{\"a\":1}", "{\"b\":2}")
    }

    @Test
    @Throws(Exception::class)
    fun recordsLargestGeneratedValue() {
        val options = options()

        SweepRunState(options, 10).use { state ->
            state.recordGenerated(10)
            state.recordGenerated(5)
            Assertions.assertThat(state.generated).isEqualTo(10)
        }
    }

    @Test
    @Throws(Exception::class)
    fun progressReportsAreTriggeredByCheckedCases() {
        val options = options(0)
        val output: ByteArrayOutputStream = progressOutputAfterCheckedCases(options, 10, 10, 10)

        Assertions.assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("progress=100.0% elapsed=1m40s eta=0s total=10 checked=10 divergences=0")
    }

    @Test
    @Throws(Exception::class)
    fun progressReportsPercentageOfTotalChecks() {
        val options = options(0)
        val output: ByteArrayOutputStream = progressOutputAfterCheckedCases(options, 853, 853, 1000)

        Assertions.assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("progress=85.3% elapsed=1m40s eta=17s total=1,000 checked=853 divergences=0")
    }

    @Test
    @Throws(Exception::class)
    fun progressReportsUseCurrentRunCheckedCountForNonzeroRanges() {
        val options = options(1000000)
        val output: ByteArrayOutputStream = progressOutputAfterCheckedCases(options, 10, 1010000, 10)

        Assertions.assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("progress=100.0% elapsed=1m40s eta=0s total=10 checked=10 divergences=0")
    }

    @Test
    @Throws(Exception::class)
    fun progressReporterCheckpointsLiveWorkerPosition() {
        val options = options()
        val clock = TestClock()

        SweepRunState(options, 10, LongSupplier { clock.nanoTime() }).use { state ->
            state.enableCompactLogs(
                "test",
                10,
                mutableListOf<String?>("UNKNOWN"),
                List.of<DivergenceStatus?>(DivergenceStatus.UNKNOWN)
            )
            val reporter = ProgressReporter(state, 0)
            reporter.checked()
            reporter.reportIfNeeded(7)
            state.checkpointCompactLogs()
        }
        Assertions.assertThat(Files.readString(tempDir!!.resolve("progress.json"))).contains("\"nextCaseIndex\":7")
    }

    private fun options(rangeStartInclusive: Long = 0): SweepOptions {
        return SweepOptions(rangeStartInclusive, Long.MAX_VALUE, tempDir, 10, 1, null, "out.jsonl")
    }

    private class TestClock {
        private var nanos: Long = 0

        fun nanoTime(): Long {
            return nanos
        }

        fun advanceSeconds(seconds: Long) {
            nanos += TimeUnit.SECONDS.toNanos(seconds)
        }
    }

    companion object {
        @Throws(Exception::class)
        private fun progressOutputAfterCheckedCases(
            options: SweepOptions, checkedCases: Long, generated: Long, totalChecks: Long
        ): ByteArrayOutputStream {
            val output = ByteArrayOutputStream()
            val originalOut = System.out
            val clock = TestClock()

            SweepRunState(options, totalChecks, LongSupplier { clock.nanoTime() }).use { state ->
                try {
                    System.setOut(PrintStream(output, true, StandardCharsets.UTF_8))

                    state.reportProgressIfNeeded(generated)
                    Assertions.assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty()

                    clock.advanceSeconds(100)
                    for (i in 0..<checkedCases) {
                        state.checked.increment()
                    }
                    state.reportProgressIfNeeded(generated)
                } finally {
                    System.setOut(originalOut)
                }
            }
            return output
        }
    }
}
