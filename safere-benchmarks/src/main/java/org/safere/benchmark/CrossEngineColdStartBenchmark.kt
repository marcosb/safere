// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.safere.benchmark.CrossEngineWorkload.TimingGroup
import java.util.concurrent.TimeUnit

/** Generic single-shot entry point for fresh-process cold-start declarations.  */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
class CrossEngineColdStartBenchmark {
    /** Planned workload and execution-variant ID supplied by `run-java-benchmarks.sh`.  */
    @Param
    var crossEngineColdStartTrial: String? = null

    private var runner: CrossEngineTrialRunner? = null

    /** Resolves the declaration without performing the measured compile operation.  */
    @Setup
    fun setup() {
        runner =
            CrossEngineTrialRunner.Companion.prepareColdStart(
                crossEngineColdStartTrial, TimingGroup.MILLISECONDS
            )
    }

    /** Releases resources retained by the selected execution variant.  */
    @TearDown
    fun tearDown() {
        runner!!.close()
    }

    /** Executes one declared cold-start operation.  */
    @Benchmark
    fun run(blackhole: Blackhole?) {
        runner!!.run(blackhole)
    }
}
