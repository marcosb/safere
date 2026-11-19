// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.util.concurrent.TimeUnit

/** Private cursors and alternating consumption roles share one compiled Pattern.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
class CapturePolicySharedBenchmark {
    /** Materialized workload and String/UTF-8 representation.  */
    @Param([])
    var captureTrial: String? = null

    /** Consumption protocol for reader threads.  */
    @Param([])
    var consumption: String? = null

    private var tasks: ThreadLocal<Tasks?>? = null

    /** Creates independent cursors for each thread using the same Pattern.  */
    @Setup
    fun setup() {
        val selected: CapturePolicyCase = CapturePolicyCase.load(captureTrial, consumption)
        tasks = ThreadLocal.withInitial({
            org.safere.benchmark.CapturePolicySharedBenchmark.Tasks(
                selected.task(),
                selected.booleanTask()
            )
        })
    }

    /** Uses an even thread count to balance text readers and Boolean consumers.  */
    @Benchmark
    fun run(thread: ThreadParams): Int {
        val local: Tasks = tasks.get()
        return (if ((thread.getThreadIndex() and 1) === 0) local.reader else local.booleanConsumer).run()
    }

    private class Tasks(reader: CapturePolicyProtocol.Task?, booleanConsumer: CapturePolicyProtocol.Task?) {
        val reader: CapturePolicyProtocol.Task?
        val booleanConsumer: CapturePolicyProtocol.Task?


    }
}
