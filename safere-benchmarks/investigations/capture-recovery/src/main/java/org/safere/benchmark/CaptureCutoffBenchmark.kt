// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonObject

/** Warm capture-demand cutoff study, selected explicitly through the investigation wrapper.  */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class CaptureCutoffBenchmark {
    /** Corpus workload and native representation.  */
    @Param([])
    var cutoffTrial: String? = null

    /** Public matching operation. The corpus patterns explicitly anchor find at the start.  */
    @Param(["matches", "lookingAt", "find"])
    var operation: String? = null

    /** Independent hit and conditional capture-read schedule, owned by the corpus.  */
    @Param(["text", "mixed", "rare", "reject", "boolean"])
    var profile: String? = null

    private var task: CaptureCutoffTask? = null

    /** Checks a separate SafeRE task against JDK before preparing the measurement task.  */
    @Setup
    fun setup() {
        val trial: CrossEngineBenchmarkPlan.Trial = CrossEngineBenchmarkPlan.load().resolve(cutoffTrial)
        val workload: CrossEngineWorkload = trial.workload()
        val config: JsonObject = BenchmarkData.get().investigationConfiguration()
        val schedule: JsonObject = config.getAsJsonObject("profiles").getAsJsonObject(profile)
        kotlin.require(
            !(schedule == null || !List.of("matches", "lookingAt", "find").contains(operation))
        ) { "Unknown cutoff operation or profile" }
        val documents: Int = config.get("documentsPerInvocation").getAsInt()
        val hitEvery: Int = schedule.get("hitEvery").getAsInt()
        val readEvery: Int = schedule.get("readEvery").getAsInt()
        prepare(RegexEngineVariant.JDK_STRING, workload, documents, hitEvery, readEvery).use { oracle ->
            prepare(trial.variant(), workload, documents, hitEvery, readEvery).use { candidate ->
                for (round in 0..3) {
                    kotlin.check(oracle.run() === candidate.run()) { "Cutoff protocol differs from JDK: " + cutoffTrial }
                }
            }
        }
        task = prepare(trial.variant(), workload, documents, hitEvery, readEvery)
    }

    private fun prepare(
        variant: RegexEngineVariant,
        workload: CrossEngineWorkload,
        documents: Int,
        hitEvery: Int,
        readEvery: Int
    ): CaptureCutoffTask {
        return CaptureCutoffTask(
            variant,
            workload.patterns().getFirst(),
            variant.prepareInputs(BenchmarkData.get(), workload.inputKeys()),
            operation,
            documents,
            hitEvery,
            readEvery
        )
    }

    /** Runs the declared document batch; input construction and encoding are outside timing.  */
    @Benchmark
    fun run(): Long {
        return task.run()
    }

    /** Releases the compiled pattern after the trial.  */
    @TearDown
    fun tearDown() {
        if (task != null) {
            task.close()
        }
    }
}
