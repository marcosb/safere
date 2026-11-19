// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.util.concurrent.TimeUnit

/** Four workers share a Pattern while retaining private cursors and adaptation state.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
class AdaptiveCaptureSharedBenchmark {
    /** Explicit investigation-owned scenario.  */
    @Param([])
    var scenario: String? = null

    /** Native String or UTF-8 execution.  */
    @Param(["string", "utf8"])
    var representation: String? = null

    /** Experimental library policy selected before Matcher initialization in each fresh fork.  */
    @Param([])
    var policy: String? = null

    /** Reproducible Matcher-local exploration seed.  */
    @Param(["1"])
    var seed: Long = 0

    private var tasks: ThreadLocal<Tasks?>? = null

    /** Prepares one shared Pattern and independently validates both consumption roles.  */
    @Setup
    fun setup() {
        AdaptiveCaptureBenchmark.configure(policy, seed)
        val selected: AdaptiveCaptureCase = AdaptiveCaptureCase.load(scenario, representation)
        selected.validate()
        AdaptiveCaptureBenchmark.verify(policy, seed)
        val shared: AdaptiveCaptureCase.Shared = selected.shared()
        tasks = ThreadLocal.withInitial({
            org.safere.benchmark.AdaptiveCaptureSharedBenchmark.Tasks(
                shared.task(false),
                shared.task(true)
            )
        })
    }

    /** Even workers read captures; odd workers consume only match existence.  */
    @Benchmark
    fun run(thread: ThreadParams): Long {
        val local: Tasks = tasks.get()
        return (if ((thread.getThreadIndex() and 1) === 0) local.reader else local.booleanConsumer)
            .run(null)
    }

    private class Tasks(reader: AdaptiveCaptureCase.Task?, booleanConsumer: AdaptiveCaptureCase.Task?) {
        val reader: AdaptiveCaptureCase.Task?
        val booleanConsumer: AdaptiveCaptureCase.Task?


    }
}
