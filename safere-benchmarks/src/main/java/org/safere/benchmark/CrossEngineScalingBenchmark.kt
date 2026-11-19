// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import java.util.concurrent.TimeUnit

/** Shared JMH entry point for ordinary cross-engine scaling workloads.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
class CrossEngineScalingBenchmark {
    /** Planned workload and execution-variant ID supplied by `run-java-benchmarks.sh`.  */
    @Param
    var crossEngineScalingTrial: String? = null

    private var runner: CrossEngineTrialRunner? = null

    /** Prepares the selected engine, representation, operation, and materialized input.  */
    @Setup
    fun setup() {
        runner =
            CrossEngineTrialRunner.Companion.prepare(
                crossEngineScalingTrial, TimingGroup.MICROSECONDS
            )
    }

    /** Releases resources retained by the selected execution variant.  */
    @TearDown
    fun tearDown() {
        runner!!.close()
    }

    /** Executes the pre-bound operation and consumes its result.  */
    @Benchmark
    fun run(blackhole: Blackhole?) {
        runner!!.run(blackhole)
    }
}
