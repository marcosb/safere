// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/** Generic JMH entry point for SafeRE-specific nanosecond workloads.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class SpecializedBenchmark {
    /** Planned specialized workload and execution-variant ID.  */
    @Param
    var specializedTrial: String? = null

    private var runner: SpecializedTrialRunner? = null

    /** Prepares the selected specialized operation from its declaration.  */
    @Setup
    fun setup() {
        runner = SpecializedTrialRunner.Companion.prepare(specializedTrial)
    }

    /** Restores specialized global state after the trial.  */
    @TearDown
    fun tearDown() {
        runner!!.close()
    }

    /** Executes the selected operation.  */
    @Benchmark
    fun run(blackhole: Blackhole?) {
        runner!!.run(blackhole)
    }
}
