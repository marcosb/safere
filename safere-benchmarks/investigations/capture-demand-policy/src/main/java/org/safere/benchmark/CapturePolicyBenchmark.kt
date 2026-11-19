// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.util.concurrent.TimeUnit

/** Explicitly selected capture-history investigation, excluded from the release corpus.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class CapturePolicyBenchmark {
    /** Materialized workload and String/UTF-8 representation.  */
    @Param([])
    var captureTrial: String? = null

    /** Consumption protocol declared in the investigation configuration.  */
    @Param([])
    var consumption: String? = null

    private var task: CapturePolicyProtocol.Task? = null

    /** Uses the declared cursor lifetime for both the oracle and measurement.  */
    @Setup
    fun setup() {
        val selected: CapturePolicyCase = CapturePolicyCase.load(captureTrial, consumption)
        selected.validate()
        task = selected.task()
    }

    /** Runs one complete capture/Boolean/capture cycle and consumes its checksum.  */
    @Benchmark
    fun run(): Int {
        return task.run()
    }
}
