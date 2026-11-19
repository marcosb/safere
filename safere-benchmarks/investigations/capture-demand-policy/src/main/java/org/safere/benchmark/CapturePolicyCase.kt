// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).
package org.safere.benchmark

import com.google.gson.JsonObject

/** Joins materialized inputs and investigation-owned protocol settings.  */
internal class CapturePolicyCase(
    trial: CrossEngineBenchmarkPlan.Trial,
    inputSource: Function<String?, Input?>?,
    configuration: JsonObject,
    consumption: String?
) {
    private val pattern: CapturePolicyProtocol.Compiled?
    private val inputs: List<Input?>?
    private val group: Int
    private val retained: Boolean
    private val rounds: Int
    private val usage: Usage
    private val expected: Int

    init {
        val workload: CrossEngineWorkload = trial.workload()
        kotlin.require(workload.operation() === BenchmarkOperation.FIND_SAMPLED_GROUP) { "Expected a find-and-capture-participation workload" }
        val declared: JsonObject = configuration.getAsJsonObject("usages")
        kotlin.require(declared.has(consumption)) { "Unknown capture usage: " + consumption }
        val settings: JsonObject = declared.getAsJsonObject(consumption)
        usage =
            Usage(
                settings.get("mode").getAsString(), settings.get("readEvery").getAsInt()
            )
        rounds = configuration.get("rounds").getAsInt()
        group = workload.groups()[0]
        retained = workload.lifecycle().matcher() === DeclarativeBenchmarkPlan.MatcherReuse.RETAINED
        inputs = workload.inputKeys().stream().map(inputSource).toList()
        pattern = CapturePolicyProtocol.compile(workload.patterns().getFirst(), trial.variant())
        expected =
            configuration
                .getAsJsonObject("checksums")
                .getAsJsonObject(workload.id())
                .get(consumption)
                .getAsInt()
    }

    fun task(): CapturePolicyProtocol.Task {
        return CapturePolicyProtocol.bind(pattern, inputs, group, retained, rounds, usage)
    }

    fun booleanTask(): CapturePolicyProtocol.Task {
        return CapturePolicyProtocol.bind(
            pattern, inputs, group, retained, rounds, Usage("boolean", 1)
        )
    }

    fun validate() {
        kotlin.require(task().run() === expected) { "Capture-policy cycle did not match its declared checksum" }
    }

    companion object {
        fun load(trialId: String?, consumption: String?): CapturePolicyCase {
            val data: BenchmarkData = BenchmarkData.get()
            return org.safere.benchmark.CapturePolicyCase(
                CrossEngineBenchmarkPlan.load().resolve(trialId),
                Function { key -> Input(data.getInputString(key), data.getInputBytes(key)) },
                data.investigationConfiguration(),
                consumption
            )
        }
    }
}
