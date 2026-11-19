// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import java.util.concurrent.TimeUnit

/** Complete uninstrumented adaptation cycles; requires explicit investigation selection.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class AdaptiveCaptureBenchmark {
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

    protected var task: AdaptiveCaptureCase.Task? = null

    /** Validates against a separate JDK oracle without teaching the measurement Pattern.  */
    @Setup
    fun setup() {
        org.safere.benchmark.AdaptiveCaptureBenchmark.Companion.configure(policy, seed)
        val selected: AdaptiveCaptureCase = AdaptiveCaptureCase.load(scenario, representation)
        selected.validate()
        org.safere.benchmark.AdaptiveCaptureBenchmark.Companion.verify(policy, seed)
        task = selected.task(false)
    }

    /** Scans the declared phases and consumes capture checksums.  */
    @Benchmark
    fun run(): Long {
        return task.run(null)
    }

    companion object {
        fun configure(policy: String, seed: Long) {
            System.setProperty(
                "safere.experimental.capturePolicy", if (policy.equals("patchedMain")) "main" else policy
            )
            System.setProperty("safere.experimental.captureSeed", Long.toString(seed))
        }

        fun verify(policy: String, seed: Long) {
            val actual: String? = System.getProperty("safere.experimental.captureActualMode")
            if (actual == null
                && (policy.equals("main")
                        || policy.equals(System.getProperty("safere.experimental.bareControl")))
            ) {
                return
            }
            val expected = if (policy.equals("patchedMain")) "main" else policy
            kotlin.check(
                !(!expected.equals(actual)
                        || !Long.toString(seed)
                    .equals(System.getProperty("safere.experimental.captureActualSeed")))
            ) { "Experimental policy/seed initialized before benchmark setup" }
        }
    }
}
